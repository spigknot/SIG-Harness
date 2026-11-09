#!/usr/bin/env node
// SIG Harness bridge: runs in Termux, drives "dsh --profile acp" and exposes a
// tiny line-delimited JSON protocol on 127.0.0.1 for the Android app.
import { spawn } from 'node:child_process';
import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { chmodSync, closeSync, existsSync, mkdirSync, openSync, readFileSync, readdirSync, renameSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import readline from 'node:readline';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const PROTOCOL = 1;
const HOME = process.env.HOME || '/data/data/com.termux/files/home';
const STATE_DIR = path.join(HOME, '.sig-harness');
const DSH_HOME = path.join(HOME, '.dsh');
const SESSIONS_ROOT = path.join(DSH_HOME, 'sessions');
const PROJCACHE_ROOT = path.join(DSH_HOME, 'storages', 'session_projcache', 'sessions');
const ATTACHMENTS_DIR = path.join(STATE_DIR, 'attachments');
const MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024;
const DEFAULT_DSH_BIN = '/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js';

function log(msg) {
  const line = new Date().toISOString() + ' ' + msg;
  try { console.log(line); } catch {}
}

function parseArgs(argv) {
  const o = {
    port: 43117,
    token: '',
    cwd: HOME,
    daemon: false,
    log: path.join(STATE_DIR, 'bridge.log'),
    profile: 'acp',
    patch: path.join(STATE_DIR, 'acp.patch.yml'),
    permissionMode: 'danger-full-access',
    idleMinutes: 30,
    autoStart: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--daemon') { o.daemon = true; continue; }
    if (a === '--auto-start') { o.autoStart = true; continue; }
    const v = argv[++i];
    if (v === undefined) continue;
    if (a === '--port') o.port = parseInt(v, 10) || 43117;
    else if (a === '--token') o.token = v;
    else if (a === '--cwd') o.cwd = v;
    else if (a === '--log') o.log = v;
    else if (a === '--profile') o.profile = v;
    else if (a === '--patch') o.patch = v;
    else if (a === '--permission-mode') o.permissionMode = v;
    else if (a === '--idle-minutes') { const n = parseInt(v, 10); o.idleMinutes = Number.isFinite(n) ? n : 30; }
  }
  return o;
}

const opts = parseArgs(process.argv.slice(2));

function daemonize() {
  mkdirSync(STATE_DIR, { recursive: true });
  try {
    if (statSync(opts.log).size > 5 * 1024 * 1024) renameSync(opts.log, opts.log + '.1');
  } catch {}
  let fd;
  try { fd = openSync(opts.log, 'a'); } catch { fd = 'ignore'; }
  const argv = process.argv.slice(2).filter((a) => a !== '--daemon');
  const child = spawn(process.execPath, [fileURLToPath(import.meta.url), ...argv], {
    detached: true,
    stdio: ['ignore', fd, fd],
    cwd: HOME,
    env: process.env,
  });
  child.unref();
  if (typeof fd === 'number') closeSync(fd);
  process.exit(0);
}

if (opts.daemon) daemonize();
if (!opts.token) {
  console.error('sig-bridge: --token is required');
  process.exit(2);
}

function resolveDshBin() {
  const candidates = [
    process.env.DSH_BIN,
    DEFAULT_DSH_BIN,
    process.env.PREFIX ? path.join(process.env.PREFIX, 'lib/node_modules/@deepseek-ai/dsh/lib/bin.js') : null,
    path.join(HOME, '.npm-global/lib/node_modules/@deepseek-ai/dsh/lib/bin.js'),
  ].filter(Boolean);
  for (const c of candidates) { try { if (existsSync(c)) return c; } catch {} }
  return null;
}

class BridgeError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}

function safeStat(p) {
  try { return statSync(p); } catch { return null; }
}

function readJson(p) {
  try { return JSON.parse(readFileSync(p, 'utf8')); } catch { return null; }
}

function contentText(content) {
  if (!content) return '';
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map(contentText).filter(Boolean).join('');
  if (typeof content === 'object') {
    if (typeof content.text === 'string') return content.text;
    if (content.type === 'text' && typeof content.text === 'string') return content.text;
  }
  return '';
}

function blocksText(blocks) {
  if (!Array.isArray(blocks)) return '';
  return blocks.filter((b) => b && b.type === 'text' && typeof b.text === 'string').map((b) => b.text).join('');
}

function displayUserText(blocks) {
  return blocksText(blocks).replace(/\[resource_link name="((?:\\.|[^"\\])*)" uri="(?:\\.|[^"\\])*"\]/g, (m, name) => {
    try { return '📎 ' + JSON.parse('"' + name + '"'); } catch { return '📎 ' + name; }
  });
}

function clip(s, n) {
  const str = String(s == null ? '' : s);
  return str.length > n ? str.slice(0, n - 1) + '…' : str;
}

class AcpRuntime {
  constructor(conf, callbacks) {
    this.conf = conf;
    this.cb = callbacks;
    this.child = null;
    this.pending = new Map();
    this.nextId = 1;
    this.state = 'stopped';
    this.detail = '';
    this.stderrTail = [];
    this.initialized = false;
    this.stopping = false;
    this.startPromise = null;
    this.restarts = 0;
    this.lastStartAt = 0;
  }

  setState(state, detail) {
    this.state = state;
    this.detail = detail || '';
    this.cb.onState(state, this.detail);
  }

  /**
   * Reaplica a camada dsh-web a cada start do bridge: o perfil só existe depois do
   * primeiro boot do dsh, então o provisionamento feito pelo app pode ter chegado antes.
   * apply.sh é idempotente e roda em segundo plano.
   */
  converge() {
    try {
      const base = process.env.HOME || process.cwd();
      const script = path.join(base, '.sig-harness', 'dsh-web', 'apply.sh');
      if (!existsSync(script)) return;
      const out = openSync(path.join(base, '.sig-harness', 'provision.log'), 'a');
      const child = spawn('/data/data/com.termux/files/usr/bin/bash', [script, '--auto'], {
        detached: true, stdio: ['ignore', out, out]
      });
      child.unref();
      log('[bootstrap] apply.sh --auto disparado');
    } catch (err) {
      log('[bootstrap] converge falhou: ' + (err && err.message));
    }
  }
  async start() {
    this.converge();
    if (this.state === 'ready') return;
    if (this.startPromise) return this.startPromise;
    this.startPromise = this.startOnce().finally(() => { this.startPromise = null; });
    return this.startPromise;
  }

  async startOnce() {
    if (this.child) await this.stop();
    this.stopping = false;
    this.restarts = 0;
    this.setState('starting', 'Iniciando o runtime do DSH…');
    const bin = resolveDshBin();
    if (!bin) throw new BridgeError('dsh_not_found', 'DSH não foi encontrado no Termux. Instale com: npm i -g @deepseek-ai/dsh');
    const args = ['--expose-internals', bin, '--profile', this.conf.profile];
    if (this.conf.patch && existsSync(this.conf.patch)) args.push('--patch', this.conf.patch);
    const env = Object.assign({}, process.env, { DSH_PERMISSION_MODE: this.conf.permissionMode });
    log('[acp] spawn ' + process.execPath + ' ' + args.join(' '));
    const child = spawn(process.execPath, args, { cwd: this.conf.cwd, env, stdio: ['pipe', 'pipe', 'pipe'] });
    this.child = child;
    this.lastStartAt = Date.now();
    child.on('error', (err) => { log('[acp] spawn error: ' + err.message); });
    const rl = readline.createInterface({ input: child.stdout, crlfDelay: Infinity });
    rl.on('line', (line) => this.handleLine(line));
    child.stderr.on('data', (buf) => {
      const text = String(buf);
      for (const line of text.split('\n')) {
        if (!line.trim()) continue;
        this.stderrTail.push(line);
        if (this.stderrTail.length > 60) this.stderrTail.shift();
        log('[acp:err] ' + clip(line, 400));
      }
      this.cb.onLog(text);
    });
    child.on('exit', (code, signal) => this.handleExit(code, signal));
    try {
      const init = await this.send('initialize', {
        protocolVersion: 1,
        clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false },
        clientInfo: { name: 'sig-harness-android', version: '1.0.0' },
      }, 45000);
      this.initialized = true;
      this.setState('ready', 'Runtime pronto');
      log('[acp] initialized');
      return init;
    } catch (err) {
      log('[acp] initialize failed: ' + (err && err.message));
      try { child.kill('SIGKILL'); } catch {}
      this.child = null;
      this.setState('error', err && err.message ? err.message : String(err));
      throw err;
    }
  }

  send(method, params, timeoutMs) {
    return new Promise((resolve, reject) => {
      const child = this.child;
      if (!child || !child.stdin || !child.stdin.writable) {
        reject(new BridgeError('runtime_down', 'O runtime do Harness não está em execução.'));
        return;
      }
      const id = this.nextId++;
      const entry = { resolve, reject, method, timer: null };
      if (timeoutMs && timeoutMs > 0) {
        entry.timer = setTimeout(() => {
          this.pending.delete(id);
          reject(new BridgeError('timeout', 'Tempo esgotado ao chamar ' + method));
        }, timeoutMs);
      }
      this.pending.set(id, entry);
      this.write({ jsonrpc: '2.0', id, method, params });
    });
  }

  write(obj) {
    try {
      this.child.stdin.write(JSON.stringify(obj) + '\n');
    } catch (err) {
      log('[acp] write failed: ' + err.message);
    }
  }

  handleLine(line) {
    if (!line.trim()) return;
    let msg;
    try { msg = JSON.parse(line); } catch { log('[acp] non-json stdout: ' + clip(line, 300)); return; }
    if (msg.method !== undefined && msg.id !== undefined) {
      this.cb.onRequest(msg);
      return;
    }
    if (msg.method !== undefined) {
      this.cb.onNotification(msg);
      return;
    }
    if (msg.id !== undefined) {
      const entry = this.pending.get(msg.id);
      if (!entry) return;
      this.pending.delete(msg.id);
      if (entry.timer) clearTimeout(entry.timer);
      if (msg.error) {
        const err = new BridgeError('acp_error', msg.error.message || 'Erro do runtime');
        err.acpCode = msg.error.code;
        err.acpData = msg.error.data;
        entry.reject(err);
      } else {
        entry.resolve(msg.result);
      }
    }
  }

  handleExit(code, signal) {
    const wasReady = this.state === 'ready' || this.initialized;
    this.child = null;
    this.initialized = false;
    for (const [, entry] of this.pending) {
      if (entry.timer) clearTimeout(entry.timer);
      entry.reject(new BridgeError('runtime_down', 'O runtime do Harness encerrou inesperadamente.'));
    }
    this.pending.clear();
    this.cb.onExit(code, signal, wasReady);
    if (this.stopping) return;
    if (!wasReady) {
      if (this.state !== 'error') this.setState('error', 'O runtime encerrou. Código ' + code + '.');
      return;
    }
    const quick = Date.now() - this.lastStartAt < 120000;
    if (quick) this.restarts++;
    if (this.restarts <= 3) {
      this.setState('starting', 'O runtime caiu. Reiniciando…');
      setTimeout(() => {
        this.start().catch((err) => {
          this.setState('error', err && err.message ? err.message : String(err));
        });
      }, 1200);
    } else {
      this.setState('error', 'O runtime caiu repetidamente. Toque em reiniciar.');
    }
  }

  async stop() {
    this.stopping = true;
    const child = this.child;
    if (!child) { this.setState('stopped', 'Harness parado'); this.stopping = false; return; }
    log('[acp] stopping child ' + child.pid);
    try { child.kill('SIGTERM'); } catch {}
    const exited = await new Promise((resolve) => {
      const t = setTimeout(() => resolve(false), 4000);
      child.once('exit', () => { clearTimeout(t); resolve(true); });
    });
    if (!exited) { try { child.kill('SIGKILL'); } catch {} await new Promise((r) => setTimeout(r, 300)); }
    this.child = null;
    this.pending.clear();
    this.setState('stopped', 'Harness parado');
    this.stopping = false;
  }
}

class Bridge {
  constructor(conf) {
    this.conf = conf;
    this.clients = new Set();
    this.pendingPermissions = new Map();
    this.activePrompts = new Map();
    this.sessions = new Map();
    this.transcripts = new Map();
    this.openAssistant = new Map();
    this.toolCalls = new Map();
    this.lastClientAt = Date.now();
    this.server = null;
    this.port = conf.port;
    this.meta = new Map();
    this.logIndex = new Map();
    this.metaAt = 0;
    this.titleCache = new Map();
    this.titles = this.loadTitles();
    this.queues = new Map();
    this.promoteItems = new Map();
    this.presetCache = new Map();
    this.webChild = null;
    this.webUrl = null;
    this.webOwned = false;
    this.webPort = 0;
    this.webStarting = null;
    this.webStderr = [];
    this.startingHarness = false;
    this.shuttingDown = false;
    this.runtime = new AcpRuntime(conf, {
      onState: (state, detail) => this.broadcast({ type: 'event', event: 'harness.status', data: { state, detail } }),
      onLog: (text) => this.broadcast({ type: 'event', event: 'harness.log', data: { line: clip(text, 800) } }),
      onNotification: (msg) => this.onNotification(msg),
      onRequest: (msg) => this.onRequest(msg),
      onExit: (code, signal, wasReady) => this.onRuntimeExit(code, signal, wasReady),
    });
  }

  async listen() {
    for (let p = this.conf.port; p < this.conf.port + 12; p++) {
      const server = await new Promise((resolve) => {
        const s = net.createServer((socket) => this.handleConnection(socket));
        s.once('error', () => resolve(null));
        s.listen(p, '127.0.0.1', () => resolve(s));
      });
      if (server) { this.server = server; this.port = p; break; }
    }
    if (!this.server) throw new Error('Nenhuma porta livre entre ' + this.conf.port + ' e ' + (this.conf.port + 11));
    try {
      writeFileSync(path.join(STATE_DIR, 'bridge.json'), JSON.stringify({ port: this.port, pid: process.pid, startedAt: Date.now() }));
    } catch {}
    log('SIG_BRIDGE_READY port=' + this.port + ' pid=' + process.pid + ' home=' + HOME);
  }

  checkToken(token) {
    const a = createHash('sha256').update(String(token)).digest();
    const b = createHash('sha256').update(String(this.conf.token)).digest();
    return timingSafeEqual(a, b);
  }

  handleConnection(socket) {
    socket.setNoDelay(true);
    const client = { socket, authed: false };
    let alive = true;
    const rl = readline.createInterface({ input: socket, crlfDelay: Infinity });
    const timer = setTimeout(() => { if (!client.authed) { try { socket.destroy(); } catch {} } }, 7000);
    socket.on('error', () => {});
    socket.on('close', () => {
      alive = false;
      clearTimeout(timer);
      this.clients.delete(client);
      this.lastClientAt = Date.now();
      log('[net] client disconnected; clients=' + this.clients.size);
    });
    rl.on('line', (line) => {
      if (!alive) return;
      let msg;
      try { msg = JSON.parse(line); } catch { return; }
      if (!client.authed) {
        if (msg.type !== 'hello' || !this.checkToken(msg.token)) {
          this.send(client, { type: 'error', code: 'unauthorized', message: 'Token inválido.' });
          setTimeout(() => { try { socket.destroy(); } catch {} }, 300);
          return;
        }
        client.authed = true;
        clearTimeout(timer);
        this.clients.add(client);
        this.lastClientAt = Date.now();
        log('[net] client authenticated from ' + socket.remoteAddress + '; clients=' + this.clients.size);
        this.send(client, this.welcome());
        if (this.runtime.state === 'stopped') {
          this.startHarness().catch((err) => log('[harness] start failed: ' + (err && err.message)));
        }
        return;
      }
      if (msg.type === 'ping') { this.send(client, { type: 'pong', time: Date.now() }); return; }
      if (msg.type === 'request' && typeof msg.method === 'string') {
        this.dispatch(msg.method, msg.params || {})
          .then((result) => this.send(client, { type: 'response', id: msg.id, ok: true, result: result === undefined ? null : result }))
          .catch((err) => {
            const code = err && err.code ? err.code : 'error';
            const message = err && err.message ? err.message : String(err);
            log('[req] ' + msg.method + ' failed: ' + message);
            this.send(client, { type: 'response', id: msg.id, ok: false, error: { code, message } });
          });
      }
    });
  }

  welcome() {
    return {
      type: 'welcome',
      protocol: PROTOCOL,
      pid: process.pid,
      port: this.port,
      home: HOME,
      version: '1.2.1',
      state: this.runtime.state,
      detail: this.runtime.detail,
      model: this.conf.model || null,
      autoStart: !!this.conf.autoStart,
      webRunning: !!this.webUrl,
      activePrompts: Array.from(this.activePrompts.keys()),
      pendingPermissions: Array.from(this.pendingPermissions.values()).map((p) => ({
        requestId: p.requestId,
        sessionId: p.sessionId,
        toolCall: p.toolCall,
        toolCallDetails: p.toolCallDetails || null,
        options: p.options,
      })),
    };
  }

  send(client, obj) {
    if (!client || !client.socket || client.socket.destroyed) return;
    try { client.socket.write(JSON.stringify(obj) + '\n'); } catch (err) { log('[net] write error: ' + err.message); }
  }

  broadcast(obj) {
    const line = JSON.stringify(obj) + '\n';
    for (const client of this.clients) {
      if (!client.authed || client.socket.destroyed) continue;
      try { client.socket.write(line); } catch {}
    }
  }

  async startHarness() {
    if (this.webChild) { try { await this.webStop(); } catch {} }
    if (this.runtime.state === 'ready') return { state: 'ready' };
    if (this.startingHarness) return { state: this.runtime.state };
    this.startingHarness = true;
    this.runtime.start()
      .then(() => { this.broadcast({ type: 'event', event: 'sessions.changed', data: {} }); })
      .catch((err) => log('[harness] ' + (err && err.message)))
      .finally(() => { this.startingHarness = false; });
    return { state: this.runtime.state };
  }

  async stopHarness() {
    for (const [sid] of this.activePrompts) {
      this.activePrompts.delete(sid);
      this.broadcast({ type: 'event', event: 'session.promptDone', data: { sessionId: sid, stopReason: 'cancelled', error: 'Harness parado.' } });
    }
    for (const [requestId, p] of this.pendingPermissions) {
      try { this.runtime.write({ jsonrpc: '2.0', id: p.jsonRpcId, result: { outcome: { outcome: 'cancelled' } } }); } catch {}
      this.pendingPermissions.delete(requestId);
    }
    for (const sid of Array.from(this.queues.keys())) this.clearQueue(sid, 'harness parado');
    this.promoteItems.clear();
    await this.runtime.stop();
    this.sessions.clear();
    this.transcripts.clear();
    this.openAssistant.clear();
    this.broadcast({ type: 'event', event: 'sessions.changed', data: {} });
    return { state: this.runtime.state };
  }

  presetFor(sessionId) {
    if (this.presetCache.has(sessionId)) return this.presetCache.get(sessionId);
    this.refreshMeta(false);
    const info = this.logIndex.get(sessionId);
    let preset = null;
    if (info) {
      try {
        const raw = zlib.zstdDecompressSync(readFileSync(info.file)).toString('utf8');
        const header = JSON.parse(raw.split('\n')[0]);
        preset = header && header.agentPreset ? String(header.agentPreset) : null;
      } catch (err) {
        log('[preset] ' + sessionId + ': ' + err.message);
      }
    }
    this.presetCache.set(sessionId, preset);
    return preset;
  }

  isWebOnly(sessionId) {
    return this.presetFor(sessionId) === 'ptc';
  }

  bootConfigure(params) {
    const enabled = !(params && params.enabled === false);
    const bootDir = path.join(HOME, '.termux', 'boot');
    const file = path.join(bootDir, 'sig-harness.sh');
    if (!enabled) {
      try { unlinkSync(file); } catch {}
      log('[boot] disabled');
      return { enabled: false, path: file };
    }
    mkdirSync(bootDir, { recursive: true });
    const cwd = params && typeof params.cwd === 'string' && params.cwd.startsWith('/') ? params.cwd : this.conf.cwd;
    const node = '/data/data/com.termux/files/usr/bin/node';
    const lines = [
      '#!/data/data/com.termux/files/usr/bin/bash',
      '# SIG Harness: sobe o harness no boot (Termux:Boot).',
      'export HOME=' + HOME,
      'export PATH=/data/data/com.termux/files/usr/bin:$PATH',
      'LOG="$HOME/.sig-harness/boot.log"',
      'mkdir -p "$HOME/.sig-harness"',
      'CODE=$(/data/data/com.termux/files/usr/bin/curl -s -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:' + this.port + '/ 2>/dev/null)',
      'if [ -n "$CODE" ] && [ "$CODE" != "000" ]; then echo "$(date) bridge em execucao" >> "$LOG"; exit 0; fi',
      '[ -f "$HOME/.sig-harness/sig-bridge.mjs" ] || { echo "$(date) bridge ausente" >> "$LOG"; exit 0; }',
      'echo "$(date) iniciando SIG Harness" >> "$LOG"',
      'exec ' + node + ' "$HOME/.sig-harness/sig-bridge.mjs" --daemon --port ' + this.port + ' --token "' + String(this.conf.token) + '" --cwd "' + cwd + '" --permission-mode ' + this.conf.permissionMode + ' --idle-minutes 0 --log "$HOME/.sig-harness/bridge.log"',
    ];
    writeFileSync(file, lines.join('\n') + '\n', { mode: 0o700 });
    try { chmodSync(file, 0o700); } catch {}
    log('[boot] script ' + file);
    return { enabled: true, path: file, port: this.port, cwd };
  }

  existingWeb() {
    let text = '';
    try { text = readFileSync(path.join(HOME, 'dsh-web.log'), 'utf8'); } catch { return []; }
    const urls = [];
    for (const line of text.split('\n')) {
      const trimmed = line.trim();
      if (!trimmed.startsWith('dsh web: ')) continue;
      const url = trimmed.slice('dsh web: '.length).trim();
      if (url.startsWith('http://127.0.0.1:') && url.indexOf('/?token=') >= 0) urls.push(url);
    }
    return urls;
  }

  validateUrl(url) {
    return new Promise((resolve) => {
      let parsed;
      try { parsed = new URL(url); } catch { resolve(false); return; }
      let done = false;
      const finish = (value) => { if (!done) { done = true; resolve(value); } };
      const req = http.get({ hostname: parsed.hostname, port: parsed.port, path: parsed.pathname + parsed.search, timeout: 5000 }, (res) => {
        res.resume();
        finish(res.statusCode === 303 || res.statusCode === 302 || res.statusCode === 200);
      });
      req.on('timeout', () => { req.destroy(); finish(false); });
      req.on('error', () => finish(false));
    });
  }

  tcpOpen(host, port) {
    return new Promise((resolve) => {
      const socket = net.connect(Number(port), host);
      const done = (value) => { try { socket.destroy(); } catch {} resolve(value); };
      socket.setTimeout(1500);
      socket.once('connect', () => done(true));
      socket.once('timeout', () => done(false));
      socket.once('error', () => done(false));
    });
  }

  async webStart() {
    if (this.webChild && this.webUrl) return { url: this.webUrl, owned: this.webOwned, port: this.webPort };
    if (this.webStarting) return this.webStarting;
    this.webStarting = (async () => {
      await this.stopHarness();
      const candidates = this.existingWeb();
      for (let i = candidates.length - 1; i >= 0; i--) {
        const url = candidates[i];
        let parsed;
        try { parsed = new URL(url); } catch { continue; }
        if (!await this.tcpOpen(parsed.hostname, parsed.port)) continue;
        if (!await this.validateUrl(url)) {
          throw new BridgeError('web_stale', 'Ha um dsh web rodando, mas o link de autenticacao esta desatualizado. Reinicie o dsh web no Termux.');
        }
        this.webUrl = url;
        this.webOwned = false;
        this.webPort = Number(parsed.port) || 0;
        log('[web] attached to running server on port ' + this.webPort);
        return { url, owned: false, port: this.webPort };
      }
      const bin = resolveDshBin();
      if (!bin) throw new BridgeError('dsh_not_found', 'DSH nao encontrado no Termux.');
      this.webStderr = [];
      const env = Object.assign({}, process.env, { DSH_PERMISSION_MODE: this.conf.permissionMode });
      // cwd inexistente (aparelho novo) mata o spawn sem stderr: cai para o HOME do Termux.
      const webCwd = existsSync(this.conf.cwd) ? this.conf.cwd : (process.env.HOME || process.cwd());
      if (webCwd !== this.conf.cwd) log('[web] cwd ausente, usando ' + webCwd);
      const child = spawn(process.execPath, ['--expose-internals', bin, 'web', '--no-open', '--host', '127.0.0.1', '--port', '0'], {
        cwd: webCwd,
        env,
        stdio: ['ignore', 'pipe', 'pipe'],
      });
      this.webChild = child;
      let found = null;
      const scan = (chunk) => {
        for (const line of String(chunk).split('\n')) {
          const at = line.indexOf('dsh web: ');
          if (at < 0) continue;
          const candidate = line.slice(at + 'dsh web: '.length).trim();
          if (candidate.startsWith('http://127.0.0.1:') && candidate.indexOf('/?token=') >= 0) found = candidate;
        }
      };
      child.stdout.on('data', scan);
      child.stderr.on('data', (buf) => {
        for (const line of String(buf).split('\n')) {
          if (!line.trim()) continue;
          this.webStderr.push(line);
          if (this.webStderr.length > 40) this.webStderr.shift();
          log('[web:err] ' + clip(line, 300));
        }
      });
      let spawnFailure = null;
      child.on('error', (err) => { spawnFailure = err; log('[web] spawn error: ' + err.message); });
      const deadline = Date.now() + 60000;
      while (!found && Date.now() < deadline) {
        if (child.exitCode !== null || spawnFailure !== null) break;
        await new Promise((r) => setTimeout(r, 250));
      }
      if (!found) {
        try { child.kill('SIGKILL'); } catch {}
        this.webChild = null;
        const tail = this.webStderr.slice(-5).join(' | ');
        const motivo = spawnFailure !== null ? spawnFailure.message : tail;
        throw new BridgeError('web_failed', 'O servidor web nao iniciou. ' + (motivo ? motivo : ''));
      }
      this.webUrl = found;
      this.webOwned = true;
      try { this.webPort = Number(new URL(found).port) || 0; } catch { this.webPort = 0; }
      child.on('exit', () => {
        if (this.webChild === child) {
          this.webChild = null;
          this.webUrl = null;
          this.webOwned = false;
          this.webPort = 0;
        }
      });
      log('[web] started on port ' + this.webPort);
      return { url: found, owned: true, port: this.webPort };
    })().finally(() => { this.webStarting = null; });
    return this.webStarting;
  }

  async webStop() {
    const child = this.webChild;
    const owned = this.webOwned;
    this.webChild = null;
    this.webUrl = null;
    this.webOwned = false;
    this.webPort = 0;
    if (child && owned) {
      try { child.kill('SIGTERM'); } catch {}
      await new Promise((resolve) => {
        const timer = setTimeout(() => { try { child.kill('SIGKILL'); } catch {} resolve(); }, 4000);
        child.once('exit', () => { clearTimeout(timer); resolve(); });
      });
    }
    return { stopped: true, owned };
  }

  webStatus() {
    return { running: !!this.webUrl, owned: this.webOwned, port: this.webPort };
  }

  async dispatch(method, params) {
    switch (method) {
      case 'ping': return { pong: true, time: Date.now() };
      case 'harness.start': return this.startHarness();
      case 'harness.stop': return this.stopHarness();
      case 'harness.status': return { state: this.runtime.state, detail: this.runtime.detail, pid: process.pid, port: this.port };
      case 'sessions.list': return { sessions: await this.listSessions() };
      case 'sessions.new': return { session: await this.newSession(params) };
      case 'session.open': return { session: await this.openSession(params.sessionId) };
      case 'session.history': return this.history(params.sessionId);
      case 'session.prompt': return this.prompt(params);
      case 'session.queue': return { items: this.queueView(params.sessionId) };
      case 'session.queueRemove': return this.queueRemove(params);
      case 'session.queuePromote': return this.queuePromote(params);
      case 'session.queueClear': this.clearQueue(params.sessionId, 'removed'); return { items: [] };
      case 'file.upload': return this.importFile(params);
      case 'session.cancel': return this.cancel(params);
      case 'session.close': return this.closeSession(params);
      case 'session.setConfigOption': return this.setConfigOption(params);
      case 'session.rename': return this.renameSession(params);
      case 'permission.answer': return this.answerPermission(params);
      case 'boot.configure': return this.bootConfigure(params);
      case 'web.start': return this.webStart();
      case 'web.stop': return this.webStop();
      case 'web.status': return this.webStatus();
      case 'bridge.shutdown': setTimeout(() => this.shutdown('requested').catch(() => {}), 120); return { ok: true };
      default: throw new BridgeError('unknown_method', 'Método desconhecido: ' + method);
    }
  }

  ensureReady() {
    if (this.runtime.state !== 'ready') throw new BridgeError('harness_not_ready', 'O Harness ainda não está pronto. Estado: ' + this.runtime.state);
  }

  refreshMeta(force) {
    const now = Date.now();
    if (!force && now - this.metaAt < 4000) return;
    this.metaAt = now;
    const meta = new Map();
    try {
      for (const file of readdirSync(PROJCACHE_ROOT)) {
        if (!file.endsWith('.json')) continue;
        const id = file.slice(0, -5);
        const doc = readJson(path.join(PROJCACHE_ROOT, file));
        if (!doc || !doc.record) continue;
        const identity = doc.record.identity || {};
        const rows = doc.record.rows || {};
        meta.set(id, {
          title: rows.title && rows.title.val ? String(rows.title.val) : null,
          cwd: identity.cwd || null,
          createdAt: identity.createdAt || 0,
        });
      }
    } catch {}
    const index = new Map();
    try {
      for (const cwdDir of readdirSync(SESSIONS_ROOT)) {
        const dir = path.join(SESSIONS_ROOT, cwdDir);
        let entries = [];
        try { entries = readdirSync(dir); } catch { continue; }
        for (const sid of entries) {
          const file = path.join(dir, sid, 'session.v4.jsonl.zstd');
          const st = safeStat(file);
          if (!st) continue;
          index.set(sid, { file, mtime: st.mtimeMs, size: st.size });
        }
      }
    } catch {}
    this.meta = meta;
    this.logIndex = index;
  }

  parseLog(sessionId) {
    this.refreshMeta(false);
    const info = this.logIndex.get(sessionId);
    if (!info) return [];
    let raw;
    try { raw = zlib.zstdDecompressSync(readFileSync(info.file)); } catch (err) { log('[history] decompress failed: ' + err.message); return []; }
    const lines = raw.toString('utf8').split('\n');
    const out = [];
    for (const line of lines) {
      if (!line.trim()) continue;
      let ev;
      try { ev = JSON.parse(line); } catch { continue; }
      if (ev.type === 'user/message') {
        const d = ev.data || {};
        const kind = d.source && d.source.kind;
        if (kind !== 'user') continue;
        const text = displayUserText(d.content);
        if (!text.trim()) continue;
        out.push({ role: 'user', text, time: ev.time || 0 });
      } else if (ev.type === 'assistant/message') {
        const msg = ev.data && ev.data.message;
        const text = blocksText(msg && msg.content);
        if (!text.trim()) continue;
        out.push({ role: 'assistant', text, time: ev.time || 0 });
      }
    }
    return out.slice(-400);
  }

  parseTitle(sessionId) {
    this.refreshMeta(false);
    const info = this.logIndex.get(sessionId);
    if (!info) return null;
    const cached = this.titleCache.get(sessionId);
    if (cached && cached.mtime === info.mtime) return cached.title;
    let title = null;
    try {
      const raw = zlib.zstdDecompressSync(readFileSync(info.file)).toString('utf8');
      for (const line of raw.split('\n')) {
        if (!line.includes('session/title')) continue;
        let ev;
        try { ev = JSON.parse(line); } catch { continue; }
        if (ev.type === 'session/title' && ev.data && ev.data.title) title = String(ev.data.title);
      }
    } catch {}
    this.titleCache.set(sessionId, { mtime: info.mtime, title });
    return title;
  }

  hashFromTranscript(sessionId) {
    const t = this.transcripts.get(sessionId);
    if (!t || !t.length) return null;
    const firstUser = t.find((m) => m.role === 'user' && m.text && m.text.trim());
    if (!firstUser) return null;
    return clip(firstUser.text.replace(/\s+/g, ' ').trim(), 60);
  }

  loadTitles() {
    const map = new Map();
    try {
      const doc = JSON.parse(readFileSync(path.join(STATE_DIR, 'titles.json'), 'utf8'));
      if (doc && typeof doc === 'object' && !Array.isArray(doc)) {
        for (const [id, value] of Object.entries(doc)) {
          if (typeof value === 'string' && value.trim()) map.set(id, value.trim().slice(0, 120));
        }
      }
    } catch {}
    return map;
  }

  saveTitles() {
    try {
      mkdirSync(STATE_DIR, { recursive: true });
      const doc = {};
      for (const [id, value] of this.titles) doc[id] = value;
      const file = path.join(STATE_DIR, 'titles.json');
      writeFileSync(file + '.tmp', JSON.stringify(doc, null, 2));
      renameSync(file + '.tmp', file);
    } catch (err) {
      log('[titles] save failed: ' + err.message);
    }
  }

  renameSession(params) {
    const sessionId = params && params.sessionId ? String(params.sessionId) : '';
    const title = String(params && params.title != null ? params.title : '').replace(/\s+/g, ' ').trim();
    if (!sessionId) throw new BridgeError('bad_request', 'sessionId ausente');
    if (!title) throw new BridgeError('bad_request', 'O nome não pode ficar vazio.');
    if (title.length > 120) throw new BridgeError('bad_request', 'O nome deve ter no máximo 120 caracteres.');
    this.titles.set(sessionId, title);
    this.saveTitles();
    const sess = this.sessions.get(sessionId);
    if (sess) sess.title = title;
    this.broadcast({ type: 'event', event: 'sessions.changed', data: { sessionId } });
    return { sessionId, title };
  }

  titleFor(sessionId) {
    const renamed = this.titles.get(sessionId);
    if (renamed) return renamed;
    const m = this.meta.get(sessionId);
    if (m && m.title) return m.title;
    const sess = this.sessions.get(sessionId);
    if (sess && sess.title) return sess.title;
    const fromLog = this.parseTitle(sessionId);
    if (fromLog) return fromLog;
    const derived = this.hashFromTranscript(sessionId);
    if (derived) return derived;
    return 'Nova conversa';
  }

  async listSessions() {
    this.ensureReady();
    const fromAcp = [];
    let cursor = null;
    for (let page = 0; page < 3; page++) {
      const res = await this.runtime.send('session/list', cursor ? { cursor } : {}, 25000);
      if (res && Array.isArray(res.sessions)) fromAcp.push(...res.sessions);
      cursor = res && res.nextCursor ? res.nextCursor : null;
      if (!cursor) break;
    }
    this.refreshMeta(true);
    const merged = new Map();
    for (const s of fromAcp) merged.set(s.sessionId, { cwd: s.cwd });
    for (const [id, s] of this.sessions) if (!merged.has(id)) merged.set(id, { cwd: s.cwd });
    const list = [];
    for (const [id, base] of merged) {
      const meta = this.meta.get(id) || {};
      const log = this.logIndex.get(id);
      const sess = this.sessions.get(id);
      const cwd = base.cwd || meta.cwd || (sess && sess.cwd) || this.conf.cwd;
      const updatedAt = Math.max(log ? log.mtime : 0, meta.createdAt || 0, sess && sess.updatedAt ? sess.updatedAt : 0);
      const preset = this.presetFor(id);
      list.push({
        id,
        title: this.titleFor(id),
        preset,
        native: preset !== 'ptc',
        cwd,
        cwdName: path.basename(cwd) || cwd,
        createdAt: meta.createdAt || 0,
        updatedAt,
        active: this.activePrompts.has(id),
        model: sess && sess.model ? sess.model : null,
      });
    }
    list.sort((a, b) => b.updatedAt - a.updatedAt);
    return list;
  }

  modelFromConfigOptions(configOptions) {
    if (!Array.isArray(configOptions)) return null;
    const option = configOptions.find((o) => o && o.id === 'model');
    if (!option || !option.currentValue) return null;
    try {
      const parsed = JSON.parse(String(option.currentValue));
      if (Array.isArray(parsed) && parsed.length >= 2) return parsed[1];
    } catch {}
    return String(option.currentValue);
  }

  async newSession(params) {
    this.ensureReady();
    const cwd = params.cwd && String(params.cwd).startsWith('/') ? String(params.cwd) : this.conf.cwd;
    try { mkdirSync(cwd, { recursive: true }); } catch (err) { log('[new] mkdir ' + cwd + ': ' + err.message); }
    const res = await this.runtime.send('session/new', { cwd, mcpServers: [] }, 60000);
    let configOptions = res.configOptions || [];
    if (params.model) {
      try {
        const r = await this.runtime.send('session/set_config_option', { sessionId: res.sessionId, configId: 'model', value: String(params.model) }, 20000);
        if (r && r.configOptions) configOptions = r.configOptions;
      } catch (err) { log('[new] model: ' + err.message); }
    }
    if (params.reasoningEffort !== undefined && params.reasoningEffort !== null) {
      try {
        const r = await this.runtime.send('session/set_config_option', { sessionId: res.sessionId, configId: 'reasoning_effort', value: String(params.reasoningEffort) }, 20000);
        if (r && r.configOptions) configOptions = r.configOptions;
      } catch (err) { log('[new] reasoning: ' + err.message); }
    }
    const session = {
      id: res.sessionId,
      cwd,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      configOptions,
      model: this.modelFromConfigOptions(configOptions),
      preset: null,
      native: true,
      title: 'Nova conversa',
    };
    this.sessions.set(session.id, session);
    this.transcripts.set(session.id, []);
    this.broadcast({ type: 'event', event: 'sessions.changed', data: {} });
    return session;
  }

  async openSession(sessionId) {
    this.ensureReady();
    if (!sessionId) throw new BridgeError('bad_request', 'sessionId ausente');
    if (this.sessions.has(sessionId)) return this.sessions.get(sessionId);
    this.refreshMeta(false);
    const preset = this.presetFor(sessionId);
    if (preset === 'ptc') {
      throw new BridgeError('web_only', 'Esta conversa foi criada na interface web (preset PTC) e nao pode ser continuada no modo nativo. Use o modo web.');
    }
    const meta = this.meta.get(sessionId) || {};
    const cwd = meta.cwd || this.conf.cwd;
    let res;
    try {
      res = await this.runtime.send('session/resume', { sessionId, cwd, mcpServers: [] }, 60000);
    } catch (err) {
      throw new BridgeError('resume_failed', 'Não foi possível abrir esta conversa: ' + (err.message || err));
    }
    const session = {
      id: sessionId,
      cwd,
      createdAt: meta.createdAt || 0,
      updatedAt: Date.now(),
      configOptions: (res && res.configOptions) || [],
      model: this.modelFromConfigOptions(res && res.configOptions),
      preset,
      native: preset !== 'ptc',
      title: this.titleFor(sessionId),
    };
    this.sessions.set(sessionId, session);
    return session;
  }

  history(sessionId) {
    if (!sessionId) throw new BridgeError('bad_request', 'sessionId ausente');
    let messages = this.transcripts.get(sessionId);
    if (!messages) {
      messages = this.parseLog(sessionId);
      this.transcripts.set(sessionId, messages);
    }
    const sess = this.sessions.get(sessionId);
    const meta = this.meta.get(sessionId) || {};
    const preset = this.presetFor(sessionId);
    return {
      sessionId,
      preset,
      webOnly: preset === 'ptc',
      native: preset !== 'ptc',
      title: this.titleFor(sessionId),
      cwd: (sess && sess.cwd) || meta.cwd || this.conf.cwd,
      messages: messages.slice(-400),
      active: this.activePrompts.has(sessionId),
      queue: this.queueView(sessionId),
    };
  }

  publicAttachments(attachments) {
    return (attachments || []).map((a) => ({
      name: a.name,
      path: a.path,
      mimeType: a.mimeType || null,
      size: a.size || 0,
    }));
  }

  normalizeAttachments(value) {
    if (!Array.isArray(value)) return [];
    const out = [];
    for (const raw of value) {
      if (!raw || typeof raw !== 'object') continue;
      let file;
      try { file = path.resolve(String(raw.path || '')); } catch { continue; }
      if (!file.startsWith(ATTACHMENTS_DIR + path.sep)) continue;
      let st;
      try { st = statSync(file); } catch { continue; }
      if (!st.isFile()) continue;
      const name = String(raw.name || path.basename(file)).slice(0, 120) || 'arquivo';
      out.push({
        path: file,
        name,
        mimeType: raw.mimeType ? String(raw.mimeType) : 'application/octet-stream',
        size: st.size,
      });
    }
    return out;
  }

  importFile(params) {
    const rawName = String(params && params.name ? params.name : 'arquivo');
    const name = rawName.replace(/[\/\\\u0000-\u001f]+/g, '_').slice(0, 120) || 'arquivo';
    const data = String(params && params.data ? params.data : '');
    if (!data) throw new BridgeError('bad_request', 'Arquivo vazio.');
    const buffer = Buffer.from(data, 'base64');
    if (!buffer.length) throw new BridgeError('bad_request', 'Arquivo vazio.');
    if (buffer.length > MAX_ATTACHMENT_BYTES) throw new BridgeError('too_large', 'O arquivo passa de 20 MB.');
    mkdirSync(ATTACHMENTS_DIR, { recursive: true });
    const safe = name.replace(/[^\w.\- ()]+/g, '_').slice(-100) || 'arquivo';
    const file = path.join(ATTACHMENTS_DIR, Date.now().toString(36) + '-' + randomUUID().slice(0, 8) + '-' + safe);
    writeFileSync(file, buffer);
    const mimeType = String(params && params.mimeType ? params.mimeType : 'application/octet-stream');
    log('[file] imported ' + safe + ' (' + buffer.length + ' bytes)');
    return { name, path: file, uri: 'file://' + file, size: buffer.length, mimeType };
  }

  queueView(sessionId) {
    const list = this.queues.get(sessionId) || [];
    return list.map((item, index) => ({
      queueId: item.queueId,
      position: index + 1,
      text: item.text,
      attachments: this.publicAttachments(item.attachments),
      queuedAt: item.queuedAt,
    }));
  }

  queueRemove(params) {
    const sessionId = params && params.sessionId ? String(params.sessionId) : '';
    const queueId = params && params.queueId ? String(params.queueId) : '';
    if (!sessionId || !queueId) throw new BridgeError('bad_request', 'sessionId e queueId são obrigatórios');
    const list = (this.queues.get(sessionId) || []).filter((item) => item.queueId !== queueId);
    if (list.length === 0) this.queues.delete(sessionId); else this.queues.set(sessionId, list);
    this.broadcast({ type: 'event', event: 'session.queueChanged', data: { sessionId, items: this.queueView(sessionId), reason: 'removed' } });
    return { items: this.queueView(sessionId) };
  }

  queuePromote(params) {
    const sessionId = params && params.sessionId ? String(params.sessionId) : '';
    const queueId = params && params.queueId ? String(params.queueId) : '';
    if (!sessionId || !queueId) throw new BridgeError('bad_request', 'sessionId e queueId são obrigatórios');
    const list = this.queues.get(sessionId) || [];
    const index = list.findIndex((item) => item.queueId === queueId);
    if (index < 0) throw new BridgeError('not_queued', 'Esta mensagem não está mais na fila.');
    const item = list.splice(index, 1)[0];
    if (list.length === 0) this.queues.delete(sessionId); else this.queues.set(sessionId, list);
    this.broadcast({ type: 'event', event: 'session.queueChanged', data: { sessionId, items: this.queueView(sessionId), reason: 'promoted', queueId } });
    if (!this.activePrompts.has(sessionId)) {
      this.startPrompt(sessionId, item, true);
      return { promoting: true, queueId };
    }
    this.promoteItems.set(sessionId, item);
    this.runtime.write({ jsonrpc: '2.0', method: 'session/cancel', params: { sessionId } });
    this.broadcast({ type: 'event', event: 'session.promptCancelling', data: { sessionId } });
    return { promoting: true, queueId };
  }

  clearQueue(sessionId, reason) {
    const list = this.queues.get(sessionId) || [];
    this.queues.delete(sessionId);
    if (list.length > 0) {
      this.broadcast({ type: 'event', event: 'session.queueChanged', data: { sessionId, items: [], reason: reason || null } });
    }
  }

  startPrompt(sessionId, item, fromQueue) {
    if (this.activePrompts.has(sessionId)) {
      const list = this.queues.get(sessionId) || [];
      list.unshift(item);
      this.queues.set(sessionId, list);
      this.broadcast({ type: 'event', event: 'session.queueChanged', data: { sessionId, items: this.queueView(sessionId), reason: 'requeued' } });
      return;
    }
    const text = item.text || '';
    const attachments = item.attachments || [];
    let transcript = this.transcripts.get(sessionId);
    if (!transcript) { transcript = this.parseLog(sessionId); this.transcripts.set(sessionId, transcript); }
    let display = text;
    if (attachments.length > 0) {
      const names = attachments.map((a) => '📎 ' + a.name).join('\n');
      display = display.trim() ? display + '\n\n' + names : names;
    }
    transcript.push({ role: 'user', text: display, time: Date.now() });
    this.openAssistant.delete(sessionId);
    if (transcript.length > 500) transcript.splice(0, transcript.length - 400);
    this.activePrompts.set(sessionId, {
      startedAt: Date.now(),
      lastEventAt: Date.now(),
      lastStallWarn: 0,
      queueId: item.queueId || null,
      clientId: item.clientId || null,
    });
    const sess = this.sessions.get(sessionId);
    if (sess) sess.updatedAt = Date.now();
    this.broadcast({ type: 'event', event: 'session.promptStarted', data: { sessionId, queueId: item.queueId || null, clientId: item.clientId || null, fromQueue: !!fromQueue } });
    this.broadcast({ type: 'event', event: 'sessions.changed', data: { sessionId } });
    const blocks = [];
    if (text.trim()) blocks.push({ type: 'text', text });
    for (const a of attachments) {
      blocks.push({ type: 'resource_link', name: a.name, uri: 'file://' + a.path, mimeType: a.mimeType, size: a.size });
    }
    let pending;
    try {
      pending = this.runtime.send('session/prompt', { sessionId, prompt: blocks }, 0);
    } catch (err) {
      const message = err && err.message ? err.message : String(err);
      this.activePrompts.delete(sessionId);
      this.clearQueue(sessionId, 'erro');
      this.broadcast({ type: 'event', event: 'session.promptDone', data: { sessionId, stopReason: 'error', error: message, queueId: item.queueId || null } });
      return;
    }
    pending
      .then((res) => {
        const stopReason = res && res.stopReason ? res.stopReason : 'end_turn';
        this.activePrompts.delete(sessionId);
        this.openAssistant.delete(sessionId);
        this.broadcast({ type: 'event', event: 'session.promptDone', data: { sessionId, stopReason, queueId: item.queueId || null, clientId: item.clientId || null } });
        this.broadcast({ type: 'event', event: 'sessions.changed', data: { sessionId } });
        const promoted = this.promoteItems.get(sessionId) || null;
        this.promoteItems.delete(sessionId);
        if (promoted) this.startPrompt(sessionId, promoted, true);
        else if (stopReason === 'cancelled') this.clearQueue(sessionId, 'cancelada');
        else this.drainQueue(sessionId);
      })
      .catch((err) => {
        const message = err && err.message ? err.message : String(err);
        this.activePrompts.delete(sessionId);
        this.openAssistant.delete(sessionId);
        this.promoteItems.delete(sessionId);
        this.clearQueue(sessionId, 'erro');
        this.broadcast({ type: 'event', event: 'session.promptDone', data: { sessionId, stopReason: 'error', error: message, queueId: item.queueId || null, clientId: item.clientId || null } });
        this.broadcast({ type: 'event', event: 'sessions.changed', data: { sessionId } });
      });
  }

  drainQueue(sessionId) {
    const list = this.queues.get(sessionId);
    if (!list || list.length === 0) { this.queues.delete(sessionId); return; }
    if (this.runtime.state !== 'ready' || this.shuttingDown) {
      this.clearQueue(sessionId, 'runtime indisponível');
      return;
    }
    const item = list.shift();
    if (list.length === 0) this.queues.delete(sessionId); else this.queues.set(sessionId, list);
    this.broadcast({ type: 'event', event: 'session.queueChanged', data: { sessionId, items: this.queueView(sessionId), reason: 'started' } });
    setTimeout(() => { this.startPrompt(sessionId, item, true); }, 120);
  }

  async prompt(params) {
    this.ensureReady();
    const sessionId = params.sessionId;
    const text = params.text == null ? '' : String(params.text);
    const attachments = this.normalizeAttachments(params.attachments);
    if (!sessionId) throw new BridgeError('bad_request', 'sessionId ausente');
    if (!text.trim() && attachments.length === 0) throw new BridgeError('bad_request', 'Mensagem vazia');
    if (this.activePrompts.has(sessionId)) {
      if (!params.queue) throw new BridgeError('busy', 'Já existe uma resposta em andamento nesta conversa.');
      const item = {
        queueId: randomUUID(),
        text,
        attachments,
        clientId: params.clientId ? String(params.clientId) : null,
        queuedAt: Date.now(),
      };
      const list = this.queues.get(sessionId) || [];
      list.push(item);
      this.queues.set(sessionId, list);
      this.broadcast({ type: 'event', event: 'session.promptQueued', data: { sessionId, queueId: item.queueId, position: list.length, text, attachments: this.publicAttachments(attachments), clientId: item.clientId } });
      return { queued: true, sessionId, queueId: item.queueId, position: list.length };
    }
    await this.openSession(sessionId);
    this.startPrompt(sessionId, { text, attachments, clientId: params.clientId ? String(params.clientId) : null, queueId: null }, false);
    return { accepted: true, sessionId };
  }

  async cancel(params) {
    const sessionId = params.sessionId;
    if (!sessionId) throw new BridgeError('bad_request', 'sessionId ausente');
    this.promoteItems.delete(sessionId);
    this.clearQueue(sessionId, 'cancelada');
    this.runtime.write({ jsonrpc: '2.0', method: 'session/cancel', params: { sessionId } });
    this.broadcast({ type: 'event', event: 'session.promptCancelling', data: { sessionId } });
    return { cancelling: true };
  }

  async closeSession(params) {
    const sessionId = params.sessionId;
    if (!sessionId) throw new BridgeError('bad_request', 'sessionId ausente');
    this.ensureReady();
    try { await this.runtime.send('session/close', { sessionId }, 20000); } catch (err) { log('[session] close: ' + err.message); }
    this.sessions.delete(sessionId);
    this.transcripts.delete(sessionId);
    this.openAssistant.delete(sessionId);
    this.activePrompts.delete(sessionId);
    for (const [requestId, p] of this.pendingPermissions) {
      if (p.sessionId === sessionId) {
        try { this.runtime.write({ jsonrpc: '2.0', id: p.jsonRpcId, result: { outcome: { outcome: 'cancelled' } } }); } catch {}
        this.pendingPermissions.delete(requestId);
      }
    }
    this.broadcast({ type: 'event', event: 'sessions.changed', data: { sessionId } });
    return { closed: true };
  }

  async setConfigOption(params) {
    this.ensureReady();
    const { sessionId, configId, value } = params;
    if (!sessionId || !configId) throw new BridgeError('bad_request', 'sessionId e configId são obrigatórios');
    const res = await this.runtime.send('session/set_config_option', { sessionId, configId, value }, 20000);
    const sess = this.sessions.get(sessionId);
    if (sess && res && res.configOptions) {
      sess.configOptions = res.configOptions;
      sess.model = this.modelFromConfigOptions(res.configOptions);
    }
    return { configOptions: res && res.configOptions ? res.configOptions : [] };
  }

  async answerPermission(params) {
    const requestId = params.requestId;
    const optionId = params.optionId;
    const p = this.pendingPermissions.get(requestId);
    if (!p) throw new BridgeError('permission_gone', 'Esta solicitação de permissão expirou.');
    if (optionId) {
      this.runtime.write({ jsonrpc: '2.0', id: p.jsonRpcId, result: { outcome: { outcome: 'selected', optionId: String(optionId) } } });
    } else {
      this.runtime.write({ jsonrpc: '2.0', id: p.jsonRpcId, result: { outcome: { outcome: 'cancelled' } } });
    }
    this.pendingPermissions.delete(requestId);
    this.broadcast({ type: 'event', event: 'session.permissionResolved', data: { requestId } });
    return { answered: true };
  }

  onNotification(msg) {
    if (msg.method === 'session/update') {
      const params = msg.params || {};
      const sessionId = params.sessionId;
      const update = params.update;
      if (!sessionId || !update) return;
      this.recordUpdate(sessionId, update);
      this.broadcast({ type: 'event', event: 'session.update', data: { sessionId, update } });
      return;
    }
    if (msg.method === 'session/cancel') return;
    this.broadcast({ type: 'event', event: 'harness.log', data: { line: 'Notificação: ' + msg.method } });
  }

  toolKey(sessionId, id) { return sessionId + '\u0000' + id; }

  recordUpdate(sessionId, update) {
    const active = this.activePrompts.get(sessionId);
    if (active) active.lastEventAt = Date.now();
    const kind = update.sessionUpdate;
    const sess = this.sessions.get(sessionId);
    if (sess) sess.updatedAt = Date.now();
    if (kind === 'agent_message_chunk') {
      const text = contentText(update.content);
      if (!text) return;
      let transcript = this.transcripts.get(sessionId);
      if (!transcript) { transcript = this.parseLog(sessionId); this.transcripts.set(sessionId, transcript); }
      let last = this.openAssistant.get(sessionId);
      if (!last || transcript[transcript.length - 1] !== last) {
        last = { role: 'assistant', text: '', time: Date.now() };
        transcript.push(last);
        this.openAssistant.set(sessionId, last);
      }
      last.text += text;
      return;
    }
    if (kind === 'tool_call' || kind === 'tool_call_update') {
      const id = update.toolCallId;
      if (id) {
        const key = this.toolKey(sessionId, id);
        const prev = this.toolCalls.get(key) || {};
        this.toolCalls.set(key, {
          title: update.title || prev.title || 'Ferramenta',
          kind: update.kind || prev.kind || 'other',
          status: update.status || prev.status || 'pending',
          rawInput: update.rawInput !== undefined ? update.rawInput : prev.rawInput,
          content: update.content !== undefined ? update.content : prev.content,
        });
      }
      this.openAssistant.delete(sessionId);
      return;
    }
    if (kind === 'config_option_update') {
      if (sess && Array.isArray(update.configOptions)) {
        sess.configOptions = update.configOptions;
        sess.model = this.modelFromConfigOptions(update.configOptions);
      }
    }
  }

  onRequest(msg) {
    if (msg.method === 'session/request_permission') {
      const params = msg.params || {};
      const requestId = 'perm-' + randomUUID();
      const toolCall = params.toolCall || {};
      const key = toolCall.toolCallId ? this.toolKey(params.sessionId, toolCall.toolCallId) : null;
      const details = key ? this.toolCalls.get(key) || null : null;
      const entry = { requestId, sessionId: params.sessionId, toolCall, toolCallDetails: details, options: params.options || [], jsonRpcId: msg.id };
      this.pendingPermissions.set(requestId, entry);
      log('[perm] request ' + requestId + ' session=' + params.sessionId + ' options=' + entry.options.map((o) => o.optionId).join(','));
      this.broadcast({
        type: 'event',
        event: 'session.permission',
        data: { requestId, sessionId: params.sessionId, toolCall, toolCallDetails: details, options: entry.options },
      });
      return;
    }
    const err = { code: -32601, message: 'Método não suportado pelo cliente: ' + msg.method };
    this.runtime.write({ jsonrpc: '2.0', id: msg.id, error: err });
  }

  onRuntimeExit(code, signal, wasReady) {
    log('[acp] exited code=' + code + ' signal=' + signal + ' wasReady=' + wasReady);
    for (const [sessionId] of this.activePrompts) {
      this.activePrompts.delete(sessionId);
      this.broadcast({ type: 'event', event: 'session.promptDone', data: { sessionId, stopReason: 'error', error: 'O runtime do Harness caiu.' } });
    }
    for (const [requestId, p] of this.pendingPermissions) {
      this.pendingPermissions.delete(requestId);
      this.broadcast({ type: 'event', event: 'session.permissionResolved', data: { requestId } });
    }
    for (const sid of Array.from(this.queues.keys())) this.clearQueue(sid, 'runtime caiu');
    this.promoteItems.clear();
    this.sessions.clear();
    this.transcripts.clear();
    this.openAssistant.clear();
  }

  async shutdown(reason) {
    if (this.shuttingDown) return;
    this.shuttingDown = true;
    log('[bridge] shutdown: ' + reason);
    try { await this.webStop(); } catch {}
    try { await this.runtime.stop(); } catch {}
    try { this.server.close(); } catch {}
    for (const client of this.clients) { try { client.socket.destroy(); } catch {} }
    try { writeFileSync(path.join(STATE_DIR, 'bridge.json'), JSON.stringify({ stoppedAt: Date.now() })); } catch {}
    process.exit(0);
  }
}

const bridge = new Bridge(opts);
await bridge.listen();
setInterval(() => {
  if (bridge.shuttingDown) return;
  if (opts.autoStart || opts.idleMinutes <= 0) return;
  const idleFor = Date.now() - bridge.lastClientAt;
  if (bridge.clients.size === 0 && bridge.activePrompts.size === 0 && idleFor > opts.idleMinutes * 60 * 1000) {
    bridge.shutdown('idle').catch(() => {});
  }
}, 60000).unref();
setInterval(() => {
  if (bridge.shuttingDown) return;
  const now = Date.now();
  for (const [sessionId, prompt] of bridge.activePrompts) {
    const last = prompt.lastEventAt || prompt.startedAt || now;
    const idle = now - last;
    if (idle > 15 * 60 * 1000 && (!prompt.lastStallWarn || now - prompt.lastStallWarn > 10 * 60 * 1000)) {
      prompt.lastStallWarn = now;
      bridge.broadcast({ type: 'event', event: 'session.promptStalled', data: { sessionId, idleMs: idle } });
    }
  }
}, 60000).unref();

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => { bridge.shutdown('signal ' + sig).catch(() => {}); });
}
process.on('uncaughtException', (err) => { log('[fatal] ' + (err && err.stack ? err.stack : String(err))); });
process.on('unhandledRejection', (err) => { log('[unhandled] ' + (err && err.stack ? err.stack : String(err))); });

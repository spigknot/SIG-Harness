import http from 'node:http';
import os from 'node:os';

const PORT = 3080;
const HOP = new Set(['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'te', 'trailer', 'transfer-encoding', 'upgrade']);

function isTailscaleIPv4(address) {
  const [a, b] = (address || '').split('.').map(Number);
  return a === 100 && b >= 64 && b <= 127;
}

function tailscaleAddress() {
  for (const [name, addresses] of Object.entries(os.networkInterfaces())) {
    if (!/^tun\d+$/.test(name)) continue;
    for (const item of addresses) {
      if (item.family !== 'IPv4' || item.internal) continue;
      if (isTailscaleIPv4(item.address)) return item.address;
    }
  }
  return null;
}

function hosts(ip) {
  const names = ip === '100.108.27.64' ? ['oneplus15', 'oneplus15.tail362d30.ts.net', ip] : [ip];
  return names.map(name => name + ':' + PORT);
}

function headersWithoutHop(headers) {
  const result = {};
  const names = String(headers.connection || '').toLowerCase().split(',').map(x => x.trim());
  for (const [key, value] of Object.entries(headers)) {
    if (!HOP.has(key) && !names.includes(key)) result[key] = value;
  }
  return result;
}

export class TailscaleWeb {
  constructor(getAddress = tailscaleAddress) {
    this.getAddress = getAddress;
    this.server = null;
    this.sockets = new Set();
    this.address = null;
    this.target = null;
    this.upstreamAuth = null;
    this.authUntil = 0;
  }

  state() {
    if (!this.address || !this.server?.listening) return {};
    const [preferred] = hosts(this.address);
    return { address: 'http://' + preferred, ipAddress: 'http://' + this.address + ':' + PORT };
  }

  async open(url) {
    const ip = this.getAddress();
    if (!ip) { this.close(); return { unavailable: 'Conecte o Tailscale no OnePlus para liberar o acesso.' }; }
    if (this.target !== url) { this.target = url; this.upstreamAuth = null; this.authUntil = 0; }
    if (this.address === ip && this.server?.listening) return this.state();
    if (this.server) {
      this.close();
      this.target = url;
    }
    const server = http.createServer({ requestTimeout: 20000, headersTimeout: 10000 }, (req, res) => {
      this.handle(req, res).catch(() => {
        if (res.headersSent) res.destroy();
        else res.writeHead(502).end('O DSH ainda não está disponível.');
      });
    });
    server.on('upgrade', (req, socket, head) => this.upgrade(req, socket, head));
    server.on('connection', socket => {
      if (!isTailscaleIPv4(socket.remoteAddress)) {
        socket.destroy();
        return;
      }
      this.sockets.add(socket);
      socket.once('close', () => this.sockets.delete(socket));
    });
    this.server = server;
    try {
      await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(PORT, ip, resolve);
      });
      if (server !== this.server) return {};
      this.address = ip;
      return this.state();
    } catch {
      if (server === this.server) this.close();
      return { unavailable: 'Não foi possível abrir a porta 3080 no endereço Tailscale do OnePlus.' };
    }
  }

  close() {
    const server = this.server;
    this.server = null;
    this.address = null;
    this.target = null;
    this.upstreamAuth = null;
    this.authUntil = 0;
    if (server) {
      server.close();
      for (const socket of this.sockets) socket.destroy();
    }
  }

  origin(req) {
    const host = String(req.headers.host || '').toLowerCase();
    return isTailscaleIPv4(req.socket.remoteAddress) && this.address && hosts(this.address).includes(host)
      ? 'http://' + host : null;
  }

  async credentials() {
    if (!this.target) throw new Error('DSH indisponível');
    if (!this.upstreamAuth || this.authUntil <= Date.now()) {
      this.authUntil = Date.now() + 5 * 60_000;
      this.upstreamAuth = new Promise((resolve, reject) => {
        const url = new URL(this.target);
        const req = http.get(url, { timeout: 5000 }, res => {
          res.resume();
          const cookie = (res.headers['set-cookie'] || []).find(item => item.startsWith('dsh-auth-'));
          if (!cookie || ![302, 303].includes(res.statusCode)) return reject(new Error('Login local indisponível'));
          resolve({ port: url.port, host: url.host, cookie: cookie.split(';', 1)[0] });
        });
        req.on('timeout', () => req.destroy(new Error('Timeout')));
        req.on('error', reject);
      }).catch(error => { this.upstreamAuth = null; throw error; });
    }
    return this.upstreamAuth;
  }

  upstreamHeaders(req, auth) {
    const headers = headersWithoutHop(req.headers);
    headers.host = auth.host;
    headers.cookie = auth.cookie;
    if (headers.origin) headers.origin = 'http://' + auth.host;
    for (const key of ['referer', 'forwarded', 'x-forwarded-host', 'x-forwarded-for',
      'x-forwarded-proto', 'x-forwarded-port', 'x-real-ip', 'authorization']) delete headers[key];
    return headers;
  }

  async handle(req, res) {
    const origin = this.origin(req);
    if (!origin || !req.url?.startsWith('/') || req.url.startsWith('//')) return res.writeHead(400).end();
    const opening = req.method === 'GET' && req.url === '/' && req.headers['sec-fetch-mode'] === 'navigate'
      && req.headers['sec-fetch-dest'] === 'document';
    if ((!opening && req.headers['sec-fetch-site'] === 'cross-site') ||
        (req.headers.origin && req.headers.origin !== origin) ||
        (!['GET', 'HEAD', 'OPTIONS'].includes(req.method) && req.headers.origin !== origin)) {
      return res.writeHead(403).end();
    }
    const auth = await this.credentials();
    if (res.destroyed) return;
    const upstream = http.request({
      host: '127.0.0.1', port: auth.port, method: req.method, path: req.url,
      headers: this.upstreamHeaders(req, auth),
    }, response => {
      const headers = headersWithoutHop(response.headers);
      delete headers['set-cookie'];
      const prefix = 'http://' + auth.host;
      if (headers.location?.startsWith(prefix + '/')) headers.location = headers.location.slice(prefix.length);
      res.writeHead(response.statusCode, headers);
      response.on('error', () => res.destroy());
      response.pipe(res);
    });
    res.once('close', () => upstream.destroy());
    upstream.on('error', () => {
      if (res.headersSent) res.destroy();
      else if (!res.destroyed) res.writeHead(502).end();
    });
    req.pipe(upstream);
  }

  upgrade(req, socket, head) {
    socket.on('error', () => socket.destroy());
    const origin = this.origin(req);
    if (!origin || req.headers.origin !== origin ||
        !req.url?.startsWith('/') || req.url.startsWith('//') || req.headers.upgrade?.toLowerCase() !== 'websocket') {
      socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');
      return;
    }
    socket.pause();
    this.credentials().then(auth => {
      if (socket.destroyed) return;
      const headers = this.upstreamHeaders(req, auth);
      headers.connection = 'Upgrade';
      headers.upgrade = 'websocket';
      const upstream = http.request({ host: '127.0.0.1', port: auth.port, path: req.url, headers, timeout: 10000 });
      socket.once('close', () => upstream.destroy());
      upstream.on('timeout', () => { upstream.destroy(); socket.destroy(); });
      upstream.on('error', () => socket.destroy());
      upstream.on('response', res => { res.resume(); socket.destroy(); });
      upstream.on('upgrade', (res, peer, upstreamHead) => {
        upstream.setTimeout(0);
        peer.on('error', () => socket.destroy());
        peer.once('close', () => socket.destroy());
        socket.once('close', () => peer.destroy());
        let response = 'HTTP/1.1 101 Switching Protocols\r\n';
        for (const [key, value] of Object.entries(res.headers)) if (key !== 'set-cookie') response += key + ': ' + value + '\r\n';
        socket.write(response + '\r\n');
        if (upstreamHead.length) socket.write(upstreamHead);
        if (head.length) peer.write(head);
        socket.pipe(peer).pipe(socket);
        socket.resume();
      });
      upstream.end();
    }).catch(() => socket.destroy());
  }
}

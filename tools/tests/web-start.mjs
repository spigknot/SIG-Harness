#!/usr/bin/env node
// Sobe e derruba o host web pelo protocolo do bridge, sem abrir o app.
// Uso: node tools/tests/web-start.mjs <token> <porta>
import net from 'node:net';

const token = process.argv[2];
const port = Number(process.argv[3] || 43117);
if (!token) {
  console.error('uso: node tools/tests/web-start.mjs <token> [porta]');
  process.exit(2);
}

const socket = net.connect(port, '127.0.0.1');
socket.setEncoding('utf8');
let buffer = '';
let nextId = 1;
const pending = new Map();

function send(obj) {
  socket.write(JSON.stringify(obj) + '\n');
}

function request(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = nextId++;
    pending.set(id, { resolve, reject });
    send({ type: 'request', id, method, params });
  });
}

const welcome = new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error('timeout no welcome')), 5000);
  socket.on('data', (chunk) => {
    buffer += chunk;
    let at;
    while ((at = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, at);
      buffer = buffer.slice(at + 1);
      if (!line.trim()) continue;
      const message = JSON.parse(line);
      if (message.type === 'welcome') {
        clearTimeout(timer);
        resolve(message);
      } else if (message.type === 'response') {
        const entry = pending.get(message.id);
        if (!entry) continue;
        pending.delete(message.id);
        if (message.ok) entry.resolve(message.result);
        else entry.reject(Object.assign(new Error(message.error?.message || 'erro'), { code: message.error?.code }));
      }
    }
  });
  socket.on('error', reject);
});

socket.on('connect', () => send({ type: 'hello', token }));

try {
  await welcome;
  await request('web.stop');
  const web = await request('web.start');
  console.log('WEB ' + JSON.stringify({
    owned: web.owned,
    port: web.port,
    url: String(web.url).replace(/token=[^&]+/, 'token=***'),
  }));
  await request('web.stop');
  console.log('WEB STOPPED');
} catch (error) {
  console.error('ERR ' + (error.code || '') + ' ' + error.message);
  process.exitCode = 1;
} finally {
  socket.end();
}

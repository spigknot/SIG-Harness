// Select or start the private SIG bridge after the installation has been checked.
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');

const [action, dir, base, token, cwd, revision] = process.argv.slice(2);
const bridge = path.join(dir, 'sig-bridge.mjs');
const state = path.join(dir, 'bridge.json');
const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

function running() {
  try {
    const info = JSON.parse(fs.readFileSync(state, 'utf8'));
    if (!Number.isSafeInteger(info.pid) || info.pid <= 1) return null;
    const args = fs.readFileSync('/proc/' + info.pid + '/cmdline', 'utf8').split('\0');
    if (args[0] !== process.execPath || args[1] !== bridge) return null;
    return { ...info, args };
  } catch { return null; }
}

async function stop(info) {
  if (!info) return;
  try { process.kill(info.pid, 'SIGTERM'); } catch (error) {
    if (error.code === 'ESRCH') return;
    throw error;
  }
  for (let i = 0; i < 100; i++) {
    if (running()?.pid !== info.pid) return;
    await sleep(100);
  }
  throw new Error('A ponte anterior ainda está encerrando.');
}

async function main() {
  const existing = running();
  if (action === 'stop') return stop(existing);
  const args = existing?.args || [];
  const arg = (flag) => args.includes(flag) ? args[args.indexOf(flag) + 1] : '';
  if (existing && arg('--revision') === revision && arg('--token') === token
      && arg('--cwd') === cwd && existing.port >= Number(base) && existing.port < Number(base) + 12) {
    console.log('SIG_READY', existing.port, existing.pid);
    return;
  }
  await stop(existing);
  const log = path.join(dir, 'bridge.log');
  try {
    if (fs.statSync(log).size > 5 * 1024 * 1024) fs.renameSync(log, log + '.1');
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  const fd = fs.openSync(log, 'a', 0o600);
  const child = spawn(process.execPath, [bridge, '--port', base, '--token', token,
    '--cwd', cwd, '--revision', revision], { detached: true, stdio: ['ignore', fd, fd] });
  fs.closeSync(fd);
  let failed = false;
  child.once('error', () => { failed = true; });
  child.unref();
  for (let i = 0; i < 120 && !failed && child.exitCode === null; i++) {
    const info = running();
    if (info && info.pid === child.pid) {
      console.log('SIG_READY', info.port, info.pid);
      return;
    }
    await sleep(100);
  }
  try { child.kill(); } catch {}
  throw new Error('A ponte local não abriu. Consulte ~/.sig-harness/bridge.log.');
}

main().catch(err => { console.error(err.message); process.exitCode = 1; });

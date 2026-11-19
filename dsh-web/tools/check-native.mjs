// Checks that the Android addon loads and that the actual DSH flock handles contention.
import { closeSync, mkdtempSync, openSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const root = process.argv[2];
const load = createRequire(join(root, 'package.json'));
load('node-addon-require-builtin');
const { tryLockExclusive } = await import(pathToFileURL(join(
  root, 'node_modules/@deepseek-ai/node-addon-system/lib/flock.js'
)).href);
const temp = mkdtempSync(join(tmpdir(), 'sig-flock-'));
const lock = join(temp, 'probe');
const first = openSync(lock, 'a');
const second = openSync(lock, 'a');
try {
  await tryLockExclusive(first);
  try {
    await tryLockExclusive(second);
    throw new Error('flock concedeu dois locks exclusivos.');
  } catch (error) {
    if (error.code !== 'EAGAIN' && error.code !== 'EWOULDBLOCK') throw error;
  }
} finally {
  closeSync(second);
  closeSync(first);
  rmSync(temp, { recursive: true, force: true });
}

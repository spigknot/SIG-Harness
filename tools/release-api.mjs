#!/usr/bin/env node
/**
 * Apoio ao tools/release.sh: manifesto, release e conferência.
 * O token vem de GH_TOKEN (nunca é impresso).
 *
 *   node tools/release-api.mjs version            -> "1.3.3 33"
 *   node tools/release-api.mjs bump [--dry-run]   -> incrementa versionCode e versionName
 *   node tools/release-api.mjs publish <tag>      -> cria a release e envia o APK
 *   node tools/release-api.mjs verify <tag> <apk> -> confere download público
 */
import { readFileSync, writeFileSync, statSync, createReadStream } from 'node:fs';

const REPO = 'spigknot/SIG-Harness';
const ROOT = new URL('..', import.meta.url).pathname;
const MANIFEST = ROOT + 'app/AndroidManifest.xml';
const [cmd, ...args] = process.argv.slice(2);

function manifest() {
  const xml = readFileSync(MANIFEST, 'utf8');
  const code = Number(/android:versionCode="(\d+)"/.exec(xml)[1]);
  const name = /android:versionName="([^"]+)"/.exec(xml)[1];
  return { xml, code, name };
}

function bump(dry) {
  const { xml, code, name } = manifest();
  const parts = name.split('.');
  parts[parts.length - 1] = String(Number(parts[parts.length - 1]) + 1);
  const nextName = parts.join('.');
  const nextCode = code + 1;
  if (!dry) {
    writeFileSync(MANIFEST, xml
      .replace(`android:versionCode="${code}"`, `android:versionCode="${nextCode}"`)
      .replace(`android:versionName="${name}"`, `android:versionName="${nextName}"`));
  }
  console.log(`${nextName} ${nextCode}`);
}

async function api(path, init = {}) {
  const token = process.env.GH_TOKEN;
  if (!token) throw new Error('GH_TOKEN ausente');
  const res = await fetch(`https://api.github.com/repos/${REPO}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json', ...(init.headers || {}) }
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* resposta vazia */ }
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${text.slice(0, 200)}`);
  return json;
}

async function publish(tag) {
  const { name } = manifest();
  const version = name;
  const apkPath = ROOT + 'dist/SIG-Harness.apk';
  const exists = await api(`/releases/tags/${tag}`).catch(() => null);
  const release = exists ?? await api('/releases', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      tag_name: tag,
      name: `SIG Harness ${version}`,
      target_commitish: 'main',
      body: `APK autossuficiente: aplica patches, plugins, perfil e addon nativo no boot.\n\nversionName ${version}`
    })
  });
  const assetName = `SIG-Harness-${version}.apk`;
  const already = (release.assets || []).find((a) => a.name === assetName);
  if (already) { console.log(`asset já presente: ${already.size} bytes`); return; }
  const url = String(release.upload_url).replace('{?name,label}', `?name=${assetName}`);
  const size = statSync(apkPath).size;
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${process.env.GH_TOKEN}`,
      'content-type': 'application/vnd.android.package-archive',
      'content-length': String(size)
    },
    body: createReadStream(apkPath),
    duplex: 'half'
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`upload HTTP ${res.status}: ${JSON.stringify(json).slice(0, 200)}`);
  console.log(`asset publicado: ${json.name} · ${json.size} bytes`);
  console.log(json.browser_download_url);
}

async function verify(tag, apkPath) {
  const release = await api(`/releases/tags/${tag}`);
  const asset = (release.assets || [])[0];
  if (!asset) throw new Error('release sem assets');
  const local = statSync(apkPath).size;
  if (asset.size !== local) throw new Error(`tamanho divergente: release ${asset.size} vs local ${local}`);
  const res = await fetch(asset.browser_download_url, { method: 'HEAD', redirect: 'follow' });
  if (!res.ok) throw new Error(`download público HTTP ${res.status}`);
  console.log(`release ok: ${asset.name} · ${asset.size} bytes · download HTTP ${res.status}`);
}

if (cmd === 'version') {
  const { code, name } = manifest();
  console.log(`${name} ${code}`);
} else if (cmd === 'bump') {
  bump(args.includes('--dry-run'));
} else if (cmd === 'publish') {
  await publish(args[0]);
} else if (cmd === 'verify') {
  await verify(args[0], args[1]);
} else {
  console.error('comando inválido: ' + cmd);
  process.exit(2);
}

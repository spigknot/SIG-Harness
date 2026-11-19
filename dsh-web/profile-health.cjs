// Manages only the Web profile and the plugins shipped in this APK.
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const { spawnSync } = require('node:child_process');
const [dsh, profile, action] = process.argv.slice(2);
const yaml = createRequire(path.join(dsh, 'package.json'))('yaml');
const source = path.join(__dirname, 'profile');
const plugins = path.join(__dirname, 'plugin');
const modules = path.join(profile, 'node_modules');
const pluginNames = fs.readdirSync(plugins);
const pinnedPackage = JSON.parse(fs.readFileSync(path.join(source, 'package.json'), 'utf8'));
const bundles = pinnedPackage.dsh.profile.bundles;
const read = (file) => fs.readFileSync(file, 'utf8');
const json = (file) => { try { return JSON.parse(read(file)); } catch { return null; } };
const nonempty = (file) => { try { return fs.statSync(file).size > 0; } catch { return false; } };

function write(name, text) {
  const target = path.join(profile, name);
  const temp = target + '.sig-next';
  fs.writeFileSync(temp, text, { mode: 0o600 });
  fs.renameSync(temp, target);
}

function validYaml(file, validShape) {
  try {
    const doc = yaml.parseDocument(read(file));
    return !doc.errors.length && validShape(doc.toJSON()) ? doc : null;
  } catch { return null; }
}

function ensureYaml(name, shape) {
  const file = path.join(profile, name);
  if (validYaml(file, shape)) return;
  if (fs.existsSync(file)) fs.copyFileSync(file, file + '.sig-backup-' + Date.now());
  write(name, read(path.join(source, name)));
}

function hasBundles(pack) {
  const installed = pack?.dsh?.profile?.bundles;
  return !!pack?.dependencies?.['dsh-web-mobile'] && Array.isArray(installed)
    && bundles.every(name => installed.includes(name));
}

function prepare() {
  fs.mkdirSync(profile, { recursive: true });
  const manifest = path.join(profile, 'package.json');
  const pack = json(manifest);
  if (!hasBundles(pack)) {
    if (fs.existsSync(manifest)) fs.copyFileSync(manifest, manifest + '.sig-backup-' + Date.now());
    const next = pack && typeof pack === 'object' && !Array.isArray(pack) ? pack : {};
    const oldBundles = next.dsh?.profile?.bundles;
    next.name ||= pinnedPackage.name;
    next.private = true;
    next.dependencies = { ...next.dependencies, ...pinnedPackage.dependencies };
    next.dsh = { ...next.dsh, profile: { ...next.dsh?.profile, bundles: [
      ...new Set([...(Array.isArray(oldBundles) ? oldBundles : []), ...bundles]),
    ] } };
    write('package.json', JSON.stringify(next, null, 2) + '\n');
  }
  ensureYaml('pnpm-workspace.yaml', value => !!value && typeof value === 'object' && !Array.isArray(value));
  ensureYaml('cordis.yml', Array.isArray);
  ensureYaml('cordis.patch.yml', Array.isArray);
}

function bundleOk() {
  const mobile = path.join(modules, 'dsh-web-mobile');
  return hasBundles(json(path.join(profile, 'package.json')))
    && json(path.join(mobile, 'package.json'))?.name === 'dsh-web-mobile'
    && ['lib/index.js', 'lib/client.js', 'cordis.patch.yml'].every(file => nonempty(path.join(mobile, file)))
    && bundles.filter(name => name !== 'dsh-web-mobile').every(name =>
      nonempty(path.join(dsh, 'node_modules', name, 'package.json')));
}

function pluginOk(name) {
  const from = path.join(plugins, name);
  const to = path.join(modules, name);
  const version = json(path.join(to, 'package.json'))?.version;
  if (typeof version !== 'string') return false;
  const expected = json(path.join(from, 'package.json')).version;
  if (version.localeCompare(expected, 'en', { numeric: true }) > 0) {
    return nonempty(path.join(to, 'lib/index.js')) && nonempty(path.join(to, 'lib/client.js'));
  }
  return spawnSync('diff', ['-qr', from, to], { stdio: 'ignore' }).status === 0;
}

function patchEntries(entries) {
  const additions = [];
  const byId = id => entries.filter(item => item?.id === id).at(-1);
  const inserted = entries.flatMap(item => Array.isArray(item?.insert) ? item.insert : []);
  const baseline = yaml.parse(read(path.join(source, 'cordis.patch.yml')));
  const managedIds = ['llm-deepseek-account', 'ui-settings-account', 'web', 'session-query-sqlite'];
  // Um primeiro boot válido pode deixar apenas o perfil padrão. Complete os
  // ajustes do APK e preserve as entradas já personalizadas pelo usuário.
  for (const item of baseline) {
    if (item.id && !managedIds.includes(item.id) && !byId(item.id)) additions.push(item);
  }
  for (const name of pluginNames) {
    if (!inserted.some(item => item?.id === name && item.name === name)) {
      additions.push({ insert: [{ id: name, name }] });
    }
  }
  for (const id of ['llm-deepseek-account', 'ui-settings-account']) {
    if (byId(id)?.disabled !== true) additions.push({ id, disabled: true });
  }
  const web = byId('web')?.config;
  if (web?.searchProvider !== 'tavily' || !web?.fetchProvider) {
    additions.push({ id: 'web', config: { fetchProvider: 'http', ...web, searchProvider: 'tavily' } });
  }
  if (!byId('session-query-sqlite')?.config?.path) {
    additions.push({ id: 'session-query-sqlite', config: {
      path: path.join(process.env.HOME, '.dsh', 'session-search.db'), openAt: 'first-search',
    } });
  }
  return additions;
}

function apply() {
  fs.mkdirSync(modules, { recursive: true });
  for (const name of pluginNames) {
    if (pluginOk(name)) continue;
    const target = path.join(modules, name);
    const temp = fs.mkdtempSync(path.join(profile, '.sig-plugin-'));
    const next = path.join(temp, 'next');
    const old = path.join(temp, 'old');
    try {
      fs.cpSync(path.join(plugins, name), next, { recursive: true });
      try { fs.renameSync(target, old); } catch (error) { if (error.code !== 'ENOENT') throw error; }
      try { fs.renameSync(next, target); } catch (error) {
        if (fs.existsSync(old)) fs.renameSync(old, target);
        throw error;
      }
    } finally { fs.rmSync(temp, { recursive: true, force: true }); }
  }
  const cfg = validYaml(path.join(profile, 'cordis.patch.yml'), Array.isArray);
  if (!cfg) throw new Error('Configuração web do DSH inválida.');
  const additions = patchEntries(cfg.toJSON());
  if (additions.length) {
    for (const item of additions) cfg.add(item);
    write('cordis.patch.yml', cfg.toString());
  }
}

function check() {
  const cfg = validYaml(path.join(profile, 'cordis.patch.yml'), Array.isArray);
  return !!cfg && !patchEntries(cfg.toJSON()).length
    && validYaml(path.join(profile, 'cordis.yml'), Array.isArray)
    && validYaml(path.join(profile, 'pnpm-workspace.yaml'), value => !!value && typeof value === 'object')
    && bundleOk() && pluginNames.every(pluginOk);
}

try {
  if (action === 'prepare') prepare();
  else if (action === 'apply') apply();
  else if (!(action === 'bundles' ? bundleOk() : check())) process.exitCode = 1;
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}

#!/usr/bin/env node
// Extrai do cache do npm a cópia pristine (publicada) de um pacote. Funciona offline.
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import crypto from "node:crypto";

const [name, version, out] = process.argv.slice(2);
if (!name || !version || !out) {
  console.error("uso: pristine.mjs <pacote> <versao> <saida.tgz>");
  process.exit(2);
}

const short = name.split("/").pop();
const url = "https://registry.npmjs.org/" + name + "/-/" + short + "-" + version + ".tgz";
const cache = path.join(os.homedir(), ".npm", "_cacache");

function walk(dir, onFile) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(p, onFile);
    else onFile(p);
  }
}

let integrity = null;
try {
  walk(path.join(cache, "index-v5"), (file) => {
    if (integrity || !fs.existsSync(file)) return;
    let text;
    try { text = fs.readFileSync(file, "utf8"); } catch { return; }
    if (!text.includes(url)) return;
    for (const line of text.split("\n")) {
      const tab = line.indexOf("\t");
      if (tab < 0) continue;
      try {
        const entry = JSON.parse(line.slice(tab + 1));
        if (String(entry.key).endsWith(url)) integrity = entry.integrity;
      } catch { /* entrada inválida */ }
    }
  });
} catch { /* sem cache */ }

if (!integrity) {
  console.error("cache miss: " + url);
  process.exit(1);
}

const alg = integrity.split("-")[0];
const b64 = integrity.split("-")[1];
const hex = Buffer.from(b64, "base64").toString("hex");
const file = path.join(cache, "content-v2", alg, hex.slice(0, 2), hex.slice(2, 4), hex.slice(4));
const data = fs.readFileSync(file);
if (crypto.createHash("sha512").update(data).digest("base64") !== b64) {
  console.error("integridade não confere: " + file);
  process.exit(1);
}
fs.writeFileSync(out, data);
console.log(out + " (" + data.length + " bytes)");

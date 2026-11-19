#!/data/data/com.termux/files/usr/bin/bash
# Garante que apply.sh restaura o addon e o koffi num DSH "recem-instalado".
set -euo pipefail

export TMPDIR=/data/data/com.termux/files/usr/tmp
export PATH=/data/data/com.termux/files/usr/bin:$PATH

HERE="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

export HOME="$TMP/home"
mkdir -p "$HOME/.dsh"
DSH="$TMP/dsh"
PROFILE="$TMP/profile"
mkdir -p "$DSH/node_modules/@deepseek-ai" \
         "$DSH/node_modules/node-addon-require-builtin" \
         "$DSH/node_modules/koffi" \
         "$PROFILE/node_modules"
printf '{"name":"@deepseek-ai/dsh","version":"0.2.1-rc.5"}\n' > "$DSH/package.json"

# Evita que ensure_profile_bundles rode o dsh real contra o profile falso.
mkdir -p "$TMP/bin"
printf '#!/bin/sh\nexit 0\n' > "$TMP/bin/dsh"
chmod +x "$TMP/bin/dsh"
export PATH="$TMP/bin:$PATH"

SIG_DSH_ROOT="$DSH" SIG_PROFILE_PATH="$PROFILE" \
  bash "$HERE/dsh-web/apply.sh" --auto > "$TMP/out.log" 2>&1 || {
    cat "$TMP/out.log"
    exit 1
  }

ADDON="$DSH/node_modules/node-addon-require-builtin/build/napi/napi-v9-android-arm64/require_builtin.node"
KOFFI="$DSH/node_modules/koffi/build/koffi/android_arm64/koffi.node"

[ -f "$ADDON" ] || { echo "FALHOU: addon ausente"; cat "$TMP/out.log"; exit 1; }
[ -f "$KOFFI" ] || { echo "FALHOU: koffi ausente"; cat "$TMP/out.log"; exit 1; }
cmp -s "$HERE/dsh-web/artifacts/require_builtin-napi-v9-android-arm64.node" "$ADDON" \
  || { echo "FALHOU: addon diferente do artefato"; exit 1; }
cmp -s "$HERE/dsh-web/artifacts/koffi-android-arm64.node" "$KOFFI" \
  || { echo "FALHOU: koffi diferente do artefato"; exit 1; }

echo "ok: addon e koffi restaurados"

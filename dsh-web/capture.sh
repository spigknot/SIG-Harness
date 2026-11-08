#!/data/data/com.termux/files/usr/bin/bash
# Regenera dsh-web/patches/* comparando o pacote publicado com o que está
# instalado. Rode depois de atualizar o DSH, revise os diffs e commite.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DSH="${DSH_ROOT:-/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh}"
WORK="${WORK:-$HOME/.cache/sig-dsh-capture}"
# shellcheck disable=SC1091
. "$HERE/pin.env"

INSTALLED="$(node -p "require('$DSH/package.json').version")"
if [ "$INSTALLED" != "$DSH_VERSION" ]; then
  echo "aviso: instalado $INSTALLED, pin.env diz $DSH_VERSION — ajuste pin.env primeiro" >&2
  exit 1
fi

mkdir -p "$WORK/pristine"
for spec in $DSH_PACKAGES; do
  pkg="@@{spec%@*}"
  ver="@@{spec##*@}"
  if [ -f "$WORK/pristine/$pkg/.version" ] && [ "$(cat "$WORK/pristine/$pkg/.version")" = "$ver" ]; then
    continue
  fi
  tb="$WORK/pristine/$pkg.tgz"
  rm -f "$tb"
  if ! node "$HERE/tools/pristine.mjs" "@deepseek-ai/$pkg" "$ver" "$tb" 2>/dev/null; then
    echo "cache miss em $pkg@$ver — baixando com npm pack" >&2
    ( cd "$WORK/pristine" && npm pack "@deepseek-ai/$pkg@$ver" >/dev/null )
    mv "$WORK/pristine/$pkg-$ver.tgz" "$tb"
  fi
  rm -rf "$WORK/pristine/$pkg"
  mkdir -p "$WORK/pristine/$pkg"
  tar xzf "$tb" -C "$WORK/pristine/$pkg"
  printf '%s' "$ver" > "$WORK/pristine/$pkg/.version"
done

PATCH_SETS=(
  "0001-attachment-local-android-durability.patch|dsh-attachment-local/lib/index.js"
  "0002-conversation-error-detail.patch|dsh-client-ui-conversation/lib/client.js"
  "0003-directory-picker-start-directory.patch|dsh-host-directory-picker-browse/lib/index.js dsh-host-directory-picker-browse/README.md dsh-host-directory-picker-auto/lib/index.js dsh-host-directory-picker-auto/README.md"
  "0004-node-addon-flock-android-ffi.patch|node-addon-system/lib/flock.js"
  "0005-session-persistence-link-fallback.patch|dsh-session-persistence-jsonl/lib/index.js"
)

for set in "${PATCH_SETS[@]}"; do
  out="${set%%|*}"
  files="${set#*|}"
  : > "$HERE/patches/$out"
  for pair in $files; do
    pkg="${pair%%/*}"
    rel="${pair#*/}"
    diff -u --label "a/node_modules/@deepseek-ai/$pkg/$rel" --label "b/node_modules/@deepseek-ai/$pkg/$rel" "$WORK/pristine/$pkg/package/$rel" "$DSH/node_modules/@deepseek-ai/$pkg/$rel" >> "$HERE/patches/$out" || true
  done
  if [ -s "$HERE/patches/$out" ]; then
    echo "gerado  $out"
  else
    rm -f "$HERE/patches/$out"
    echo "sem diferenças  $out (removido)"
  fi
done

capture_artifact

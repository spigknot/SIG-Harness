#!/data/data/com.termux/files/usr/bin/bash
# Regenera dsh-web/patches/* e o artefato nativo a partir do DSH instalado.
# Rode depois de atualizar o DSH, revise os diffs e commite.
#
# Um patch por ARQUIVO: dois patches sobre o mesmo arquivo não podem coexistir,
# porque cada um é um diff completo contra o pacote publicado.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DSH="${SIG_DSH_ROOT:-/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh}"
WORK="${WORK:-$HOME/.cache/sig-dsh-capture}"
# shellcheck disable=SC1091
. "$HERE/pin.env"

INSTALLED="$(node -p "require('$DSH/package.json').version" 2>/dev/null || echo desconhecida)"
if [ "$INSTALLED" != "$DSH_VERSION" ]; then
  echo "aviso: instalado $INSTALLED, pin.env diz $DSH_VERSION — ajuste pin.env primeiro" >&2
  exit 1
fi

mkdir -p "$WORK/pristine"
for spec in $DSH_PACKAGES; do
  pkg="${spec%@*}"
  ver="${spec##*@}"
  if [ -f "$WORK/pristine/$pkg/.version" ] && [ "$(cat "$WORK/pristine/$pkg/.version")" = "$ver" ]; then
    continue
  fi
  tb="$WORK/pristine/$pkg.tgz"
  rm -f "$tb"
  if ! node "$HERE/tools/pristine.mjs" "@deepseek-ai/$pkg" "$ver" "$tb" 2>/dev/null; then
    echo "cache miss em $pkg@$ver — baixando com npm pack" >&2
    ( cd "$WORK/pristine" && npm pack "@deepseek-ai/$pkg@$ver" >/dev/null 2>&1 )
    if [ -f "$WORK/pristine/$pkg-$ver.tgz" ]; then mv "$WORK/pristine/$pkg-$ver.tgz" "$tb"; fi
  fi
  if [ ! -f "$tb" ]; then
    echo "erro: não consegui obter $pkg@$ver (sem rede e sem cache)" >&2
    exit 1
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
  "0006-workspace-enter-content-search.patch|dsh-client-ui-workspace/lib/client.js"
)

rm -f "$HERE"/patches/0002-conversation-error-detail-and-views-slot.patch "$HERE"/patches/0006-conversation-views-trailing-slot.patch "$HERE"/patches/0007-workspace-enter-content-search.patch

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

ARTIFACT_SRC="$DSH/node_modules/node-addon-require-builtin/build/napi/napi-v9-android-arm64/require_builtin.node"
if [ -f "$ARTIFACT_SRC" ]; then
  mkdir -p "$HERE/artifacts"
  cp "$ARTIFACT_SRC" "$HERE/artifacts/require_builtin-napi-v9-android-arm64.node"
  ( cd "$HERE/artifacts" && sha256sum require_builtin-napi-v9-android-arm64.node > SHA256SUMS )
  echo "artefato atualizado  require_builtin.node"
fi

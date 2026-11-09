#!/data/data/com.termux/files/usr/bin/bash
# Reaplica as customizações do SIG Harness sobre a instalação do DeepSeek Harness.
#
#   bash dsh-web/apply.sh --dry-run     mostra o que faria
#   bash dsh-web/apply.sh               aplica os patches e instala plugin + addon
#   bash dsh-web/apply.sh --profile     também sobrescreve ~/.dsh/profiles/web (com backup)
#   bash dsh-web/apply.sh --force       aplica mesmo com versão do DSH diferente
#   bash dsh-web/apply.sh --auto        modo APK: nunca falha, pula os patches se a
#                                       versão não bater e só registra o plugin no
#                                       cordis.patch.yml, sem sobrescrever o resto
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DSH="${SIG_DSH_ROOT:-/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh}"
# DSH_PROFILE é exportado pelo harness com o *nome* do profile ("web"), não o caminho.
PROFILE="${SIG_PROFILE_PATH:-$HOME/.dsh/profiles/${DSH_PROFILE:-web}}"
# shellcheck disable=SC1091
. "$HERE/pin.env"

DRY=0; WITH_PROFILE=0; FORCE=0; AUTO=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY=1 ;;
    --profile) WITH_PROFILE=1 ;;
    --force) FORCE=1 ;;
    --auto) AUTO=1 ;;
    -h|--help) sed -n '3,11p' "$0"; exit 0 ;;
    *) echo "argumento desconhecido: $arg" >&2; [ "$AUTO" = 1 ] || exit 2 ;;
  esac
done

status=0
SKIP_PATCHES=0

if [ ! -d "$DSH/node_modules/@deepseek-ai" ]; then
  echo "DSH não encontrado em $DSH" >&2
  if [ "$AUTO" = 1 ]; then SKIP_PATCHES=1; else exit 1; fi
else
  INSTALLED="$(node -p "require('$DSH/package.json').version" 2>/dev/null || echo desconhecida)"
  if [ "$INSTALLED" != "$DSH_VERSION" ]; then
    echo "aviso: DSH instalado é $INSTALLED, mas os patches foram escritos para $DSH_VERSION" >&2
    if [ "$FORCE" = 1 ]; then
      echo "       seguindo por --force" >&2
    elif [ "$AUTO" = 1 ]; then
      echo "       auto: patches ignorados até capture.sh ser rodado" >&2
      SKIP_PATCHES=1
    else
      echo "       rode capture.sh depois de atualizar, ou use --force" >&2
      exit 1
    fi
  fi
fi

if [ "$SKIP_PATCHES" = 0 ]; then
  for p in "$HERE"/patches/*.patch; do
    name="$(basename "$p")"
    if patch -p1 -R --dry-run -s -f -d "$DSH" < "$p" >/dev/null 2>&1; then
      echo "já aplicado   $name"
      continue
    fi
    if ! patch -p1 --dry-run -s -f -d "$DSH" < "$p" >/dev/null 2>&1; then
      echo "não aplica    $name (base mudou: rode capture.sh)" >&2
      status=1
      continue
    fi
    if [ "$DRY" = 1 ]; then echo "aplicaria     $name"; continue; fi
    patch -p1 -s -d "$DSH" < "$p"
    echo "aplicado      $name"
  done
fi

if [ ! -d "$PROFILE" ]; then
  echo "profile ausente  $PROFILE (plugins e config não instalados)"
else
# Não rebaixa plugin: se o profile tem versão mais nova que o payload (correção feita à mão),
# mantém a instalada. Evita que um APK antigo desfaça trabalho local.
version_gt() {
  [ "$1" = "$2" ] && return 1
  [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]
}

  for src in "$HERE"/plugin/*/; do
    [ -d "$src" ] || continue
    PLUGIN="$(basename "$src")"
    if [ "$DRY" = 1 ]; then
      echo "copiaria      plugin $PLUGIN para $PROFILE/node_modules/"
      continue
    fi
    dst="$PROFILE/node_modules/$PLUGIN"
    payload_ver="$(node -p "require('$src/package.json').version" 2>/dev/null || echo 0)"
    installed_ver="$(node -p "require('$dst/package.json').version" 2>/dev/null || echo 0)"
    if [ -d "$dst" ] && version_gt "$installed_ver" "$payload_ver"; then
      echo "plugin mantido $PLUGIN ($installed_ver > $payload_ver do payload)"
      continue
    fi
    mkdir -p "$PROFILE/node_modules"
    rm -rf "${PROFILE:?}/node_modules/$PLUGIN"
    cp -R "$src" "$PROFILE/node_modules/$PLUGIN"
    echo "plugin ok     $PLUGIN"
  done
fi

ARTIFACT_SRC="$HERE/artifacts/require_builtin-napi-v9-android-arm64.node"
ARTIFACT_DST="$DSH/node_modules/node-addon-require-builtin/build/napi/napi-v9-android-arm64/require_builtin.node"
if [ ! -f "$ARTIFACT_SRC" ]; then
  echo "aviso: artefato nativo ausente, o addon não será instalado" >&2
elif [ ! -d "$DSH" ]; then
  :
elif [ "$DRY" = 1 ]; then
  echo "copiaria      addon nativo -> node-addon-require-builtin/build/napi/napi-v9-android-arm64/"
elif [ -f "$ARTIFACT_DST" ] && cmp -s "$ARTIFACT_SRC" "$ARTIFACT_DST"; then
  echo "addon ok      require_builtin.node (já no lugar)"
else
  mkdir -p "$(dirname "$ARTIFACT_DST")"
  cp "$ARTIFACT_SRC" "$ARTIFACT_DST"
  chmod 755 "$ARTIFACT_DST"
  echo "addon ok      require_builtin.node instalado"
fi

ensure_plugin_entry() {
  local cfg="$PROFILE/cordis.patch.yml"
  [ -d "$PROFILE" ] || return 0
  local need=""
  for p in dsh-web-sig-voice dsh-web-sig-find dsh-web-sig-restart dsh-web-search-tavily dsh-web-sig-search-tavily; do
    grep -q "$p" "$cfg" 2>/dev/null || need="$need $p"
  done
  if [ -z "$need" ]; then
    echo "profile ok    plugins já registrados em cordis.patch.yml"
    return 0
  fi
  if [ "$DRY" = 1 ]; then
    echo "registraria   plugins:$need"
    return 0
  fi
  # Arquivo ausente, vazio ou apenas "[]" (lista vazia) recomeça a lista;
  # um arquivo com outras entradas aceita os itens novos no fim.
  local stripped
  stripped="$(sed -e 's/#.*$//' "$cfg" 2>/dev/null | tr -d '[:space:]')"
  if [ ! -f "$cfg" ] || [ -z "$stripped" ] || [ "$stripped" = "[]" ]; then
    : > "$cfg"
  fi
  for p in $need; do
    printf "\n# Plugin local do SIG Harness.\n- insert:\n    - id: %s\n      name: '%s'\n" "$p" "$p" >> "$cfg"
  done
  echo "profile ok    plugins registrados:$need"
}

ensure_index_entry() {
  local cfg="$PROFILE/cordis.patch.yml"
  [ -d "$PROFILE" ] || return 0
  if grep -q 'session-query-sqlite' "$cfg" 2>/dev/null; then
    echo "profile ok    índice de busca já habilitado"
    return 0
  fi
  if [ "$DRY" = 1 ]; then
    echo "habilitaria   índice FTS5 em $cfg"
    return 0
  fi
  local stripped
  stripped="$(sed -e 's/#.*$//' "$cfg" 2>/dev/null | tr -d '[:space:]')"
  if [ ! -f "$cfg" ] || [ -z "$stripped" ] || [ "$stripped" = "[]" ]; then
    : > "$cfg"
  fi
  printf "\n# Busca full-text no histórico (FTS5 do DSH), aberto na primeira busca.\n- id: session-query-sqlite\n  config:\n    path: %s\n    openAt: first-search\n" "$HOME/.dsh/session-search.db" >> "$cfg"
  echo "profile ok    índice de busca habilitado"
}

ensure_disabled_entries() {
  local cfg="$PROFILE/cordis.patch.yml"
  [ -f "$cfg" ] || return 0
  local need=""
  grep -q 'llm-deepseek-account' "$cfg" 2>/dev/null || need="$need llm-deepseek-account"
  grep -q 'ui-settings-account' "$cfg" 2>/dev/null || need="$need ui-settings-account"
  if [ -z "$need" ]; then
    echo "profile ok    provedor de conta e UI já desligados"
    return 0
  fi
  if [ "$DRY" = 1 ]; then
    echo "desligaria    $need"
    return 0
  fi
  local stripped
  stripped="$(sed -e 's/#.*$//' "$cfg" 2>/dev/null | tr -d '[:space:]')"
  if [ -z "$stripped" ] || [ "$stripped" = "[]" ]; then : > "$cfg"; fi
  for entry in $need; do
    printf "\n- id: %s\n  disabled: true\n" "$entry" >> "$cfg"
  done
  echo "profile ok    desligados:$need"
}

ensure_web_search_tavily() {
  local cfg="$PROFILE/cordis.patch.yml"
  [ -d "$PROFILE" ] || return 0
  if grep -q 'searchProvider: tavily' "$cfg" 2>/dev/null; then
    echo "profile ok    busca na web já usa Tavily"
    return 0
  fi
  if [ "$DRY" = 1 ]; then
    echo "configuraria  busca na web -> provedor tavily"
    return 0
  fi
  local stripped
  stripped="$(sed -e 's/#.*$//' "$cfg" 2>/dev/null | tr -d '[:space:]')"
  if [ ! -f "$cfg" ] || [ -z "$stripped" ] || [ "$stripped" = "[]" ]; then
    : > "$cfg"
  fi
  # `config` de uma linha existente é substituído por inteiro: o fetchProvider
  # da base precisa ser repetido, senão o web_fetch perde o provedor.
  printf '\n# Busca na web: Tavily é o provedor padrão do seam ctx.web.\n- id: web\n  config:\n    searchProvider: tavily\n    fetchProvider: http\n' >> "$cfg"
  echo "profile ok    busca na web -> tavily"
}

# Perfil vazio ou alheio (sem a nossa camada de modelos) recebe o perfil completo
# embutido no APK; um perfil já configurado só recebe as entradas pontuais.
ensure_profile_baseline() {
  local cfg="$PROFILE/cordis.patch.yml"
  [ -d "$PROFILE" ] || return 0
  if [ -f "$cfg" ] && grep -q 'opencode-go' "$cfg" 2>/dev/null; then
    return 0
  fi
  if [ "$DRY" = 1 ]; then
    echo "copiaria      perfil completo (base sem a nossa camada)"
    return 0
  fi
  if [ -f "$cfg" ]; then cp "$cfg" "$cfg.bak-$(date +%Y%m%d-%H%M%S)"; fi
  cp "$HERE/profile/cordis.patch.yml" "$cfg"
  echo "profile ok    perfil completo aplicado"
}


# koffi (FFI do flock no Android): o pacote npm não traz o binário deste aparelho,
# e sem ele todo turno falha com "Cannot find the native Koffi module".
KOFFI_SRC="$HERE/artifacts/koffi-android-arm64.node"
KOFFI_DST="$DSH/node_modules/koffi/build/koffi/android_arm64/koffi.node"
if [ ! -f "$KOFFI_SRC" ]; then
  echo "aviso: artefato do koffi ausente, o FFI do flock não será instalado" >&2
elif [ ! -d "$DSH/node_modules/koffi" ]; then
  echo "koffi pulado   pacote koffi não instalado"
elif [ "$DRY" = 1 ]; then
  echo "copiaria      koffi nativo -> node_modules/koffi/build/koffi/android_arm64/"
elif [ -f "$KOFFI_DST" ] && cmp -s "$KOFFI_SRC" "$KOFFI_DST"; then
  echo "koffi ok       binário nativo já no lugar"
else
  mkdir -p "$(dirname "$KOFFI_DST")"
  cp "$KOFFI_SRC" "$KOFFI_DST" && echo "koffi ok       binário nativo instalado"
fi

if [ "$WITH_PROFILE" = 1 ]; then
  stamp="$(date +%Y%m%d-%H%M%S)"
  for f in cordis.patch.yml cordis.yml package.json pnpm-workspace.yaml; do
    if [ "$DRY" = 1 ]; then
      echo "copiaria      profile/$f"
      continue
    fi
    if [ -f "$PROFILE/$f" ] && ! cmp -s "$HERE/profile/$f" "$PROFILE/$f"; then
      cp "$PROFILE/$f" "$PROFILE/$f.bak-$stamp"
    fi
    cp "$HERE/profile/$f" "$PROFILE/$f"
    echo "profile ok    $f"
  done
elif [ "$AUTO" = 1 ]; then
  ensure_profile_baseline
  ensure_plugin_entry
  ensure_web_search_tavily
  ensure_index_entry
  ensure_disabled_entries
fi

echo
echo "reinicie o host web para a interface recarregar:  pkill -f 'dsh web' ; dsh web"
[ "$AUTO" = 1 ] && exit 0
exit "$status"

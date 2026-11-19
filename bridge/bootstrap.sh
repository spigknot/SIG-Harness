#!/data/data/com.termux/files/usr/bin/bash
# Executado pelo RUN_COMMAND com o UID do Termux. Só o caminho do pacote viaja no Intent.
set -Eeuo pipefail
umask 077
archive=$1 expected=$2 progress=$3 mode=$4 port=$5 token=$6 cwd=$7
export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$PREFIX/bin:/system/bin"
export TMPDIR="$PREFIX/tmp"
export DSH_HOME="$HOME/.dsh"
export DEBIAN_FRONTEND=noninteractive
# RUN_COMMAND usa o ambiente do Termux. Evita escrever no HOME de um shell su.
if [ "$(id -u)" = 0 ] || [ "${HOME##*/}" != home ] || [ "$(uname -m)" != aarch64 ]; then
  printf 'SIG_FAILED environment\n'
  exit 1
fi
state="$HOME/.sig-harness"
mkdir -p "$state" "$TMPDIR"
stage=""
package_backup=""
package_target=""
last_step=""
# Apenas chaves de estado sem credenciais são gravadas no armazenamento compartilhado.
step() {
  last_step=$1
  printf '%s\n' "$1" > "$progress" || :
}
cleanup() {
  local rc=$?
  if [ -n "$package_backup" ] && [ -e "$package_backup" ]; then
    rm -rf -- "$package_target"
    mv -- "$package_backup" "$package_target"
  fi
  if [ -n "$stage" ]; then rm -rf -- "$stage"; fi
  if [ "$rc" != 0 ]; then printf 'SIG_FAILED %s\n' "$last_step"; fi
}
trap cleanup EXIT
step lock
exec 9>"$state/install.lock"
# O flock do Android já existe antes da instalação de pacotes no Termux.
/system/bin/timeout -k 5s 10m /system/bin/flock -x 9
# Não deixamos o apt/npm receber entrada do usuário. Diagnóstico completo fica no Termux.
log="$state/bootstrap.log"
: > "$log"
exec 2>>"$log"
stage="$(mktemp -d "$state/install.XXXXXXXX")"

install_packages() {
  timeout -k 10s 6m pkg install -y -o Dpkg::Options::=--force-confold "$@" </dev/null >>"$log"
}
step tools
missing=()
for pair in timeout:coreutils tar:tar patch:patch diff:diffutils git:git; do
  /system/bin/timeout 5 "${pair%:*}" --version >/dev/null 2>&1 || missing+=("${pair#*:}")
done
# Usa o coreutils do Termux para os limites de tempo e SHA, inclusive durante a instalação.
if [ ! -x "$PREFIX/bin/timeout" ] || [ ! -x "$PREFIX/bin/sha256sum" ]; then
  /system/bin/timeout 300 pkg install -y -o Dpkg::Options::=--force-confold coreutils </dev/null >>"$log"
fi
if [ "${#missing[@]}" -gt 0 ]; then
  if ! install_packages "${missing[@]}"; then
    timeout -k 10s 5m pkg update -y </dev/null >>"$log"
    install_packages "${missing[@]}"
  fi
  for pair in timeout:coreutils tar:tar patch:patch diff:diffutils git:git; do
    if ! /system/bin/timeout 5 "${pair%:*}" --version >/dev/null 2>&1; then
      timeout -k 10s 5m apt-get install -y --reinstall "${pair#*:}" </dev/null >>"$log"
      /system/bin/timeout 5 "${pair%:*}" --version >/dev/null
    fi
  done
fi

step assets
cp -- "$archive" "$stage/payload.tgz"
printf '%s  %s\n' "$expected" "$stage/payload.tgz" | sha256sum -c --status
tar xzf "$stage/payload.tgz" -C "$stage"
layer="$stage/dsh-web"
. "$layer/pin.env"
(cd "$layer/artifacts" && sha256sum --strict -c SHA256SUMS >>"$log")
[ -d "$cwd" ] && [ -x "$cwd" ] || cwd="$HOME"
manage="$stage/bridge/ensure-bridge.cjs"

step node
node_ok() {
  timeout 10s node -e 'require("node:crypto"); require("node:fs"); if (process.platform !== "android" || process.arch !== "arm64") process.exit(1)' &&
    timeout 15s npm --version >>"$log"
}
if ! node_ok; then
  step node_install
  node_package=nodejs
  if dpkg-query -W -f='${db:Status-Status}' nodejs-lts 2>/dev/null | grep -qx installed; then
    node_package=nodejs-lts
  fi
  timeout -k 10s 5m pkg update -y </dev/null >>"$log"
  # O upgrade resolve bibliotecas de sistema incompatíveis com a versão do Node.
  timeout -k 10s 8m pkg upgrade -y -o Dpkg::Options::=--force-confold </dev/null >>"$log"
  install_packages "$node_package" npm
  if ! node_ok; then
    timeout -k 10s 6m apt-get install -y --reinstall -o Dpkg::Options::=--force-confold "$node_package" npm </dev/null >>"$log"
    node_ok
  fi
fi

step pnpm
pnpm_tool="$state/pnpm"
if ! timeout -k 3s 15s pnpm --version >>"$log"; then
  if ! timeout -k 3s 15s "$pnpm_tool/bin/pnpm" --version >>"$log"; then
    next_pnpm="$stage/pnpm-tool"
    timeout -k 10s 3m npm install --prefix "$next_pnpm" "pnpm@$PNPM_VERSION" \
      --ignore-scripts --no-audit --no-fund --fetch-retries=1 --fetch-timeout=30000 </dev/null >>"$log"
    mkdir -p "$next_pnpm/bin"
    cat > "$next_pnpm/bin/pnpm" <<'PNPM'
#!/data/data/com.termux/files/usr/bin/bash
exec node "$(dirname "$0")/../node_modules/pnpm/bin/pnpm.cjs" "$@"
PNPM
    chmod 700 "$next_pnpm/bin/pnpm"
    timeout -k 3s 15s "$next_pnpm/bin/pnpm" --version >>"$log"
    if [ -d "$pnpm_tool" ]; then mv "$pnpm_tool" "$stage/old-pnpm"; fi
    mv "$next_pnpm" "$pnpm_tool"
  fi
  export PATH="$pnpm_tool/bin:$PATH"
fi

stop_bridge() { node "$manage" stop "$state"; }
dsh="$PREFIX/lib/node_modules/@deepseek-ai/dsh"
export DSH_BIN="$dsh/lib/bin.js"
package_target="$dsh"
# Recupera a troca se o Android tiver encerrado o app no meio do rename.
previous_dsh="$state/dsh.previous"
if [ -d "$previous_dsh" ] && [ ! -e "$dsh" ]; then mv -- "$previous_dsh" "$dsh"; fi
profile="$HOME/.dsh/profiles/web"
core() {
  SIG_DSH_ROOT="$1" timeout -k 5s 90s bash "$layer/apply.sh" --managed-core "$2" >>"$log"
}
prepare_dsh() {
  local target="$1"
  timeout -k 5s 20s node --expose-internals "$target/lib/bin.js" --version |
    grep -Fxq "$DSH_VERSION"
}

step dsh
reinstall=0
if [ "$mode" = recover ] || ! prepare_dsh "$dsh"; then
  reinstall=1
elif ! core "$dsh" --check; then
  step native
  stop_bridge
  if ! core "$dsh" --apply; then reinstall=1; fi
fi
if [ "$reinstall" = 1 ]; then
  step dsh_install
  install_dsh() {
    timeout -k 10s 6m npm install -g --prefix "$1" "@deepseek-ai/dsh@$DSH_VERSION" \
      --ignore-scripts --no-audit --no-fund --fetch-retries=1 --fetch-timeout=30000 "${@:2}" </dev/null >>"$log"
  }
  npm_prefix="$stage/npm"
  if ! install_dsh "$npm_prefix"; then
    # A segunda tentativa não reaproveita a árvore parcial nem o cache do npm.
    npm_prefix="$stage/npm-retry"
    install_dsh "$npm_prefix" --cache "$stage/npm-cache"
  fi
  candidate="$npm_prefix/lib/node_modules/@deepseek-ai/dsh"
  prepare_dsh "$candidate"
  core "$candidate" --apply
  stop_bridge
  mkdir -p "$(dirname "$dsh")"
  if [ -e "$dsh" ] || [ -L "$dsh" ]; then
    if [ -d "$previous_dsh" ]; then
      mv -- "$dsh" "$stage/interrupted-dsh"
    else
      mv -- "$dsh" "$previous_dsh"
    fi
    package_backup="$previous_dsh"
  fi
  mv -- "$candidate" "$dsh"
  prepare_dsh "$dsh"
  core "$dsh" --check
  package_backup=""
fi
if [ -d "$previous_dsh" ]; then rm -rf -- "$previous_dsh"; fi
# O launcher do npm às vezes some mesmo com o pacote intacto.
if [ ! -x "$PREFIX/bin/dsh" ] || [ "$(readlink -f "$PREFIX/bin/dsh")" != "$dsh/lib/bin.js" ]; then
  ln -sfn ../lib/node_modules/@deepseek-ai/dsh/lib/bin.js "$PREFIX/bin/dsh"
fi

step profile
if [ ! -s "$profile/package.json" ]; then
  step profile_initialize
  stop_bridge
  # Inicializa o perfil pelo próprio DSH, sem abrir o navegador nem imprimir a configuração.
  timeout -k 5s 45s node --expose-internals "$dsh/lib/bin.js" --profile web --dump-default-config >/dev/null
  [ -s "$profile/package.json" ]
fi
profile_health() {
  node "$layer/profile-health.cjs" "$dsh" "$profile" "$1" >>"$log"
}
if ! profile_health check; then
  step profile_repair
  stop_bridge
  profile_health prepare
  if ! profile_health bundles; then
    mobile="$profile/node_modules/dsh-web-mobile"
    if [ -e "$mobile" ] || [ -L "$mobile" ]; then mv -- "$mobile" "$stage/previous-mobile"; fi
    if ! CI=true timeout -k 10s 6m node --expose-internals "$dsh/lib/bin.js" \
              plugin --profile web install --no-frozen-lockfile </dev/null >>"$log" ||
       ! profile_health bundles; then
      if [ -e "$stage/previous-mobile" ] || [ -L "$stage/previous-mobile" ]; then
        rm -rf -- "$mobile"
        mkdir -p "$(dirname "$mobile")"
        mv -- "$stage/previous-mobile" "$mobile"
      fi
      exit 1
    fi
  fi
  profile_health apply
  profile_health check
fi
# O MCP do REA é uma integração opcional. O script do perfil continua sendo a origem.
if ! timeout -k 5s 20s bash "$layer/rea.sh" --sync >>"$log"; then
  printf 'REA: configuração opcional indisponível\n' >>"$log"
fi

step bridge
restart=0
if ! cmp -s "$stage/bridge/sig-bridge.mjs" "$state/sig-bridge.mjs" ||
   ! cmp -s "$stage/bridge/tailscale-web.mjs" "$state/tailscale-web.mjs" ||
   ! diff -qr "$layer" "$state/dsh-web" >>"$log" 2>&1; then restart=1; fi
if [ "$mode" != check ] || [ "$restart" = 1 ]; then stop_bridge; fi
if [ "$restart" = 1 ]; then
  mv -f -- "$stage/bridge/tailscale-web.mjs" "$state/tailscale-web.mjs"
  mv -f -- "$stage/bridge/sig-bridge.mjs" "$state/sig-bridge.mjs"
  if [ -d "$state/dsh-web" ]; then mv -- "$state/dsh-web" "$stage/previous-layer"; fi
  if ! mv -- "$layer" "$state/dsh-web"; then
    if [ -d "$stage/previous-layer" ]; then mv -- "$stage/previous-layer" "$state/dsh-web"; fi
    exit 1
  fi
fi
if [ ! -s "$state/acp.patch.yml" ]; then mv -- "$stage/bridge/acp.patch.yml" "$state/acp.patch.yml"; fi
cp -- "$manage" "$state/ensure-bridge.cjs.tmp"
mv -f -- "$state/ensure-bridge.cjs.tmp" "$state/ensure-bridge.cjs"
node "$manage" start "$state" "$port" "$token" "$cwd" "$expected" 9>&-

#!/data/data/com.termux/files/usr/bin/bash
# Liga o DSH ao REA (Reverse Engineer Anything) que roda no PC da rede Tailscale.
#
# Por que no PC: o REA sobe o Ghidra/JADX na MESMA máquina — no Android ele recusa
# o host ("android arm64"), então o servidor MCP mora no PC e o DSH fala com ele
# por streamable-http. Este script grava a URL em rea.env e a entrada `rea-mcp`
# no patch de casa ($DSH_HOME/cordis.patch.yml), que vale para todos os profiles
# e não é sobrescrito pelo APK.
#
#   bash dsh-web/rea.sh 100.101.102.103          # porta 8765
#   bash dsh-web/rea.sh meu-pc.tailnet.ts.net:9000
#   bash dsh-web/rea.sh --sync                   # reaplica o que está em rea.env
#   bash dsh-web/rea.sh --off                    # remove a entrada do DSH
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DSH_HOME="${DSH_HOME:-$HOME/.dsh}"
PATCH="$DSH_HOME/cordis.patch.yml"
ENVFILE="$HERE/rea.env"
PORT="${REA_MCP_PORT:-8765}"
BEGIN='# >>> rea-mcp (gerado por dsh-web/rea.sh)'
END='# <<< rea-mcp'

write_block() {  # $1 = URL (vazia remove o bloco)
  local url="$1" tmp
  # Nada a remover: não mexe no arquivo (o --auto roda a cada boot).
  if [ -z "$url" ] && { [ ! -f "$PATCH" ] || ! grep -qxF "$BEGIN" "$PATCH"; }; then
    return 0
  fi
  mkdir -p "$DSH_HOME"
  tmp="$(mktemp "${TMPDIR:-/data/data/com.termux/files/usr/tmp}/rea.XXXXXX")" || return 1
  if [ -f "$PATCH" ]; then
    awk -v b="$BEGIN" -v e="$END" '
      $0 == b { skip = 1; next }
      $0 == e { skip = 0; next }
      skip != 1 { print }
    ' "$PATCH" > "$tmp"
  fi
  if [ -n "$url" ]; then
    # Uma linha em branco separa o bloco do que já existia.
    [ -s "$tmp" ] && printf '\n' >> "$tmp"
    printf '%s\n' "$BEGIN" >> "$tmp"
    printf '%s\n' '- insert:' >> "$tmp"
    printf '    - id: rea-mcp\n' >> "$tmp"
    printf '      name: "@deepseek-ai/dsh-mcp-client"\n' >> "$tmp"
    printf '      config:\n' >> "$tmp"
    printf '        serverName: rea\n' >> "$tmp"
    printf '        transport: streamable-http\n' >> "$tmp"
    printf '        url: %s\n' "$url" >> "$tmp"
    printf '        toolCallTimeoutMs: 600000\n' >> "$tmp"
    printf '%s\n' "$END" >> "$tmp"
  fi
  # Arquivo sem conteúdo real (só linhas em branco/comentário) não precisa existir.
  if [ -f "$tmp" ] && ! grep -qv -e '^[[:space:]]*$' -e '^[[:space:]]*#' "$tmp"; then
    rm -f "$PATCH" "$tmp"
    return 0
  fi
  mv "$tmp" "$PATCH"
}

current_url() {
  [ -f "$PATCH" ] || return 0
  # Comparação literal, não endereço de sed: o marcador contém "/" e "(".
  awk -v b="$BEGIN" -v e="$END" '
    $0 == b { inb = 1; next }
    $0 == e { inb = 0; next }
    inb && $1 == "url:" { print $2; exit }
  ' "$PATCH"
}

normalize() {  # aceita host, host:porta, http(s)://host[:porta][/mcp]
  local v="$1"
  v="${v#http://}"; v="${v#https://}"; v="${v%/}"; v="${v%/mcp}"
  case "$v" in
    *:*) ;;
    *) v="$v:$PORT" ;;
  esac
  printf 'http://%s/mcp' "$v"
}

sync() {
  local url=""
  if [ -f "$ENVFILE" ]; then
    # shellcheck disable=SC1090
    . "$ENVFILE"
    url="${REA_MCP_URL:-}"
  fi
  if [ -z "$url" ]; then
    write_block ""
    echo "rea: sem URL em dsh-web/rea.env (nada registrado)"
    return 0
  fi
  if [ "$(current_url)" = "$url" ]; then
    echo "rea ok       $url"
    return 0
  fi
  write_block "$url" && echo "rea ok       registro -> $url"
}

case "${1:-}" in
  --sync) sync ;;
  --off)
    printf '# Endereço do REA no PC da rede Tailscale, no formato http://host:porta/mcp.\n' > "$ENVFILE"
    printf '# Preenchido por: bash dsh-web/rea.sh <ip-ou-nome-do-pc>[:porta]\n' >> "$ENVFILE"
    printf 'REA_MCP_URL=\n' >> "$ENVFILE"
    write_block ""
    echo "rea ok       entrada removida de $PATCH"
    ;;
  -h|--help) sed -n '10,13p' "$0" ;;
  "")
    echo "uso: bash dsh-web/rea.sh <ip-ou-nome-tailscale-do-pc>[:porta] | --sync | --off" >&2
    exit 2 ;;
  *)
    URL="$(normalize "$1")"
    printf 'REA_MCP_URL=%s\n' "$URL" > "$ENVFILE"
    write_block "$URL" && echo "rea ok       registro -> $URL"
    echo "reinicia o host web para o DSH conectar (botão ⟳ do SIG Harness)"
    ;;
esac

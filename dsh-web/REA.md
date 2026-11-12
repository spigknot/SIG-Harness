# REA (Reverse Engineer Anything) no DSH

O REA é um servidor MCP (255 ferramentas na 6.9) que decompila binários, lê APK
com JADX, reconstrói apps Electron/JS e conversa com o Ghidra. **Ele tem de rodar
na mesma máquina que o Ghidra** — no próprio PC, não no celular.

Instalação em uso: **container `rea` no `servidor`** (Tailscale `100.70.207.12`),
irmão do container do Ghidra, ambos na imagem `ghidra-web:12.1.4` que já traz
Node 26, JDK 25 e Ghidra 12.1.4.

## Por que no PC

Teste feito aqui com o pacote instalado (`rea doctor`):

| Verificação | Resultado |
|---|---|
| `host` | `android arm64` → **unsupported** |
| `jadx` | `Android JADX subprocess ownership is unsupported on android/arm64` |
| `ghidra` | precisa de `GHIDRA_INSTALL_DIR` e do decompilador nativo da arquitetura |

Ou seja: instalado no Termux o REA sobe, mas justamente as ferramentas de APK e de
binário nativo — as que interessam — ficam recusadas. Por isso o desenho é:

```
PC (Tailscale)                          Celular (SIG Harness)
  Ghidra 12.x + JDK 21+                   DSH (perfil web)
  rea mcp --http --port 8765   <── MCP streamable-http ──  ~/.dsh/cordis.patch.yml
```

O DSH expõe as ferramentas como `mcp__rea__<nome>`.

## No PC (uma vez)

1. Node compatível: `^22.23.4 || ^24.22.1 || >=26.12.1`.
2. `npm install -g rea-agents` (o pacote traz `scripts/rea.mjs`; o binário `rea` é atalho).
3. Ghidra 12.1+ extraído e um JDK declarado pela instalação: 12.1 pede **JDK 21+**,
   12.2 pede **JDK 25+**. Aponte o diretório:

   ```bash
   export GHIDRA_INSTALL_DIR=/opt/ghidra_12.1.4_PUBLIC
   ```

   No Windows o Ghidra entra em **P0 (somente leitura)**: dá para decompilar e ler,
   não para renomear funções.

4. Conferir: `rea doctor` → `host` e `ghidra` verdes.

## Subir o MCP

O ponto importante é **desligar os output schemas**: com eles o catálogo sai com
**3,5 MB**; sem eles, ~300 KB. Isso é o que o DSH manda de contexto a cada turno.

```bash
# Linux / macOS
REA_MCP_OUTPUT_SCHEMA_PROFILE=off rea mcp --http --host 0.0.0.0 --port 8765
```

```powershell
# Windows (PowerShell)
$env:REA_MCP_OUTPUT_SCHEMA_PROFILE = "off"
rea mcp --http --host 0.0.0.0 --port 8765
```

O listener escreve uma linha JSON em stderr:

```json
{"transport":"streamable-http","url":"http://0.0.0.0:8765/mcp","msg":"MCP transport listening"}
```

- `--host`: no REA **6.9** um bind fora de loopback **é recusado** sem
  `MCP_ALLOWED_HOSTS` — não é aviso, é falha de startup:

  ```
  Refusing to serve 0.0.0.0 outside loopback without an operator-declared host
  allowlist; set MCP_ALLOWED_HOSTS (or --allowed-hosts) ...
  ```

  A variável leva os nomes/IPs que o cliente usa, **sem** esquema nem porta
  (`100.70.207.12,servidor,servidor.tail362d30.ts.net`). Cada cliente que
  apontar para um nome diferente precisa estar na lista.
- Uma instância atende **um cliente por vez**: um segundo cliente derruba a
  sessão do primeiro (`MCP HTTP session replaced by a new client`). Se a sessão
  do DSH morrer assim, o cliente **não** se reinicializa sozinho — é preciso
  reiniciar o host (⟳). Não faça `curl` de teste com o DSH conectado.
- O processo roda até SIGINT.

Teste antes de mexer no celular (com o DSH desconectado):

```bash
curl -s -X POST http://100.x.y.z:8765/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}'
```

Resposta esperada: `"serverInfo":{"name":"rea","version":"6.9.0"}`.

Referência de como está no `servidor` (Ghidra 12.1.4 em container; a mesma
imagem já tem Node 26 + JDK 25 + Ghidra), em `docker-compose.yml`:

```yaml
  rea:
    image: ghidra-web:12.1.4        # mesma imagem do serviço ghidra
    container_name: rea
    restart: unless-stopped
    entrypoint: ["/opt/node/bin/node",
                 "/opt/node/lib/node_modules/rea-agents/scripts/rea.mjs",
                 "mcp", "--http", "--host", "0.0.0.0", "--port", "8765"]
    environment:
      GHIDRA_INSTALL_DIR: /opt/ghidra
      MCP_ALLOWED_HOSTS: "100.70.207.12,servidor,servidor.tail362d30.ts.net,localhost,127.0.0.1"
      REA_MCP_OUTPUT_SCHEMA_PROFILE: "off"
    ports:
      - "100.70.207.12:8765:8765"   # só na tailnet
    volumes:
      - ./rea:/opt/rea              # alvos/snapshots persistentes
      - ./binaries:/binaries
      - ./projects:/projects
```

O container `rea` e o `ghidra` são **irmãos** e independentes: o REA sobe um
Ghidra headless próprio dentro do seu container. Reiniciar um não derruba o outro.

Se o PC for Windows, libere a porta no firewall:
`New-NetFirewallRule -DisplayName "REA MCP" -Direction Inbound -LocalPort 8765 -Protocol TCP -Action Allow`.

## Disponível em qualquer sessão e qualquer projeto

O registro mora no **patch de casa** (`~/.dsh/cordis.patch.yml`), que é a camada
aplicada depois do profile e antes da linha de comando. Consequências:

- vale para **todos os profiles** (`web`, `webtest`, `headless`, `acp`);
- vale **independente do diretório** — abrir outro projeto não muda nada, porque a
  linha de MCP é do host, não da sessão;
- **não** é tocado pelo APK: `apply.sh --auto` só escreve
  `~/.dsh/profiles/*/cordis.patch.yml`.

Conferir:

```bash
for p in web webtest headless acp; do
  dsh --profile "$p" --dump-config | grep -c "id: rea-mcp"
done
```

Deve dar `1` em todos.

### Um cliente por vez — cuidado com host duplicado

O listener do REA mantém **uma sessão**: um segundo cliente que conecta toma a
sessão do primeiro. Na prática, se você subir outro processo do Harness
(`dsh headless`, `dsh acp`, um segundo `dsh web`) esse processo rouba a sessão
do host web, e as `mcp__rea__*` do host que estava usando param — o cliente MCP
do DSH não re-inicializa sozinho nesse caso, só o ⟳ resolve.

Se precisar dos dois ao mesmo tempo, suba uma segunda instância em outra porta
(`rea mcp --http --port 8766`) e registre-a com outro `serverName`.

### Custo de contexto

O catálogo completo são **255 ferramentas** (~361 KB de schema com
`REA_MCP_OUTPUT_SCHEMA_PROFILE=off`). Isso entra no contexto de **toda** sessão.
Para reduzir, defina `REA_MCP_TOOL_FAMILIES` (no container) com um subconjunto de:

`direct, enhanced, native, artifact, managed, firmware, android, pcap, flutter,
frida, apktool, jeb, adb, browser, electron, javascript-runtime, application, session`

Exemplo enxuto para binário nativo + Android:
`REA_MCP_TOOL_FAMILIES=direct,enhanced,native,artifact,android,session`

Ferramenta fora do escopo **continua registrada, mas desabilitada** — some do
`tools/list` e a chamada falha com `Tool ... disabled`.

## No celular

```bash
bash dsh-web/rea.sh 100.70.207.12     # este é o servidor de hoje; porta 8765
bash dsh-web/rea.sh host:9000         # porta diferente
bash dsh-web/rea.sh --off             # remove a entrada
```

Depois, **⟳** no rodapé da barra lateral (ou `pkill -f 'dsh web'; dsh web`). Os
logs do host mostram a entrada `rea-mcp` conectando.

O que o script faz:

1. grava `REA_MCP_URL` em `dsh-web/rea.env` (versionado);
2. escreve/atualiza o bloco `rea-mcp` em `~/.dsh/cordis.patch.yml` — o **patch de
   casa**, que vale para todos os profiles e que o APK não sobrescreve;
3. `apply.sh --auto` repete o passo 2 a cada boot (`ensure_rea_mcp`), então o
   registro volta sozinho mesmo depois de reinstalar o app.

A entrada gerada:

```yaml
- insert:
    - id: rea-mcp
      name: "@deepseek-ai/dsh-mcp-client"
      config:
        serverName: rea
        transport: streamable-http
        url: http://100.x.y.z:8765/mcp
        toolCallTimeoutMs: 600000
```

`toolCallTimeoutMs` é 10 min porque o Ghidra pode levar até 330 s só para subir e
importar o alvo (o padrão do DSH é 60 s e cortaria a chamada).

## Diagnóstico

| Sintoma | Onde olhar |
|---|---|
| `mcp__rea__*` não aparece | `dsh --profile web --dump-config \| grep -A6 rea-mcp` |
| entrada ausente | `cat ~/.dsh/cordis.patch.yml`; refaça `bash dsh-web/rea.sh --sync` |
| `Session not found` / `Server not initialized` | outro cliente tomou a sessão (curl de teste, segundo DSH): reinicie o host com ⟳ |
| conecta e cai | `docker logs rea` no servidor; Tailscale de pé nos dois lados; porta liberada |
| `Refusing to serve 0.0.0.0` | falta `MCP_ALLOWED_HOSTS` com o nome/IP que o cliente usa |
| ferramenta estoura timeout | `toolCallTimeoutMs` na entrada; primeira análise do Ghidra é a mais lenta |
| contexto enorme a cada turno | faltou `REA_MCP_OUTPUT_SCHEMA_PROFILE=off` no PC |

O REA não enxerga o celular: o alvo precisa estar no PC (copie o APK/binário para
lá). Para usar a análise na conversa, peça o caminho do arquivo no PC.

## Contexto

- REA: <https://github.com/morluto/rea> (`rea-agents` no npm, MIT).
- Integração com o Harness documentada pelo próprio REA:
  `docs/getting-started/client-configuration.md#deepseek-harness`.
- Instalação local no Termux **não** é usada pelo DSH. Se quiser o CLI no celular
  para ferramentas estáticas (ELF/JS/.NET): `npm install -g rea-agents`.

# Camada web do SIG Harness

O SIG Harness não é só o APK. Ele carrega a interface web do **DeepSeek Harness**, e essa
interface recebeu ajustes que não existem no pacote publicado. Este diretório guarda
**tudo o que foi mudado fora do app**, para que um `git clone` + `apply.sh` reconstrua o
ambiente inteiro.

## O que tem aqui

| Caminho | O que é |
|---|---|
| `patches/` | 5 diffs contra os pacotes publicados do DSH `0.2.1-rc.5` |
| `artifacts/` | addon nativo compilado localmente (o pacote publicado não traz binário para Android) |
| `plugin/dsh-web-sig-voice/` | plugin local que cria a aba **Voz** nas Configurações do DSH |
| `plugin/dsh-web-search-tavily/` | plugin host que registra **Tavily** como provedor de busca do `ctx.web` |
| `plugin/dsh-web-sig-search-tavily/` | página **Busca na web** nas Configurações: grava a chave no cofre via rota do host |
| `profile/` | conteúdo de `~/.dsh/profiles/web` — `cordis.patch.yml` é a camada de configuração |
| `apply.sh` | aplica os patches, instala o plugin e o addon |
| `rea.sh` / `rea.env` | liga o DSH ao **REA** que roda no PC (MCP por streamable-http, ver `REA.md`) |
| `REA.md` | como instalar o REA + Ghidra no PC e apontar o DSH para ele |
| `capture.sh` | regenera os patches e o artefato depois de atualizar o DSH |
| `pin.env` | versão de cada pacote contra a qual os patches foram gerados |
| `tools/pristine.mjs` | recupera o pacote publicado a partir do cache do npm (funciona offline) |

## O que cada patch resolve

| Patch | Pacote | Por quê |
|---|---|---|
| `0001 attachment-local` | `dsh-attachment-local` | Android não permite *hard link* em dados de app: imagens funcionavam, mas **upload de arquivo falhava** porque o alias do objeto também usava `link` — agora cai para `copyFile` quando o link é recusado. O `rename` atômico e o `fsync` de ancestrais com `EACCES`/`EPERM` completam o conjunto. |
| `0002 conversation` | `dsh-client-ui-conversation` | O toast de erro passa a mostrar `error.details.reason`, não só mensagem + código. |
| `0003 directory-picker` | `dsh-host-directory-picker-browse`, `-auto` | Opção `startDirectory`: o seletor de pastas abre em `/storage/emulated/0/Projetos` em vez de `$HOME`, com fallback para home quando o diretório não é legível. |
| `0004 node-addon flock` | `node-addon-system` | No Termux `process.platform` é `"android"`, fora da lista suportada, e o prebuild bionic não existe — dava `ERR_FLOCK_UNSUPPORTED_PLATFORM`. Passa a chamar `flock(2)` da libc via `koffi`, mantendo a semântica não bloqueante. |
| `0005 session link fallback` | `dsh-session-persistence-jsonl` | A publicação atômica do log de sessão usa `link(2)`, proibido no armazenamento do app (`EACCES`) — o primeiro turno morria calado. Cai para `rename` só quando o `link` é recusado, preservando a checagem de existência. |

## O artefato nativo

`artifacts/require_builtin-napi-v9-android-arm64.node` é o addon que
`node-addon-native-custom-loader` procura em
`node-addon-require-builtin/build/napi/napi-v9-android-arm64/`. O pacote publicado não traz
binário para Android, então ele foi compilado localmente com **NDK r30**, N-API v9, arm64,
a partir do fonte `dsh-node-addon-require-builtin`. `SHA256SUMS` registra o hash instalado.

## Instalação sem passo manual

O `build.sh` empacota este diretório em `app/assets/dsh-web.tgz`. A cada abertura, o app envia o
pacote ao Termux, o bridge descompacta em `~/.sig-harness/dsh-web` e roda
`apply.sh --auto` antes de subir o `dsh web`:

```bash
printf '%s' '<base64>' | base64 -d > ~/.sig-harness/dsh-web.tgz
rm -rf ~/.sig-harness/dsh-web
tar xzf ~/.sig-harness/dsh-web.tgz -C ~/.sig-harness
bash ~/.sig-harness/dsh-web/apply.sh --auto >> ~/.sig-harness/provision.log 2>&1
```

O modo `--auto` nunca falha e é idempotente: aplica só o que falta, pula os patches quando a
versão do DSH não é a fixada em `pin.env` (com aviso no `provision.log`) e só **adiciona** a
entrada do plugin no `cordis.patch.yml`, sem sobrescrever a configuração existente.

## Busca

Duas superfícies, dois custos:

| Onde | Disparo | O que faz |
|---|---|---|
| Barra lateral | a cada tecla | filtra por **título**, em memória |
| Barra lateral | **Enter** | busca no **conteúdo** via FTS5 do DSH (`session-query-sqlite`), com skeleton de carregamento e snippets |
| Dentro da sessão | a cada tecla | caixa flutuante com destaque, contador e ↑/↓ entre ocorrências |

O índice é o do próprio DSH, aberto na primeira busca (`openAt: first-search`) e guardado em
`~/.dsh/session-search.db` — arquivo derivado, pode ser apagado a qualquer momento. Medição
com o histórico atual (7 sessões, 5,8M caracteres): **0,8 s** de construção, **131 MB** de pico
de RSS, **7,8 MB** de índice.

A caixa dentro da sessão é `plugin/dsh-web-sig-find`, escrita em **DOM puro**: ela se posiciona
como sobreposição abaixo da linha das abas e não participa da árvore React. No estado recolhido ela é só uma **lupa** no canto direito da linha das abas; o clique abre a
caixa, e o **×** (ou Esc) volta para a lupa. Uma versão anterior
registrava um slot `conversation.session.header.views.trailing` dentro do cabeçalho e foi
revertida — o widget fora do React não pode derrubar o cabeçalho da conversa.

## Busca na web (Tavily)

O `web_search` do modelo não fala com a Tavily diretamente: ele chama o seam `ctx.web`, que
aceita vários provedores e **recusa a chamada quando há mais de um utilizável**. Por isso a
linha `web` do profile fixa `searchProvider: tavily`, e o `config` dela é reescrito por
inteiro — o `fetchProvider: http` tem de ser repetido, senão o `web_fetch` perde o provedor.

O provedor é host puro (`plugin/dsh-web-search-tavily`), sem imports de `@deepseek-ai/*`:
um plugin em `~/.dsh/profiles/web/node_modules` não resolve os pacotes do DSH, que vivem na
instalação. O erro é reproduzido por forma (`name: 'WebError'` + `code`), que é o que o
chamador roteia.

A chave se grava em **Configurações → Busca na web** (`plugin/dsh-web-sig-search-tavily`):
a página fala com `GET|POST /api/sig/tavily-key`, que o plugin host expõe via `webServer`, e a
rota escreve no cofre com `ctx.credentials.set` — o valor nunca volta na resposta, nem para a
própria página. A página mostra a **origem efetiva** (`stored` / `environment` / `cli-config`):
sem isso ela diria "sem chave" enquanto a busca funciona pelo arquivo do CLI.

A chave nunca entra no YAML. A resolução tenta, nesta ordem: cofre de credenciais do DSH
(`TAVILY_API_KEY`), ambiente de lançamento, `process.env` e, por fim, o arquivo que o próprio
CLI grava (`~/.tavily/config.json`, 0600). O provedor `deepseek-official` continua registrado
e volta a atender se `searchProvider` for trocado.

## Raciocínio (reasoning effort)

O menu vem dos níveis que o **provedor** declara por modelo; o patch `0007` acrescenta duas
decisões que o DSH não toma sozinho:

- **Detecção de "off" pelo formato do provedor.** Cada protocolo sabe desligar o raciocínio de
  um jeito (`thinking.type=disabled`, `enable_thinking=false`, `reasoning.enabled=false`…).
  O catálogo marca `off: null` em boa parte dos modelos, o que suprimia o parâmetro e escondia
  a opção. Agora, quando o `thinkingFormat` do modelo admite desligamento, o `off` é liberado;
  quando não admite, continua oculto.
- **Padrão = maior nível do modelo.** Sem nível configurado, o host escolhe o mais alto
  (`max` para deepseek/glm, `xhigh` para o muse-spark) e o expõe como `defaultEffort` — o que
  também remove a opção "Default" do menu.

| Seleção | Payload |
|---|---|
| Off | `thinking: { type: "disabled" }` |
| Low / High / Max | `thinking: { type: "enabled" }` + `reasoning_effort` |

## Alterar um plugin e vê-lo no navegador

A URL de cada módulo do cliente carrega uma revisão (`&rev=…`) e o navegador cacheia por ela.
Essa revisão **muda com a versão do pacote**, não com o conteúdo do `client.js`:

```bash
# depois de editar lib/client.js, suba a versão
#   "version": "1.2.0"  →  "1.3.0"
```

Sem o `bump`, o host serve o arquivo novo, mas a página continua executando o módulo antigo —
o sintoma é "mudei o arquivo e nada mudou na tela".

## Como usar

Depois de instalar ou atualizar o DSH (o `npm i` sobrescreve patches e apaga o addon):

```bash
bash dsh-web/apply.sh --dry-run     # confere o que seria feito
bash dsh-web/apply.sh               # aplica
pkill -f 'dsh web' && dsh web       # reinicia o host para carregar
```

O armazenamento compartilhado não executa scripts diretamente, por isso sempre `bash ...`
em vez de `./apply.sh`.

O plugin `dsh-web-sig-voice` mora em `~/.dsh/profiles/web/node_modules` — um `pnpm install`
no profile pode apagá-lo. `apply.sh` recoloca a cópia versionada.

## REA (engenharia reversa)

O **REA** é um servidor MCP de engenharia reversa que precisa do Ghidra/JADX na
mesma máquina — por isso ele roda no **PC**, não no Termux (`rea doctor` recusa
`android arm64`). Hoje: container `rea` no `servidor` (Tailscale `100.70.207.12:8765`),
irmao do container do Ghidra. O DSH conecta por `streamable-http` na rede Tailscale:

```bash
bash dsh-web/rea.sh 100.x.y.z      # grava a URL e registra a entrada rea-mcp
bash dsh-web/rea.sh --off          # remove
```

O registro vai para `~/.dsh/cordis.patch.yml` (patch de **casa**, aplicado a todos
os profiles e não sobrescrito pelo APK) e é refeito por `apply.sh --auto`. As
ferramentas aparecem como `mcp__rea__*`. Passo a passo do PC em [REA.md](REA.md).

## Atualizar o DSH

1. Ajuste as versões em `pin.env`.
2. Rode `bash dsh-web/capture.sh` — ele baixa (ou pega do cache) o pacote publicado e refaz os diffs.
3. Revise `git diff dsh-web/patches/`: cada alteração ainda faz sentido? Alguma já foi corrigida upstream?
4. `bash dsh-web/apply.sh --force` e teste o app.

Se o addon nativo for recompilado, `capture.sh` atualiza `artifacts/` e o `SHA256SUMS`.

## O que não entra aqui

- O `node_modules` inteiro do DSH (~2.500 arquivos de terceiros) — versionamos só o delta.
- `~/.dsh/.credentials.yaml`, `sessions/`, `logs/`, `storages/`, `attachments/` — dados locais e segredos.
- `dsh-web-mobile` — dependência de terceiros (`github:mexiaosqwq/dsh-web-mobile`), instalada do registry e não modificada.
- `~/.dsh/profiles/acp` e `headless` — scaffolding padrão, sem customização.

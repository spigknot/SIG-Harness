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
| `profile/` | conteúdo de `~/.dsh/profiles/web` — `cordis.patch.yml` é a camada de configuração |
| `apply.sh` | aplica os patches, instala o plugin e o addon |
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

# Mapa de Módulos — DSH Web (DeepSeek Harness Web GUI)

> **Para que serve este arquivo.** Quando for preciso alterar o DSH Web, este mapa diz exatamente qual pacote, arquivo e símbolo governa cada parte da tela e do servidor — sem precisar varrer `node_modules` de novo.
>
> **Versão mapeada:** `@deepseek-ai/dsh@0.2.1-rc.5` (todas as dependências `@deepseek-ai/*` na mesma versão).
> **GUI em execução no momento do levantamento:** `http://127.0.0.1:35083` (porta efêmera; confira a URL impressa por `dsh web`).
> **Data:** 2026-11-07. **Host:** Termux/Android.
> **Método:** leitura da árvore instalada, dos contratos `.d.ts`, dos `package.json` e do patch de composição do profile. Todos os caminhos foram verificados no disco (ver §14 para reverificar).

---

## Conteúdo

1. [Resumo em 60 segundos](#1-resumo-em-60-segundos)
2. [Convenções de caminho](#2-convenções-de-caminho)
3. [Arquitetura](#3-arquitetura-em-uma-imagem)
4. [Boot do host](#4-boot-do-host--passo-a-passo-com-arquivos)
5. [Boot do navegador](#5-boot-do-navegador--globais-e-ordem)
6. [Mapa de diretórios](#6-mapa-de-diretórios-árvore-comentada)
7. [Host: pacotes](#7-host--pacotes-e-pontos-de-entrada)
8. [Rotas e API Remote](#8-rotas-http-e-api-remote)
9. [Núcleo do cliente](#9-núcleo-do-cliente-quem-manda-no-quê)
10. [Anatomia da UI + catálogo de slots](#10-anatomia-da-ui-e-catálogo-de-slots)
11. [Serviços / pontos de extensão](#11-serviços-os-pontos-de-extensão-reais)
12. [Onde mudar o quê](#12-onde-mudar-o-quê-índice-por-tarefa)
13. [Fluxo de dados](#13-fluxo-de-dados-de-uma-mensagem-para-saber-onde-tocar)
14. [Aplicar e verificar](#14-como-aplicar-e-verificar-uma-alteração)
15. [Lacunas conhecidas](#15-lacunas-conhecidas-deste-mapa)
- [Apêndice A — arquivos-fonte](#apêndice-a--inventário-de-arquivos-fonte-dos-pacotes-principais)
- [Apêndice B — pacote → repo](#apêndice-b--pacote-de-ui--pasta-no-repositório--bundle-executado)

---

## 1. Resumo em 60 segundos

1. **Tudo é plugin Cordis.** O servidor (`dsh web`) e o navegador rodam o mesmo modelo: um Loader monta plugins; plugins registram serviços (`ctx.*`), rotas (`ctx.webServer`) e UI (`ctx.slots`).
2. **O "DSH Web" não é um app monolítico.** É o profile `web` = bundle `dsh-base` + bundle `dsh-web-app` + o bundle local `dsh-web-mobile`, mais o patch do usuário.
3. **A casca do navegador é um build pronto** (`@deepseek-ai/dsh-web-frontend/dist`, Vite, arquivos com hash). Ela não é editável de forma prática; ela só inicializa o kernel, instala a tabela de módulos e monta a raiz.
4. **Toda a UI visível vem de plugins de cliente** (`dsh-client-ui-*`), cada um com um bundle `lib/client.js` servido em `/plugins/<id>/client.js`. É aí que se mexe em tela, texto, cor e interação.
5. **Os dados vêm de "Remotes"** (RPC tipado) expostos pelo host em `/api` — `session/*`, `workspace/*`, `settings/*`, `pluginManager/*`, `schedule/*`, etc.
6. **Há dois caminhos de alteração:** (a) editar o bundle instalado do plugin — recarrega a quente via `dsh-client-hmr`; (b) criar/editar um plugin local no profile (como o `dsh-web-mobile`) — é a via que sobrevive a atualizações.

---

## 2. Convenções de caminho

| Macro | Caminho real | O que é |
|---|---|---|
| `$DSH` | `/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh` | Pacote CLI instalado (o "checkout" visível ao agente). |
| `$PKGS` | `$DSH/node_modules/@deepseek-ai` | Todos os pacotes `@deepseek-ai/*` resolvidos para esta instalação. |
| `$FRONT` | `$PKGS/dsh-web-frontend/dist` | Build do SPA servido pelo `dsh web`. |
| `$HOME/.dsh` | `$DSH_HOME` | Estado do usuário: profiles, sessões, storages, logs, credenciais. |
| `$PROFILE` | `$HOME/.dsh/profiles/web` | Profile `web`: manifesto, patch do usuário, `node_modules` do profile. |
| `$LOCAL` | `$PROFILE/node_modules/dsh-web-mobile` | Plugin de terceiros instalado neste profile (adaptação mobile). |
| **repo** | `github.com/deepseek-ai/deepseek-harness` → `packages/...` | Fonte TypeScript canônica de cada pacote (campo `repository.directory` do `package.json`). |

Atalhos usados nas tabelas: `ui-chat` = `dsh-client-ui-chat`; `client-modules` = `dsh-client-modules`; `api-gateway` = `dsh-api-gateway`.

> **Importante:** os pacotes publicados **não trazem o código TypeScript** (só `lib/*.js` compilado + `lib/types/**/*.d.ts`). O bundle compilado preserva comentários e marcadores `//#region lib/types/client/...`, que revelam o arquivo-fonte original. Use isso para localizar o equivalente em `src/` no repositório.

---

## 3. Arquitetura em uma imagem

```
┌──────────────────────────── NAVEGADOR ─────────────────────────────┐
│ $FRONT/index.html  (assets com hash: index-*.js / vendor-*.js)     │
│   └─ kernel do shell: lê window.__DSH_BOOT__ + __ModuleLoader__    │
│        ├─ instala tabela de módulos compartilhados (react, cordis,  │
│        │   client-store, ui-slots, ui-primitives, ui-dockkit, zod) │
│        ├─ carrega cada entrada: /plugins/<id>/client.js             │
│        └─ monta a raiz quando uiRenderer ativa                     │
│                                                                    │
│  plugins de cliente (Cordis browser ctx)                           │
│    ui-layout → AppFrame (3 colunas)                                │
│    ui-sidebar / ui-workspace         (coluna esquerda)             │
│    ui-conversation / ui-chat / ui-tool (centro)                    │
│    ui-sidebar-right + abas           (coluna direita)              │
│    ui-settings* / ui-plugin-manager  (modais e páginas)            │
│    composição por SLOTS (ctx.slots) — ver §10                      │
└───────────────▲───────────────────────────────┬────────────────────┘
                │ /api  (RPC + /api/remote.mux) │ /plugins/* (bundles)
┌───────────────┴───────────────────────────────▼────────────────────┐
│ HOST (processo `dsh web`)                                          │
│  bin.js → profile-boot → Loader (cordis) → bundles/patch           │
│    webServer (rotas HTTP)  + frontend-static (fallback do SPA)     │
│    api-gateway (/api)      + client-connection (auth, transporte)  │
│    client-modules (varre dsh.client, gera __DSH_BOOT__, /plugins)  │
│    controllers Remote: session, workspace, settings, terminal,     │
│    job, account, workspaceFiles, pluginManager, schedule, ...      │
│    serviços de domínio: dsh-session (event-sourced), dsh-agent-loop,│
│    dsh-llm-*, dsh-tools, dsh-fs, dsh-jobs, dsh-schedule, ...       │
└────────────────────────────────────────────────────────────────────┘
```

---

## 4. Boot do host — passo a passo, com arquivos

| # | O que acontece | Onde está o código |
|---|---|---|
| 1 | `dsh web` é o launcher: `dsh <nome>` abrevia `--profile <nome>`. Ele só interpreta `--profile`, `--patch` e dumps; o resto vai para o app. | `$DSH/lib/bin.js` (`lib/types/args.js`) |
| 2 | Prepara o profile (manifesto, dependências, patch do usuário) e roda a árvore Cordis. | `$DSH/lib/profile-boot.js` → `lib/profile-boot-C14i-8IN.js` (`prepareProfile`, `runProfile`, `homePatchPath`) |
| 3 | O profile declara os bundles na ordem: `dsh-base`, `dsh-web-app`, `dsh-web-mobile`. | `$PROFILE/package.json` → `dsh.profile.bundles` |
| 4 | `cordis.yml` é uma raiz vazia (`[]`). A árvore real vem dos patches, em camadas: `dsh-base/cordis.patch.yml` (96 linhas) → `dsh-web-app/cordis.patch.yml` → `$PROFILE/cordis.patch.yml` → `--patch` extras. **Edite o patch do profile, nunca o `cordis.yml`.** | `$PROFILE/cordis.patch.yml` |
| 5 | Glue de boot: carrega `.env`, resolve config por snapshot e faz a auditoria "fail-loud" das entradas obrigatórias. | `$PKGS/dsh-app-boot/lib/index.js` |
| 6 | Flags do `web` viram o serviço `webStartup` (injetado nas linhas seguintes). | `$PKGS/dsh-web-app/lib/startup.js` |
| 7 | `web-runtime` resolve o dist do frontend, monta o `frontend-static`, imprime a URL e (opcionalmente) abre o navegador. | `$PKGS/dsh-web-app/lib/index.js` (`resolveDist`, `auditStartupEntries`) |
| 8 | `webserver` sobe HTTP/HTTPS, rotas, upgrades, gzip, injeções no `index.html` e um "fallback seat". | `$PKGS/dsh-host-webserver/lib/index.js` |
| 9 | `frontend-static` ocupa o fallback: serve `index.html`/assets e injeta as linhas de boot no HTML. | `$PKGS/dsh-host-frontend-static/lib/index.js` |
| 10 | `api-gateway` intercepta RPC em `/api` e abre o mux de streams em `/api/remote.mux`. | `$PKGS/dsh-api-gateway/lib/index.js` (`REMOTE_STREAM_MUX_PATH`) |
| 11 | `client-connection` (metade node) aplica a política de acesso (loopback/`trustedHosts`) e o gate de autenticação — sem token, a resposta é `401` com "dsh web authentication required". | `$PKGS/dsh-client-connection/lib/index.js` |
| 12 | `client-modules` (metade node) varre as entradas com `dsh.client`, compõe `window.__DSH_BOOT__` e registra a rota `/plugins`. | `$PKGS/dsh-client-modules/lib/index.js` |
| 13 | `client-hmr` observa os artefatos (`stat` a cada 500 ms, via `mtime/ctime/size`) e entrega grafo + reload por SSE. | `$PKGS/dsh-client-hmr/lib/index.js` |

### Flags aceitas por `dsh web`

`--host`, `--port`, `--no-open`, `--public-url`, `--tls-cert`, `--tls-key`, `--trusted-host <authority...>` (fonte: `$PKGS/dsh-web-app/lib/startup.js`; os valores caem em `ctx.webStartup`).

### Precedência de configuração (importante para saber onde persistir)

1. defaults do schema do plugin;
2. `config` do bundle que declara a linha (`dsh-base`/`dsh-web-app`);
3. **patch do profile** `$PROFILE/cordis.patch.yml` (é o alvo de escrita do `configEditor`);
4. **patch do home** `$HOME/.dsh/cordis.patch.yml` (é onde o `ui-theme` grava a preferência por navegador, segundo o README do pacote);
5. overlays `--patch`.

Um patch substitui o `config` inteiro da linha alvo — restate todas as chaves.

---

## 5. Boot do navegador — globais e ordem

1. `GET /` devolve `$FRONT/index.html`, que aponta para `assets/index-<hash>.js`, `assets/vendor-<hash>.js` e dois CSS com hash.
2. O shell recebe do host, por injeção no HTML, as linhas de boot: `global`, `script`, `script-src`, `style`, `html`. Entre elas:
   - `window.__DSH_BOOT__` — grafo de entradas (id, bundle, tier imediato, seções);
   - `window.__ModuleLoader__` — fachada que cada bundle de plugin chama (`__ModuleLoader__.load({id, factory})`);
   - `window.__DSH_TRANSPORT__` / `window.__DSH_BOOT_READY__` — usados no modo desktop/Electron;
   - bootstrap do tema (paleta e fontes aplicadas antes do primeiro paint).
3. O kernel (código já dentro de `assets/index-*.js`) cria o `ClientModuleSystem` (`$PKGS/dsh-client-modules/lib/client.js`, exporta `createClientModuleSystem`, `parseBootManifest`), registra a tabela de módulos estáticos e faz *prefetch* das entradas `immediately`.
4. Enquanto carrega, mostra a tela "Loading plugins…" (atributo `data-dsh-boot`); falhas viram "Failed to load plugins".
5. Cada plugin é avaliado e vira uma entrada Cordis; quando `uiRenderer` ativa, o shell chama `ctx.uiRenderer.mount(#root)`.
6. `ui-layout` registra `AppFrame` no slot `root` e declara as colunas; os demais plugins se penduram nos slots filhos.

### Tabela de módulos compartilhados (o "import map" do navegador)

Os bundles de plugin **não** empacotam React nem Cordis: eles `require()` estes ids, resolvidos pela tabela do shell:

`react`, `react/jsx-runtime`, `react-dom`, `react-dom/client`, `@deepseek-ai/cordis`, `zod`, `@tanstack/react-virtual`, `@deepseek-ai/dsh-client-store`, `@deepseek-ai/dsh-client-ui-slots`, `@deepseek-ai/dsh-client-ui-primitives`, `@deepseek-ai/dsh-client-ui-dockkit`.

Fonte: tabela estática dentro de `$FRONT/assets/index-*.js` (procure por `dsh-client-ui-dockkit`). Consequência prática: um plugin novo só pode importar esses ids — o resto precisa ser bundlado junto.

---

## 6. Mapa de diretórios (árvore comentada)

```
$DSH/
├── lib/bin.js                     # launcher `dsh` (profiles, --patch, dumps)
├── lib/profile-boot.js            # prepareProfile / runProfile
├── lib/profile-boot-*.js          # implementação do boot de profile
└── node_modules/@deepseek-ai/
    ├── dsh-web-frontend/dist/     # SPA compilado (index.html + assets com hash)
    ├── dsh-web-app/               # bundle web: patch, startup (flags), runtime (dist/URL)
    ├── dsh-base/                  # bundle base: 96 linhas de plugins de núcleo
    ├── dsh-app-boot/              # glue de boot, auditoria, config por snapshot
    ├── dsh-host-webserver/        # rotas HTTP/upgrade, gzip, injeção no index
    ├── dsh-host-frontend-static/  # fallback do SPA, renderIndex
    ├── dsh-api-*/                 # controllers Remote (session, workspace, settings...)
    ├── dsh-client-modules/        # kernel de módulos (node: scan/boot/rota; browser: loader)
    ├── dsh-client-connection/     # transporte RPC/SSE + gate de auth
    ├── dsh-client-hmr/            # reload a quente de plugins e do grafo
    ├── dsh-client-*/              # runtime de cliente (store, locale, shortcuts, resources...)
    ├── dsh-client-ui-*/           # TODA a UI: layout, sidebar, chat, composer, settings...
    ├── dsh-session*/              # sessão event-sourced e projeções
    ├── dsh-llm*/ dsh-tools/ dsh-fs*/ dsh-jobs*/ ...  # domínio do agente
    └── ...

$PROFILE/
├── cordis.yml                     # raiz vazia — não editar
├── cordis.patch.yml               # SEU patch (tema, locale, modelo, directory picker...)
├── package.json                   # dsh.profile.bundles (base, web-app, dsh-web-mobile)
├── pnpm-lock.yaml / pnpm-workspace.yaml
└── node_modules/dsh-web-mobile/   # plugin local (src/client/** + lib/client.js)
```

---

## 7. Host — pacotes e pontos de entrada

| Área | Pacote | Arquivo-chave | Papel |
|---|---|---|---|
| CLI / profiles | `dsh` | `lib/bin.js`, `lib/profile-boot.js` | Launcher, seleção de profile, overlays, dumps. |
| Boot | `dsh-app-boot` | `lib/index.js` | `.env`, guards fail-loud, auditoria de entradas, `profileContext`. |
| Composição web | `dsh-web-app` | `cordis.patch.yml`, `lib/startup.js`, `lib/index.js` | Rows web-only, flags, resolução do dist, URL, abertura do browser. |
| Núcleo | `dsh-base` | `cordis.patch.yml` | 96 linhas: agente, sessão, LLM, tools, fs, jobs, approvals... |
| HTTP | `dsh-host-webserver` | `lib/index.js` | `ctx.webServer.register/registerUpgrade/registerFallback/renderIndex`, evento `webserver/index-inject`. |
| SPA | `dsh-host-frontend-static` | `lib/index.js` | Fallback do SPA, `index.html`, assets, injeções. |
| RPC | `dsh-api-gateway` | `lib/index.js` | Dispatcher Typert em `/api`, streams em `/api/remote.mux`. |
| Transporte | `dsh-client-connection` | `lib/index.js` / `lib/client.browser.js` | Auth/loopback/trustedHosts; cliente fetch+SSE no browser. |
| Módulos de cliente | `dsh-client-modules` | `lib/index.js` / `lib/client.js` | Varredura `dsh.client`, `__DSH_BOOT__`, rota `/plugins`, loader no browser. |
| Hot reload | `dsh-client-hmr` | `lib/index.js` / `lib/client.js` | Grafo ao vivo + reload de bundles recompilados (`/plugins/events`). |
| Sessão | `dsh-api-session-controller` | `lib/typert.host.js`, `lib/index.js`, `lib/client.js` | Comandos e leituras de sessão; adapter do cliente. |
| Workspace | `dsh-api-workspace-controller` | idem | Workspaces, ordenação, pin, directory picker. |
| Settings | `dsh-api-settings-controller` | idem | `settings/describe`, `settings/mutate`, `settings/update`, `settings/replace`, credenciais. |
| Terminal | `dsh-api-terminal-controller` | idem | Terminais interativos por sessão. |
| Jobs | `dsh-api-job-controller` | idem | Lista/follow/kill de jobs em background. |
| Arquivos | `dsh-api-workspace-files` | `lib/typert.host.js` | `read`, `readBytes`, `list`, `stat`, `changes`. |
| Conta | `dsh-api-account-controller` | `lib/typert.host.js` | Login DeepSeek, saldo, bônus. |
| Plugins | `dsh-plugin-manager` | `lib/typert.host.js` + `lib/index.js` | Instalar/ativar/remover bundles do profile. |
| Catálogo | `dsh-plugin-market` | `lib/index.js` | Índices de market para a aba Discover. |
| Config | `dsh-config-editor` | `lib/index.js` | Escreve o patch do profile com validação e reconciliação. |
| Inventário | `dsh-host-plugin-inventory` | `lib/typert.host.js` | Projeção somente leitura do Loader. |
| Abrir em app | `dsh-host-open-in-app` | `lib/index.js` | 3 rotas + catálogo de apps do SO. |
| Diretórios | `dsh-host-directory-picker-auto` (+ `-browse`, `-native`) | `lib/index.js` | Seam de escolha de pasta (no profile: começa em `/storage/emulated/0/Projetos`). |
| Agenda | `dsh-schedule` | `lib/typert.host.js` | Lembretes duráveis do host. |
| Export | `dsh-session-log-export` | `lib/index.js` | Download do log da sessão. |

> **Regra dos controllers:** a superfície pública (métodos `namespace/metodo` e schemas Zod) fica em `lib/typert.host.js`, **gerada** por `@deepseek-ai/dsh-typert-generator` ("do not edit"). O tipo/consumo no browser fica em `lib/typert.remote-client.js`; a parte React/estado, quando existe, em `lib/client.js`.

---

## 8. Rotas HTTP e API Remote

### Rotas

| Rota | Registrada por | Função |
|---|---|---|
| `/` e `/assets/*` | `dsh-host-frontend-static` (fallback) | SPA + assets com hash + injeções de boot. |
| `/api` | `dsh-api-gateway` | RPC tipado (POST) — todas as chamadas `ctx.remote.*`. |
| `/api/remote.mux` | `dsh-api-gateway` | Mux WebSocket de streams (follow/página/eventos). |
| `/plugins/<id>/...` | `dsh-client-modules` (prefixo) | Bundles de cliente, combos e source maps. |
| `/plugins/events` | `dsh-client-hmr` | SSE de grafo/reload. |
| rotas de apps/ícones/launch | `dsh-host-open-in-app` | "Open In…" do header. |

### Namespaces Remote (métodos verificados em `lib/typert.host.js`)

| Namespace | Métodos |
|---|---|
| `session/*` | `create`, `list`, `prompt`, `control`, `page`, `projections`, `follow`, `fork`, `cancel`, `rename`, `search`, `selectModel`, `modelCatalog`, `updateQueue`, `attachment`, `initializeDefaultModel`, `canOpenWorkspacePath`, `openWorkspacePath`, `workspacePathApplications` |
| `fileReferences/*` | `list` |
| `skills/*` | `list` |
| `workspace/*` | `create`, `delete`, `rename`, `follow`, `archiveSession`, `unarchiveSession`, `pinSession`, `unpinSession`, `insertBefore`, `insertSessionBefore`, `initializeDefault` |
| `directoryPicker/*` | `list`, `createDirectory`, `pick` |
| `settings/*` | `describe`, `mutate`, `update`, `replace`, `openSettingsDocument` |
| `credentials/*` | `describe`, `set`, `unset` |
| `terminal/*` | `create`, `attach`, `close`, `environment`, `list`, `rename`, `retain`, `shells` |
| `job/*` | `list`, `follow`, `kill` |
| `workspaceFiles/*` | `read`, `readBytes`, `list`, `stat`, `changes` |
| `account/*` | `getState`, `getProfile`, `getBalance`, `watch`, `watchExpiry`, `startSignIn`, `cancelSignIn`, `signOut`, `hasRunningAccountTasks`, `getUnnotifiedBonuses`, `ackBonusNotified` |
| `pluginManager/*` | `listPlugins`, `listBundles`, `inspect`, `installBundle`, `removeBundle`, `setPluginEnabled`, `setBundleEnabled`, `cancelInstall`, `waitForInstall`, `registries`, `listVersionExemptions`, `setVersionExemption` |
| `pluginInventory/*` | `list` |
| `schedule/*` | `catalog`, `list`, `history`, `update`, `delete` |
| `fileUploads/*` | `upload` |
| `sessionFeedback/*` | `record` |

No cliente, a chamada é `ctx.remote.<namespace>.<metodo>(...)` (serviço `ClientRemote` do `dsh-api-gateway`).

---

## 9. Núcleo do cliente (quem manda no quê)

| Pacote | Serviço | Arquivo | Responsabilidade |
|---|---|---|---|
| `dsh-client-modules` | `ctx.modules` | `lib/client.js` | Tabela de módulos, manifesto, ciclo de vida de entradas. |
| `dsh-client-ui-renderer` | `ctx.slots`, `ctx.uiRenderer` | `lib/client.js` (`registry.js`, `bindings.js`, `app.js`) | Dono do slot `root`, renderização de slots, montagem do app. |
| `dsh-client-ui-layout` | `ctx.layout` | `lib/client.js` (`AppFrame.js`, `columns.js`, `service.js`, `theme-presenter.js`) | Colunas, drag handles, navegação de painel, tema no DOM. |
| `dsh-client-ui-session` | `ctx.uiSession` | `lib/client.js` | Adapta o Session Controller para React e slots por sessão. |
| `dsh-client-store` | hooks | fornecido pelo shell | Store observável (`useSyncExternalStore`, seletores). |
| `dsh-client-resources` | `ctx.resources` | `lib/client.js` | Modelo de recursos por URL (arquivos, previews). |
| `dsh-client-locale` | `ctx.locale` | `lib/client.js` | Preferência de idioma, catálogo, dicionários por namespace. |
| `dsh-client-ui-theme` | `ctx.theme` | `lib/client.js` + `lib/styles/*` | Tokens `--dsw-*`, light/dark/system, fontes, settings de aparência. |
| `dsh-commands` | `ctx.commands` | `lib/index.js` | Registro de comandos humanos (`/comando`) + execução. |
| `dsh-client-shortcuts` | `ctx.shortcuts` | `lib/client.js` | Comandos de teclado e catálogo efetivo. |
| `dsh-client-ui-input-trigger` | `ctx.inputTriggers` | `lib/client.js` | Pipeline `/` e `@`, menu de candidatos, roteamento. |
| `dsh-client-ui-conversation` | `ctx.conversation`, `ctx.uiConversation` | `lib/client.js` | Montagem da conversa, shell, composer, fila, navegação de views. |
| `dsh-client-ui-sidebar-right` | `ctx.sidebarRight`, `ctx.sidebarRightTabs` | `lib/client.js` (`shell/*`, `tab-registry.js`) | Docking, abas, fullscreen, persistência. |
| `dsh-client-ui-settings` | `ctx.configForms`, `ctx.settingsSchema` | `lib/client.js` (`config-form.js`, `schema.js`, `settings-mirror.js`) | Formulários derivados de schema e espelho do host. |

---

## 10. Anatomia da UI e catálogo de slots

### 10.1 O frame e quem ocupa cada região

O slot `root` é renderizado pelo shell e ocupado por **`ui-layout`/`AppFrame`**. Tudo o mais é declarado como filho dele. As larguras vêm de `columns.js` (`computeColumns`, `SIDEBAR_AUTO_COLLAPSE = 1024`, `RIGHTBAR_DEFAULT_RATIO`).

```
┌────────────────────────────────────────────────────────────────────────────┐
│ shell.leading  (ui-sidebar: controles de janela)                            │
│ ┌───────────────┬──────────────────────────────────────┬─────────────────┐ │
│ │ sidebar       │ main (keyed: "conversation")         │ rightbar        │ │
│ │ (ui-sidebar)  │ ┌──────────────────────────────────┐ │ (ui-sidebar-    │ │
│ │  bandeira     │ │ conversation.header              │ │  right)         │ │
│ │  novo chat    │ │ conversation.session.header      │ │  abas: arquivos │ │
│ │  panellist    │ │ conversation.session             │ │  terminal       │ │
│ │  workspaces   │ │   └ conversation.view            │ │  documento      │ │
│ │   └ sessões   │ │       └ ui-chat ChatView         │ │  plano/entreg.  │ │
│ │  settings     │ └──────────────────────────────────┘ │                 │ │
│ │               │ conversation.composer (chain)        │                 │ │
│ └───────────────┴──────────────────────────────────────┴─────────────────┘ │
│ shell.bottom (ui-layout) / shell.overlay (lista flutuante)                 │
└────────────────────────────────────────────────────────────────────────────┘
```

### 10.2 Região da tela → pacote → arquivo

| Região visível | Pacote governante | Arquivos (regiões do bundle) |
|---|---|---|
| Colunas, drag, navegação de painel, tema aplicado ao DOM | `dsh-client-ui-layout` | `AppFrame.js`, `columns.js`, `service.js`, `stores.js`, `theme-presenter.js`, `DocumentTitle.js` |
| Bandeja de marca, "novo chat", lista de painéis, pé de configurações | `dsh-client-ui-sidebar` | `SidebarRoot.js`, `HeaderLeadingControls.js`; marca por `dsh-client-ui-brand-official` |
| Lista de workspaces/sessões, busca, agrupamento, menus de linha, toasts | `dsh-client-ui-workspace` | `rows/Rows.js`, `rows/AnimatedRows.js`, `rows/WorkspaceBrowser.js`, `navigation.js`, `pin-order.js`, `WorkspacePicker.js`, `session-actions/*`, `toast/*` |
| Shell da conversa, header, navegação de views, área vazia (hero) | `dsh-client-ui-conversation` | `skeleton/ConversationRoot.js`, `ConversationMainPanel.js`, `ConversationContent.js`, `ConversationSession.js`, `ConversationHeader.js`, `EmptyHero.js`, `DefaultConversationViews.js`, `ConversationWidthControls.js` |
| Composer: editor, anexos, fila, política de envio, medidor de contexto | `dsh-client-ui-conversation` | `skeleton/InputBar.js`, `ContextMeter.js`, `input/editor/DraftEditor.js`, `input/editor/ComposerContentEditable.js`, `input/hub.js`, `input/machine.js`, `input/submission-policy.js`, `queue/QueueDock.js`, `settings/EnterBehaviorRow.js` |
| Transcript do chat: turnos, mensagens, raciocínio, navegação de turnos | `dsh-client-ui-chat` | `chat/ChatView.js`, `MessageItem.js`, `TurnNavigator.js`, `TurnProcessNodeView.js`, `StatsPills.js`, `conversation-nodes/*.js` |
| Cards de tool call (bash, read, diff, grep, terminal, web, ask) | `dsh-client-ui-tool` | `ToolCallTree.js`, `toolviews/GenericToolCard.js`, `file-mutation-row.js`, `read-family-row.js`, `bash-sample.js`, `models/*.js` |
| Aprovação de permissão (takeover do composer) | `dsh-client-ui-approval` | `lib/client.js` + slot `conversation.composer` |
| Plan mode (cards e barra) | `dsh-client-ui-plan` | `lib/client.js` + slots `conversation.chat.turnTail`, `conversation.input.plan` |
| Perguntas ao usuário (`ask_user_question`) e revisão de plano | `dsh-client-ui-user-questions` | `lib/client.js`, serviço `userQuestionPanels` |
| Barra de objetivo da sessão | `dsh-client-ui-goal` | `lib/client.js` (`conversation.input.dock`) |
| Header: jobs, agenda, subagentes, open-in-app, export | `ui-jobs`, `ui-schedule`, `ui-subagent`, `ui-open-in-app`, `dsh-session-log-export` | cada um registra em `conversation.session.header.actions` / `.utilities` |
| Coluna direita: abas, split, fullscreen, persistência | `dsh-client-ui-sidebar-right` | `shell/SidebarRight.js`, `shell/RightbarRoot.js`, `shell/ExpandButton.js`, `tabs/guide/*`, `tab-domain.js`, `tab-registry.js`, `persistence.js` |
| Abas: arquivos, terminal, documento, browser | `ui-sidebar-files`, `ui-sidebar-terminal`, `ui-sidebar-documentpreview`, `ui-sidebar-browser` | `lib/client.js` de cada um (registram `sidebar.right.pane.tab` / `.title`) |
| Modal de configurações, navegação e seções | `ui-settings` + `ui-settings-general` | `SettingsRoot.js`, `GeneralSection.js`, `chrome.js`, `settings-document-store.js`; seções de `ui-settings-models`, `settings-plugins`, `settings-account`, `ui-agent-preset` |
| Página Plugins (sidebar) e Discover | `ui-plugin-manager` + `ui-plugin-market` | `lib/client.js` (painel `main` com `PANEL_ID`), `plugins.tab` |
| Aparência (tema, fontes, tamanhos) | `dsh-client-ui-theme` | `AppearanceRow.js`, `FontSettingsGroup.js`, `FontFamilyRow.js`, `FontSizeRow.js`, `settings-store.js`, `styles/base.css` |
| Overlays globais (busca no chat, toasts, diálogos) | `ui-chat`, `ui-schedule`, `ui-settings*`, `ui-plugin-manager` | todos registram em `shell.overlay` |
| Adaptação mobile (este profile) | `dsh-web-mobile` (local) | `$LOCAL/src/client/**`; bundle `$LOCAL/lib/client.js` |

### 10.3 Catálogo completo de slots

Cada slot é um **contrato tipado** (`SlotMap` em `lib/types/**/*.d.ts`). O tipo/escopo decide quantos ocupantes cabem (`single`, `keyed`, `list`, `chain`) e a que escopo pertencem (`root` ou `session`). As tabelas abaixo foram extraídas dos `.d.ts` instalados.

### Frame raiz e colunas (ui-layout)

| Slot | Tipo / escopo | Declarado em | Registrantes instalados | Para que serve |
|---|---|---|---|---|
| `main` | keyed / root | ui-layout | ui-conversation, ui-plugin-manager, ui-schedule | Central panel selected by sidebar entry id. |
| `rightbar` | single / root | ui-layout | ui-sidebar-right | The right column: a track the centre makes room for, or nothing. |
| `root` | single / root | ui-renderer | ui-layout | The built-in render-tree root hole (seeded by SlotCore): the one slot the shell itself renders, and the ancestor of every other seat. |
| `shell.bottom` | single / root | ui-layout | — | Full-width bottom content below all three columns. |
| `shell.leading` | single / root | ui-layout | ui-sidebar | Window-chrome seat at the frame's top-left, over every main panel. |
| `shell.overlay` | list / root | ui-layout | ui-chat, ui-plugin-manager, ui-schedule, ui-settings-account, ui-settings-general, ui-settings-session-log, ui-shortcuts, ui-workspace | Frame-wide floating layer, above every column and outside their scroll containers. |
| `sidebar` | single / root | ui-layout | ui-sidebar | The whole left column. |

### Sidebar esquerda

| Slot | Tipo / escopo | Declarado em | Registrantes instalados | Para que serve |
|---|---|---|---|---|
| `sidebar.brand.mark` | single / root | ui-sidebar | ui-brand-official | Brand mark rendered in the expanded brand row and collapsed rail. |
| `sidebar.brand.name` | single / root | ui-sidebar | ui-brand-official | Brand name rendered beside the expanded mark. |
| `sidebar.footer.action` | list / root | ui-sidebar | ui-cordis | Optional actions beside Settings at the sidebar foot. |
| `sidebar.panellist` | list / root | ui-sidebar | ui-plugin-manager, ui-schedule | Global panel icons. |
| `sidebar.panellist.actions` | list / root | ui-sidebar | ui-entry-customization | Rows of the panel list's More menu; the sidebar shows More only while this list has registrations. |
| `sidebar.session.row.hover` | list / root | ui-workspace | ui-schedule | Section of the Session row's hover card between its relative time and its trailing status line. |
| `sidebar.session.row.leading` | list / root | ui-workspace | ui-schedule | Leading decoration of one Session row, in the 16px cell before the title that the row's own state dot otherwise occupies. |
| `sidebar.settings` | single / root | ui-sidebar | ui-settings-general | The settings seat at the sidebar foot. |
| `sidebar.toggle.badge` | single / root | ui-sidebar | ui-settings-general | Non-interactive notification inside the collapsed sidebar expand button. |
| `sidebar.workspaces` | single / root | ui-sidebar | ui-workspace | The workspace/session browsing region: section header, search, the grouped/flat session list, and every workspace dialog. |
| `sidebar.workspaces.directoryFlow` | single / root | ui-workspace | ui-directory-picker-browse, ui-directory-picker-native | Directory-flow hole under the sidebar browsing region (declared by the WorkspaceBrowser entry). |
| `sidebar.workspaces.session.menu.item` | list / root | ui-workspace | ui-workspace | The rows of one Session's "..." menu, in ascending `order`. |
| `sidebar.workspaces.session.row.action` | list / root | ui-workspace | ui-workspace | The hover buttons at the end of one Session row, in ascending `order`, after the "..." menu trigger. |

### Right sidebar / painéis laterais

| Slot | Tipo / escopo | Declarado em | Registrantes instalados | Para que serve |
|---|---|---|---|---|
| `rightbar.session` | single / session | ui-sidebar-right | ui-sidebar-right | Session content selected by the root-scoped right Sidebar controller. |
| `sidebar.right.pane.tab` | keyed / session | ui-sidebar-right | experimental-session-inspector, ui-deliverables, ui-plan, ui-schedule, ui-sidebar-browser, ui-sidebar-documentpreview, ui-sidebar-files, ui-sidebar-right, ui-sidebar-terminal, ui-subagent | One tab's body, dispatched with the `id` of the type in force for `tab.kind`. |
| `sidebar.right.pane.tab.title` | keyed / session | ui-sidebar-right | ui-plan, ui-schedule, ui-sidebar-browser, ui-sidebar-documentpreview, ui-sidebar-files, ui-sidebar-right, ui-sidebar-terminal | A tab's title as its chip (and a floating panel's header) shows it, dispatched with the same key and information hook as the body. |
| `sidebar.right.tab.document` | keyed / session | ui-sidebar-documentpreview | ui-sidebar-documentpreview | Document body selected by a registered implementation id. |
| `sidebar.right.tab.document.action` | keyed / session | ui-sidebar-documentpreview | ui-sidebar-documentpreview | Renderer-specific controls before the document toolbar's reload button. |
| `sidebar.right.tab.document.actions` | list / session | ui-sidebar-documentpreview | ui-open-in-app | Header toolbar contributions acting on the previewed file, rendered after the preview's own controls once the file's Host path is known. |
| `sidebar.right.tab.document.office.pdf` | keyed / session | ui-sidebar-documentpreview | ui-sidebar-documentpreview | PDF presentation supplied with Office-owned converted bytes. |
| `sidebar.right.tab.document.unpreviewable` | list / session | ui-sidebar-documentpreview | ui-open-in-app | Empty-state contributions for a file this preview cannot render, offered where Retry would stand once the file's Host path is known. |
| `sidebar.right.tab.files.actions` | list / session | ui-sidebar-files | ui-open-in-app | Workspace directory actions after the file tree's reload control. |
| `sidebar.right.tab.guide` | chain / session | ui-sidebar-right | — | The guide tab's body. |
| `sidebar.right.tab.guide.actions` | list / root | ui-sidebar-right | ui-entry-customization | Rows of the guide's More menu; the guide shows More only while this list has registrations. |
| `sidebar.right.tab.guide.entry` | keyed / session | ui-sidebar-right | ui-sidebar-terminal | One provider's guide card, with the standard card as the owner's fallback. |
| `sidebar.right.tab.menu.item` | list / session | ui-sidebar-right | — | Extra items at the end of one tab's actions menu, in registration order. |

### Conversa: shell, header, composer

| Slot | Tipo / escopo | Declarado em | Registrantes instalados | Para que serve |
|---|---|---|---|---|
| `conversation.composer` | chain / session | ui-conversation | ui-approval, ui-subagent, ui-user-questions | Selector-routed replacements for the current Session's resident composer. |
| `conversation.composer.bar` | single / session-maybe | ui-conversation | ui-conversation | Resident composer body, including the no-Session inert state. |
| `conversation.composer.dock` | list / session | ui-conversation | ui-chat | Ambient entries below the composer card. |
| `conversation.header` | single / session-maybe | ui-conversation | ui-conversation | Resident navigation container, including when no Session is selected. |
| `conversation.header.leading` | single / root | ui-conversation | — | Global navigation before the Session title, available without a Session. |
| `conversation.hero.agentPreset` | single / session-maybe | ui-conversation | ui-agent-preset | Agent-preset control staged for a New Session. |
| `conversation.hero.brand.mark` | single / root | ui-conversation | — | Brand mark shown before the blank-session headline. |
| `conversation.hero.options` | list / session | ui-conversation | — | Compact options right-aligned in the New Session row after the workspace and preset controls; rendered only with a Session. |
| `conversation.hero.workspace` | single / root | ui-conversation | ui-workspace | Workspace picker shown by the blank-session Hero. |
| `conversation.hero.workspace.directoryFlow` | single / root | ui-workspace | ui-directory-picker-browse, ui-directory-picker-native | Directory-flow hole under the conversation empty-state picker (declared by the WorkspacePicker entry). |
| `conversation.input.activity` | single / session | ui-conversation | experimental-client-ui-voice-input | Compact action after the model selector; it can expand across the toolbar while retaining the editor and submit action. |
| `conversation.input.attachments` | single / session-maybe | ui-conversation | ui-attachment | Optional draft-attachment rail and drop target. |
| `conversation.input.dock` | list / session | ui-conversation | ui-conversation, ui-goal | Full-width entries above the composer card. |
| `conversation.input.left` | list / session | ui-conversation | — | Compact controls at the left of the composer tool row. |
| `conversation.input.model` | single / session | ui-conversation | ui-model-selection | Model selector inside the composer tool row. |
| `conversation.input.model.label` | single / session | ui-model-selection | ui-deepseek-model-label-1017 | Non-interactive inline model label; selection controls remain owner-managed. |
| `conversation.input.overlay` | list / session | ui-conversation | ui-commands, ui-input-trigger, ui-message-feedback | Floating entries rendered inside the resident composer card. |
| `conversation.input.permission` | single / session | ui-conversation | ui-permission-presets | Current-session permission control inside the composer tool row. |
| `conversation.input.plan` | single / session | ui-conversation | ui-plan | Plan control inside the composer tool row. |
| `conversation.input.right` | list / session | ui-conversation | — | Compact controls before the composer submit action. |
| `conversation.plan-review.actions` | list / session | ui-user-questions | ui-plan | Actions for the exact plan under review; approval remains with the question composer. |
| `conversation.session` | single / session | ui-conversation | ui-conversation | Strict per-Session Conversation body. |
| `conversation.session.header` | single / session | ui-conversation | ui-conversation | Strict per-Session title, actions, and View navigation. |
| `conversation.session.header.actions` | list / session | ui-conversation | experimental-client-ui-agent-team, ui-agent-preset, ui-jobs, ui-subagent | Title-adjacent Session actions in ascending order. |
| `conversation.session.header.corner` | single / session | ui-conversation | ui-sidebar-right | The header's far-right corner, past the utilities' edge and into the header's own padding, for one control. |
| `conversation.session.header.lineage` | single / session | ui-conversation | ui-subagent | Optional replacement for one Session breadcrumb title. |
| `conversation.session.header.utilities` | list / session | ui-conversation | session-log-export, ui-open-in-app, ui-schedule | Right-aligned Session utilities in ascending order. |
| `conversation.session.header.views.actions` | list / root | ui-conversation | ui-entry-customization | Rows of the View tab row's More menu; the header shows More only while this list has registrations. |
| `conversation.user.text.node` | keyed / session | ui-conversation | ui-conversation, ui-goal, ui-reference, ui-secret-mask, ui-skill | One business-owned inline renderer for a pure JSON text node. |
| `conversation.view` | list / session | ui-conversation | ui-chat, ui-trajectory | Registered Conversation target Views, rendered one at a time. |

### Chat, mensagens, tools e entregáveis

| Slot | Tipo / escopo | Declarado em | Registrantes instalados | Para que serve |
|---|---|---|---|---|
| `conversation.approval.detail` | single / session | ui-approval | ui-chat | Optional detail for the Tool call correlated with an approval request. |
| `conversation.chat.assistant-actions` | list / session | ui-chat | ui-message-feedback | Ordered actions for one finalized assistant message. |
| `conversation.chat.commandview` | keyed / session | ui-chat | — | Command row keyed by the command name. |
| `conversation.chat.flow` | single / session | ui-chat | ui-chat | Ordered Chat rows and local echoes with viewport-bound visibility hooks. |
| `conversation.chat.message.images` | single / session | ui-chat | ui-attachment | Renderer for one consecutive group of durable message images. |
| `conversation.chat.node` | keyed / session | ui-chat | ui-chat, ui-goal, ui-tool, ui-user-questions, ui-workflow-run | Final Chat node renderer, keyed by `ChatNodeKind`. |
| `conversation.chat.reasoning.body` | single / session | ui-chat | experimental-client-ui-cot-translation, ui-chat | Expanded reasoning body with raw and display-ready text. |
| `conversation.chat.turnTail` | list / session | ui-chat | ui-deliverables, ui-plan, ui-schedule | Ordered feature contributions before a completed Turn's action row. |
| `conversation.chat.user.images` | single / session | ui-chat | ui-attachment | User-message images, including pending and local-echo previews, independently replaceable from output images. |
| `conversation.trajectory.images` | single / session | ui-trajectory | ui-attachment | Renderer for one group of durable record images in the Trajectory ledger. |
| `deliverables.file.actions` | list / session | ui-deliverables | ui-open-in-app | Open one file through its authorized Session event coordinates. |
| `deliverables.review.file.actions` | list / session | ui-deliverables | ui-open-in-app | The same file actions owned by a changed-file review tab. |
| `tool.call.images` | single / session | ui-tool | ui-attachment | Durable images of a settled image-bearing Tool call, rendered through the attachment presentation plugin. |
| `tool.call.toolview` | keyed / session | ui-tool | ui-cordis, ui-deliverables, ui-skill, ui-tool | Keyed Tool call view dispatched by wire Tool name. |
| `tool.view.cordis` | keyed / session | ui-cordis | — | Interactive Package-owned region rendered inside the latest eligible `cordis_run` card in the conversation flow. |

### Settings

| Slot | Tipo / escopo | Declarado em | Registrantes instalados | Para que serve |
|---|---|---|---|---|
| `settings.action` | list / root | ui-settings | ui-settings-general | Optional actions rendered in the content-column header before Close. |
| `settings.close` | single / root | ui-settings | ui-settings-general | The close button's visually-hidden label text (the button itself — icon, geometry, focus — is shell chrome). |
| `settings.general.item` | list / root | ui-settings | client-locale, ui-chat, ui-conversation, ui-permission-presets, ui-settings-general, ui-settings-session-log, ui-shortcuts, ui-theme | One preference row inside the General section — the additive seat for a single setting that needs no page of its own (a whole page is `settings.secti… |
| `settings.header` | single / root | ui-settings | ui-settings-general | The panel title text seat. |
| `settings.launcher` | single / root | ui-settings | ui-settings-account | Optional sidebar account launcher; opens the shell-owned settings panel. |
| `settings.models.footer` | list / root | ui-settings-models | — | Ordered extension area after the provider rows and the add controls. |
| `settings.models.provider-card` | keyed / root | ui-settings-models | — | One provider card's adapter extension area, dispatched with `entryKey = settingsNs` on every card that renders a directory row: a saved row's card (i… |
| `settings.models.sign-in` | single / root | ui-settings-models | ui-settings-account | Optional account login choice before the credential editor. |
| `settings.onboarding` | list / root | ui-settings | ui-settings-models | Root-scoped onboarding steps contributed by settings features. |
| `settings.plugins.tab` | list / root | ui-settings | ui-settings-plugin-inventory | One page inside the Plugins settings section. |
| `settings.section` | list / root | ui-settings | ui-agent-preset, ui-settings-account, ui-settings-general, ui-settings-models, ui-settings-plugins | One settings page per list entry. |
| `settings.trigger` | single / root | ui-settings | ui-settings-general | The sidebar-foot trigger row content: icon + label, supplied as slot content (the accessible name comes from the content — rail state renders the lab… |

### Página Plugins

| Slot | Tipo / escopo | Declarado em | Registrantes instalados | Para que serve |
|---|---|---|---|---|
| `plugins.add.actions` | list / root | ui-plugin-manager | ui-agent-preset | Additional MenuItemButton rows after installation: 72px high, with a title and a description capped at two lines. |
| `plugins.bundle.activation` | keyed / root | ui-plugin-manager | experimental-client-ui-voice-input | Optional guidance after the user enables a bundle from the list, keyed by npm package name. |
| `plugins.bundle.config` | keyed / root | ui-plugin-manager | experimental-client-ui-cot-translation, experimental-client-ui-voice-input | A bundle's own configuration, keyed by the bundle's package name and rendered on the bundle's page between its description and its rows (`view: 'page… |
| `plugins.detail.actions` | list / root | ui-plugin-manager | — | Controls at the head of a detail page, before the page's own switch and uninstall, rendered with the page's subject. |
| `plugins.detail.badge` | list / root | ui-plugin-manager | — | Tags beside a detail page's title, after the version, beta, and problem tags the page draws itself, rendered with the page's subject. |
| `plugins.detail.section` | list / root | ui-plugin-manager | — | Sections under a detail page's own content: after the rows on a bundle's page, after the configuration on a row's or an official plugin's page. |
| `plugins.item` | list / root | ui-plugin-manager | ui-settings-agent-loop, ui-settings-shell, ui-settings-subagent, ui-settings-web-search | One official plugin the Plugins page lists in its Official group after the official bundles: `label` is the card's title and `order` its place. |
| `plugins.row.config` | keyed / root | ui-plugin-manager | — | The configuration of one row a bundle declares, keyed by `<package name>#<row id>` with the row id as the bundle's patch declares it: the row on the… |
| `plugins.tab` | list / root | ui-plugin-manager | ui-plugin-market | Tabs after the page's own Installed tab, such as Discover: `label` names the tab and `order` places it. |

### Workspace, toasts e linhas de sessão

| Slot | Tipo / escopo | Declarado em | Registrantes instalados | Para que serve |
|---|---|---|---|---|
| `sidebar.chat.conversation` | single / session | ui-subagent | ui-subagent | Session-scoped Conversation occurrence hosted by one Sidebar chat tab. |
| `workspace.toast` | keyed / root | ui-workspace | ui-workspace | Workspace notices selected by their existing business discriminator. |

### Outros

| Slot | Tipo / escopo | Declarado em | Registrantes instalados | Para que serve |
|---|---|---|---|---|
| `shell.quota-notice` | chain / root | ui-chat | ui-settings-account | Frame-wide quota notice chain. |
| `main.conversation` | single / session-maybe | ui-conversation | ui-conversation | Conversation shell beneath its root-scoped main-panel entry. |

**Total: 103 slots** declarados pelos pacotes instalados (extraídos de `lib/types/**/*.d.ts`).

---

## 11. Serviços (os pontos de extensão reais)

### 11.1 No navegador (`ctx.*`)

| Serviço | Definido em | Use para |
|---|---|---|
| `ctx.slots` | `ui-renderer` | Declarar slots, registrar/ocupar, stores por escopo. É o mecanismo central da UI. |
| `ctx.layout` | `ui-layout` | Selecionar painel principal, recolher/expandir sidebar, largura da coluna direita. |
| `ctx.theme` | `ui-theme` | Ler `ThemeSnapshot`; registrar tema de terceiros (`registerTheme`) com overrides de tokens. |
| `ctx.locale` | `client-locale` | Registrar namespace de tradução, traduzir, reagir a troca de idioma. |
| `ctx.commands` | `dsh-commands` | Registrar comandos `/…` (host-side registry, consumido pela UI). |
| `ctx.shortcuts` | `client-shortcuts` | Registrar atalho + entrada no catálogo de referência. |
| `ctx.inputTriggers` | `ui-input-trigger` | Novas fontes para `/` e `@` (candidatos, inserção). |
| `ctx.resources` | `client-resources` | Resolver URLs (arquivo, preview) em valores vivos. |
| `ctx.uiSession` | `ui-session` | Criar fontes/observáveis por sessão e hooks para slots. |
| `ctx.uiConversation` / `ctx.conversation` | `ui-conversation` | Registries da conversa e ações por sessão. |
| `ctx.sessions`, `ctx.workspaces` | `api-session-controller`, `api-workspace-controller` (`lib/client.js`) | Estado e comandos de sessão/workspace no cliente. |
| `ctx.remote` | `api-gateway` (`client.browser.js`) | Chamar qualquer namespace Remote. |
| `ctx.sidebarRight` / `ctx.sidebarRightTabs` | `ui-sidebar-right` | Abrir/fechar painel, registrar tipo de aba. |
| `ctx.documentPreviews` | `ui-sidebar-documentpreview` | Renderizador por extensão de arquivo. |
| `ctx.userQuestionPanels` | `ui-tool` | Painel de resposta para `ask_user_question`. |
| `ctx.modules` | `client-modules` | Introspecção do grafo de plugins do navegador. |

### 11.2 No host (`ctx.*`)

`ctx.webServer` (rotas/injeções), `ctx.typertGateway` (dispatch Remote), `ctx.connection`, `ctx.clientModules`, `ctx.hmr`, `ctx.configEditor` (patch do profile), `ctx.pluginManager`, `ctx.pluginMarket`, `ctx.settingsController`, `ctx.sessionController`, `ctx.workspaceController`, `ctx.terminalController`, `ctx.jobController`, `ctx.directoryPicker`, `ctx.schedule`, `ctx.sessionLogDownload`, `ctx.workspaceFiles`, `ctx.fileUploads`, `ctx.profileContext`, `ctx.webApp`.

Para a lista completa com descrição: `grep -n "interface Context" -A20 $PKGS/*/lib/types/**/*.d.ts` (ou use `cordis_inspect_list` / `cordis_inspect_query` com o runtime no ar).

---

## 12. Onde mudar o quê (índice por tarefa)

> Todos os caminhos são relativos a `$PKGS/<pacote>/`. “região” = marcador `//#region` dentro de `lib/client.js`.

| Se a mudança é… | Vá para | Detalhe |
|---|---|---|
| Texto/label de qualquer tela | `lib/client.js`, região `lib/types/client/locales.js` | Procure `const NS = "…"`, `const en = {`, `const zh = {`. Os `locale/*.json` são só metadados (`{"meta":{"title":…}}`). |
| Cores, superfícies, bordas | `dsh-client-ui-theme/lib/client.js` | CSS inline (`\0dsh-inline-css:.../src/styles/base.css.mjs`), tokens `--dsw-alias-*`, `--dsw-static-*`; `lib/styles/brand-font.css` para a fonte da marca. |
| Tema padrão (light/dark/system) | `$PROFILE/cordis.patch.yml` | Linha `ui-theme` → `config.preference` (hoje `dark`). Preferência por navegador vai para `$HOME/.dsh/cordis.patch.yml`. |
| Tema de terceiros (sem editar bundle) | plugin próprio | `ctx.theme.registerTheme({id, colorScheme, tokens})`; ver contrato em `ui-theme/lib/types/client/index.d.ts`. |
| Tamanhos de fonte, famílias | `ui-theme` | `FontSizeRow.js`, `FontFamilyRow.js`, `FontSettingsGroup.js`, `settings-store.js`; aplicação no DOM em `ui-layout`/`theme-presenter.js`. |
| Largura das colunas / auto-collapse | `ui-layout` | `columns.js` (`computeColumns`, `SIDEBAR_AUTO_COLLAPSE=1024`, `RIGHTBAR_MAX_RATIO`, `RIGHTBAR_DEFAULT_RATIO`). |
| Título da aba, favicon, PWA | `$FRONT/index.html`, `$FRONT/manifest.webmanifest`, `$FRONT/favicon*.svg` (build); `ui-layout/DocumentTitle.js` para o título dinâmico | `productTitle` default `"DeepSeek Harness"`. Exige rebuild do app shell. |
| Sidebar: marca, “novo chat”, lista de painéis, rodapé | `ui-sidebar` | `SidebarRoot.js`; slots `sidebar.brand.*`, `sidebar.panellist`, `sidebar.settings`, `sidebar.footer.action`. |
| Árvore/busca/agrupamento de sessões | `ui-workspace` | `rows/Rows.js`, `WorkspaceBrowser.js`, `navigation.js`, `pin-order.js`, `session-actions/*`. |
| Header da sessão (título, ações, utilidades) | `ui-conversation` + contribuintes | `skeleton/ConversationHeader.js`; registre em `conversation.session.header.actions` / `.utilities` / `.lineage`. |
| Composer (editor, envio, fila, anexos) | `ui-conversation` | `skeleton/InputBar.js`, `input/editor/DraftEditor.js`, `input/editor/ComposerContentEditable.js`, `input/submission-policy.js`, `queue/QueueDock.js`. |
| Enter vs. steer em sessão ocupada | `$PROFILE/cordis.patch.yml` + `ui-conversation` | Config `ui-conversation.busyEnter: steer`; UI em `settings/EnterBehaviorRow.js`. |
| Model picker / catálogo | `ui-model-selection` | Registra `conversation.input.model`; dados de `session/modelCatalog`. |
| Presets de permissão | `ui-permission-presets` + `dsh-permission-presets` | Slot `conversation.input.permission`, seção em Settings. |
| Aprovação (permitir/negar) | `ui-approval` | Takeover de `conversation.composer` + `conversation.approval.detail`; seam host `dsh-user-approval`. |
| Transcript, mensagens, raciocínio, turnos | `ui-chat` | `chat/ChatView.js`, `MessageItem.js`, `TurnNavigator.js`, `conversation-nodes/*`; renderer por tipo em `conversation.chat.node`. |
| Card de uma tool específica | `ui-tool` | `toolviews/*` + registro keyed `tool.call.toolview` com `key: '<nome da tool>'`. |
| Nova seção em Configurações | `ui-settings-*` | Registre em `settings.section` (list) com `id`, `order`, `label`; formulário via `ctx.configForms` (`ui-settings/config-form.js`). |
| Página Plugins / instalar bundle | `ui-plugin-manager` + `pluginManager/*` | Painel `main` com `PANEL_ID`; página `ui-settings-plugins` e `ui-settings-plugin-inventory`. |
| Nova aba na coluna direita | `ui-sidebar-right` | `ctx.sidebarRightTabs.register(...)` + corpo em `sidebar.right.pane.tab` (keyed por id) + título em `.title`. |
| Novo painel principal (página na sidebar) | `ui-plugin-manager` como modelo | Registre `main` com `key: PANEL_ID` e um item em `sidebar.panellist`; declare os filhos no `children`. |
| Comando `/…` novo | `dsh-commands` + `ui-commands` | `ctx.commands.register(definition)` no host; o composer resolve `command/<name>`. |
| Atalho de teclado | `client-shortcuts` + `ui-shortcuts` | `ctx.shortcuts.register({id, defaults, regions, resolve})` (modelo em `ui-layout`, atalho `sidebar.left.toggle`). |
| Overlay/toast global | `ui-layout` | Registre em `shell.overlay` (lista, click-through; opte por pointer events). |
| Endpoint/API nova | `dsh-api-*` (host) | `lib/typert.host.js` é gerado do FaceModel; consumidor em `lib/typert.remote-client.js`; chamada `ctx.remote.<ns>.<metodo>`. |
| Nova projeção de sessão | `dsh-session-projection*` | Registre a projeção e exponha via `session/projections`; UI lê no snapshot do chat. |
| Comportamento mobile / gestos / sheets | `$LOCAL/src/client/**` | `styles/*.css.ts`, `effects/*.ts`, `core/*-core.ts`, `index.tsx`; bundle em `$LOCAL/lib/client.js`. |
| Modelo LLM padrão / providers | `$PROFILE/cordis.patch.yml` | Linhas `agent-default-model` e `llm-pi-ai` (hoje `opencode-go/deepseek-v4.1-flash`). |
| Idioma padrão | `$PROFILE/cordis.patch.yml` | Linha `locale.config.preference` (hoje `en`). |
| Escolha de pasta de projeto | `$PROFILE/cordis.patch.yml` | Linha `directory-picker.config.startDirectory`. |

---

## 13. Fluxo de dados de uma mensagem (para saber onde tocar)

```
composer (ui-conversation)
  input/editor/DraftEditor.js + input/submission-policy.js + input/hub.js
      │  ctx.remote.session.prompt({...})
      ▼
/api  →  api-gateway  →  session-controller (lib/typert.host.js)
      ▼
dsh-session (event-sourced: eventos turn/step/tool/…)
      ▼
dsh-agent-loop + dsh-llm-* + dsh-tools + dsh-fs  (host)
      ▼
projeções (dsh-session-projection*; sessão → chat, stats, outline, goals)
      ▼
session/control (stream) + session/page|projections
      ▼
ui-session (ctx.uiSession) → ui-chat (ChatSnapshot → ChatView/MessageItem/TurnNavigator)
```

Para rastrear um campo que aparece na tela: comece pelo componente em `ui-chat`/`ui-conversation`, veja qual `hooks`/`props` ele lê do slot, e siga até o contrato em `lib/types/client/contract/*.d.ts` — o nome do campo leva ao evento/projeção correspondente no host (`dsh-session*`).

---

## 14. Como aplicar e verificar uma alteração

### 14.1 Editar um plugin de UI já instalado (mais rápido)

1. Edite `$PKGS/<pacote>/lib/client.js` (JS e CSS inline). Se editar só `src/` (quando existir), nada muda — o que roda é o bundle.
2. O `dsh-client-hmr` faz `stat` do artefato a cada **500 ms** (`pollIntervalMs`). Alterar `mtime/ctime/tamanho` dispara o reload daquele plugin na página aberta; plugins dependentes recarregam em cascata.
3. Se a página não atualizar: recarregue (F5) — o bundle é servido por URL com revisão; um reload busca a versão nova. Em último caso, reinicie `dsh web`.
4. **Custo:** o estado React do plugin recarregado é perdido; sessão, workspace e conexão sobrevivem.

### 14.2 Editar a casca (`apps/web`) ou estilos globais

- O shell é build pronto em `$FRONT`. CSS/JS do shell têm hash e o dist é resolvido no boot (`resolveDist` em `dsh-web-app/lib/index.js`).
- Mudança exige o repositório + build Vite (`pnpm --filter @deepseek-ai/dsh-web-frontend build`, origem `apps/web` sobre `packages/client/web`) e **reiniciar** o `dsh web` (novo hash/nome de asset).
- Para tokens/cores, prefira `ui-theme` (plugin) — não exige rebuild do shell.

### 14.3 Criar um plugin local (sobrevive a updates)

Modelo real neste profile: `$LOCAL` (`dsh-web-mobile`).

1. Pacote em `$PROFILE/node_modules/<id>/` com:
   - `package.json`: `"main": "lib/index.js"`, `"exports": {".": …, "./client": "./lib/client.js"}`, `"dsh": {"client": {"platform": "web", "inject": [...]}}`;
   - `lib/index.js`: metade host (pode ser um `apply()` vazio);
   - `lib/client.js`: bundle no formato `window.__ModuleLoader__.load({ id, factory })`, importando só os módulos da tabela do shell (§5);
   - opcional `cordis.patch.yml` com `- insert: [{ id: <id>, name: '<id>' }]`.
2. Registre a linha no `$PROFILE/cordis.patch.yml` (ou instale como bundle, como o `dsh-web-mobile`).
3. Recarregue a página. O plugin entra no grafo (`__DSH_BOOT__`) e passa a receber HMR.
4. Build do bundle: use o preset tsdown do `@deepseek-ai/dsh-client-modules` (upstream) ou copie o padrão do `dsh-web-mobile`. **Neste profile não há `scripts/` nem `tsconfig` do mobile** — para recompilar é preciso clonar o repositório.

### 14.4 Persistir configuração

- Pela UI (Settings/Plugins): grava no **patch do profile** via `config-editor` (valida, preserva comentários e `!!js`, reconcilia via Loader).
- Preferências por navegador (tema/fontes): `ui-theme` grava no **patch do home** `$HOME/.dsh/cordis.patch.yml`.
- Patch substitui o `config` inteiro da linha — ao editar à mão, mantenha todas as chaves.

### 14.5 Cuidados

| Risco | Efeito | Mitigação |
|---|---|---|
| Atualizar o CLI (`npm i -g @deepseek-ai/dsh@…`) | sobrescreve `$PKGS/**` (suas edições somem) | manter mudanças em plugin do profile; reaplicar após update |
| `dsh plugin add/remove` | reescreve `$PROFILE/package.json` + `node_modules` | versionar o plugin local fora do profile |
| Editar `cordis.yml` | é a raiz vazia; suas linhas somem | editar `cordis.patch.yml` |
| Editar `typert.host.js` | arquivo gerado ("do not edit") | mudar o FaceModel/origem e regerar |
| Confiar em `src/` sem build | nada muda em runtime | editar `lib/` ou recompilar |
| Esquecer o token da URL | `401 authentication required` | usar a URL impressa por `dsh web` |

### 14.6 Comandos de verificação

```bash
# versão em execução
cat $DSH/package.json | grep '"version"'

# lista de regiões-fonte de um bundle (revela os arquivos .ts/.tsx originais)
grep -oE '^[[:space:]]*//#region lib/types/client/.*' $PKGS/dsh-client-ui-chat/lib/client.js | head -40

# quem registra/ocupa um slot
grep -rn 'name: "settings.section"' $PKGS/*/lib/client.js

# métodos de um namespace Remote
grep -oE "^    '[a-z]+/[A-Za-z]+': \{" $PKGS/dsh-api-session-controller/lib/typert.host.js

# strings de UI (dicionário compilado no bundle)
grep -n 'const en = {' $PKGS/dsh-client-ui-chat/lib/client.js

# plugins carregados pelo navegador (grafo) — requer o token da URL
curl -s "$GUI_URL/plugins/dsh-web-mobile/client.js" | head -c 200
```

---

## 15. Lacunas conhecidas deste mapa

1. **Sem fontes TypeScript** dos pacotes oficiais: só `lib/*.js` + `.d.ts`. O caminho `src/...` citado nas regiões não existe localmente; existe no repositório (`repository.directory`).
2. **O shell (`apps/web` → `packages/client/web`) não está instalado** — só o dist. `dsh-client-web`, `dsh-client-ui-slots`, `ui-primitives`, `ui-dockkit` e `dsh-client-store` são fornecidos em runtime pela tabela do shell e não têm pasta em `$PKGS`.
3. **`*.js.map` não são publicados**; a leitura é pelo bundle compilado (com comentários e regiões).
4. **`typert.host.js` é gerado** — edições locais são frágeis.
5. **`dsh-web-mobile`** tem `src/client/**`, mas o pacote publicado não inclui `scripts/`/`tsconfig`; recompilar exige o repositório.
6. Este mapa cobre a composição **deste** profile (`web` + `dsh-web-mobile`). Outros profiles (`acp`, `headless`) e bundles experimentais mudam o conjunto de plugins.

Para regenerar/atualizar: os catálogos de slots e de pacotes foram extraídos por script de `$PKGS/*/lib/types/**/*.d.ts` e `$PKGS/*/package.json`. Um `grep` pelas âncoras acima confirma se algo mudou de lugar após um upgrade.

---

## Apêndice A — inventário de arquivos-fonte dos pacotes principais

Nomes conforme as regiões `//#region lib/types/...` (o fonte correspondente é `src/client/...` no repositório).

**ui-layout** — `AppFrame.tsx`, `columns.ts`, `DocumentTitle.tsx`, `service.ts`, `stores.ts`, `theme-presenter.ts`, `shortcut-locales.ts`

**ui-renderer** — `app.tsx`, `bindings.ts`, `registry.ts`, `scoped-slots.ts`, `bind.ts`, `errors.ts`

**client-modules** — `manifest.ts`, `system.ts`, `entries.ts`, `entry-lifecycle.ts`, `notify.ts`, `release-change.ts`

**ui-conversation** — `skeleton/{ConversationRoot,ConversationMainPanel,ConversationContent,ConversationSession,ConversationHeader,EmptyHero,InputBar,ContextMeter,TodoPanel,ConversationWidthControls}.tsx`, `input/{hub,machine,submission-policy,blocks,facade}.ts`, `input/editor/{DraftEditor,ComposerContentEditable,keymap,view-binding,Decorations,ReferenceChip,CommandChip}.tsx`, `queue/QueueDock.tsx`, `settings/EnterBehaviorRow.tsx`, `conversation/{assembler,view-registry,group-registry,event-registry}.ts`, `user-text/UserText.tsx`

**ui-chat** — `chat/{ChatView,MessageItem,TurnNavigator,TurnProcessNodeView,TurnTailNodeView,StatsPills,MessageIconActions}.tsx`, `chat/{use-chat-viewport,use-scroll-follow,use-disclosure,message-chrome}.ts`, `conversation-nodes/{message,tool,assistant,command,compaction,turn-error,turn-process,turn-tail,turn-navigation}.tsx`, `contract/{snapshot,chat-nodes,slots,store}.ts`, `settings/TranscriptViewRow.tsx`

**ui-tool** — `ToolCallTree.tsx`, `tool/components/{ToolRow,ToolDetails,AskQuestionCard,ExitCodeBadge,QuestionToolRow}.tsx`, `tool/toolviews/{GenericToolCard,file-mutation-row,read-family-row,read-row,read-image-row,bash-sample,ask-question-row}.tsx`, `tool/models/{tool-call-model,raw-tool-call,read-card-model,diff-card-model,search-card-model,terminal-card-model,web-card-model,image-card-model}.ts`

**ui-sidebar** — `SidebarRoot.tsx`, `HeaderLeadingControls.tsx`, `locales.ts`

**ui-workspace** — `rows/{Rows,AnimatedRows,WorkspaceBrowser}.tsx`, `WorkspacePicker.tsx`, `navigation.ts`, `pin-order.ts`, `stores.ts`, `session-actions/{ArchiveSession,ForkSession,PinSession,RenameSession,RowActionToast}.tsx`, `toast/{Toast,register}.tsx`

**ui-sidebar-right** — `shell/{SidebarRight,RightbarRoot,ExpandButton,close-focus}.tsx`, `tabs/guide/{GuideBody,GuideTitle,GuideMoreIcon,definition}.tsx`, `tab-domain.ts`, `tab-registry.ts`, `tab-inventory.ts`, `service.ts`, `persistence.ts`, `focus.ts`

**ui-settings** — `schema.ts`, `config-form.ts`, `settings-mirror.ts`, `developer-tools.ts`

**ui-settings-general** — `SettingsRoot.tsx`, `GeneralSection.tsx`, `chrome.tsx`, `settings-document-store.ts`, `SettingsDocumentAction.tsx`, `DeveloperToolsRow.tsx`, `HostReleasePrompt.tsx`

**ui-theme** — `AppearanceRow.tsx`, `FontSettingsGroup.tsx`, `FontFamilyRow.tsx`, `FontSizeRow.tsx`, `settings-store.ts`, `styles.ts`, `styles/{base,corner-shape,design-platform,focus,onboarding,scrollbar,gradient-shadow-text,shiki}.css`

**client-connection** — `lib/client.browser.ts`, `lib/index.ts` (metade node com gate de auth)

**api-gateway** — `lib/client.browser.ts`, `lib/client.node.ts`, `lib/index.ts`

---

## Apêndice B — pacote de UI → pasta no repositório → bundle executado

| Pacote (id do plugin) | Fonte no repo | Bundle executado |
|---|---|---|
| `dsh-api-gateway` | `packages/api/gateway` | `./dsh-api-gateway/lib/client.browser.js` |
| `dsh-api-job-controller` | `packages/api/job-controller` | `./dsh-api-job-controller/lib/client.js` |
| `dsh-api-remote-access` | `packages/api/remote-access` | `./dsh-api-remote-access/lib/client.js` |
| `dsh-api-remotes` | `packages/api/remotes` | `./dsh-api-remotes/lib/client.js` |
| `dsh-api-session-controller` | `packages/api/session-controller` | `./dsh-api-session-controller/lib/client.js` |
| `dsh-api-terminal-controller` | `packages/api/terminal-controller` | `./dsh-api-terminal-controller/lib/client.js` |
| `dsh-api-workspace-controller` | `packages/api/workspace-controller` | `./dsh-api-workspace-controller/lib/client.js` |
| `dsh-api-workspace-files` | `packages/api/workspace-files` | `./dsh-api-workspace-files/lib/client.js` |
| `dsh-client-connection` | `packages/client/connection` | `./dsh-client-connection/lib/client.browser.js` |
| `dsh-client-file-upload` | `packages/client/file-upload` | `./dsh-client-file-upload/lib/client.js` |
| `dsh-client-hmr` | `packages/client/hmr` | `./dsh-client-hmr/lib/client.js` |
| `dsh-client-locale` | `packages/client/locale` | `./dsh-client-locale/lib/client.js` |
| `dsh-client-modules` | `packages/client/modules` | `./dsh-client-modules/lib/client.js` |
| `dsh-client-product-analytics` | `packages/client/product-analytics` | `./dsh-client-product-analytics/lib/client.js` |
| `dsh-client-resources` | `packages/client/resources` | `./dsh-client-resources/lib/client.js` |
| `dsh-client-shortcuts` | `packages/client/shortcuts` | `./dsh-client-shortcuts/lib/client.js` |
| `dsh-client-ui-agent-preset` | `packages/client/ui-agent-preset` | `./dsh-client-ui-agent-preset/lib/client.js` |
| `dsh-client-ui-approval` | `packages/client/ui-approval` | `./dsh-client-ui-approval/lib/client.js` |
| `dsh-client-ui-attachment` | `packages/client/ui-attachment` | `./dsh-client-ui-attachment/lib/client.js` |
| `dsh-client-ui-brand-official` | `packages/client/ui-brand-official` | `./dsh-client-ui-brand-official/lib/client.js` |
| `dsh-client-ui-chat` | `packages/client/ui-chat` | `./dsh-client-ui-chat/lib/client.js` |
| `dsh-client-ui-commands` | `packages/client/ui-commands` | `./dsh-client-ui-commands/lib/client.js` |
| `dsh-client-ui-conversation` | `packages/client/ui-conversation` | `./dsh-client-ui-conversation/lib/client.js` |
| `dsh-client-ui-cordis` | `packages/extensions/ui-cordis` | `./dsh-client-ui-cordis/lib/client.js` |
| `dsh-client-ui-deepseek-model-label-1017` | `packages/client/ui-deepseek-model-label-1017` | `./dsh-client-ui-deepseek-model-label-1017/lib/client.js` |
| `dsh-client-ui-deliverables` | `packages/client/ui-deliverables` | `./dsh-client-ui-deliverables/lib/client.js` |
| `dsh-client-ui-directory-picker-browse` | `packages/client/ui-directory-picker-browse` | `./dsh-client-ui-directory-picker-browse/lib/client.js` |
| `dsh-client-ui-directory-picker-native` | `packages/client/ui-directory-picker-native` | `./dsh-client-ui-directory-picker-native/lib/client.js` |
| `dsh-client-ui-entry-customization` | `packages/client/ui-entry-customization` | `./dsh-client-ui-entry-customization/lib/client.js` |
| `dsh-client-ui-goal` | `packages/client/ui-goal` | `./dsh-client-ui-goal/lib/client.js` |
| `dsh-client-ui-input-trigger` | `packages/client/ui-input-trigger` | `./dsh-client-ui-input-trigger/lib/client.js` |
| `dsh-client-ui-jobs` | `packages/client/ui-jobs` | `./dsh-client-ui-jobs/lib/client.js` |
| `dsh-client-ui-layout` | `packages/client/ui-layout` | `./dsh-client-ui-layout/lib/client.js` |
| `dsh-client-ui-message-feedback` | `packages/client/ui-message-feedback` | `./dsh-client-ui-message-feedback/lib/client.js` |
| `dsh-client-ui-model-selection` | `packages/client/ui-model-selection` | `./dsh-client-ui-model-selection/lib/client.js` |
| `dsh-client-ui-open-in-app` | `packages/client/ui-open-in-app` | `./dsh-client-ui-open-in-app/lib/client.js` |
| `dsh-client-ui-permission-presets` | `packages/client/ui-permission-presets` | `./dsh-client-ui-permission-presets/lib/client.js` |
| `dsh-client-ui-plan` | `packages/client/ui-plan` | `./dsh-client-ui-plan/lib/client.js` |
| `dsh-client-ui-plugin-manager` | `packages/client/ui-plugin-manager` | `./dsh-client-ui-plugin-manager/lib/client.js` |
| `dsh-client-ui-plugin-market` | `packages/client/ui-plugin-market` | `./dsh-client-ui-plugin-market/lib/client.js` |
| `dsh-client-ui-reference` | `packages/client/ui-reference` | `./dsh-client-ui-reference/lib/client.js` |
| `dsh-client-ui-renderer` | `packages/client/ui-renderer` | `./dsh-client-ui-renderer/lib/client.js` |
| `dsh-client-ui-schedule` | `packages/client/ui-schedule` | `./dsh-client-ui-schedule/lib/client.js` |
| `dsh-client-ui-secret-mask` | `packages/client/ui-secret-mask` | `./dsh-client-ui-secret-mask/lib/client.js` |
| `dsh-client-ui-session` | `packages/client/ui-session` | `./dsh-client-ui-session/lib/client.js` |
| `dsh-client-ui-settings` | `packages/client/ui-settings` | `./dsh-client-ui-settings/lib/client.js` |
| `dsh-client-ui-settings-account` | `packages/client/ui-settings-account` | `./dsh-client-ui-settings-account/lib/client.js` |
| `dsh-client-ui-settings-agent-loop` | `packages/client/ui-settings-agent-loop` | `./dsh-client-ui-settings-agent-loop/lib/client.js` |
| `dsh-client-ui-settings-general` | `packages/client/ui-settings-general` | `./dsh-client-ui-settings-general/lib/client.js` |
| `dsh-client-ui-settings-models` | `packages/client/ui-settings-models` | `./dsh-client-ui-settings-models/lib/client.js` |
| `dsh-client-ui-settings-plugin-inventory` | `packages/client/ui-settings-plugin-inventory` | `./dsh-client-ui-settings-plugin-inventory/lib/client.js` |
| `dsh-client-ui-settings-plugins` | `packages/client/ui-settings-plugins` | `./dsh-client-ui-settings-plugins/lib/client.js` |
| `dsh-client-ui-settings-session-log` | `packages/client/ui-settings-session-log` | `./dsh-client-ui-settings-session-log/lib/client.js` |
| `dsh-client-ui-settings-shell` | `packages/client/ui-settings-shell` | `./dsh-client-ui-settings-shell/lib/client.js` |
| `dsh-client-ui-settings-subagent` | `packages/client/ui-settings-subagent` | `./dsh-client-ui-settings-subagent/lib/client.js` |
| `dsh-client-ui-settings-web-search` | `packages/client/ui-settings-web-search` | `./dsh-client-ui-settings-web-search/lib/client.js` |
| `dsh-client-ui-shortcuts` | `packages/client/ui-shortcuts` | `./dsh-client-ui-shortcuts/lib/client.js` |
| `dsh-client-ui-sidebar` | `packages/client/ui-sidebar` | `./dsh-client-ui-sidebar/lib/client.js` |
| `dsh-client-ui-sidebar-browser` | `packages/client/ui-sidebar-browser` | `./dsh-client-ui-sidebar-browser/lib/client.js` |
| `dsh-client-ui-sidebar-documentpreview` | `packages/client/ui-sidebar-documentpreview` | `./dsh-client-ui-sidebar-documentpreview/lib/client.js` |
| `dsh-client-ui-sidebar-files` | `packages/client/ui-sidebar-files` | `./dsh-client-ui-sidebar-files/lib/client.js` |
| `dsh-client-ui-sidebar-right` | `packages/client/ui-sidebar-right` | `./dsh-client-ui-sidebar-right/lib/client.js` |
| `dsh-client-ui-sidebar-terminal` | `packages/client/ui-sidebar-terminal` | `./dsh-client-ui-sidebar-terminal/lib/client.js` |
| `dsh-client-ui-skill` | `packages/client/ui-skill` | `./dsh-client-ui-skill/lib/client.js` |
| `dsh-client-ui-subagent` | `packages/client/ui-subagent` | `./dsh-client-ui-subagent/lib/client.js` |
| `dsh-client-ui-theme` | `packages/client/ui-theme` | `./dsh-client-ui-theme/lib/client.js` |
| `dsh-client-ui-tool` | `packages/client/ui-tool` | `./dsh-client-ui-tool/lib/client.js` |
| `dsh-client-ui-trajectory` | `packages/client/ui-trajectory` | `./dsh-client-ui-trajectory/lib/client.js` |
| `dsh-client-ui-user-questions` | `packages/client/ui-user-questions` | `./dsh-client-ui-user-questions/lib/client.js` |
| `dsh-client-ui-workflow-run` | `packages/client/ui-workflow-run` | `./dsh-client-ui-workflow-run/lib/client.js` |
| `dsh-client-ui-workspace` | `packages/client/ui-workspace` | `./dsh-client-ui-workspace/lib/client.js` |
| `dsh-cordis-client-runner` | `packages/extensions/cordis-client-runner` | `./dsh-cordis-client-runner/lib/client.js` |
| `dsh-experimental-client-ui-agent-team` | `packages/experimental/client-ui-agent-team` | `./dsh-experimental-client-ui-agent-team/lib/client.js` |
| `dsh-experimental-client-ui-cot-translation` | `packages/experimental/client-ui-cot-translation` | `./dsh-experimental-client-ui-cot-translation/lib/client.js` |
| `dsh-experimental-client-ui-voice-input` | `packages/experimental/client-ui-voice-input` | `./dsh-experimental-client-ui-voice-input/lib/client.js` |
| `dsh-experimental-inspector` | `packages/experimental/inspector` | `./dsh-experimental-inspector/lib/client.js` |
| `dsh-experimental-session-inspector` | `packages/experimental/session-inspector` | `./dsh-experimental-session-inspector/lib/client.js` |
| `dsh-session-log-export` | `packages/session-query/session-log-export` | `./dsh-session-log-export/lib/client.js` |
| `dsh-typert-registry` | `packages/typert/registry` | `./dsh-typert-registry/lib/client.browser.js` |

---

_Fim do mapa._ Levantado em 2026-11-07 contra `@deepseek-ai/dsh@0.2.1-rc.5`. Ao atualizar o harness, reconfirme as âncoras com os comandos da §14.6 antes de editar — versões novas podem renomear pacotes, slots ou mover bundles.

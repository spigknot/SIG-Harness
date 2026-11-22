# RUNBOOK — “Não foi possível abrir o modo web”

Use quando o SIG Harness não abre a interface web. Três falhas diferentes
produzem a mesma tela; identifique pelo log antes de mexer.

## Diagnóstico em 60 segundos

No Termux (fora do `su`):

```bash
tail -80 ~/.sig-harness/bridge.log
```

Procure a linha fatal. As três assinaturas são:

```text
No usable native binding found for node-addon-require-builtin-android-arm64
```

```text
TransactionTooLargeException: data parcel size 1332368 bytes
```

```text
HTTP/1.1 431 Request Header Fields Too Large
```

Confira também as versões:

```bash
node -v
npm -v
node /data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js --version
dumpsys package com.sig.harness | grep -E "versionCode|versionName"
```

Referência da release atual: app **1.5.5 / versionCode 73**, bridge **1.5.0** e DSH **0.2.1-rc.5**. Node.js, npm e pnpm são verificados pelo bootstrap.

---

## Caso A — addon nativo ausente

**Sintoma no app:** “Não foi possível abrir o modo web / O servidor web nao
iniciou. attempts: undefined | …”.

**Linha fatal:**

```text
dsh: host preparation failed: No usable native binding found for
node-addon-require-builtin-android-arm64 (auto)
```

**Causa:** um `npm i -g @deepseek-ai/dsh` recriou `node_modules` e apagou o
`require_builtin.node` que o `apply.sh` tinha copiado. O bridge antigo (antes
do 1.3.2) só reaplicava a camada no boot, não antes do `web.start`.

**Correção manual:**

```bash
bash /storage/emulated/0/Projetos/sig-harness/dsh-web/apply.sh --auto
```

Saída esperada (trechos):

```text
aplicado      0004-node-addon-flock-android-ffi.patch
addon ok      require_builtin.node instalado
koffi ok       binário nativo instalado em .../koffi/build/koffi/android_arm64
```

**Verificação:**

```bash
D=/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh
ls -l "$D/node_modules/node-addon-require-builtin/build/napi/napi-v9-android-arm64/require_builtin.node"
ls -l "$D/node_modules/koffi/build/koffi/android_arm64/koffi.node"
```

Esperado: 245832 e 1140176 bytes.

No app 1.5.2+ o bridge roda esse `apply.sh` sozinho antes de cada
`web.start` (log `[layer] apply.sh ok`).

---

## Caso B — `TransactionTooLargeException` (~1,3 MB)

**Sintoma no app:** “Não foi possível falar com o Termux” e
`android.os.TransactionTooLargeException: data parcel size 1332368 bytes`.

**Causa:** o `dsh-web.tgz` com o koffi (1,14 MB) passava em base64 pelo
`Intent` do Termux. O Binder limita a transação a ~1 MB; strings em Parcel são
UTF-16 e dobram o tamanho.

**Correção:** atualizar para o APK **1.5.2+**. Ele grava o pacote em:

```text
/storage/emulated/0/Android/media/com.sig.harness/dsh-web.tgz
```

e envia ao Termux apenas o caminho. O `TermuxRunner` faz `cp` e extrai.

**Verificação:**

```bash
ls -l /storage/emulated/0/Android/media/com.sig.harness/dsh-web.tgz
```

Esperado: 435299 bytes (build atual). O arquivo é reescrito quando o hash do
asset muda; não apague durante o uso.

**Não use `pm clear com.sig.harness`**: isso apaga token, cwd e preferências.
O APK novo resolve sem perder dados.

---

## Caso C — HTTP 431 / `ERR_HTTP_RESPONSE_CODE_FAILURE`

**Sintoma no app:** página Chromium “Webpage not available”, URL
`http://127.0.0.1:<porta>/?token=…` e
`net::ERR_HTTP_RESPONSE_CODE_FAILURE`.

**Causa:** cada `web.start` usa uma porta aleatória, mas cookie HTTP não é
isolado por porta. O WebView acumulava um cookie `dsh-auth-<hash(host:porta)>`
por porta; com ~70 cookies o header `Cookie` passava de 16 KB, o Node
respondia `431 Request Header Fields Too Large`, e o WebView mostrava o erro
como falha de resposta HTTP.

**Correção:** o `WebScreen.load()` limpa os cookies do host local antes de
carregar a URL nova. Desde a versão 1.5.7, expira somente `dsh-auth-*` em
`127.0.0.1`, preservando as sessões das abas remotas. Se o app estiver preso
na página antiga, force a parada e abra de novo:

```bash
su -c 'am force-stop --user 0 com.sig.harness'
```

**Verificação opcional (com root):** o jar do WebView fica em
`/data/data/com.sig.harness/app_webview/Default/Cookies` (SQLite, tabela
`cookies`). O esperado é **1** cookie `dsh-auth-*` (ou 0 logo após abrir);
dezenas indicam APK antigo.

Se estiver depurando a rede, a sequência correta é `303 See Other` seguida de
`200 OK`; `431` confirma o header grande.

---

## Depois de qualquer `npm i -g @deepseek-ai/dsh`

Rode sempre:

```bash
bash ~/.sig-harness/dsh-web/apply.sh --auto
```

O `npm i` apaga patches e o addon. O bridge 1.5.0 repete isso antes do
`web.start`, mas o comando manual é o fallback quando o app está em versão
antiga.

## Testes rápidos

Teste E2E do host web (não abre o app):

```bash
cd /storage/emulated/0/Projetos/sig-harness
TOKEN=$(tr '\0' ' ' < /proc/$(grep -o '"pid":[0-9]*' ~/.sig-harness/bridge.json | cut -d: -f2)/cmdline | sed -n 's/.*--token \([^ ]*\).*/\1/p')
PORT=$(grep -o '"port":[0-9]*' ~/.sig-harness/bridge.json | cut -d: -f2)
node tools/tests/web-start.mjs "$TOKEN" "$PORT"
```

Esperado: `WEB {"owned":true,...}` e `WEB STOPPED`.

Teste da camada nativa (usa DSH falso temporário):

```bash
bash tools/tests/native-layer.test.sh
```

Esperado: `ok: addon e koffi restaurados`.

## Pegadinha ao rodar `apply.sh` fora do Termux

Se o script for executado via `su`/`setpriv` com o `TMPDIR` do Hermes, o
`patch` não consegue criar o arquivo temporário e o `apply.sh` imprime
`não aplica ... (base mudou: rode capture.sh)`. Não é base mudada: é
permissão. Rode no Termux normal ou exporte:

```bash
export TMPDIR=/data/data/com.termux/files/usr/tmp
```

## Versões e arquivos de referência

- App: `app/AndroidManifest.xml` (versionCode/versionName)
- Bridge: `bridge/sig-bridge.mjs` (`version: '1.5.0'` no `welcome()`)
- Camada: `dsh-web/apply.sh`, `dsh-web/artifacts/`
- Logs: `~/.sig-harness/bridge.log`, `provision.log`, `bootstrap.log`
- Payload em mídia: `/storage/emulated/0/Android/media/com.sig.harness/dsh-web.tgz`

## Fluxo a partir da versão 1.4.0

Em uma instalação nova, espere **Criando o perfil Web no DeepSeek Harness** e **Reparando o
perfil e os plugins**. A primeira etapa usa `dsh --profile web --dump-default-config` para
criar a base em segundo plano. A segunda instala o bundle móvel fixado, registra os plugins
e completa os ajustes do SIG. Somente depois a interface é carregada com autenticação local.

Em uma instalação existente, **Reparar instalação** verifica os mesmos itens e reinicia a ponte.
O log da etapa fica em `~/.sig-harness/bootstrap.log`. Em um ambiente de teste isolado,
retire um binário Android e um plugin da instalação, abra o app e confira que ambos foram
restaurados. Em uma segunda abertura com a instalação íntegra, a ponte pode ser reutilizada.


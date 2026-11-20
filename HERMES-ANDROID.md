# Hermes Agent Android — dashboard, boot e correções

> Documento de referência do **Hermes Agent Android** (`com.hermesagent.android`), mantido neste
> repositório por ser o mesmo aparelho/Termux. Não é o SIG Harness: o SIG usa o DSH.

Atualizado em 2026-11-19 (America/Sao_Paulo).

Status: confirmado no Android em 2026-11-19 22:05 (Chrome Android).

Escopo: app com.hermesagent.android, dashboard em 0.0.0.0:8080, gateway Hermes e a stack root/app
que os mantém de pé.

---

## 1. Sintomas que voltaram

1. "waiting for provider" para qualquer provedor.
2. "profile mismatch" ao tentar retomar a sessão "Hermes Dashboard".
3. Não dá para digitar no chat pelo Chrome do Android (no PC funciona).

Os três voltaram depois que o próprio Hermes Agent reescreveu o launcher de boot e depois que a
stack foi reiniciada fora do namespace do app.

---

## 2. Causas raiz confirmadas

### 2.1 DNS / "waiting for provider"

O boot chamava:

    su 10490 -c "$LAUNCHER"

Esse su não preserva os grupos suplementares. O processo subia com UID 10490 mas sem o grupo
3003 (inet). Nesse estado, o resolvedor do Android devolve EAI_NODATA para qualquer hostname,
embora sockets TCP/IP diretos continuem funcionando.

Sintoma nos logs:

    httpx.ConnectError: [Errno 7] No address associated with hostname
    openai.APIConnectionError: Connection error.

A atividade exibida pela UI vem de:

    self.agent._touch_activity("waiting for provider response (streaming)")

Confirmação: o processo do gateway tinha Groups: vazio; o processo normal do app tinha
Groups: 3003 9997 20490 50490 99809997 99909997. Rodando o Python do app como UID 10490 sem o
grupo, getaddrinfo falhava para todos os hosts; com -G 3003, funcionava.

### 2.2 "profile mismatch"

O Hermes usa o caminho do HERMES_HOME como profile_id (e como parte do hash do ID da sessão).
Dentro do namespace do app, o mesmo diretório aparece como:

    /data/data/com.hermesagent.android/files/home/.hermes
    /data/user/0/com.hermesagent.android/files/home/.hermes

A sessão antiga foi criada com o primeiro caminho; a stack nova usava o segundo. O receipt da sessão
(gateway.local_policy.v1:<sid> em state_meta) e as credenciais congeladas ficavam presos ao
caminho antigo.

Correções aplicadas:

- gateway/session_local_recovery.py: aceita os dois caminhos quando são o mesmo diretório físico
  (Path.samefile).
- patches/monkey_patch.py: normaliza Path.resolve() de /data/data/com.hermesagent.android para
  /data/user/0/com.hermesagent.android.
- config_secret_ref de 5 sessões antigas foi re-vinculado ao home atual.
- O gateway passou a registrar hermes_home: /data/user/0/... no gateway.lock.

### 2.3 Input do Android (screenReaderMode do xterm)

O chat usa xterm.js 6.x. O ChatPage criava o terminal com `screenReaderMode: true`.
No xterm 6.x, `CoreBrowserTerminal._inputEvent` descarta todo evento `input` com
`inputType === "insertText"` quando `screenReaderMode` está ligado:

    if (e.data && e.inputType === 'insertText' &&
        (e.composed || !this._keyDownSeen) &&
        !this.optionsService.rawOptions.screenReaderMode) { ...triggerDataEvent... }

Teclados virtuais do Android não emitem `keydown` para letras: o texto chega como
`insertText`. Com `screenReaderMode: true`, o terminal abre o teclado e não recebe
nada. Teclado físico, PC e iOS funcionam porque geram `keydown`.

A correção é desligar `screenReaderMode` só no Android:

    screenReaderMode: !/Android/i.test(navigator.userAgent)

Nesta instalação Android **não existe web/src** (é build empacotado). O patch foi
aplicado no bundle compilado: `ChatPage-...-imefix.js` e `index-...-imefix.js`,
com o `index.html` apontando para o main novo. O guard reaplica isso via
`ensure_ime_bundle.py`, chamado pelo `apply.sh`, a cada 60s e no boot.

### 2.4 Modelo inválido no command-code

O endpoint rejeitava o ID sem namespace:

    HTTP 400: Model "deepseek-v4.1-flash" is not supported on this endpoint.

O ID aceito é:

    deepseek/deepseek-v4.1-flash

A config foi ajustada em model.default e custom_providers[0].model.

---

## 3. Arquitetura atual

    /data/adb/service.d/00-hermes-guard.sh       (root, imutável)
      -> reaplica os patches antes do 99 e a cada 60s

    /data/adb/service.d/99-hermes-dashboard.sh   (root)
      -> reaplica patches
      -> espera boot/app
      -> nsenter -t <app_pid> -m -- su -g 10490 -G 1023 -G 3003 ... 10490 -c launcher

    ~/.hermes/scripts/boot-hermes-dashboard.sh
      -> valida grupo 3003
      -> inicia dashboard-supervisor.sh

    ~/.hermes/scripts/dashboard-supervisor.sh
      -> ensure-gateway.sh
      -> ensure-dashboard.sh
      -> repete a cada 30s

    ensure-gateway.sh
      -> gateway run --quiet (persistente, sem --idle-exit)

    ensure-dashboard.sh
      -> hermes dashboard --host 0.0.0.0 --port 8080 --no-open --skip-build

Todos os processos do app (supervisor, dashboard, gateway, TUI) devem rodar com:

- UID/GID 10490;
- grupos 1023 3003 9997 20490 50490 99809997 99909997;
- o mesmo mount namespace do processo principal do app
  (readlink /proc/<pid>/ns/mnt igual ao do com.hermesagent.android);
- /data/data/com.termux oculto nesse namespace.

---

## 4. Camada de auto-reparo

### 4.1 Arquivos canônicos

    /data/adb/hermes-android-fixes/canonical/
      99-hermes-dashboard.sh
      boot-hermes-dashboard.sh
      dashboard-supervisor.sh
      ensure-dashboard.sh
      ensure-gateway.sh
      apply_fixes.py
      apply.sh
      00-hermes-guard.sh

Os arquivos canônicos e o guard ativo estão com chattr +i (imutáveis).

### 4.2 O que o apply faz

/data/adb/hermes-android-fixes/canonical/apply.sh chama apply_fixes.py como root e:

- restaura o 99-hermes-dashboard.sh se ele perder -G 3003, nsenter ou o hook de apply;
- move qualquer 99-hermes-dashboard.sh.bak* de service.d para
  /data/adb/hermes-dashboard-backups/;
- restaura os scripts do app a partir do canônico;
- reaplica a normalização de caminho no monkey_patch.py;
- reaplica o alias de profile no session_local_recovery.py;
- reaplica o patch do xterm screenReaderMode no bundle compilado, via ensure_ime_bundle.py.

Códigos de retorno:

| Código | Significado |
|---:|---|
| 0 | tudo já estava certo |
| 10 | o script de boot foi restaurado; o guard reinicia o loop 99 |
| 20 | código/scripts do app mudaram; o guard reinicia dashboard/gateway/supervisor |
| 1 | erro; ver status.json e o log |

### 4.3 O guard

00-hermes-guard.sh:

- roda uma vez no boot, antes do 99;
- mantém um loop a cada 60s;
- se o apply retornar 10, reinicia o loop 99;
- se retornar 20, mata dashboard/gateway/supervisor para que o 99 os recrie com o código
  corrigido;
- se o loop 99 sumir, inicia de novo.

O 99 também tem um apply no topo, antes de lançar a stack, para o caso de a ordem do
service.d mudar.

### 4.4 Logs e status

    /data/adb/hermes-android-fixes/status.json
    /data/adb/hermes-dashboard-boot.log
    ~/.hermes/logs/dashboard-supervisor.log
    ~/.hermes/logs/dashboard.log
    ~/.hermes/logs/gateway.log
    ~/.hermes/logs/errors.log

status.json deve terminar com:

    {"errors": [], "source_changed": false, "dashboard_script_changed": false}

---

## 5. Verificação rápida

    # Processos e grupos
    su -c 'ps -A -o pid,ppid,user,args | grep -E "00-hermes-guard|99-hermes-dashboard|dashboard-supervisor|dashboard --host|hermes_cli.main.*gateway" | grep -v grep'

    # Namespace: os PIDs do dashboard/gateway devem ter o mesmo mnt do app
    APP_PID=$(pidof com.hermesagent.android | awk '{print $1}')
    readlink /proc/$APP_PID/ns/mnt
    for p in $(pgrep -f 'dashboard --host|hermes_cli.main.*gateway'); do echo -n "$p "; readlink /proc/$p/ns/mnt; done

    # Grupos: todos precisam de 3003
    for p in $(pgrep -f 'dashboard --host|dashboard-supervisor|hermes_cli.main.*gateway'); do
      echo "== $p"; grep -E '^(Uid|Groups)' /proc/$p/status
    done

    # Porta e home do gateway
    ss -ltnp 2>/dev/null | grep 8080
    cat ~/.hermes/gateway.lock

    # Patches
    cat /data/adb/hermes-android-fixes/status.json

O gateway.lock deve conter "hermes_home": "/data/user/0/com.hermesagent.android/files/home/.hermes".

Teste de retomada (troque <sid> pelo ID da sessão):

    su -g 10490 -G 1023 -G 3003 -G 9997 -G 20490 -G 50490 -G 99809997 -G 99909997 10490 -c '
      export HOME=/data/user/0/com.hermesagent.android/files/home
      export PREFIX=/data/user/0/com.hermesagent.android/files/usr
      export PATH=$HOME/.hermes-android/bin:$PREFIX/bin:/system/bin
      export LD_LIBRARY_PATH=$PREFIX/lib
      export LD_PRELOAD=$PREFIX/lib/libtermux-exec.so
      export HERMES_HOME=$HOME/.hermes
      export SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem
      export OPENSSL_CONF=$PREFIX/etc/tls/openssl.cnf
      cd $HOME
      ./.hermes-android/bin/hermes -z "Teste de retomada. Responda apenas OK." --resume <sid> --cli
    '

---

## 6. Manutenção intencional

Não edite os arquivos ativos diretamente. O canônico é a fonte da verdade.

    chattr -i /data/adb/service.d/00-hermes-guard.sh
    chattr -i /data/adb/hermes-android-fixes/canonical/*

    # edite o(s) arquivo(s) em .../canonical/

    chattr +i /data/adb/service.d/00-hermes-guard.sh
    chattr +i /data/adb/hermes-android-fixes/canonical/*
    sh /data/adb/hermes-android-fixes/canonical/apply.sh

Se o apply retornar 10/20, ele já sinalizou ao guard o que reiniciar.

---

## 7. Limitações e avisos

- Não existe garantia absoluta contra root. Quem rodar chattr -i e apagar o guard pode desfazer
  tudo. A camada cobre edições acidentais, atualizações, rebuilds e edições do agente.
- Cache do Chrome: o guard garante o patch no servidor; o navegador precisa recarregar o HTML.
  Use http://127.0.0.1:8080/?v=20261120b ou uma aba anônima.
- Se o teclado nem abrir no Android (sintoma diferente), aí é o bug #143761: o Chromium recusa
  anexar o IME ao textarea do xterm (`opacity:0; z-index:-5`). Nesse caso, e só nesse, aplicar
  CSS restrito a Android: `.xterm .xterm-helper-textarea { opacity:0.01; z-index:0; }`.
- Um viewer por terminal: se o PC estiver com a mesma sessão aberta, o Android pode ficar como
  viewer sem input. Feche/recarregue a aba do PC.
- Atualização do app Hermes: se o upstream mudar os marcadores usados pelo apply, o status.json
  vai mostrar errors; aí o patch precisa ser reescrito.
- gateway status pode dizer "not running" por causa do wrapper python3 -c ...; isso é um falso
  negativo do matcher de PID, não necessariamente o gateway caído.
- Sessões de teste: alguns turnos "Teste ... OK" foram adicionados às sessões durante a
  validação; podem ser ignorados/apagados.

---

## 8. Backups

    /data/adb/hermes-dashboard-backups/
      state-20261119-203436/            # state.db + WAL antes do rebind
      session_local_recovery.py.bak-*   # antes do patch de profile
      monkey_patch.py.bak-*             # antes da normalização de caminho
      99-hermes-dashboard.sh.bak-*      # versões antigas do launcher
      index.html.bak-*                  # antes do script Android
      index.html.selftest               # teste do auto-reparo

Backups de config:

    ~/.hermes/backups/config/config.yaml.good.*
    ~/.hermes/backups/config/config.yaml.before-model-fix-*

---

## 9. Pendência de segurança

Durante o diagnóstico, um diff da config.yaml imprimiu a API key do provider command-code.
Recomenda-se rotacionar essa chave. Nenhum valor de chave deve ser gravado neste documento.

## 10. Pasta padrão dos projetos (2026-11-20)

O Hermes Agent foi configurado para usar a mesma pasta de projetos do restante do aparelho:

```yaml
terminal:
  cwd: /data/media/0/Projetos
desktop:
  repo_scan_enabled: true
  repo_scan_roots:
    - /data/media/0/Projetos
```

`/data/media/0/Projetos` é o **mesmo diretório físico** que
`/storage/emulated/0/Projetos`; muda só o caminho usado por dentro do Android. O app Hermes não
tem `MANAGE_EXTERNAL_STORAGE`, então o FUSE de `/storage/emulated/0` recusa escrita para o UID
dele. O caminho `/data/media/0/Projetos` é acessível com o grupo suplementar **1023 (media_rw)**.

Por isso, todos os processos do agente (supervisor, dashboard, gateway e TUI) precisam rodar com
`-G 1023`:

```text
su -g 10490 -G 1023 -G 3003 -G 9997 -G 20490 -G 50490 -G 99809997 -G 99909997 10490 ...
```

Os scripts de boot/guarda foram atualizados para exigir os grupos 3003 e 1023. O guard
`00-hermes-guard.sh` restaura os scripts canônicos; se um update os trocar, a checagem falha e o
log mostra `missing media_rw group 1023` em vez de subir um agente sem acesso à pasta.

Efeitos:

- **chats novos** começam em `/data/media/0/Projetos` (via `terminal.cwd`);
- o sidebar **Projects** varre essa pasta (via `desktop.repo_scan_roots`);
- sessões antigas continuam no cwd em que foram criadas; para usar a pasta nova, abra um chat novo
  ou escolha a pasta no seletor de workspace;
- a interface pode mostrar `/data/media/0/Projetos`; é a mesma pasta que
  `/storage/emulated/0/Projetos` no gerenciador de arquivos/PC.

Verificação:

```sh
su -g 10490 -G 1023 -G 3003 10490 -c 'id -G; test -w /data/media/0/Projetos && echo WRITE_OK'
for p in $(pgrep -f 'dashboard --host|hermes_cli.main.*gateway|dashboard-supervisor'); do
  echo "== $p"; grep -E '^(Uid|Groups)' /proc/$p/status
done
```

O `Groups` dos três processos precisa conter `1023` e `3003`. O `terminal.cwd` também pode ser
conferido com `hermes config get terminal.cwd` (deve imprimir `/data/media/0/Projetos`).

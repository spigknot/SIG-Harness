# SIG Harness

O SIG Harness é o app Android que instala, verifica e abre o DeepSeek Harness (DSH) no Termux. Ele carrega a interface web do DSH dentro do próprio app, autentica a sessão automaticamente e aplica as personalizações do SIG.

A versão atual é **1.5.2**. [Baixe o APK na página de releases](https://github.com/spigknot/SIG-Harness/releases/latest).

## Primeira configuração

1. Instale o Termux por uma fonte compatível, como [F-Droid](https://f-droid.org/packages/com.termux/) ou o [repositório oficial](https://github.com/termux/termux-app/releases). Abra o Termux uma vez e aguarde a configuração inicial.
2. Instale o APK do SIG Harness e abra o app.
3. Conceda a permissão **Executar comandos no Termux** quando o Android solicitar. Se o Termux bloquear comandos de outros apps, use **Copiar comando para liberar o Termux** na tela do SIG Harness, execute o comando no Termux e volte ao app.
4. Conceda ao Termux acesso aos arquivos do aparelho se o Android pedir.

## Abertura e reparo

A cada abertura, o app verifica o ambiente do Termux, Node.js, npm, pnpm, a versão fixada do DSH, os módulos nativos, os patches e o perfil web. Ele instala ou corrige os itens necessários, inicia o DSH e abre a interface pronta para uso. A tela mostra o andamento da verificação e da instalação.

O perfil web é criado antes da aplicação dos ajustes do SIG. Isso permite que a interface do DSH inicialize corretamente e receba as personalizações na ordem esperada.

O botão **Reparar instalação** fica disponível durante a configuração e em caso de falha. Depois que o chat abrir, ele fica no fim da primeira aba das configurações do DSH. O reparo verifica os componentes individualmente, reinstala o que estiver ausente ou inválido e reinicia o serviço.

Para investigar uma falha, consulte **~/.sig-harness/bootstrap.log** e **~/.sig-harness/bridge.log** no Termux. O [runbook](dsh-web/RUNBOOK.md) reúne os sintomas conhecidos e os passos de diagnóstico.

## Acesso pela rede Tailscale

Com o Tailscale conectado no OnePlus, o app libera automaticamente a porta fixa **3080**. Em um navegador conectado à mesma tailnet, abra **http://IP-Tailscale-do-OnePlus:3080**. A porta encaminha para o DSH ativo, mesmo quando a porta interna dele muda.

O acesso é controlado pelas regras da sua tailnet. Não é necessário configurar encaminhamento de portas no roteador.

## O que o APK inclui

- Bridge local entre o app e o Termux.
- Inicialização e recuperação automática do DSH.
- Interface web dentro do WebView com autenticação automática.
- Patches e plugins do SIG para compatibilidade e personalização no Android.
- Acesso remoto pela Tailscale na porta 3080.
- Ditado por voz com Meta Muse Voice ou Gemini, usando as chaves configuradas no app.

As personalizações ficam em **dsh-web/** e são empacotadas em **app/assets/dsh-web.tgz**. O app confere o hash do pacote antes de enviá-lo ao Termux.

## Requisitos

- Android 9 ou superior.
- Termux instalado e inicializado.
- Permissão do Android para o SIG Harness executar comandos no Termux.
- Para acesso pelo navegador, Tailscale conectado no OnePlus e no dispositivo cliente.

Node.js, npm, pnpm e a versão compatível do DSH são verificados e preparados pelo SIG Harness.

## Desenvolvimento

O código Android está em **app/**, a bridge está em **bridge/** e as personalizações web estão em **dsh-web/**. Para compilar no Termux:

    bash build.sh

O roteiro de validação e publicação fica em [UPDATE.md](UPDATE.md). Consulte também [dsh-web/README.md](dsh-web/README.md) para os patches e plugins.

## Dados e diagnósticos

As conversas e configurações do DSH ficam no ambiente do Termux. O SIG Harness guarda localmente as preferências do app e as chaves de voz configuradas nele. A instalação do APK por cima da versão anterior preserva os dados do aplicativo.

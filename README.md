# SIG Harness 1.3.4

Aplicativo Android **nativo** para usar o DeepSeek Harness no Termux sem abrir o Chrome e sem copiar o link de autenticação.

## Instalar

O APK tem cerca de 230 KB e **não traz o DeepSeek Harness dentro**: ele prepara o aparelho,
instala o que falta e aplica as customizações. Um pacote só, com alguns toques seus.

**Pré-requisitos (uma vez por aparelho):**

1. Instale o **Termux** pelo [F-Droid](https://f-droid.org/packages/com.termux/) ou pelo
   [GitHub](https://github.com/termux/termux-app/releases). A versão da Play Store está
   descontinuada e não funciona com este app.
2. Abra o Termux uma vez e libere comandos externos:

   ```bash
   mkdir -p ~/.termux
   echo "allow-external-apps=true" >> ~/.termux/termux.properties
   ```

   Sem isso o Termux recusa receber comandos de outros aplicativos (e o SIG Harness avisa).
3. Instale o APK do SIG Harness pela aba **Releases** deste repositório.

**Depois, no SIG Harness:**

- toque em **Iniciar Harness** e autorize o Termux quando o Android pedir;
- o app instala o que faltar (`node`, `@deepseek-ai/dsh@0.2.1-rc.5`), aplica patches, plugins
  e perfil, e abre a interface já autenticada;
- a primeira execução baixa **~700 MB** e pode levar de 5 a 15 minutos. Tenha **~1 GB livre**.

**Se o Termux recusar o comando**, toque em **Comando manual (copiar)** na tela inicial e cole
o texto no Termux — um comando resolve, e depois é só voltar e tocar em Iniciar Harness.

> Nas execuções seguintes o app só reaplica a camada `dsh-web` quando o APK muda. Nada é
> baixado de novo.
## Localização

O código deste projeto fica em `/storage/emulated/0/Projetos/sig-harness`.
`~/harness` é um symlink para essa pasta; comandos e caminhos antigos continuam funcionando.
O mapa completo da migração está em `../README-MIGRACAO.md`.

Build recomendado (o armazenamento compartilhado não executa scripts diretamente):

    bash ~/harness/build.sh


## Estrutura do repositório

| Caminho | O que é |
|---|---|
| `app/` | código Android (Java), manifest e recursos |
| `bridge/` | bridge Node que sobe o `dsh web` e autentica o WebView |
| `dsh-web/` | camada web do DSH: patches, plugin da aba Voz e profile — ver [dsh-web/README.md](dsh-web/README.md) |
| `dist/` | APK gerado localmente (fora do Git) |

As customizações da interface web **vão dentro do APK**: o `build.sh` empacota `dsh-web/` em
`app/assets/dsh-web.tgz` e o bridge reaplica patches, plugin da aba Voz e addon nativo no Termux
antes de subir o `dsh web`. Instalar um APK novo já basta — não há passo manual, nem depois de
um `npm i -g @deepseek-ai/dsh`.

O mesmo pacote serve para reaplicar à mão: `bash dsh-web/apply.sh`.

O app abre **direto na interface web do DSH**, autenticada automaticamente pelo bridge. O antigo modo nativo (ACP) foi removido: nenhum runtime ACP é iniciado. O botão de microfone acima do enviar dita o prompt usando Meta Muse Voice ou Gemini 3.5 Transcribe, com as chaves configuradas no próprio app.

## Novidades da 1.3.4

- **Provedor de transcrição no microfone**: toque normal dita; **segurar ~0,5 s** abre o menu
  de provedores no formato do seletor de modelo, com ✓ no ativo.
- **Voz dentro da aba Modelos**: a seção própria saiu das configurações; o bloco *Voz* agora
  fica no fim da aba de modelos, com os dois provedores e o botão **Chaves API**.
- **Provedor sem chave não é selecionável**: a linha aparece como “requer chave” e o toque
  abre o diálogo das chaves.
- **Instalação guiada**: o app detecta Termux ausente, permissão bloqueada e pacotes
  faltando, com passo a passo na tela inicial e comando manual como alternativa.
## Novidades da 1.3.2

- **Sem barra superior**: a faixa nativa com título, voltar, engrenagem e recarregar foi removida; a interface web ocupa a tela toda.
- **Recarregar dentro da interface**: botão circular à esquerda do rótulo do preset ("Creator mode"/"Standard mode"), no cabeçalho da conversa ou na linha do hero.
- **Ajustes de voz nas Configurações do DSH**: a engrenagem virou a aba **Voz** no menu de configurações nativo, servida pelo plugin local `dsh-web-sig-voice` no profile; o botão abre o diálogo de provedor/chaves do app.

## Novidades da 1.3.1

- **Microfone na fileira do composer**: o botão de ditado deixou de flutuar acima do enviar e agora é parte da linha de controles, entre o seletor de modelo/esforço e o botão de enviar — mesma altura e material dos outros controles, com o texto parcial ainda aparecendo num balão acima.

## Novidades da 1.3.0

- **Abre direto no modo web**: o app não inicia nem oferece mais o modo nativo/ACP. O bridge sobe o `dsh web` e a interface autenticada abre em seguida.
- **Ditado no modo web**: botão de microfone injetado logo acima do botão de enviar. Grava pelo `SttSession` nativo (Meta Muse Voice ou Gemini 3.5 Transcribe), mostra a parcial acima do botão e insere o texto final no composer.
- **Ajustes de voz** no ícone ⚙ do cabeçalho: provedor e chaves.
- Na primeira abertura após atualizar, o host web antigo é reiniciado uma vez para carregar as correções de anexos.

## Novidades da 1.2.5

- **Download de arquivos do chat**: o botão de download (e o fallback de compartilhamento) agora salva o arquivo direto em `Download/`, sem subpasta. O WebView não baixa `blob:` sozinho; a página entrega os bytes por uma ponte JavaScript para o app gravar.
- **Anexar arquivos e imagens no modo web**: implementado `onShowFileChooser`, que o WebView exige para abrir o seletor do sistema. O atalho de imagem filtra `image/*`; o de anexo abre o seletor completo, com múltipla seleção.

## Novidades da 1.2.4

- **Download direto em Downloads**: no modo web, qualquer download do WebView (inclusive **+ → Export**) agora é salvo direto em `Download/`, sem subpasta. O nome vem do `Content-Disposition` (ex.: `dsh-session-<id>.zip`), com sufixo ` (1)`, ` (2)`… se já existir. O cookie de autenticação do DSH é reaproveitado na requisição.

## Novidades da 1.2.3

- **Espaçamento do clipe**: 4 dp entre o clipe e a caixa de texto, padronizando com a distância do microfone.

## Novidades da 1.2.2

- **Botão de enviar 30% menor**: 54 dp → 38 dp, com ícone e raio proporcionais.

## Novidades da 1.2.1

- **Compositor mais limpo**: a legenda dos ícones saiu; clipe, microfone e ampulheta ficaram menores, com o microfone empilhado sobre a ampulheta.
- **Arquivar e apagar conversas**: na lista, use **⋮** (Arquivar/Apagar), **arraste para a esquerda** para apagar ou **para a direita** para arquivar, sempre com confirmação. Há uma seção **Arquivadas** com **Desarquivar** e **Apagar**. A remoção é da lista do app; o histórico no DSH/Termux não é apagado.

## Novidades da 1.2.0

- **Supergrupo de ferramentas**: ao terminar cada tarefa, as chamadas de ferramentas daquele turno são recolhidas num único item **“⚙ Ferramentas · N chamadas”** no fim da conversa. Ele abre a lista de tarefas; cada tarefa abre as chamadas; cada chamada abre os detalhes. Tudo começa colapsado, enxugando a tela.

## Novidades da 1.1.9

- **Correção do falso “provedor encerrou”**: o `SoTimeout` de 500 ms era tratado como EOF. Agora timeout de leitura continua aguardando; só um EOF real encerra. O Gemini pode ficar em silêncio depois do setup/áudio sem derrubar a sessão.

## Novidades da 1.1.8

- **Correção do Gemini**: o `setupComplete` e os eventos do Gemini chegam em frames WebSocket **binários**; o app agora decodifica opcode 1 (texto) e opcode 2 (binário) como JSON UTF-8. Antes o setup era ignorado e a conexão caía.

## Novidades da 1.1.7

- **Correção do ditado**: o Android não tem handler para `wss://` em `java.net.URL`; o app agora parseia a URL com `URI` e abre o TLS manualmente. O erro “unknown protocol: wss” não deve mais aparecer.

## Novidades da 1.1.6

- **Ditado por voz**: botão 🎤 no compositor. O áudio é capturado em PCM 16 kHz e transcrito ao vivo por **Meta Muse Voice** ou **Gemini 3.5 Transcribe**; o texto final entra no campo de mensagem.
- **Ajustes → Transcrição de voz**: escolha do modelo e campos para as API keys (Muse e Google AI Studio). As chaves ficam só nas preferências locais do app e vão direto ao provedor, sem passar pelo bridge.
- Permissão de microfone solicitada na primeira utilização.

## Novidades da 1.1.5

- **Botão único de enviar/parar**: livre, mostra **↑** e envia; trabalhando, vira **■** vermelho e para.
- **Botão menor de fila (⏳)**: aparece enquanto o agente trabalha e enfileira a mensagem.
- **Enviar agora da fila**: ao expandir a fila, cada mensagem tem **Enviar**, que interrompe o trabalho atual e envia aquele item na hora; os demais continuam na fila.
- **Cursor estável**: as atualizações de raciocínio não roubam mais o foco do campo de texto.

## Novidades da 1.1.4

- **Fila expansível**: toque na barra “N mensagens na fila” para mostrar o texto das mensagens; toque de novo para recolher. O **⌄/⌃** indica o estado.

## Novidades da 1.1.3

- **Envio e fila separados**: o botão principal envia; um botão menor enfileira. (O gesto de segurar foi substituído na 1.1.5.)

## Novidades da 1.1.2

- **Anexar arquivos**: clipe 📎 no compositor; o app lê o arquivo escolhido, envia ao bridge e ele fica em `~/.sig-harness/attachments`. O prompt usa `resource_link` do ACP, então o agente pode abrir o arquivo pelo caminho no Termux. Limite de 20 MB por arquivo.
- **Fila de mensagens**: o botão ⏳ coloca a mensagem na fila enquanto o agente responde; a fila mostra “Na fila”, envia sozinha ao terminar e pode ser limpa em “Limpar”. Fica no bridge e é recuperada ao reabrir a conversa.

## Novidades da 1.1.1

- **Renomear conversas**: lápis no cartão da conversa (ou toque longo) e menu ⋮ do chat. O nome fica salvo no aparelho, em `~/.sig-harness/titles.json`.
- **Abrir no fim da conversa**: ao entrar em uma conversa, o chat rola para a última mensagem, mesmo com histórico longo e teclado aberto.
- **Texto selecionável e copiar**: respostas e prompts podem ser selecionados; cada resposta tem **Copiar** no canto superior direito.

## Novidades da 1.1

- **Seletor de modelo e raciocínio**: menu ⋮ do chat → **Modelo e raciocínio**. A escolha vale para a conversa e para as próximas; os modelos disponíveis vêm do catálogo do DSH (DeepSeek, opencode-go etc.).
- **Modo web completo** com autenticação automática: Ajustes → **Abrir modo web completo**. O app anexa ao `dsh web` já em execução (por exemplo, o da porta 3080) ou inicia um servidor temporário, carrega o link com token e autentica sozinho. Ao voltar, o runtime nativo é reiniciado.
- **Início no boot**: Ajustes → **Iniciar o harness no boot**. O app cria `~/.termux/boot/sig-harness.sh`; com o app Termux:Boot, o bridge e o runtime sobem sozinhos quando o Android liga.
- **Pasta padrão de projetos**: `/storage/emulated/0/Projetos` (migração automática para quem usava a home).
- **Conversas da web marcadas com WEB**: sessões criadas na interface web usam o preset PTC e não podem ser executadas pelo perfil ACP nativo. O app agora mostra o selo WEB e oferece **Abrir modo web** ou **Nova conversa**, em vez de falhar com “all tools deferred”.
- **Recuperação de travamentos**: o chat mostra o tempo de “pensando”, tem **Parar** clicável, aviso após 15 min sem eventos e **Reiniciar runtime** nos erros e em Ajustes.

## Requisitos

- Android 9 ou superior (minSdk 28).
- Termux (F-Droid ou GitHub) com `allow-external-apps=true`.
- DeepSeek Harness instalado no Termux: `npm i -g @deepseek-ai/dsh`.
- Credenciais do provedor no DSH (as mesmas do perfil web).
- Para o boot: app **Termux:Boot**, aberto uma vez.

## Instalação e uso

1. Instale `dist/SIG-Harness.apk`.
2. Abra **SIG Harness** e toque em **Iniciar Harness**.
3. Na primeira vez, permita “Executar comandos no Termux”.
4. Depois do runtime pronto, crie uma conversa nova; a pasta padrão é Projetos.
5. Para trocar o modelo: ⋮ → Modelo e raciocínio.
6. Para ditar: toque em **🎤** (o botão fica vermelho enquanto ouve), fale e toque de novo para finalizar. O texto entra no campo; depois envie ou enfileire. Configure modelo/chaves em Ajustes → Transcrição de voz.
7. Para anexar: toque em **📎**, escolha um ou mais arquivos e envie. Livre, **↑** envia; trabalhando, **■** para e **⏳** enfileira. Toque na barra da fila para ver as mensagens e use **Enviar** em uma delas para interromper e enviá-la na hora.
8. Para renomear uma conversa: toque no lápis **✎** do cartão (ou toque longo nele) ou use ⋮ → **Renomear conversa** dentro do chat.
9. Para uma conversa marcada **WEB**: toque nela → **Abrir modo web**. O app abre a interface web autenticada e você continua lá.
10. Para o boot: Ajustes → Iniciar o harness no boot (ativado por padrão). O script fica em `~/.termux/boot/sig-harness.sh`.

Não é necessário abrir o Termux, rodar `dsh web` nem copiar URLs para usar o modo nativo. O botão Iniciar Harness autentica o app no bridge local e sobe o runtime.

## Como funciona

    App Android (Java)
        │  TCP 127.0.0.1:43117  (JSON por linha, token local)
        ▼
    Bridge Node  ~/.sig-harness/sig-bridge.mjs
        │  ACP (JSON-RPC por stdio)
        ▼
    dsh --profile acp --patch ~/.sig-harness/acp.patch.yml
        ▼
    sessões e credenciais em ~/.dsh

O bridge é embutido no APK e escrito no Termux a cada início do app; o patch do modelo só é criado se ainda não existir. O app escuta apenas no loopback e exige o token guardado nas preferências.

### Boot

O bridge grava, via pedido `boot.configure`, um script no diretório do Termux:Boot:

    ~/.termux/boot/sig-harness.sh

O script inicia o bridge com `--auto-start --idle-minutes 0`, então o runtime fica pronto sem o app aberto e não encerra por inatividade. Se já houver um bridge na porta, ele não duplica. O usuário pode desligar em Ajustes; o script é removido.

### Modo web

`web.start` procura um `dsh web` em execução em `~/dsh-web.log`, valida o link com token e anexa sem matar esse servidor. Se não houver, inicia um `dsh web --no-open --port 0` temporário. O app pausa o runtime ACP enquanto a web está aberta e o reinicia ao voltar. O WebView permite HTTP apenas para 127.0.0.1/localhost.

## Modelo padrão

O patch embutido usa o mesmo provedor do perfil web deste aparelho:

    provider: opencode-go
    model: deepseek-v4.1-flash
    reasoningEffort: max

A troca pela interface grava a preferência no app e aplica `session/set_config_option` na sessão. Para alterar o padrão de todas as conversas, edite `~/.sig-harness/acp.patch.yml` no Termux e reinicie o runtime; o app não sobrescreve esse arquivo se ele existir. Se apagar o arquivo, o padrão volta no próximo início.

## Sessões web (preset PTC)

As conversas criadas no `dsh web` usam o preset **ptc** e não funcionam no perfil ACP: o provedor recusa a requisição porque todas as ferramentas estariam “deferred”. Por isso o app:

- mostra o selo **WEB** na lista;
- não tenta abri-las no chat nativo;
- oferece **Abrir modo web** (autenticado) ou **Nova conversa** em Projetos.

As conversas criadas pelo app (sem selo) são nativas e continuam normalmente.

## Recuperação

- Se uma resposta demorar, o chat mostra o tempo e um **Parar** clicável.
- Após 15 min sem nenhum evento, aparece um aviso com **Interromper** e **Reiniciar runtime**.
- Qualquer erro no chat tem **Reiniciar runtime**.
- Ajustes → **Reiniciar runtime** ou **Parar harness**.
- No modo web, se a interface web travar, reinicie o `dsh web` no Termux; o app anexa ao novo link (ou inicia um servidor se não houver).

## Build

No Termux, com `aapt2`, `d8`, `apksigner`, Java e o `android.jar` (android-35) em `~/android-sdk`:

    bash ./build.sh

Saída: `dist/SIG-Harness.apk`. A keystore fica em `app/sig.jks` (senha `sigpass`); mantenha-a para atualizar por cima.

## Estrutura

    app/                 código Java, resources, assets embutidos e keystore
    bridge/              bridge Node e patch ACP de referência
    dist/SIG-Harness.apk APK assinado
    build.sh             compilação reprodutível
    README.md

## Limitações

- O modo nativo usa o perfil ACP; recursos exclusivos da UI web (planos, terminal embutido, comandos slash) ficam no modo web.
- Ao reabrir uma conversa nativa, o histórico mostra texto; chamadas de ferramentas antigas não são re-renderizadas.
- O novo nome de uma conversa vale no SIG Harness (lista e chat). Ele não altera o título mostrado na interface web do DSH.
- A fila fica na memória do bridge. Se o bridge/runtime reiniciar, mensagens ainda não enviadas são perdidas.
- As API keys de voz ficam nas preferências privadas do app (SharedPreferences, sem criptografia adicional) e são enviadas direto ao provedor; não passam pelo bridge. Use chaves com escopo/limite.
- O ditado depende da chave e da disponibilidade de cada provedor; o app não faz fallback automático entre Muse e Gemini.
- Os anexos ficam em `~/.sig-harness/attachments` e não são apagados automaticamente; o limite é 20 MB por arquivo.
- O modo web depende do `dsh web`; se ele travar, o app não o reinicia sozinho quando está anexado a um servidor do usuário.
- O boot do harness nativo exige o Termux:Boot. O app cria o script mesmo sem ele, mas avisa e oferece o F-Droid.
- Aprovações aparecem quando a política pede; o app inicia o runtime em `danger-full-access`, porque o sandbox `workspace-write` não funciona no Termux.
- A pasta Projetos fica no armazenamento compartilhado; o Termux precisa de acesso a arquivos (já configurado).

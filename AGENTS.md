# SIG Harness — regras do projeto

App Android (Java) + bridge Node + a camada `dsh-web/`, aplicada sobre o DeepSeek Harness
instalado no Termux. Leia também `dsh-web/README.md` para a mecânica de patches.

## O modelo mental

```
APK (assets/dsh-web.tgz)
  └─ abertura: bridge/bootstrap.sh valida e repara a instalação no Termux
       ├─ patches/      diffs contra os pacotes publicados do DSH
       ├─ plugin/       plugins locais do cliente web
       ├─ profile/      cordis.patch.yml (modelos, busca, desligamentos)
       └─ artifacts/    addon nativo compilado
```

**O APK fornece os patches e plugins.** O bootstrap verifica a versão do DSH, os binários
nativos e o perfil a cada abertura, inclusive quando já existe uma ponte em execução.
O DSH cria o perfil Web na primeira execução silenciosa. O bootstrap aplica o bundle
móvel e as personalizações depois dessa inicialização.

## Regras que já causaram regressão

1. **Plugin alterado exige `version` nova no `package.json`.** A URL do módulo carrega
   `&rev=`, derivado da versão do pacote, não do conteúdo. Sem bump o navegador continua
   executando o módulo antigo — sintoma: "mudei o arquivo e a tela não mudou".
2. **O APK gerencia os itens do perfil exigidos pelo SIG.** Mantenha seus padrões em
   `dsh-web/profile/` e preserve os demais campos do perfil instalado.
3. **`apply.sh --managed-core` e `profile-health.cjs` são o contrato do bootstrap.**
   Configuração obrigatória nova precisa ser conferida e aplicada no aparelho limpo.
4. **Reescrever um plugin não pode derrubar o que ele já fazia.** A reescrita da 1.5.0 removeu
   o CSS do rodapé e a engrenagem morta voltou. Antes de reescrever, liste os efeitos atuais
   (CSS, slots, ganchos nativos) e confirme que continuam existindo.
5. **Um patch por arquivo.** Dois patches sobre o mesmo arquivo não coexistem: cada um é um
   diff completo contra o pacote publicado.
6. **Slot `kind: "single"` não aceita companhia.** `settings.launcher` é `single` — registrar
   nele substitui a engrenagem. Para conviver, use um slot `kind: "list"` ou injete DOM.
7. **Não confie em seletor CSS suposto.** Confira no bundle (`grep className`) ou peça o DOM
   real: o plugin `dsh-web-sig-restart` expõe `POST /api/sig/diag` → `~/.sig-harness/ui-diag.log`.
8. **Reiniciar o host reaplica o payload do APK após as checagens.** Mudanças de plugin precisam
   de bump de versão e de um APK que inclua o arquivo atualizado.


9. **Payload grande não viaja no Intent do Termux.** O Binder limita a
   transação a ~1 MB e strings em Parcel são UTF-16. O `dsh-web.tgz` atual
   (com koffi) tem 435 KB e estouraria. O app envia um endereço temporário
   em loopback e o Termux confere o SHA-256 ao baixar. Não volte a embutir o
   tgz em base64 no `RUN_COMMAND_ARGUMENTS` nem a exigir armazenamento compartilhado.
10. **Cookie do dsh é por autoridade.** O host troca de porta a cada
    `web.start`; cookies não são isolados por porta. Expire os cookies `dsh-auth-*`
    de `127.0.0.1` antes de cada carga (`WebScreen.load`) ou o header `Cookie` passa de
    16 KB e o Node responde 431 (`ERR_HTTP_RESPONSE_CODE_FAILURE`).
    Preserve os cookies das abas remotas, que compartilham o mesmo jar.
11. **`npm i` do DSH apaga patches e addons.** O bootstrap verifica e restaura esses
    arquivos antes de entregar a ponte ao app. O reparo manual percorre as mesmas etapas.
12. **`apply.sh` fora do Termux precisa de `TMPDIR` do Termux.** Se rodar via
    `su`/`setpriv` com o `TMPDIR` do Hermes, o `patch` falha e o script diz
    “base mudou” sem ter mudado. Exporte
    `TMPDIR=/data/data/com.termux/files/usr/tmp`.

## Verificação antes de publicar

```bash
bash dsh-web/capture.sh                      # regenera patches a partir do instalado
# pristine + patches devem reproduzir os arquivos instalados, byte a byte
bash dsh-web/apply.sh --auto --dry-run       # idempotência e sintaxe
bash build.sh                                # gera dist/SIG-Harness.apk
```

Só depois: `git add -A`, commit e push. Nunca `--force`, nunca `--no-verify`.

**Toda build vai para `Download/`.** Depois de gerar `dist/SIG-Harness.apk`, copie para
`/storage/emulated/0/Download/SIG-Harness-<versionName>-v<versionCode>.apk` e confira o
sha256. O usuário instala pelo gerenciador de arquivos; não peça para ele procurar em `dist/`.

**Somente quando o usuário pedir.** O ciclo normal é: mudar, incrementar `versionCode`,
gerar `dist/SIG-Harness.apk` e deixar o usuário testar. Várias mudanças podem ser
acumuladas antes de publicar — sem tag e sem release até o pedido explícito.

**Publicar versão** é mais que commitar: o `dist/` é ignorado pelo Git, então o APK só
chega ao usuário pela **Release** do GitHub. O passo a passo está em `UPDATE.md` —
versão publicada sem release com APK anexado não conta como entregue.

## Já está decidido (não reabrir sem motivo)

- Provedor DeepSeek do login e superfície de conta: **desligados**; o manual (`deepseek-official`,
  API key) é o que fica.
- Busca por conteúdo: **só no Enter**; tecla a tecla filtra por título.
- Raciocínio: **Off detectado pelo formato do provedor**; padrão no maior nível do modelo.
- Widget de busca dentro da sessão: **DOM puro**, nunca em slot do cabeçalho (já quebrou a barra).
- Composer no celular: **Enter quebra linha**, envio **só** pelo botão. O editor é Lexical
  (`[data-composer-input]`); a quebra entra por `beforeinput insertLineBreak`.
- REA (engenharia reversa): roda **no PC da Tailscale**, nunca no Termux — o próprio
  `rea doctor` recusa `android arm64` para JADX/Ghidra. O DSH conecta por
  `streamable-http`; a URL fica em `dsh-web/rea.env` e a entrada `rea-mcp` vai para o
  patch de casa `~/.dsh/cordis.patch.yml` (`dsh-web/rea.sh`), não para o profile. Detalhes
  em `dsh-web/REA.md`.
- Busca na web: **Tavily** (`dsh-web-search-tavily`, host plugin) fixado em
  `searchProvider: tavily` na linha `web`; a chave vem do cofre/ambiente/`~/.tavily/config.json`,
  nunca do YAML.
- Rodapé da barra lateral: **⟳ vermelho à esquerda** (longe do canto, onde o polegar encosta)
  e **⚙ à direita**, com área de toque restrita ao ícone. O reiniciar sempre pede confirmação
  Sim/Não na tela.
- Anexo de imagem no WebView: **Photo Picker** (`MediaStore.ACTION_PICK_IMAGES`, Android 13+),
  nunca `ACTION_OPEN_DOCUMENT`. Arquivo genérico continua no navegador de documentos.

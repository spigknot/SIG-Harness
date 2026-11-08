# SIG Harness — regras do projeto

App Android (Java) + bridge Node + a camada `dsh-web/`, aplicada sobre o DeepSeek Harness
instalado no Termux. Leia também `dsh-web/README.md` para a mecânica de patches.

## O modelo mental

```
APK (assets/dsh-web.tgz)
  └─ boot do app: extrai em ~/.sig-harness/dsh-web e roda apply.sh --auto
       ├─ patches/      diffs contra os pacotes publicados do DSH
       ├─ plugin/       plugins locais do cliente web
       ├─ profile/      cordis.patch.yml (modelos, busca, desligamentos)
       └─ artifacts/    addon nativo compilado
```

**O APK é a fonte da verdade.** Ele reescreve o profile e o DSH a cada boot.

## Regras que já causaram regressão

1. **Plugin alterado exige `version` nova no `package.json`.** A URL do módulo carrega
   `&rev=`, derivado da versão do pacote, não do conteúdo. Sem bump o navegador continua
   executando o módulo antigo — sintoma: "mudei o arquivo e a tela não mudou".
2. **O APK sobrescreve o profile.** Todo ajuste em `~/.dsh/profiles/web` ou nos arquivos do
   DSH precisa existir em `dsh-web/`. Edição feita só no profile desaparece no próximo boot.
3. **`apply.sh --auto` é o contrato do APK.** Configuração nova (plugin, índice, `disabled`,
   perfil) só chega a um aparelho limpo se o auto souber aplicá-la.
4. **Reescrever um plugin não pode derrubar o que ele já fazia.** A reescrita da 1.5.0 removeu
   o CSS do rodapé e a engrenagem morta voltou. Antes de reescrever, liste os efeitos atuais
   (CSS, slots, ganchos nativos) e confirme que continuam existindo.
5. **Um patch por arquivo.** Dois patches sobre o mesmo arquivo não coexistem: cada um é um
   diff completo contra o pacote publicado.
6. **Slot `kind: "single"` não aceita companhia.** `settings.launcher` é `single` — registrar
   nele substitui a engrenagem. Para conviver, use um slot `kind: "list"` ou injete DOM.
7. **Não confie em seletor CSS suposto.** Confira no bundle (`grep className`) ou peça o DOM
   real: o plugin `dsh-web-sig-restart` expõe `POST /api/sig/diag` → `~/.sig-harness/ui-diag.log`.
8. **Reiniciar o host ≠ reinstalar o APK.** A bridge sobrevive ao fechar o app; o app só
   provisiona quando o hash de `dsh-web.tgz` muda. Para aplicar mudança só de plugin: bump de
   versão + botão `⟳`. Para valer em aparelho novo: APK novo.

## Verificação antes de publicar

```bash
bash dsh-web/capture.sh                      # regenera patches a partir do instalado
# pristine + patches devem reproduzir os arquivos instalados, byte a byte
bash dsh-web/apply.sh --auto --dry-run       # idempotência e sintaxe
bash build.sh                                # gera dist/SIG-Harness.apk
```

Só depois: `git add -A`, commit e push. Nunca `--force`, nunca `--no-verify`.

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
- Rodapé da barra lateral: **⟳ vermelho à esquerda** (longe do canto, onde o polegar encosta)
  e **⚙ à direita**, com área de toque restrita ao ícone. O reiniciar sempre pede confirmação
  Sim/Não na tela.
- Anexo de imagem no WebView: **Photo Picker** (`MediaStore.ACTION_PICK_IMAGES`, Android 13+),
  nunca `ACTION_OPEN_DOCUMENT`. Arquivo genérico continua no navegador de documentos.

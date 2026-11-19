# Regressões — o que já quebrou e por quê

Registro cronológico. Cada linha evita que o mesmo erro volte.

## 2026-11-08

### Payload antigo rebaixava o plugin do profile
- **Sintoma:** o CSS do rodapé sumia de novo a cada abertura, mesmo com o arquivo corrigido.
- **Causa:** o APK instalado (v23) carregava o payload 1.5.0 e o reaplicava no boot e no botão
  reiniciar, sobrescrevendo o 1.6.0 gravado no profile.
- **Correção:** `apply.sh` compara versões e mantém a instalada quando é mais nova que a do payload.
- **Regra:** mudou plugin → APK novo no mesmo ciclo. Correção só no profile é frágil.

### O APK reaplicava uma versão antiga do plugin no boot
- **Sintoma:** corrigia o plugin, reiniciava, nada mudava na tela.
- **Causa:** o APK instalado carregava `dsh-web.tgz` com a versão velha e o `apply.sh` fazia
  `rm -rf` + `cp -R` no profile a cada boot.
- **Correção:** APK novo; e o app passou a comparar o hash do payload e reiniciar o host quando muda.
- **Regra:** ajuste feito só no profile não sobrevive ao boot.

### Revisão do módulo não mudava com o conteúdo
- **Sintoma:** arquivo novo no disco, comportamento antigo no navegador.
- **Causa:** `&rev=` deriva do `package.json`, não do `client.js`.
- **Correção:** `bump` de versão a cada alteração de plugin.
- **Regra:** mudar `lib/client.js` sempre com versão nova.

### Restart matava o shell antes do `exec`
- **Sintoma:** botão matava o servidor, tela ficava branca.
- **Causa:** `pkill -f 'bin.js web'` casava com o próprio `bash -lc` que rodava o comando.
- **Correção:** `kill` pelo pid de `bridge.json` + limpeza de órfãos com padrão que não casa com o wrapper.
- **Regra:** nunca usar `pkill -f` com texto que apareça no próprio comando.

### O botão de reiniciar substituiu a engrenagem
- **Sintoma:** engrenagem parou de abrir as configurações.
- **Causa:** `settings.launcher` é `kind: "single"`; registrar nele tomou o lugar.
- **Correção:** mover para `sidebar.footer.action` (lista) e, depois, injetar o botão na linha da engrenagem.
- **Regra:** slot single não aceita companhia.

### O rótulo e a engrenagem morta voltaram (1.5.0)
- **Sintoma:** "Settings" reapareceu e a engrenagem do `dsh-web-mobile` voltou, sem ação.
- **Causa:** a reescrita do plugin removeu o `installFooterStyle` (CSS que escondia os dois).
- **Correção:** CSS reinjetado na 1.6.0.
- **Regra:** ao reescrever um arquivo, inventarie o que ele fazia hoje e preserve.

### Widget de busca no cabeçalho derrubava a barra
- **Sintoma:** ao abrir uma sessão, a barra superior desaparecia.
- **Causa:** componente registrado num slot dentro do cabeçalho da conversa.
- **Correção:** widget reescrito em DOM puro, fora da árvore React.
- **Regra:** nada crítico dentro do cabeçalho; sobreposição é mais segura.

### Upload de arquivo falhava só para não-imagens
- **Sintoma:** `Upload failed; click to retry` ao anexar XML/PDF.
- **Causa:** o patch de Android cobria a publicação do objeto, mas não o `link(2)` do alias.
- **Correção:** alias cai para `copyFile` quando o link é recusado.
- **Regra:** procurar todos os `link(` do arquivo, não só o primeiro.

## 2026-11-19

### Modo web morria com “No usable native binding”
- **Sintoma:** “Não foi possível abrir o modo web / O servidor web nao iniciou.
  attempts: undefined | …”.
- **Causa:** `npm i -g @deepseek-ai/dsh` recriou `node_modules` e apagou o
  `require_builtin.node`; o bridge 1.2.1 não reaplicava a camada antes do
  `web.start`.
- **Correção:** `apply.sh --auto` restaura addon/koffi; bridge 1.3.2 roda isso
  antes de subir o host e valida o URL em cache.
- **Regra:** depois de qualquer `npm i` do DSH, rode `apply.sh --auto`. O APK
  não pode depender de arquivos que o npm recria.

### Payload com koffi estourou o Binder
- **Sintoma:** “Não foi possível falar com o Termux” +
  `TransactionTooLargeException: data parcel size 1332368 bytes`.
- **Causa:** o `dsh-web.tgz` (435 KB, koffi 1,14 MB) em base64/UTF-16 passava
  de 1 MB no `Intent` do Termux.
- **Correção:** o app grava o tgz em
  `/storage/emulated/0/Android/media/com.sig.harness/` e envia só o caminho; o
  `TermuxRunner` copia com `cp`. Fallback em base64 só se o diretório falhar.
- **Regra:** payload grande não viaja em extra de `Intent`; use arquivo
  compartilhado em `Android/media/<pacote>/`.

### Cookies `dsh-auth-*` acumulados -> HTTP 431
- **Sintoma:** WebView “Webpage not available” com
  `net::ERR_HTTP_RESPONSE_CODE_FAILURE`; no `tcpdump`, `431 Request Header
  Fields Too Large`.
- **Causa:** cada `web.start` usa uma porta nova e o cookie é por autoridade
  (`host:porta`), mas cookies não são isolados por porta. O jar chegou a 70
  cookies e ~16,5 KB de header.
- **Correção:** `WebScreen.load()` limpa os cookies antes de carregar o URL;
  `web.start` valida o URL em cache; geração por request ignora resposta
  antiga.
- **Regra:** ao trocar a porta do host local, limpe o cookie jar do WebView.
  `431` em localhost é header grande, não falta de token.

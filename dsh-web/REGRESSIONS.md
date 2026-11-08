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

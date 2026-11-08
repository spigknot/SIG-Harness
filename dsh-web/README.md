# Camada web do SIG Harness

O SIG Harness não é só o APK. Ele carrega a interface web do **DeepSeek Harness**, e essa
interface recebeu ajustes que não existem no pacote publicado. Este diretório guarda
**tudo o que foi mudado fora do app**, para que um `git clone` + `apply.sh` reconstrua o
ambiente inteiro.

## O que tem aqui

| Caminho | O que é |
|---|---|
| `patches/` | diffs contra os pacotes publicados do DSH `0.2.1-rc.5` |
| `plugin/dsh-web-sig-voice/` | plugin local que cria a aba **Voz** nas Configurações do DSH |
| `profile/` | conteúdo de `~/.dsh/profiles/web` — `cordis.patch.yml` é a camada de configuração |
| `apply.sh` | aplica os patches e instala o plugin |
| `capture.sh` | regenera os patches depois de atualizar o DSH |
| `pin.env` | versão do DSH contra a qual os patches foram gerados |
| `tools/pristine.mjs` | recupera o pacote publicado a partir do cache do npm (funciona offline) |

## O que cada patch resolve

| Patch | Pacote | Por quê |
|---|---|---|
| `0001 attachment-local` | `dsh-attachment-local` | Android não permite *hard link* em dados de app: anexos falhavam. Passa a usar `rename` atômico, e o `fsync` de diretórios ancestrais ignora `EACCES`/ `EPERM` (o modo 771 de `/data/data` impede abrir os pais). |
| `0002 conversation` | `dsh-client-ui-conversation` | O toast de erro passa a mostrar `error.details.reason`, não só mensagem + código. |
| `0003 directory-picker` | `dsh-host-directory-picker-browse`, `-auto` | Opção `startDirectory`: o seletor de pastas abre em `/storage/emulated/0/Projetos` em vez de `$HOME`, com fallback para home quando o diretório configurado não é legível. |

## Como usar

Depois de instalar ou atualizar o DSH (o `npm i` sobrescreve os patches):

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

1. Edite `pin.env` com a versão nova.
2. Rode `bash dsh-web/capture.sh` — ele baixa (ou pega do cache) o pacote publicado e refaz os diffs.
3. Revise `git diff dsh-web/patches/`: cada alteração ainda faz sentido? Alguma já foi corrigida upstream?
4. `bash dsh-web/apply.sh --force` e teste o app.

## O que não entra aqui

- O `node_modules` inteiro do DSH (~2.500 arquivos de terceiros) — versionamos só o delta.
- `~/.dsh/.credentials.yaml`, `sessions/`, `logs/`, `storages/`, `attachments/` — dados locais e segredos.
- `dsh-web-mobile` — é dependência de terceiros (`github:mexiaosqwq/dsh-web-mobile`), veio do registry e não foi modificada.

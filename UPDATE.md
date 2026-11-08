# UPDATE.md — tarefas de publicação de versão

Leia **inteiro** antes de começar e não misture com outra tarefa.
Cada tarefa tem um critério de pronto. Pular qualquer uma = versão **não publicada**.

> Automação: `bash tools/release.sh` executa T1–T9 (com `--dry-run` para só validar).
> O procedimento manual abaixo continua valendo como referência do que o script faz.

---

## T1 — Repositório limpo e sincronizado

```bash
git status --short --branch
```

**Pronto quando:** sem alterações pendentes e `main` = `origin/main`.
Havendo trabalho não commitado: commitar antes (não misturar com a release).

## T2 — Camada `dsh-web` íntegra

```bash
bash dsh-web/apply.sh --auto --dry-run
```

**Pronto quando:** termina sem erro e mostra `profile ok` / `já aplicado` em todos os itens.
Se você mexeu em arquivos do DSH instalado, rode `bash dsh-web/capture.sh` antes.

## T3 — Patches reproduzem o instalado

Reconstruir a partir do pristine e comparar byte a byte com o DSH instalado.

**Pronto quando:** 10 arquivos iguais, 0 divergentes.

## T4 — Incrementar a versão

Em `app/AndroidManifest.xml`:

- `android:versionCode` **sempre** +1 (o Android exige para aceitar a atualização;
  também é o que faz o app detectar payload novo);
- `android:versionName` no padrão `1.3.N`.

**Pronto quando:** os dois campos conferem com a versão a publicar.

## T5 — Build e conferência do APK

```bash
bash build.sh
aapt2 dump badging dist/SIG-Harness.apk | head -1
apksigner verify dist/SIG-Harness.apk
unzip -p dist/SIG-Harness.apk assets/dsh-web.tgz | tar xzO dsh-web/plugin/dsh-web-sig-restart/package.json
```

**Pronto quando:** `versionCode`/`versionName` corretos, assinatura OK e o payload
traz a versão atual dos plugins.

## T6 — Commit e push

```bash
git add -A
git commit -m "release: X.Y.Z (versionCode N)"
git push origin main
```

**Pronto quando:** `main` remoto aponta para o commit da release.

## T7 — Tag da versão

```bash
git tag -a vX.Y.Z -m "SIG Harness X.Y.Z (versionCode N)"
git push origin vX.Y.Z
```

**Pronto quando:** a tag existe no remoto.

## T8 — Release **com o APK anexado** (obrigatório)

O `dist/` é ignorado pelo Git: **sem a release, o APK não chega a ninguém.**

```bash
API=https://api.github.com/repos/spigknot/SIG-Harness
curl -sS -X POST -H "Authorization: Bearer $GH_TOKEN" -H 'content-type: application/json' \
  -d '{"tag_name":"vX.Y.Z","name":"SIG Harness X.Y.Z","target_commitish":"main","body":"..."}' \
  $API/releases
# pegue upload_url na resposta e troque {?name,label} por ?name=SIG-Harness-X.Y.Z.apk
curl -sS -X POST -H "Authorization: Bearer $GH_TOKEN" \
  -H 'content-type: application/vnd.android.package-archive' \
  --data-binary @dist/SIG-Harness.apk "<upload_url>"
```

**Pronto quando:** a release existe e tem o asset `.apk` com o mesmo tamanho do arquivo local.

## T9 — Conferir como usuário (sem autenticação)

```bash
curl -sSI https://github.com/spigknot/SIG-Harness/releases/download/vX.Y.Z/SIG-Harness-X.Y.Z.apk
```

**Pronto quando:** `HTTP 200` e `content-length` igual ao APK gerado.

---

## Definição de pronto

- [ ] T1 repositório limpo e sincronizado
- [ ] T2 `apply.sh` idempotente
- [ ] T3 patches reproduzem o instalado (0 divergentes)
- [ ] T4 `versionCode` incrementado e `versionName` correto
- [ ] T5 APK assinado, com payload na versão certa
- [ ] T6 commit e push em `main`
- [ ] T7 tag `vX.Y.Z` publicada
- [ ] T8 release criada **com o APK anexado**
- [ ] T9 download público respondendo 200

Faltando qualquer item, a versão **não** está publicada — mesmo que o commit exista.

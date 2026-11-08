# UPDATE.md — publicar uma versão

Procedimento de publicação. Leia inteiro antes de começar; não misture com outra tarefa.

## 1. Gate (não publique com falha)

```bash
git status --short --branch        # deve estar limpo e sincronizado
bash dsh-web/apply.sh --auto --dry-run   # idempotência
bash build.sh                      # gera dist/SIG-Harness.apk
```

Confira que **pristine + patches reproduzem o instalado** (10 arquivos, 0 divergentes).
Se houver divergência, rode `bash dsh-web/capture.sh` antes e verifique de novo.

## 2. Versão

No `app/AndroidManifest.xml`:

- `android:versionCode` **sempre** sobe (+1) — é o que faz o Android aceitar a instalação
  como atualização e o app detectar payload novo.
- `android:versionName` segue o padrão `1.3.N`.

## 3. Build e conferência

```bash
bash build.sh
aapt2 dump badging dist/SIG-Harness.apk | head -1     # versionCode/versionName
apksigner verify dist/SIG-Harness.apk                 # assinatura
unzip -p dist/SIG-Harness.apk assets/dsh-web.tgz | tar xzO \
  dsh-web/plugin/dsh-web-sig-restart/package.json     # versão dos plugins no payload
```

## 4. Commit

```bash
git add -A
git commit -m "release: X.Y.Z (versionCode N)"
git push origin main
```

## 5. Tag e release (o APK **precisa** ir para a Release)

O `dist/` é ignorado pelo Git. O APK só chega ao usuário pela **Release**.

```bash
git tag -a vX.Y.Z -m "SIG Harness X.Y.Z (versionCode N)"
git push origin vX.Y.Z
```

Depois, criar a release e anexar o APK via API (token em `$GH_TOKEN`):

```bash
API=https://api.github.com/repos/spigknot/SIG-Harness
curl -sS -X POST -H "Authorization: Bearer $GH_TOKEN" -H 'content-type: application/json' \
  -d '{"tag_name":"vX.Y.Z","name":"SIG Harness X.Y.Z","target_commitish":"main","body":"..."}' \
  $API/releases
# upload_url vem na resposta; troque {?name,label} por ?name=SIG-Harness-X.Y.Z.apk
curl -sS -X POST -H "Authorization: Bearer $GH_TOKEN" \
  -H 'content-type: application/vnd.android.package-archive' \
  --data-binary @dist/SIG-Harness.apk "<upload_url>"
```

## 6. Confirmar de fora (sem autenticação)

```bash
curl -sSI https://github.com/spigknot/SIG-Harness/releases/download/vX.Y.Z/SIG-Harness-X.Y.Z.apk
```

Tem que dar `HTTP 200` com o tamanho do APK gerado. Só então a versão está publicada.

## Checklist

- [ ] gate verde (repositório limpo, patches íntegros, apply idempotente)
- [ ] versionCode incrementado
- [ ] APK assinado e payload com os plugins certos
- [ ] commit e push em `main`
- [ ] tag `vX.Y.Z` publicada
- [ ] release criada **com o APK anexado**
- [ ] download público respondendo 200

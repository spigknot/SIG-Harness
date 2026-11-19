#!/data/data/com.termux/files/usr/bin/bash
# Build SIG Harness APK inside Termux.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
BUILD="$APP/build"
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}"
KS="$APP/sig.jks"
KS_PASS="sigpass"
UNSIGNED=0
if [ "${1:-}" = --unsigned ]; then UNSIGNED=1; fi

for tool in aapt2 d8 javac java python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing tool: $tool" >&2; exit 1; }
done
[ -f "$ANDROID_JAR" ] || { echo "android.jar not found: $ANDROID_JAR" >&2; exit 1; }

# Keep the embedded bridge and ACP patch in sync with the bridge directory.
cp "$ROOT/bridge/sig-bridge.mjs" "$APP/assets/sig-bridge.mjs"
cp "$ROOT/bridge/acp.patch.yml" "$APP/assets/acp.patch.yml"
# O app executa o bootstrap por RUN_COMMAND e fornece o pacote por arquivo.
cp "$ROOT/bridge/bootstrap.sh" "$APP/assets/bootstrap.sh"
tar czf "$APP/assets/dsh-web.tgz" -C "$ROOT" dsh-web \
  bridge/sig-bridge.mjs bridge/tailscale-web.mjs bridge/acp.patch.yml bridge/ensure-bridge.cjs

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$ROOT/dist"

aapt2 compile --dir "$APP/res" -o "$BUILD/res.zip"
aapt2 link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  -A "$APP/assets" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 28 \
  --target-sdk-version 34 \
  "$BUILD/res.zip"

javac -encoding UTF-8 --release 8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  $(find "$APP/src" "$BUILD/gen" -name '*.java')

d8 --lib "$ANDROID_JAR" --min-api 28 --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class')

python3 - "$BUILD" <<'PY'
import os, shutil, sys, zipfile
b = sys.argv[1]
shutil.copy(os.path.join(b, 'base.apk'), os.path.join(b, 'unsigned.apk'))
with zipfile.ZipFile(os.path.join(b, 'unsigned.apk'), 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(b, 'dex', 'classes.dex'), 'classes.dex')
PY

if [ "$UNSIGNED" = 1 ]; then
  cp "$BUILD/unsigned.apk" "$ROOT/dist/SIG-Harness-unsigned.apk"
  echo "APK sem assinatura: $ROOT/dist/SIG-Harness-unsigned.apk"
  exit 0
fi
command -v apksigner >/dev/null 2>&1 || { echo "Missing tool: apksigner" >&2; exit 1; }

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias sig -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=SIG Harness,O=SIG,C=BR" >/dev/null 2>&1
fi

apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$ROOT/dist/SIG-Harness.apk" "$BUILD/unsigned.apk"
apksigner verify "$ROOT/dist/SIG-Harness.apk"

echo "APK: $ROOT/dist/SIG-Harness.apk"

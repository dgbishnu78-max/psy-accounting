#!/usr/bin/env bash
# Builds and tests the app, then produces a signed APK.
#   tools/build.sh                 -> runs tests and builds build/PSY-Accounts.apk
# Signing: set KEYSTORE, KS_PASS (and optionally KS_ALIAS, default "psy") to sign with the release key.
# Without them, a local debug key is created in build/debug.keystore (fine for testing only;
# an app signed with a different key cannot update an installed copy).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
T="${TOOLS:-$ROOT/tools/cache}"
[ -x "$T/kotlinc/bin/kotlinc" ] || { echo "Run tools/setup-tools.sh first (or set TOOLS=dir)"; exit 1; }
KOTLINC="$T/kotlinc/bin/kotlinc"
STDLIB="$T/kotlinc/lib/kotlin-stdlib.jar"
PLATFORM23=/usr/lib/android-sdk/platforms/android-23/android.jar
KO="-jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline -nowarn"
B="$ROOT/build"; rm -rf "$B/classes" "$B/dex" "$B/apk"; mkdir -p "$B/classes" "$B/dex" "$B/apk"

echo "== 1. Compile core"
"$KOTLINC" $KO core/src/main/kotlin -d "$B/classes/core.jar"
echo "== 2. Compile and run tests"
"$KOTLINC" $KO -cp "$B/classes/core.jar:$T/sqlite-jdbc.jar" core/src/test/kotlin -d "$B/classes/coretest.jar"
java -Dreport="$B/test-report.txt" -cp "$B/classes/core.jar:$B/classes/coretest.jar:$T/sqlite-jdbc.jar:$STDLIB" np.psy.core.TestRunnerKt
echo "== 3. Compile app"
"$KOTLINC" $KO -no-stdlib -cp "$T/android-34.jar:$B/classes/core.jar:$STDLIB" app/src/main/kotlin -d "$B/classes/app.jar"
echo "== 4. Convert to Android bytecode (D8)"
java -cp "$T/r8lib.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "$T/android-34.jar" --output "$B/dex" \
  "$B/classes/core.jar" "$B/classes/app.jar" "$STDLIB"
echo "== 5. Package resources"
aapt package -f -M app/src/main/AndroidManifest.xml -S app/src/main/res -I "$PLATFORM23" -F "$B/apk/base.apk"
(cd "$B/dex" && aapt add "$B/apk/base.apk" classes*.dex >/dev/null)
zipalign -f 4 "$B/apk/base.apk" "$B/apk/aligned.apk"
echo "== 6. Sign"
if [ -n "${KEYSTORE:-}" ]; then
  apksigner sign --ks "$KEYSTORE" --ks-key-alias "${KS_ALIAS:-psy}" --ks-pass "env:KS_PASS" --key-pass "env:KS_PASS" --out "$B/PSY-Accounts.apk" "$B/apk/aligned.apk"
else
  DKS="$B/debug.keystore"
  [ -f "$DKS" ] || keytool -genkeypair -keystore "$DKS" -alias debug -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Debug"
  apksigner sign --ks "$DKS" --ks-key-alias debug --ks-pass pass:android --key-pass pass:android --out "$B/PSY-Accounts.apk" "$B/apk/aligned.apk"
fi
apksigner verify --print-certs "$B/PSY-Accounts.apk" | head -3
rm -f "$B/PSY-Accounts.apk.idsig"
echo "APK: $B/PSY-Accounts.apk ($(du -k "$B/PSY-Accounts.apk" | cut -f1) KB)"

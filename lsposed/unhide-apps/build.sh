#!/bin/sh
# Builds dumbdown-unhide.apk with plain SDK tools - no Gradle, no Maven.
#
# The Xposed API classes under stubs/ are compile-only: they are on javac's
# classpath but never dexed, because LSPosed supplies the real ones at runtime
# and a module that bundles its own copy fails to load.
#
# Overrides: ANDROID_HOME / ANDROID_SDK_ROOT, BUILD_TOOLS, PLATFORM, KEYSTORE,
# KS_ALIAS, KS_PASS (defaults to the standard Android debug keystore).
set -eu

cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
BT=$SDK/build-tools/${BUILD_TOOLS:-36.0.0}
ANDROID_JAR=$SDK/platforms/${PLATFORM:-android-36}/android.jar
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
KS_ALIAS=${KS_ALIAS:-androiddebugkey}
KS_PASS=${KS_PASS:-android}
export KS_PASS

OUT=dumbdown-unhide.apk
B=build

for f in "$BT/d8" "$BT/aapt2" "$BT/zipalign" "$BT/apksigner" "$ANDROID_JAR" "$KEYSTORE"; do
    [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

rm -rf "$B"
mkdir -p "$B/stubs" "$B/classes" "$B/dex"

javac --release 8 -d "$B/stubs" -cp "$ANDROID_JAR" $(find stubs -name '*.java')
javac --release 8 -Xlint:all -d "$B/classes" -cp "$ANDROID_JAR:$B/stubs" $(find src -name '*.java')

"$BT/d8" --release --min-api 30 --lib "$ANDROID_JAR" --classpath "$B/stubs" \
    --output "$B/dex" $(find "$B/classes" -name '*.class')

"$BT/aapt2" link -o "$B/unsigned.apk" -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml -A assets \
    --min-sdk-version 30 --target-sdk-version 30 \
    --version-code 4 --version-name 1.3.0

(cd "$B/dex" && zip -q -X ../unsigned.apk classes.dex)

"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass env:KS_PASS --key-pass env:KS_PASS \
    --v4-signing-enabled false \
    --out "$OUT" "$B/aligned.apk"
"$BT/apksigner" verify "$OUT"

echo "built $(pwd)/$OUT"

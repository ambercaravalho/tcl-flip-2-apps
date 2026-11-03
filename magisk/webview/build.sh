#!/bin/sh
# Builds the framework overlay into system/product/overlay/WebViewProviders.apk
# with plain SDK tools.
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

OUT=system/product/overlay/WebViewProviders.apk
B=build

for f in "$BT/aapt2" "$BT/zipalign" "$BT/apksigner" "$ANDROID_JAR" "$KEYSTORE"; do
    [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

rm -rf "$B"
mkdir -p "$B" "$(dirname "$OUT")"

"$BT/aapt2" compile --dir overlay/res -o "$B/res.zip"
# Target SDK 28: see the comment in overlay/AndroidManifest.xml.
"$BT/aapt2" link -o "$B/unsigned.apk" -I "$ANDROID_JAR" \
    --manifest overlay/AndroidManifest.xml \
    --min-sdk-version 28 --target-sdk-version 28 \
    --version-code 1 --version-name 1.0.0 \
    "$B/res.zip"

"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass env:KS_PASS --key-pass env:KS_PASS \
    --v4-signing-enabled false \
    --out "$OUT" "$B/aligned.apk"
"$BT/apksigner" verify "$OUT"
rm -rf "$B"

echo "built $(pwd)/$OUT"

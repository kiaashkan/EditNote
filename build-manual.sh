#!/usr/bin/env bash
# Manual debug build without Gradle (useful where Gradle/AGP can't be
# downloaded). The normal build is still `./gradlew assembleDebug`.
#
# Required env (defaults match a typical local setup):
#   JAVA_HOME   JDK 17
#   ANDROID_HOME Android SDK with platforms;android-34 + build-tools;34.0.0
#   KOTLINC_HOME Kotlin compiler 2.0.20 (folder containing bin/kotlinc)
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$PROJECT_DIR/app/src/main"
BUILD_DIR="$PROJECT_DIR/app/build/manual"
BT="$ANDROID_HOME/build-tools/34.0.0"
AJAR="$ANDROID_HOME/platforms/android-34/android.jar"
OUT_APK="$PROJECT_DIR/app/build/EditNote-1.4.2.apk"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex"

# Signing: prefer KiA's RELEASE keystore (lives outside the repo, never
# committed — a debug-signed APK is what makes phones show "unauthorized /
# unsafe app" warnings on install). Fall back to a local debug key only
# when the release key is not present.
KEYS_DIR="$PROJECT_DIR/../editnote-keys"
if [ -f "$KEYS_DIR/release.keystore" ] && [ -f "$KEYS_DIR/password.txt" ]; then
  KEYSTORE="$KEYS_DIR/release.keystore"
  KS_PASS="$(cat "$KEYS_DIR/password.txt")"
  KEY_ALIAS="editnote"
else
  KEYSTORE="$PROJECT_DIR/debug.keystore"
  KS_PASS="android"
  KEY_ALIAS="androiddebugkey"
  if [ ! -f "$KEYSTORE" ]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -storepass android \
      -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
      -validity 10000 -dname "CN=EditNote Debug,O=KiA,C=IR" >/dev/null 2>&1
  fi
fi

# aapt2 needs the package attribute that Gradle normally injects from the namespace.
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.kiaashkan.editnote">|' \
  "$APP_DIR/AndroidManifest.xml" > "$BUILD_DIR/AndroidManifest.xml"

"$BT/aapt2" compile --dir "$APP_DIR/res" -o "$BUILD_DIR/resources.zip"
"$BT/aapt2" link -o "$BUILD_DIR/base.apk" -I "$AJAR" \
  --manifest "$BUILD_DIR/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 18 --version-name 1.4.2 \
  "$BUILD_DIR/resources.zip"

# Kotlin + the generated R class, against android.jar only.
"$KOTLINC_HOME/bin/kotlinc" \
  $(find "$APP_DIR/java" -name '*.kt') \
  $(find "$BUILD_DIR/gen" -name '*.java') \
  -classpath "$AJAR" -jvm-target 17 -d "$BUILD_DIR/classes"

# Dex app classes + Kotlin stdlib.
"$BT/d8" --release --min-api 26 --lib "$AJAR" --output "$BUILD_DIR/dex" \
  $(find "$BUILD_DIR/classes" -name '*.class') \
  "$KOTLINC_HOME/lib/kotlin-stdlib.jar"

cd "$BUILD_DIR/dex"
zip -q "$BUILD_DIR/base.apk" classes.dex
cd "$PROJECT_DIR"
"$BT/zipalign" -f 4 "$BUILD_DIR/base.apk" "$BUILD_DIR/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" --ks-key-alias "$KEY_ALIAS" \
  --out "$OUT_APK" "$BUILD_DIR/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT_APK" >/dev/null
echo "Built: $OUT_APK"

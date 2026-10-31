#!/usr/bin/env bash
set -euo pipefail

# LiteReader APK Compiler (Android SDK 35 + Build Tools 35.0.0 + OpenJDK 21)
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="/opt/homebrew/share/android-commandlinetools"
BUILD_TOOLS="$SDK_DIR/build-tools/35.0.0"
ANDROID_JAR="$SDK_DIR/platforms/android-35/android.jar"
JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$BUILD_TOOLS:$PATH"

VERSION_CODE=51
VERSION_NAME="6.9.0"

# 1. Prepare embedded assets (TrueType reading fonts + pre-packaged offline books)
ASSETS_DIR="$PROJECT_DIR/app/src/main/assets"
mkdir -p "$ASSETS_DIR/fonts" "$ASSETS_DIR/books"

FONT_SRC_DIR="/opt/homebrew/Library/Homebrew/vendor/portable-ruby/4.0.6/lib/ruby/gems/4.0.0/gems/rdoc-7.0.4/lib/rdoc/generator/template/darkfish/fonts"
if [ -d "$FONT_SRC_DIR" ]; then
  cp -f "$FONT_SRC_DIR"/*.ttf "$ASSETS_DIR/fonts/"
fi

BUILD_DIR="$PROJECT_DIR/build_apk_out"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/compiled_res" "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex"

echo "[1/6] Compiling Android resources with aapt2..."
"$BUILD_TOOLS/aapt2" compile \
  --dir "$PROJECT_DIR/app/src/main/res" \
  -o "$BUILD_DIR/compiled_res/res.zip"

echo "[2/6] Linking resources, assets, and generating R.java (v${VERSION_NAME}, code ${VERSION_CODE})..."
"$BUILD_TOOLS/aapt2" link \
  -I "$ANDROID_JAR" \
  -A "$ASSETS_DIR" \
  --manifest "$PROJECT_DIR/app/src/main/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  -o "$BUILD_DIR/LiteReader-unaligned.apk" \
  "$BUILD_DIR/compiled_res/res.zip"

echo "[3/6] Compiling Java sources with javac (Java 17 bytecode target)..."
find "$PROJECT_DIR/app/src/main/java" "$BUILD_DIR/gen" -name "*.java" > "$BUILD_DIR/sources.txt"
"$JAVA_HOME/bin/javac" \
  --release 17 \
  -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD_DIR/classes" \
  @"$BUILD_DIR/sources.txt"

echo "[4/6] Converting JVM classes to Dalvik bytecode (classes.dex) with d8..."
find "$BUILD_DIR/classes" -name "*.class" > "$BUILD_DIR/classes.txt"
"$BUILD_TOOLS/d8" \
  --min-api 26 \
  --lib "$ANDROID_JAR" \
  --output "$BUILD_DIR/dex" \
  @"$BUILD_DIR/classes.txt"

echo "[5/6] Packaging classes.dex into APK and aligning with zipalign..."
(cd "$BUILD_DIR/dex" && zip -q "$BUILD_DIR/LiteReader-unaligned.apk" classes.dex)
"$BUILD_TOOLS/zipalign" -f -p 4 \
  "$BUILD_DIR/LiteReader-unaligned.apk" \
  "$BUILD_DIR/LiteReader-aligned.apk"

echo "[6/6] Signing APK with persistent keystore (v1 + v2 + v3 signatures all enabled)..."
KEYSTORE_DIR="$PROJECT_DIR/keystore"
mkdir -p "$KEYSTORE_DIR"
KEYSTORE="$KEYSTORE_DIR/litereader-release.jks"
if [ ! -f "$KEYSTORE" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair \
    -v \
    -keystore "$KEYSTORE" \
    -alias litereader \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass android123 \
    -keypass android123 \
    -dname "CN=LiteReader, OU=Mobile, O=LiteReader, L=Hangzhou, S=ZJ, C=CN" >/dev/null 2>&1
fi

FINAL_APK_VERSIONED="$PROJECT_DIR/LiteReader-v${VERSION_NAME}-release.apk"
FINAL_APK_CN="$PROJECT_DIR/极简阅读-v${VERSION_NAME}-release.apk"
FINAL_APK_V2="$PROJECT_DIR/LiteReader-v2.0.0-release.apk"
FINAL_APK_V1="$PROJECT_DIR/LiteReader-v1.0.0-release.apk"

"$BUILD_TOOLS/apksigner" sign \
  --min-sdk-version 21 --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --ks "$KEYSTORE" \
  --ks-key-alias litereader \
  --ks-pass pass:android123 \
  --key-pass pass:android123 \
  --out "$FINAL_APK_VERSIONED" \
  "$BUILD_DIR/LiteReader-aligned.apk"

cp -f "$FINAL_APK_VERSIONED" "$FINAL_APK_CN"
cp -f "$FINAL_APK_VERSIONED" "$FINAL_APK_V2"
cp -f "$FINAL_APK_VERSIONED" "$FINAL_APK_V1"
rm -f "$PROJECT_DIR"/*.idsig
rm -rf "$BUILD_DIR"

"$BUILD_TOOLS/apksigner" verify --verbose "$FINAL_APK_VERSIONED"
ls -lh "$FINAL_APK_VERSIONED" "$FINAL_APK_CN"
echo "SUCCESS! APK built at: $FINAL_APK_VERSIONED"

#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$ROOT_DIR/android-app"
BUILD_DIR="$ROOT_DIR/build"
OUTPUT_APK="$ROOT_DIR/OpenSCAD-Standalone.apk"
ANDROID_JAR="/data/data/com.termux/files/home/android-sdk/platforms/android-34/android.jar"
JAVAC_BIN="${JAVAC_BIN:-$(command -v javac)}"
JAVA_HOME_DIR="${JAVA_HOME_DIR:-/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk}"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "android.jar missing: $ANDROID_JAR" >&2
  exit 1
fi
if [ -z "${JAVAC_BIN:-}" ] || [ ! -x "$JAVAC_BIN" ]; then
  echo "javac not found. Install JDK in Termux." >&2
  exit 1
fi

echo "=== OpenSCAD Standalone APK Builder ==="

echo "[0/7] Bundling OpenSCAD runtime assets..."
"$ROOT_DIR/bundle-runtime.sh"

echo "[1/7] Cleaning build directory..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"/{compiled,gen,classes,dex}

echo "[2/7] Compiling resources with aapt2..."
find "$APP_DIR/res" -type f \( -name "*.xml" -o -name "*.png" \) | while read -r file; do
  aapt2 compile -o "$BUILD_DIR/compiled/" "$file"
done

echo "[3/7] Linking resources..."
aapt2 link \
  --manifest "$APP_DIR/AndroidManifest.xml" \
  -I "$ANDROID_JAR" \
  -o "$BUILD_DIR/linked.apk" \
  --java "$BUILD_DIR/gen" \
  --auto-add-overlay \
  -A "$APP_DIR/assets" \
  "$BUILD_DIR"/compiled/*.flat

echo "[4/7] Compiling Java sources..."
JAVA_SOURCES=$(find "$APP_DIR/src" "$BUILD_DIR/gen" -name "*.java")
"$JAVAC_BIN" \
  --release 8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD_DIR/classes" \
  $JAVA_SOURCES

echo "[5/7] Converting classes to DEX..."
if command -v dx >/dev/null 2>&1; then
  dx --dex --output="$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/classes"
else
  JAVA_HOME="$JAVA_HOME_DIR" d8 \
    --output "$BUILD_DIR/dex" \
    --min-api 24 \
    --lib "$ANDROID_JAR" \
    $(find "$BUILD_DIR/classes" -name "*.class")
fi

echo "[6/7] Packaging and signing APK..."
cp "$BUILD_DIR/linked.apk" "$BUILD_DIR/final.apk"
zip -j "$BUILD_DIR/final.apk" "$BUILD_DIR/dex/classes.dex" >/dev/null

KEYSTORE="$ROOT_DIR/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass android \
    -keypass android \
    -alias androiddebug \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=OpenSCADStandalone,O=OpenSCAD,C=US"
fi

APK_TO_SIGN="$BUILD_DIR/final.apk"
if command -v zipalign >/dev/null 2>&1; then
  zipalign -f 4 "$BUILD_DIR/final.apk" "$BUILD_DIR/aligned.apk"
  APK_TO_SIGN="$BUILD_DIR/aligned.apk"
fi

apksigner sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androiddebug \
  --out "$OUTPUT_APK" \
  "$APK_TO_SIGN"

echo "[7/7] Verifying APK..."
apksigner verify "$OUTPUT_APK"

SIZE=$(du -h "$OUTPUT_APK" | cut -f1)
echo ""
echo "Build successful: $OUTPUT_APK ($SIZE)"

#!/bin/bash
set -e

export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}
PLATFORM=$ANDROID_HOME/platforms/android-34/android.jar
BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0

PROJECT="$(cd "$(dirname "$0")" && pwd)"
SRC=$PROJECT/app/src/main
OUT=$PROJECT/build
PKG_PATH=dev/lr/sense

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/apk" "$OUT/compiled_res"

$BUILD_TOOLS/aapt2 compile --dir "$SRC/res" -o "$OUT/compiled_res/"
$BUILD_TOOLS/aapt2 link \
  -o "$OUT/apk/app.unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  -R "$OUT/compiled_res"/*.flat

find "$SRC/java" -name "*.java" > "$OUT/sources.txt"
echo "$OUT/gen/$PKG_PATH/R.java" >> "$OUT/sources.txt"

javac -encoding UTF-8 -source 11 -target 11 \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

$BUILD_TOOLS/d8 --output "$OUT/apk/" --lib "$PLATFORM" $(find "$OUT/classes" -name "*.class")

cd "$OUT/apk"
cp app.unsigned.apk app.tmp.apk
zip -d app.tmp.apk classes.dex 2>/dev/null || true
zip -j app.tmp.apk classes.dex
mv app.tmp.apk app.unsigned.apk

PUBLIC_KS=$PROJECT/../android/signing/zhangxinchuang-public-release.p12
PUBLIC_KS_PASSWORD=${PUBLIC_KS_PASSWORD:-zhangxinchuang-public-30600}

$BUILD_TOOLS/zipalign -f 4 app.unsigned.apk app.aligned.apk
$BUILD_TOOLS/apksigner sign \
  --ks "$PUBLIC_KS" \
  --ks-type PKCS12 \
  --ks-pass pass:"$PUBLIC_KS_PASSWORD" \
  --key-pass pass:"$PUBLIC_KS_PASSWORD" \
  --ks-key-alias zhangxinchuang-public \
  --out "$PROJECT/LR-Sense-Sensor-v0.1.apk" \
  app.aligned.apk

$BUILD_TOOLS/apksigner verify --verbose "$PROJECT/LR-Sense-Sensor-v0.1.apk"
echo "APK: $PROJECT/LR-Sense-Sensor-v0.1.apk"

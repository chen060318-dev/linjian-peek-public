#!/bin/bash
set -e

export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}
PLATFORM=$ANDROID_HOME/platforms/android-34/android.jar
BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0

PROJECT="$(cd "$(dirname "$0")" && pwd)"
SRC=$PROJECT/app/src/main
OUT=$PROJECT/build
PKG_PATH=dev/linjian/peek

if [ ! -f "$PLATFORM" ]; then
  echo "Android platform not found: $PLATFORM"
  echo "Install Android SDK platform 34 first."
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/apk" "$OUT/compiled_res"

echo "=== Compiling resources ==="
$BUILD_TOOLS/aapt2 compile --dir "$SRC/res" -o "$OUT/compiled_res/"

echo "=== Linking resources ==="
$BUILD_TOOLS/aapt2 link \
    -o "$OUT/apk/app.unsigned.apk" \
    -I "$PLATFORM" \
    --manifest "$SRC/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --auto-add-overlay \
    -R "$OUT/compiled_res"/*.flat

echo "=== Compiling Java ==="
find "$SRC/java" -name "*.java" > "$OUT/sources.txt"
echo "$OUT/gen/$PKG_PATH/R.java" >> "$OUT/sources.txt"
javac -encoding UTF-8 -source 11 -target 11 -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "=== Creating DEX ==="
$BUILD_TOOLS/d8 --output "$OUT/apk/" --lib "$PLATFORM" $(find "$OUT/classes" -name "*.class")

echo "=== Building APK ==="
cd "$OUT/apk"
cp app.unsigned.apk app.tmp.apk
zip -d app.tmp.apk classes.dex 2>/dev/null || true
zip -j app.tmp.apk classes.dex
mv app.tmp.apk app.unsigned.apk

echo "=== Loading fixed PUBLIC signing key ==="
PUBLIC_KS=$PROJECT/signing/zhangxinchuang-public-release.p12
PUBLIC_KS_PASSWORD=${PUBLIC_KS_PASSWORD:-zhangxinchuang-public-30600}
if [ ! -f "$PUBLIC_KS" ]; then
    echo "Public release keystore not found: $PUBLIC_KS"
    exit 1
fi

echo "=== Aligning ==="
$BUILD_TOOLS/zipalign -f 4 app.unsigned.apk app.aligned.apk

echo "=== Signing public APK ==="
$BUILD_TOOLS/apksigner sign \
    --ks "$PUBLIC_KS" \
    --ks-type PKCS12 \
    --ks-pass pass:"$PUBLIC_KS_PASSWORD" \
    --key-pass pass:"$PUBLIC_KS_PASSWORD" \
    --ks-key-alias zhangxinchuang-public \
    --out "$PROJECT/Zhangxinchuang-public-v0.3.10.4.apk" \
    app.aligned.apk

echo "=== Verifying fixed public signature ==="
VERIFY_OUTPUT=$($BUILD_TOOLS/apksigner verify --verbose --print-certs "$PROJECT/Zhangxinchuang-public-v0.3.10.4.apk")
echo "$VERIFY_OUTPUT"
echo "$VERIFY_OUTPUT" | grep -qi "aea75c9b2b5f5c42d56b72d4a69a79a38e1c57f27db021017be8656bc8f002fb"

echo ""
echo "=== Done ==="
echo "APK: $PROJECT/Zhangxinchuang-public-v0.3.10.4.apk"
ls -lh "$PROJECT/Zhangxinchuang-public-v0.3.10.4.apk"


echo ""
echo "=== Building WeChat side bridge APK ==="
cd "$PROJECT"
BRIDGE_SRC=$PROJECT/wechat-bridge/src/main
BRIDGE_OUT=$PROJECT/build_wechat_bridge
BRIDGE_PKG_PATH=dev/linjian/peek/wechatbridge
BRIDGE_APK=$PROJECT/Zhangxinchuang-WeChatBridge-v0.1.5.apk
BRIDGE_ICON_B64=$PROJECT/wechat-bridge/icon_final.b64

echo "=== Restoring final launcher photo from checked-in base64 ==="
python3 - "$BRIDGE_ICON_B64" "$BRIDGE_SRC/res/drawable-nodpi/bridge_photo.jpg" "$BRIDGE_SRC/res/mipmap-xxxhdpi/ic_launcher.jpg" "$BRIDGE_SRC/res/mipmap-xxxhdpi/ic_launcher_round.jpg" <<'PY'
import base64, sys
src, *dsts = sys.argv[1:]
payload = open(src, "rb").read().strip()
data = base64.b64decode(payload, validate=True)
if len(data) < 800:
    raise SystemExit(f"Decoded icon too small: {len(data)} bytes")
if not (data.startswith(b"\xff\xd8") and data.endswith(b"\xff\xd9")):
    raise SystemExit("Decoded icon is not a valid JPEG")
for dst in dsts:
    with open(dst, "wb") as fh:
        fh.write(data)
    print(f"restored JPEG: {dst} ({len(data)} bytes)")
PY

echo "=== Verifying committed original-photo launcher assets ==="
python3 - "$BRIDGE_SRC/res/drawable-nodpi/bridge_photo.jpg" "$BRIDGE_SRC/res/mipmap-xxxhdpi/ic_launcher.jpg" "$BRIDGE_SRC/res/mipmap-xxxhdpi/ic_launcher_round.jpg" <<'PY'
import os, sys
for path in sys.argv[1:]:
    data = open(path, "rb").read()
    if len(data) < 800:
        raise SystemExit(f"Icon asset too small: {path} ({len(data)} bytes)")
    if not (data.startswith(b"\xff\xd8") and data.endswith(b"\xff\xd9")):
        raise SystemExit(f"Invalid JPEG markers: {path}")
    print(f"valid JPEG: {path} ({len(data)} bytes)")
PY

rm -rf "$BRIDGE_OUT"
mkdir -p "$BRIDGE_OUT/gen" "$BRIDGE_OUT/classes" "$BRIDGE_OUT/apk" "$BRIDGE_OUT/compiled_res"

$BUILD_TOOLS/aapt2 compile --dir "$BRIDGE_SRC/res" -o "$BRIDGE_OUT/compiled_res/"
$BUILD_TOOLS/aapt2 link \
    -o "$BRIDGE_OUT/apk/app.unsigned.apk" \
    -I "$PLATFORM" \
    --manifest "$BRIDGE_SRC/AndroidManifest.xml" \
    --java "$BRIDGE_OUT/gen" \
    --auto-add-overlay \
    -R "$BRIDGE_OUT/compiled_res"/*.flat

find "$BRIDGE_SRC/java" -name "*.java" > "$BRIDGE_OUT/sources.txt"
echo "$BRIDGE_OUT/gen/$BRIDGE_PKG_PATH/R.java" >> "$BRIDGE_OUT/sources.txt"
javac -encoding UTF-8 -source 11 -target 11 -classpath "$PLATFORM" -d "$BRIDGE_OUT/classes" @"$BRIDGE_OUT/sources.txt"

$BUILD_TOOLS/d8 --output "$BRIDGE_OUT/apk/" --lib "$PLATFORM" $(find "$BRIDGE_OUT/classes" -name "*.class")
cd "$BRIDGE_OUT/apk"
cp app.unsigned.apk app.tmp.apk
zip -d app.tmp.apk classes.dex 2>/dev/null || true
zip -j app.tmp.apk classes.dex
mv app.tmp.apk app.unsigned.apk

$BUILD_TOOLS/zipalign -f 4 app.unsigned.apk app.aligned.apk
$BUILD_TOOLS/apksigner sign \
    --ks "$PUBLIC_KS" \
    --ks-type PKCS12 \
    --ks-pass pass:"$PUBLIC_KS_PASSWORD" \
    --key-pass pass:"$PUBLIC_KS_PASSWORD" \
    --ks-key-alias zhangxinchuang-public \
    --out "$BRIDGE_APK" \
    app.aligned.apk

echo "=== Verifying WeChat bridge APK ==="
$BUILD_TOOLS/apksigner verify --verbose --print-certs "$BRIDGE_APK"
echo "Bridge APK: $BRIDGE_APK"
ls -lh "$BRIDGE_APK"

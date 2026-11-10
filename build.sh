#!/bin/bash
# hide5G 构建脚本（不依赖 Gradle，直接用 aapt2 + javac + d8 + apksigner）
set -euo pipefail

cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/37.0.0"
PLATFORM="$SDK/platforms/android-37.0/android.jar"
API_JAR="build/api82.jar"
KS="$HOME/.android/hide5g.keystore"
OUT="release"
NAME="hide5G-7.0.apk"

for f in "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$BT/zipalign" "$PLATFORM" "$API_JAR"; do
  [ -e "$f" ] || { echo "缺少: $f"; exit 1; }
done

rm -rf build/res build/classes build/dex build/base.apk build/unaligned.apk build/classes.jar
mkdir -p build/res build/classes build/dex "$OUT"

echo "==> 1/6 编译资源"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "==> 2/6 链接资源 + manifest"
"$BT/aapt2" link \
  -o build/base.apk \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  -A assets \
  --min-sdk-version 29 \
  --target-sdk-version 36 \
  --version-code 54 \
  --version-name 7.0 \
  build/res.zip

echo "==> 3/6 编译 Java"
find java -name '*.java' > build/sources.txt
if ! javac -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$PLATFORM" \
  -cp "$API_JAR" \
  -d build/classes \
  @build/sources.txt > build/javac.log 2>&1; then
  echo "!! 编译失败："
  grep -v "bootstrap class path\|source value 8\|target value 8" build/javac.log | tail -30
  exit 1
fi
grep -v "bootstrap class path\|source value 8\|target value 8\|已过时\|deprecat" build/javac.log | head -5 || true

echo "==> 4/6 生成 dex"
jar cf build/classes.jar -C build/classes .
"$BT/d8" --release --min-api 29 --lib "$PLATFORM" --output build/dex build/classes.jar

echo "==> 5/6 打包"
cp build/base.apk build/unaligned.apk
( cd build/dex && zip -q -j ../unaligned.apk classes.dex )
"$BT/zipalign" -f -p 4 build/unaligned.apk build/aligned.apk

echo "==> 6/6 签名"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias hide5g -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=hide5G, O=tonyma" >/dev/null 2>&1
  echo "    已生成签名密钥: $KS"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/$NAME" build/aligned.apk
"$BT/apksigner" verify --print-certs "$OUT/$NAME" | head -3

echo
echo "完成: $(pwd)/$OUT/$NAME  ($(du -h "$OUT/$NAME" | cut -f1))"
unzip -l "$OUT/$NAME" | grep -E "xposed_init|classes.dex|AndroidManifest"

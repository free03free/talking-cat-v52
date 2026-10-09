#!/usr/bin/env bash
# بناء APK (بدون إنترنت). يحتاج: ANDROID_HOME يشير لمجلد SDK، وأمر gradle (8.11.1 مع AGP 8.9.1)
set -e
cd "$(dirname "$0")"
: "${ANDROID_HOME:?ضع ANDROID_HOME أولًا}"
echo "sdk.dir=$ANDROID_HOME" > local.properties
[ -f app/libs/sherpa-onnx.jar ] || { echo "المحرك غير منزّل، سأشغّل fetch-engine.sh"; ./fetch-engine.sh; }
${GRADLE:-gradle} --offline clean assembleDebug
OUT=app/build/outputs/apk/debug/app-debug.apk
DEST="$HOME/storage/downloads"; [ -d "$DEST" ] || DEST="$HOME"
cp "$OUT" "$DEST/talking-cat.apk" && echo "تم: $DEST/talking-cat.apk"

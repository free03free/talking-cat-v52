#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
V=1.13.8
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download
A=app/src/main/assets/models
CACHE="${CAT_CACHE:-$HOME/.cache/talking-cat-engine}"
# إن لم يوجد مجلد التنزيلات الجديد وكان في ‎~/.cache مجلد تنزيلات سابق بالنمط *-engine فنعيد استعماله بدل التنزيل من جديد
if [ ! -d "$CACHE" ]; then
  for d in "$HOME"/.cache/*-engine; do
    if [ -d "$d" ] && [ "$d" != "$CACHE" ]; then mv "$d" "$CACHE" 2>/dev/null || cp -a "$d" "$CACHE"; echo "أُعيد استعمال التنزيلات السابقة من: $d"; break; fi
  done
fi
T="$CACHE/tmp"
mkdir -p app/libs app/src/main/jniLibs/arm64-v8a "$A" "$CACHE" "$T"
need(){ command -v "$1" >/dev/null 2>&1; }
for c in curl unzip bzip2 tar; do need "$c" || { echo "ينقص الأمر: $c. ثبّته ثم أعد المحاولة."; exit 1; }; done
dl(){ [ -s "$2" ]&&return 0; echo "تنزيل: $1"; local i=0 nonet=0 rc=0
  # تنزيل قابل للاستئناف: الملف الجزئي يبقى ويُكمَّل من حيث انقطع (حتى بين محاولات البناء)
  while [ $i -lt 40 ]; do
    i=$((i+1))
    curl -fL -C - --retry 1 --connect-timeout 20 -o "$2.part" "$1" && { mv "$2.part" "$2"; return 0; }
    rc=$?
    # 6 = تعذّر حل اسم الخادم، 7 = تعذّر الاتصال، 28 = انتهت المهلة، 22 = رفض الخادم/رابط غير موجود (403/404).
    # كلها لا تُحَلّ بإعادة المحاولة، وكانت 40 محاولة تضيّع 8 دقائق قبل الفشل.
    if [ $rc -eq 6 ] || [ $rc -eq 7 ] || [ $rc -eq 28 ] || [ $rc -eq 22 ]; then nonet=$((nonet+1)); else nonet=0; fi
    if [ $nonet -ge 3 ]; then
      echo ""
      echo "✘ تعذّر الوصول إلى هذا الرابط (لا إنترنت لهذا الخادم، أو الخادم محجوب، أو الرابط غير موجود)."
      echo "  إمّا فعّل الإنترنت للبناء، أو نزّل الملف التالي يدويًا وضعه باسمه الدقيق ثم أعد البناء:"
      echo "    الرابط : $1"
      echo "    احفظه في: $2"
      exit 1
    fi
    echo "انقطع التنزيل، إعادة المحاولة ($i/40) من نفس الموضع…"; sleep 3
  done
  exit 1; }
dl "$GH/v$V/sherpa-onnx-$V.aar" "$CACHE/sherpa-$V.aar"
rm -rf "$T/aar";mkdir -p "$T/aar"
unzip -oq "$CACHE/sherpa-$V.aar" 'classes.jar' 'jni/arm64-v8a/*' -d "$T/aar"
cp "$T/aar/classes.jar" app/libs/sherpa-onnx.jar
cp "$T/aar/jni/arm64-v8a/"*.so app/src/main/jniLibs/arm64-v8a/
dl "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar" "$CACHE/kotlin-stdlib.jar"
cp "$CACHE/kotlin-stdlib.jar" app/libs/kotlin-stdlib.jar
model(){ F="$CACHE/$(basename "$1")"; dl "$1" "$F"; rm -rf "$A/$2";mkdir -p "$A/$2";tar xjf "$F" -C "$A/$2" --strip-components=1; }
# صوت النطق العربي: Supertonic 3 (31 لغة منها العربية، محلي بالكامل، 10 أصوات).
# أدق في قياس إعادة التعرّف من أصوات Piper العربية (ar_JO) التي كانت مستعملة سابقًا.
TT="sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
TF="$CACHE/$TT.tar.bz2"
dl "$GH/tts-models/$TT.tar.bz2" "$TF" || { echo "تعذّر تنزيل صوت Supertonic."; exit 1; }
rm -rf "$A/tts-ar"; mkdir -p "$A/tts-ar"
tar xjf "$TF" -C "$A/tts-ar" --strip-components=1
for f in duration_predictor.int8.onnx text_encoder.int8.onnx vector_estimator.int8.onnx vocoder.int8.onnx tts.json unicode_indexer.bin voice.bin; do
  [ -s "$A/tts-ar/$f" ] || { echo "ملف ناقص في نموذج النطق: $f"; exit 1; }
done

# نماذج التعرف على الكلام (Whisper) لوضع التدريب فقط. يُضمَّن نموذجان ويختار الكبير بينهما من الإعدادات:
#   tiny = أسرع، base = أدق. للتحكم: WHISPER_MODELS="tiny" bash fetch-engine.sh (أو "tiny base small")
WM="${WHISPER_MODELS:-tiny base}"
# تنظيف مجلد النسخة القديمة (كان نموذجًا واحدًا باسم asr-ar)
rm -rf "$A/asr-ar"
for W in $WM; do
  WN="sherpa-onnx-whisper-$W"
  WF="$CACHE/$WN.tar.bz2"
  dl "$GH/asr-models/$WN.tar.bz2" "$WF" || { echo "تعذّر تنزيل نموذج Whisper ($W)."; exit 1; }
  rm -rf "$A/asr-$W"; mkdir -p "$A/asr-$W"
  tar xjf "$WF" -C "$A/asr-$W" --strip-components=1
  rm -rf "$A/asr-$W/test_wavs"
  # نُبقي نسخة int8 فقط لتصغير التطبيق
  if ls "$A/asr-$W/"*encoder.int8.onnx >/dev/null 2>&1 && ls "$A/asr-$W/"*decoder.int8.onnx >/dev/null 2>&1; then
    rm -f "$A/asr-$W/"*encoder.onnx "$A/asr-$W/"*decoder.onnx "$A/asr-$W/"*.weights
  fi
  ls "$A/asr-$W/"*tokens.txt >/dev/null 2>&1 || { echo "ملف tokens ناقص في نموذج Whisper ($W)."; exit 1; }
done
# نزيل أي نموذج Whisper قديم لم يعد مطلوبًا كي لا يكبر التطبيق
for d in "$A"/asr-*; do
  [ -d "$d" ] || continue
  n="${d##*/asr-}"; keep=0
  for W in $WM; do [ "$W" = "$n" ] && keep=1; done
  [ $keep -eq 1 ] || rm -rf "$d"
done
touch app/libs/.engine-supertonic
echo "تم تجهيز محرك النطق العربي (Supertonic 3)."

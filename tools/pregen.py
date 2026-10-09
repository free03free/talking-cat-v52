#!/usr/bin/env python3
"""
يولّد مسبقًا أصوات الحروف والحركات والمدود والكلمات والألوان والأرقام (tools/lessons.json)
ويضعها داخل التطبيق في app/src/main/assets/tts-pre/ ، فيصير نطقها فوريًا من أول ضغطة.

الاستعمال (على الحاسوب، مرة واحدة):
    pip install sherpa-onnx numpy scipy
    python tools/pregen.py --voice 0 --steps 5
بعد تغيير --voice أعد بناء الـ APK. الأصوات التي ليست في الحزمة (صوت آخر، سرعة مختلفة، أرقام كبيرة) تُولَّد وقت الاستعمال كالعادة.
"""
import argparse, json, os, re, sys, unicodedata
import numpy as np
import sherpa_onnx
from scipy.signal import resample_poly

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.dirname(here)
ap = argparse.ArgumentParser()
ap.add_argument("--model", default=os.path.join(root, "app/src/main/assets/models/tts-ar"),
                help="مجلد نموذج Supertonic (يحوي duration_predictor.int8.onnx ...)")
ap.add_argument("--out", default=os.path.join(root, "app/src/main/assets/tts-pre"))
ap.add_argument("--voice", type=int, default=0)
ap.add_argument("--steps", type=int, default=5)
ap.add_argument("--threads", type=int, default=os.cpu_count() or 2)
ap.add_argument("--limit", type=int, default=0, help="للتجربة فقط")
a = ap.parse_args()

def clean(t):  # نفس clean() في EchoEngine.java
    t = "".join(" " if unicodedata.category(c) in ("So", "Cs", "Cn") else c for c in t)
    t = t.replace("\u0640", "")
    t = re.sub(r'["«»()\[\]*_#]', " ", t)
    return re.sub(r"\s+", " ", t).strip()

def trim(s, sr):  # نفس trim() في EchoEngine.java
    pk = float(np.abs(s).max()) if len(s) else 0
    if pk < 1e-4: return s
    th = max(0.03 * pk, 1e-4)
    idx = np.where(np.abs(s) >= th)[0]
    first, last = int(idx[0]), int(idx[-1])
    f = max(0, first - sr * 40 // 1000); t = min(len(s), last + 1 + sr * 160 // 1000)
    return s[f:t] if t - f >= 16 else s

d = a.model
cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
        duration_predictor=f"{d}/duration_predictor.int8.onnx", text_encoder=f"{d}/text_encoder.int8.onnx",
        vector_estimator=f"{d}/vector_estimator.int8.onnx", vocoder=f"{d}/vocoder.int8.onnx",
        tts_json=f"{d}/tts.json", unicode_indexer=f"{d}/unicode_indexer.bin", voice_style=f"{d}/voice.bin"),
    num_threads=a.threads))
tts = sherpa_onnx.OfflineTts(cfg)
items = json.load(open(os.path.join(here, "lessons.json"), encoding="utf-8"))
if a.limit: items = items[:a.limit]
os.makedirs(a.out, exist_ok=True)
idx_path = os.path.join(a.out, "index.json")
index = {"rate": 22050, "voice": a.voice, "items": {}}
if os.path.exists(idx_path):
    old = json.load(open(idx_path, encoding="utf-8"))
    if old.get("voice") == a.voice: index["items"] = old.get("items", {})

done = 0
for n, (text, sp) in enumerate(items):
    c = clean(text); key = f"{int(sp * 100 + 0.5)}|{c}"
    fn = f"{abs(hash(key)) % 10**9:09d}_{n}.pcm"
    if key in index["items"] and os.path.exists(os.path.join(a.out, index["items"][key])): continue
    g = sherpa_onnx.GenerationConfig(); g.sid = a.voice; g.speed = float(sp); g.num_steps = a.steps; g.extra = {"lang": "ar"}
    r = tts.generate(c, g)
    s = trim(np.asarray(r.samples, dtype=np.float32), r.sample_rate)
    if r.sample_rate != 22050: s = resample_poly(s, 22050, r.sample_rate).astype(np.float32)
    pk = float(np.abs(s).max()) or 1.0
    (s / pk * 0.95 * 32767).astype("<i2").tofile(os.path.join(a.out, fn))
    index["items"][key] = fn; done += 1
    if done % 10 == 0:
        json.dump(index, open(idx_path, "w", encoding="utf-8"), ensure_ascii=False)
        print(f"{n+1}/{len(items)}", flush=True)
json.dump(index, open(idx_path, "w", encoding="utf-8"), ensure_ascii=False)
print("done:", len(index["items"]), "items,", round(sum(os.path.getsize(os.path.join(a.out, f)) for f in index["items"].values()) / 1e6, 1), "MB")

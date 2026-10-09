package app.talkingcat;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig;
import com.k2fsa.sherpa.onnx.GenerationConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** محرك النطق العربي (محلي بالكامل). */
final class EchoEngine {
    interface Callback {
        void emit(String type, String payload);
        void report(String title, String msg);
        void toast(String msg);
    }

    private static final String ASSET_DIR = "models/tts-ar";

    private final Context ctx;
    private final Callback cb;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicInteger gen = new AtomicInteger();
    private volatile AudioTrack track;
    private OfflineTts tts;
    private TextToSpeech sys;
    private volatile boolean sysReady = false;
    private volatile boolean useSystem = false; // جوجل بلا عربية في جهازك؛ المحرك المدمج هو الأساس
    private volatile int voiceId = 9;           // Supertonic: 10 أصوات (0..9)؛ الصوت 10 كان الأوضح في القياس
    private volatile int steps = 6;             // خطوات التوليد: أقل = أسرع (3 سريع، 4 متوازن، 6 أوضح)
    // إعادة صوتك على خيط مستقل: كانت تنتظر خلف تحميل نموذج النطق وتوليده (عدة ثوانٍ) فتتأخر أو تضيع
    private final ExecutorService playExec = Executors.newSingleThreadExecutor();
    private final Object genLock = new Object(); // النموذج لا يُستدعى من خيطين معًا
    private final AtomicInteger fgBusy = new AtomicInteger(0);
    private final AtomicInteger prefetchGen = new AtomicInteger(0);
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "tts-prefetch"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t; });
    // ذاكرة مؤقتة: الحروف والكلمات تتكرر كثيرًا، فلا نعيد توليدها
    /** مقطع صوتي مع معدل عيناته (المضمَّن 22050، والمولَّد وقت التشغيل 44100). */
    private static final class Clip { final float[] s; final int rate; Clip(float[] s, int rate) { this.s = s; this.rate = rate; } }
    private final Map<String, Clip> cache = new LinkedHashMap<String, Clip>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Clip> e) { return size() > 120; }
    };
    private volatile long lastFg = 0L;           // آخر وقت ضغط فيه الطفل (لإيقاف التجهيز الخلفي أثناء اللعب)

    void setVoice(int v) { voiceId = Math.max(0, Math.min(9, v)); }
    void setSteps(int v) { steps = Math.max(2, Math.min(10, v)); }
    private LoudnessEnhancer enhancer;

    void setUseSystem(boolean on) { useSystem = on; }
    private volatile boolean prefetchPaused = false;
    /** وضع التدريب: يوقف التوليد الخلفي كي لا يزاحم القط والتعرف على الصوت. */
    void setPrefetchPaused(boolean on) { prefetchPaused = on; }

    /** صوت النظام العربي (Google/Samsung…): يعالج العربية أفضل من Piper ويقبل النص بلا تشكيل. */
    private void initSystemTts() {
        try {
            sys = new TextToSpeech(ctx, st -> {
                if (st != TextToSpeech.SUCCESS) return;
                int r = sys.setLanguage(new Locale("ar"));
                sysReady = r >= TextToSpeech.LANG_AVAILABLE;
            });
        } catch (Throwable ignored) {}
    }

    /** يولّد الكلام عبر صوت النظام إلى ملف WAV مؤقت ثم يقرأ العينات، لنعالجها بنفس خط الصوت. */
    private float[] synthSystem(String text, float speed, int[] outRate) {
        if (!sysReady || sys == null) return null;
        File f = new File(ctx.getCacheDir(), "sys_" + System.nanoTime() + ".wav");
        try {
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] ok = {false};
            final String id = "u" + System.nanoTime();
            sys.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String u) {}
                @Override public void onDone(String u) { if (id.equals(u)) { ok[0] = true; done.countDown(); } }
                @Override @SuppressWarnings("deprecation") public void onError(String u) { if (id.equals(u)) done.countDown(); }
            });
            sys.setSpeechRate(Math.max(0.5f, Math.min(1.5f, speed)));
            Bundle p = new Bundle();
            if (sys.synthesizeToFile(text, p, f, id) != TextToSpeech.SUCCESS) return null;
            if (!done.await(15, TimeUnit.SECONDS) || !ok[0]) return null;
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            int rate = 22050, pos = 12, dataOff = -1, dataLen = 0;
            while (pos + 8 <= b.length) {
                String tag = new String(b, pos, 4, "US-ASCII");
                int len = (b[pos+4]&255) | (b[pos+5]&255)<<8 | (b[pos+6]&255)<<16 | (b[pos+7]&255)<<24;
                if (tag.equals("fmt ")) rate = (b[pos+12]&255) | (b[pos+13]&255)<<8 | (b[pos+14]&255)<<16 | (b[pos+15]&255)<<24;
                if (tag.equals("data")) { dataOff = pos + 8; dataLen = (len <= 0 || dataOff + len > b.length) ? b.length - dataOff : len; break; }
                pos += 8 + len + (len & 1);
            }
            if (dataOff < 0 || dataLen < 2) return null;
            float[] out = new float[dataLen / 2];
            for (int i = 0; i < out.length; i++) out[i] = (short)((b[dataOff+2*i]&255) | (b[dataOff+2*i+1]<<8)) / 32768f;
            outRate[0] = rate;
            return out;
        } catch (Throwable t) { return null; }
        finally { f.delete(); }
    }

    /**
     * معالجة الصوت: رفع حتى متوسط طاقة (RMS) ثابت ثم محدِّد tanh بدل القصّ.
     * الأصوات الخام لـ Piper خافتة جدًا (RMS≈‎-40dBFS)؛ تطبيع الذروة وحده كان يترك الحروف والكلمات القصيرة خافتة.
     * lead/tail صمت قصير يمنع قصّ أول الحرف وآخره.
     */
    private static short[] process(float[] smp, int sr, float volume, boolean pad) {
        double sum = 0; for (float x : smp) sum += x * x;
        float rms = (float) Math.sqrt(sum / Math.max(1, smp.length));
        float user = Math.max(0f, Math.min(1f, volume)); // المنزلق يتحكم فعليًا: 0 = صمت، 100% = أعلى مستوى
        float gain = Math.min(40f, 0.20f / Math.max(rms, 1e-4f));
        int lead = pad ? sr * 120 / 1000 : 0, tail = pad ? sr * 150 / 1000 : 0;
        short[] pcm = new short[lead + smp.length + tail];
        double k = Math.tanh(0.9);
        for (int i = 0; i < smp.length; i++) {
            double y = Math.tanh(smp[i] * gain * 0.9) / k * 0.98 * user;
            pcm[lead + i] = (short) Math.max(-32767, Math.min(32767, y * 32767));
        }
        return pcm;
    }

    EchoEngine(Context ctx, Callback cb) { this.ctx = ctx; this.cb = cb; }

    /** ينسخ نموذج الصوت من assets إلى مجلد التطبيق (كان هذا مفقودًا → لا صوت أبدًا) ثم يحمّله. */
    void prepare() {
        initSystemTts();
        exec.execute(() -> {
            try {
                housekeeping();
                installAssets();
                if (canSpeak()) { getTts(); try { synthBuiltin("مَرْحَبًا", 0.9f); } catch (Throwable ignored) {} cb.emit("ttsready", "1"); }
                else cb.toast("ملفات صوت Supertonic غير موجودة داخل التطبيق. شغّل fetch-engine.sh ثم أعد البناء.");
            } catch (Throwable t) {
                cb.toast("تعذّر تجهيز الصوت: " + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        });
    }

    private File ttsDir() { return new File(ctx.getFilesDir(), "models/tts-ar"); }

    private static final String[] TTS_FILES = {
        "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
        "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin"
    };

    private boolean canSpeak() {
        File d = ttsDir();
        if (!d.isDirectory()) return false;
        for (String f : TTS_FILES) { File x = new File(d, f); if (!x.isFile() || x.length() == 0) return false; }
        return true;
    }

    private String stamp() {
        try { return String.valueOf(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime); }
        catch (Throwable t) { return "0"; }
    }

    private void installAssets() throws IOException {
        File d = ttsDir();
        File mark = new File(d, ".installed");
        String st = stamp();
        if (mark.isFile() && canSpeak()) {
            try (InputStream in = new java.io.FileInputStream(mark)) {
                byte[] b = new byte[64]; int n = in.read(b);
                if (n > 0 && st.equals(new String(b, 0, n))) return;
            } catch (IOException ignored) {}
        }
        String[] top = ctx.getAssets().list(ASSET_DIR);
        if (top == null || top.length == 0) return;
        cb.toast("جارٍ تجهيز صوت القط لأول مرة… لحظات ⏳");
        deleteRec(d);
        d.mkdirs();
        copyAsset(ASSET_DIR, d);
        try (OutputStream o = new FileOutputStream(mark)) { o.write(st.getBytes()); }
    }

    private void copyAsset(String path, File dest) throws IOException {
        String[] kids = ctx.getAssets().list(path);
        if (kids != null && kids.length > 0) {
            dest.mkdirs();
            for (String k : kids) copyAsset(path + "/" + k, new File(dest, k));
            return;
        }
        try (InputStream in = ctx.getAssets().open(path); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (java.io.FileNotFoundException e) {
            dest.mkdirs(); // مجلد فارغ
        }
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] k = f.listFiles();
        if (k != null) for (File c : k) deleteRec(c);
        f.delete();
    }

    private synchronized OfflineTts getTts() {
        if (tts != null) return tts;
        File d = ttsDir();
        OfflineTtsSupertonicModelConfig v = new OfflineTtsSupertonicModelConfig();
        v.setDurationPredictor(new File(d, "duration_predictor.int8.onnx").getAbsolutePath());
        v.setTextEncoder(new File(d, "text_encoder.int8.onnx").getAbsolutePath());
        v.setVectorEstimator(new File(d, "vector_estimator.int8.onnx").getAbsolutePath());
        v.setVocoder(new File(d, "vocoder.int8.onnx").getAbsolutePath());
        v.setTtsJson(new File(d, "tts.json").getAbsolutePath());
        v.setUnicodeIndexer(new File(d, "unicode_indexer.bin").getAbsolutePath());
        v.setVoiceStyle(new File(d, "voice.bin").getAbsolutePath());
        OfflineTtsModelConfig m = new OfflineTtsModelConfig();
        m.setSupertonic(v); m.setNumThreads(4); m.setDebug(false);
        OfflineTtsConfig c = new OfflineTtsConfig(); c.setModel(m);
        tts = new OfflineTts(null, c);
        return tts;
    }

    /** يقصّ الصمت الطويل قبل الكلام وبعده (Supertonic يضيف نحو 0.4–0.6ث قبل و0.6–0.75ث بعد)، وهو أكبر سبب لإحساس التأخر. */
    private static float[] trim(float[] s, int sr) {
        float peak = 0f; for (float x : s) peak = Math.max(peak, Math.abs(x));
        if (peak < 1e-4f) return s;
        float th = Math.max(0.03f * peak, 1e-4f);
        int first = 0, last = s.length - 1;
        while (first < s.length && Math.abs(s[first]) < th) first++;
        while (last > first && Math.abs(s[last]) < th) last--;
        int from = Math.max(0, first - sr * 40 / 1000), to = Math.min(s.length, last + 1 + sr * 160 / 1000);
        if (to - from < 16) return s;
        float[] o = new float[to - from]; System.arraycopy(s, from, o, 0, o.length);
        return o;
    }

    private File cacheDir() { File d = new File(ctx.getCacheDir(), "tts-v4"); d.mkdirs(); return d; }

    /** نصوص الدروس الثابتة فقط (من قائمة التجهيز المسبق) هي التي تُحفظ على القرص؛ أي كلام آخر (كلامك أنت) يبقى في الذاكرة فقط. */
    private final java.util.Set<String> lessonTexts = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** يحذف مجلد التخزين القديم (كان يحفظ كل نطق بما فيه الجمل المردَّدة) ويُبقي المجلد الجديد تحت حد حجم. */
    private void housekeeping() {
        try {
            File old = new File(ctx.getCacheDir(), "tts-v3");
            if (old.exists()) deleteRec(old);
            File[] fs = cacheDir().listFiles();
            if (fs == null) return;
            long total = 0; for (File f : fs) total += f.length();
            if (total <= 200L * 1024 * 1024) return;
            java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : fs) { if (total <= 150L * 1024 * 1024) break; total -= f.length(); f.delete(); }
        } catch (Throwable ignored) {}
    }

    private File cacheFile(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(); for (byte x : h) sb.append(String.format("%02x", x));
            return new File(cacheDir(), sb + ".pcm");
        } catch (Exception e) { return new File(cacheDir(), Integer.toHexString(key.hashCode()) + ".pcm"); }
    }

    private Clip readDisk(String key) {
        File f = cacheFile(key);
        if (!f.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            int rate = in.readInt(), n = in.readInt();
            if (n <= 0 || n > 44100 * 30) return null;
            float[] o = new float[n];
            for (int i = 0; i < n; i++) o[i] = in.readShort() / 32767f;
            return new Clip(o, rate);
        } catch (Throwable t) { f.delete(); return null; }
    }

    // ===== الحزمة المضمّنة في التطبيق (assets/tts-pre): تُولَّد على الحاسوب بـ tools/pregen.py =====
    private volatile org.json.JSONObject preItems = null;
    private volatile int preVoice = -1, preRate = 22050;
    private volatile boolean preLoaded = false;

    private synchronized void loadPre() {
        if (preLoaded) return;
        preLoaded = true;
        try (java.io.InputStream in = ctx.getAssets().open("tts-pre/index.json")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            org.json.JSONObject j = new org.json.JSONObject(bo.toString("UTF-8"));
            preVoice = j.optInt("voice", 0); preRate = j.optInt("rate", 22050);
            preItems = j.getJSONObject("items");
        } catch (Throwable ignored) { preItems = null; }
    }

    private static String preKey(String text, float speed) { return Math.round(speed * 100) + "|" + text; }

    private boolean hasPre(String text, float speed) {
        loadPre();
        return preItems != null && voiceId == preVoice && preItems.has(preKey(text, speed));
    }

    private Clip readPre(String text, float speed) {
        if (!hasPre(text, speed)) return null;
        try (java.io.InputStream in = ctx.getAssets().open("tts-pre/" + preItems.getString(preKey(text, speed)))) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] b = bo.toByteArray();
            float[] o = new float[b.length / 2];
            for (int i = 0; i < o.length; i++) o[i] = (short) ((b[2 * i] & 255) | (b[2 * i + 1] << 8)) / 32767f;
            return o.length == 0 ? null : new Clip(o, preRate);
        } catch (Throwable t) { return null; }
    }

    private void writeDisk(String key, float[] smp, int rate) {
        File f = cacheFile(key), tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(rate); out.writeInt(smp.length);
            float peak = 0f; for (float x : smp) peak = Math.max(peak, Math.abs(x));
            float k = peak > 0 ? 0.95f / peak : 1f;   // تخزين بأعلى دقة 16-بت؛ المعالجة لاحقًا تعتمد RMS فلا يتأثر المستوى
            for (float x : smp) out.writeShort((short) Math.round(x * k * 32767f));
        } catch (Throwable t) { tmp.delete(); return; }
        tmp.renameTo(f);
    }

    private boolean isCached(String key, String text, float speed) {
        if (hasPre(text, speed)) return true;
        synchronized (cache) { if (cache.containsKey(key)) return true; }
        return cacheFile(key).isFile();
    }

    /** هل القط مشغول بالنطق الآن (لتأجيل التجهيز الخلفي). */
    boolean busy() { return fgBusy.get() > 0; }

    /** عينات نطق القط لنص دون تشغيلها (لبناء نماذج المطابقة). null إن لم يجهز الصوت. rate[0] = التردد. */
    float[] modelSamples(String text, float speed, int[] rate) {
        try {
            String c = clean(text); if (c.isEmpty()) return null;
            if (useSystem) { float[] s = synthSystem(c, speed, rate); if (s != null && s.length > 0) return s; }
            if (!canSpeak()) return null;
            Clip cl = synthBuiltin(c, speed);
            if (cl == null || cl.s == null || cl.s.length == 0) return null;
            rate[0] = cl.rate; return cl.s;
        } catch (Throwable t) { return null; }
    }

    private String keyOf(String text, float speed) {
        return voiceId + "|" + steps + "|" + Math.round(speed * 100) + "|" + text;
    }

    /** مضمَّن في التطبيق ← ذاكرة ← قرص ← توليد (Supertonic، lang=ar). النتيجة مقصوصة الصمت. */
    private Clip synthBuiltin(String text, float speed) {
        final String key = keyOf(text, speed);
        Clip pre = readPre(text, speed);
        if (pre != null) return pre;
        synchronized (cache) { Clip hit = cache.get(key); if (hit != null) return hit; }
        Clip d = readDisk(key);
        if (d != null) { synchronized (cache) { cache.put(key, d); } return d; }
        GeneratedAudio a;
        synchronized (genLock) {
            synchronized (cache) { Clip hit = cache.get(key); if (hit != null) return hit; }
            OfflineTts t = getTts();
            GenerationConfig g = new GenerationConfig();
            g.setSid(voiceId); g.setSpeed(speed); g.setNumSteps(steps);
            HashMap<String, String> ex = new HashMap<>(); ex.put("lang", "ar"); g.setExtra(ex);
            a = t.generateWithConfig(text, g);
        }
        float[] smp = a == null ? null : a.getSamples();
        if (smp == null || smp.length == 0) return null;
        int rate = a.getSampleRate();
        Clip c = new Clip(trim(smp, rate), rate);
        synchronized (cache) { cache.put(key, c); }
        if (lessonTexts.contains(text)) writeDisk(key, c.s, c.rate);
        return c;
    }

    /** تجهيز مسبق في الخلفية لكل الحروف والكلمات: بعدها يصير النطق فوريًا. يتوقف مؤقتًا كلما احتاج الطفل نطقًا مباشرًا. */
    void prefetch(final String[] texts, final float[] speeds) {
        final int my = prefetchGen.incrementAndGet();
        for (String t : texts) lessonTexts.add(clean(t));
        bg.execute(() -> {
            try {
                housekeeping();
                for (int i = 0; i < texts.length && my == prefetchGen.get(); i++) {
                    // لا نزاحم الطفل: ننتظر حتى يمضي 20 ثانية بلا ضغط، فالتوليد الخلفي كان يؤخر أول نطق
                    while (my == prefetchGen.get() && (fgBusy.get() > 0 || prefetchPaused || System.currentTimeMillis() - lastFg < 20000)) Thread.sleep(500);
                    if (!canSpeak()) return;
                    String c = clean(texts[i]);
                    if (c.isEmpty()) continue;
                    float sp = Math.max(0.6f, Math.min(1.3f, speeds[i]));
                    if (isCached(keyOf(c, sp), c, sp)) continue;
                    synthBuiltin(c, sp);
                }
            } catch (Throwable ignored) {}
        });
    }

    /** يقسم النص الطويل إلى جمل لنبدأ النطق بعد الجملة الأولى فقط. */
    private static List<String> splitSentences(String c) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i); cur.append(ch);
            boolean end = ch == '.' || ch == '!' || ch == '?' || ch == '؟' || ch == '،' || ch == '؛' || ch == ',' || ch == ';';
            if (end && cur.toString().trim().length() >= 12) { out.add(cur.toString().trim()); cur.setLength(0); }
        }
        if (cur.toString().trim().length() > 0) {
            if (!out.isEmpty() && cur.toString().trim().length() < 8) out.set(out.size() - 1, out.get(out.size() - 1) + " " + cur.toString().trim());
            else out.add(cur.toString().trim());
        }
        return out;
    }

    private static float softClip(float x) {
        float a = Math.abs(x);
        if (a <= 0.8f) return x;
        float y = 0.8f + 0.2f * (float) Math.tanh((a - 0.8f) / 0.2f);
        return x < 0 ? -y : y;
    }

    private static String clean(String text) {
        String c = text == null ? "" : text.replaceAll("[\\p{So}\\p{Cs}\\p{Cn}]", " ");
        c = c.replace("\u0640", "").replaceAll("[\"«»()\\[\\]*_#]", " ");
        return c.replaceAll("\\s+", " ").trim();
    }

    private static void fin(Runnable r) { if (r != null) r.run(); }

    /** نطق تعليمي هادئ (وضع التعلم). */
    void say(final String text, final float pitchFactor, final int delayMs) {
        say(text, pitchFactor, delayMs, 1.0f, null);
    }

    void say(final String text, final float speedValue, final int delayMs, final float volume) {
        say(text, speedValue, delayMs, volume, null);
    }

    void say(final String text, final float speedValue, final int delayMs, final float volume, final Runnable onFinished) {
        // القيمة تُستعمل كسرعة فعلية، مع تطبيق مستوى الصوت العام.
        float speed = Math.max(0.6f, Math.min(1.2f, speedValue));
        speak(text, speed, 1.0f, delayMs, Math.max(0f, Math.min(1f, volume)), onFinished);
    }

    private void speak(final String text, final float speed, final float rateMul, final int delayMs, final float volume, final Runnable onFinished) {
        final int my = gen.incrementAndGet();
        stopTrack();
        lastFg = System.currentTimeMillis();
        fgBusy.incrementAndGet();
        exec.execute(() -> {
            try {
                if (my != gen.get()) return;
                String c = clean(text);
                if (c.isEmpty()) { fin(onFinished); return; }
                float[] smp = null; int sr = 22050;
                if (useSystem) { int[] r = {0}; smp = synthSystem(c, speed, r); if (smp != null) sr = r[0]; }
                if (smp == null) {
                    if (!canSpeak()) { cb.toast("صوت النطق العربي غير جاهز بعد."); fin(onFinished); return; }
                    List<String> parts = splitSentences(c);
                    if (parts.size() > 1 && !hasPre(c, speed)) { speakChunks(parts, speed, rateMul, delayMs, volume, my, onFinished); return; }
                    Clip cl = synthBuiltin(c, speed); if (cl != null) { smp = cl.s; sr = cl.rate; }
                }
                if (my != gen.get()) return;
                if (smp == null || smp.length == 0) { fin(onFinished); return; }
                short[] pcm = process(smp, sr, volume, false);
                fgBusy.decrementAndGet();
                try { playPcm(pcm, Math.round(sr * rateMul), delayMs, my, Math.max(0f, Math.min(1f, volume)), onFinished); }
                finally { fgBusy.incrementAndGet(); }
            } catch (Throwable t) {
                cb.report("خطأ في النطق", t.getClass().getSimpleName() + ": " + t.getMessage());
                fin(onFinished);
            } finally { fgBusy.decrementAndGet(); }
        });
    }

    /** جمل متعددة: نبدأ بعد أول جملة، وتُولَّد التالية أثناء نطق السابقة. */
    private void speakChunks(List<String> parts, float speed, float rateMul, int delayMs, float volume, int my, Runnable onFinished) throws Exception {
        FutureTask<Object[]> next = startPart(parts.get(0), speed, volume);
        for (int i = 0; i < parts.size(); i++) {
            Object[] got = next.get();
            if (my != gen.get()) return;
            if (i + 1 < parts.size()) next = startPart(parts.get(i + 1), speed, volume);
            short[] pcm = got == null ? null : (short[]) got[0];
            int rate = got == null ? 44100 : (Integer) got[1];
            if (pcm != null && pcm.length > 0) {
                fgBusy.decrementAndGet();
                try { playPcm(pcm, Math.round(rate * rateMul), i == 0 ? delayMs : 0, my, Math.max(0f, Math.min(1f, volume)), i == parts.size() - 1 ? onFinished : null); }
                finally { fgBusy.incrementAndGet(); }
            } else if (i == parts.size() - 1) fin(onFinished);
        }
    }

    private FutureTask<Object[]> startPart(final String part, final float speed, final float volume) {
        FutureTask<Object[]> f = new FutureTask<>(() -> {
            Clip c = synthBuiltin(part, speed);
            return c == null ? null : new Object[]{process(c.s, c.rate, volume, false), c.rate};
        });
        Thread t = new Thread(f, "tts-part"); t.start();
        return f;
    }

    private void playPcm(short[] pcm, int rate, int delayMs, int my, float volume, Runnable onFinished) throws Exception {
        try { Thread.sleep(Math.max(0, Math.min(1000, delayMs))); } catch (InterruptedException e) { return; }
        if (my != gen.get()) return;
        AudioTrack t = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.length * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
        t.write(pcm, 0, pcm.length);
        // لا نهمل مستوى الصوت الذي اختاره المستخدم. القيمة تُطبق على AudioTrack نفسه.
        t.setVolume(1.0f); // مستوى الصوت طُبّق داخل process()؛ لا تخفيض مزدوج هنا
        try { if (enhancer != null) enhancer.release(); enhancer = new LoudnessEnhancer(t.getAudioSessionId()); enhancer.setTargetGain(600); enhancer.setEnabled(true); } catch (Throwable ignored) { enhancer = null; }
        synchronized (this) { if (my != gen.get()) { t.release(); return; } track = t; }
        t.play();
        cb.emit("speaking", "1");
        try {
            long limit = System.currentTimeMillis() + pcm.length * 1000L / rate + 2000;
            while (my == gen.get() && t.getPlaybackHeadPosition() < pcm.length && System.currentTimeMillis() < limit) Thread.sleep(30);
        } finally {
            cb.emit("speaking", "0");
        }
        if (my == gen.get()) {
            stopTrack();
            fin(onFinished);
        }
    }

    private synchronized void stopTrack() {
        AudioTrack t = track; track = null;
        if (t != null) { try { t.stop(); } catch (Throwable ignored) {} try { t.release(); } catch (Throwable ignored) {} }
    }

    void stop() { gen.incrementAndGet(); stopTrack(); }
    void shutdown() { stop(); prefetchGen.incrementAndGet(); bg.shutdownNow(); playExec.shutdownNow(); exec.shutdownNow(); try { if (sys != null) sys.shutdown(); } catch (Throwable ignored) {} try { if (tts != null) tts.release(); } catch (Throwable ignored) {} }
}

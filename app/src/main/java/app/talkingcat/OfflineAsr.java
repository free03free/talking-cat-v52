package app.talkingcat;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * التعرف على الكلام العربي محليًا بالكامل (Whisper عبر sherpa-onnx).
 * لا إنترنت ولا خدمة قوقل. يسجّل من الميكروفون، يكتشف نهاية الجملة بالصمت، ثم يفك الترميز.
 */
final class OfflineAsr {
    interface Listener {
        void onListening(boolean on);
        void onLevel(float level);      // بنفس مقياس onRmsChanged تقريبًا (-2..10)
        void onSamples(float[] samples, int rate); // الصوت المسجَّل نفسه (للإعادة بلا أخطاء)
        void onText(String text);
        void onNoSpeech();
        void onTemplate(boolean ok, String key, String info); // مطابقة القوالب: ok = قُبل الهدف دون Whisper
        void onError(String msg);
    }

    // كل نموذج Whisper في مجلد: models/asr-tiny و models/asr-base (يُجهَّزان بواسطة fetch-engine.sh)
    private static final String[] ENGINES = {"tiny", "base"};
    private static final String DEFAULT_ENGINE = "base";
    private static final int RATE = 16000;
    private static final int CHUNK = 800;               // 50ms: التقاط أسرع لبداية الكلام ونهايته
    private static final double MIN_VOICED_RATIO = 0.40; // نسبة الصوت الفعلي من المقطع كله
    private static final double VOWEL_ZCR = 0.22;        // معدل عبور الصفر تحت هذا = صوت حنجري (مصوِّت)
    private static final int MAX_BUFFER_MS = 8000;       // أكبر حد تسجيل يمكن اختياره من الإعدادات

    // ===== معاملات تُضبط من الإعدادات (القيم هنا هي الافتراضية، وتُطبَّق فورًا بلا إعادة تشغيل) =====
    private volatile double onMin = 0.010, noiseMul = 3.5;   // حساسية الميكروفون
    private volatile int minVoicedMs = 180;                  // أقل مدة صوت تُرسَل إلى Whisper
    private volatile int minVowelMs = 90;                   // أقل مدة حرف علة (0 = المرشّح معطّل)
    private volatile int endSilenceMs = 220;                 // صمت ينهي الجملة
    private volatile int maxUtteranceMs = 4000;              // حد التسجيل
    private volatile int startChunks = 2;                    // عدد مقاطع 50ms المتواصلة لبدء الالتقاط

    /** sens: 0 منخفضة، 1 متوسطة، 2 عالية. */
    void setParams(int sens, int minVoiced, int vowel, int endMs, int maxMs, int startMs) {
        int s = Math.max(0, Math.min(2, sens));
        onMin = new double[]{0.014, 0.010, 0.006}[s];
        noiseMul = new double[]{4.5, 3.5, 2.5}[s];
        minVoicedMs = Math.max(50, Math.min(1000, minVoiced));
        minVowelMs = Math.max(0, Math.min(600, vowel));
        endSilenceMs = Math.max(150, Math.min(1500, endMs));
        maxUtteranceMs = Math.max(1500, Math.min(MAX_BUFFER_MS, maxMs));
        startChunks = Math.max(1, Math.min(8, startMs / 50));
    }

    private static final int PREROLL_MS = 400;          // نحتفظ بآخر 0.4ث قبل بدء الكلام كي لا يُقص أول الحرف

    private final Context ctx;
    private final Listener cb;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ExecutorService decExec = Executors.newSingleThreadExecutor();
    private volatile boolean cancel = false;
    private volatile boolean busy = false;
    private OfflineRecognizer rec;
    private volatile VoiceTemplates tpl;
    private volatile String target = null;          // مفتاح الهدف الحالي مثل "l:3"
    private volatile boolean autoLearn = true;
    private volatile float[][] lastFeats = null;    // ميزات آخر مقطع (أرقام فقط، لا صوت)
    void setTemplates(VoiceTemplates t) { tpl = t; }
    void setTarget(String k) { target = (k == null || k.isEmpty()) ? null : k; }
    void setStrict(int v) { VoiceTemplates t = tpl; if (t != null) t.setStrict(v); }
    void setAutoLearn(boolean on) { autoLearn = on; if (!on) lastFeats = null; }
    /** الطفل أجاب صح عبر Whisper: نضيف بصمة صوته قالبًا تلقائيًا (إن كان التعلم التلقائي مفعّلًا). */
    void confirm(String key) {
        VoiceTemplates t = tpl; float[][] f = lastFeats; lastFeats = null;
        if (t != null && autoLearn && f != null && key != null && key.equals(target)) t.addAuto(key, f);
    }
    private volatile String engine = DEFAULT_ENGINE;   // المحرك المطلوب من الإعدادات
    private String recEngine = null;                   // المحرك الذي بُني به rec فعلًا

    OfflineAsr(Context ctx, Listener cb) {
        this.ctx = ctx; this.cb = cb;
        // نقرأ آخر اختيار محفوظ فورًا، فلا يُحمَّل المحرك الافتراضي أولًا ثم يُستبدل عند فتح الواجهة
        try { String saved = ctx.getSharedPreferences("asr", Context.MODE_PRIVATE).getString("engine", null);
              if (saved != null) for (String c : ENGINES) if (c.equals(saved)) engine = saved; } catch (Throwable ignored) {}
    }

    private String assetDir(String e) { return "models/asr-" + e; }
    private File dir(String e) { return new File(ctx.getFilesDir(), assetDir(e)); }
    private File dir() { return dir(effective()); }

    /** هل نموذج هذا المحرك موجود داخل التطبيق؟ */
    private boolean bundled(String e) {
        try { String[] t = ctx.getAssets().list(assetDir(e)); return t != null && t.length > 0; }
        catch (IOException x) { return false; }
    }

    /** المحرك المستعمل فعلًا: المطلوب إن كان مضمَّنًا، وإلا أول محرك متوفر (كي لا يتعطل التدريب). */
    private String effective() {
        String e = engine;
        if (bundled(e)) return e;
        for (String c : ENGINES) if (bundled(c)) return c;
        return e;
    }

    /** يختار محرك التعرف على الكلام ("tiny" أو "base"). يُطبَّق فورًا؛ إن تغيّر يُحمَّل النموذج الجديد في الخلفية. */
    void setEngine(String e) {
        if (e == null) return;
        boolean ok = false;
        for (String c : ENGINES) if (c.equals(e)) ok = true;
        if (!ok || e.equals(engine)) return;
        engine = e;
        try { ctx.getSharedPreferences("asr", Context.MODE_PRIVATE).edit().putString("engine", e).apply(); } catch (Throwable ignored) {}
        warm = false;          // النموذج الجديد يحتاج تحميلًا وتسخينًا
        prepareTraining(null);
    }

    private File find(String suffix) {
        File[] fs = dir().listFiles();
        if (fs == null) return null;
        for (File f : fs) if (f.isFile() && f.getName().endsWith(suffix)) return f;
        return null;
    }

    boolean available() {
        return find("encoder.int8.onnx") != null || find("encoder.onnx") != null;
    }

    private String stamp() {
        try { return String.valueOf(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime); }
        catch (Throwable t) { return "0"; }
    }

    private void install() throws IOException {
        final String eng = effective();
        File d = dir(eng);
        File mark = new File(d, ".installed");
        String st = stamp();
        if (mark.isFile() && available()) {
            try (InputStream in = new java.io.FileInputStream(mark)) {
                byte[] b = new byte[64]; int n = in.read(b);
                if (n > 0 && st.equals(new String(b, 0, n))) return;
            } catch (IOException ignored) {}
        }
        String[] top = ctx.getAssets().list(assetDir(eng));
        if (top == null || top.length == 0) return;
        deleteRec(d);
        d.mkdirs();
        for (String k : top) copyAsset(assetDir(eng) + "/" + k, new File(d, k));
        try (OutputStream o = new FileOutputStream(mark)) { o.write(st.getBytes()); }
        deleteRec(new File(ctx.getFilesDir(), "models/asr-ar"));   // نسخة الإصدارات السابقة: نحرّر مساحتها
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
        }
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] k = f.listFiles();
        if (k != null) for (File c : k) deleteRec(c);
        f.delete();
    }

    private final Object recLock = new Object();

    private OfflineRecognizer getRec() {
      synchronized (recLock) {
        String eff = effective();
        if (rec != null && eff.equals(recEngine)) return rec;
        if (rec != null) { try { rec.release(); } catch (Throwable ignored) {} rec = null; }
        File enc = find("encoder.int8.onnx"); if (enc == null) enc = find("encoder.onnx");
        File dec = find("decoder.int8.onnx"); if (dec == null) dec = find("decoder.onnx");
        File tok = find("tokens.txt");
        OfflineWhisperModelConfig w = new OfflineWhisperModelConfig();
        w.setEncoder(enc.getAbsolutePath());
        w.setDecoder(dec.getAbsolutePath());
        w.setLanguage("ar");
        w.setTask("transcribe");
        OfflineModelConfig m = new OfflineModelConfig();
        m.setWhisper(w);
        m.setTokens(tok.getAbsolutePath());
        m.setNumThreads(4);
        m.setDebug(false);
        OfflineRecognizerConfig c = new OfflineRecognizerConfig();
        c.setModelConfig(m);
        rec = new OfflineRecognizer(null, c);
        recEngine = eff;
        return rec;
      }
    }


    // ===== وضع التدريب: تحويل الكلام إلى نص محليًا للمطابقة فقط (لا يُخزَّن ولا يُعرض) =====
    void setTraining(boolean on) { }   // التطبيق للتدريب فقط الآن
    boolean trainingReady() { return find("encoder.int8.onnx") != null || find("encoder.onnx") != null; }

    private volatile boolean warm = false;
    boolean recWarm() { return warm; }

    /** ينسخ نموذج Whisper (مرة لكل تحديث)، يحمّله، ثم يشغّله مرة تسخين كي لا يتأخر أول جواب. */
    void prepareTraining(final Runnable done) {
        decExec.execute(() -> {
            try {
                install();
                if (trainingReady()) {
                    synchronized (recLock) {
                        OfflineRecognizer r = getRec();
                        if (!warm) {
                            OfflineStream st = r.createStream();
                            try { st.acceptWaveform(new float[16000], 16000); r.decode(st); r.getResult(st); }
                            finally { st.release(); }
                            warm = true;
                        }
                    }
                }
            } catch (Throwable t) { cb.onError("تعذّر تجهيز التعرف على الصوت: " + t.getClass().getSimpleName() + " " + t.getMessage()); return; }
            if (done != null) done.run();
        });
    }

    // فك الترميز: الأحدث يفوز. لا طابور ولا تراكم؛ إن جاء مقطع جديد أثناء فك سابق يُستبدل المنتظر به.
    private final Object decLock = new Object();
    private float[] pendingS;
    private int pendingR;
    private boolean decBusy = false;

    /** يفك ترميز مقطع؛ النتيجة تصل عبر onText أو onNoSpeech، ثم يُمسح الصوت من الذاكرة. */
    void decode(final float[] samples, final int rate) {
        synchronized (decLock) {
            if (pendingS != null) java.util.Arrays.fill(pendingS, 0f);
            pendingS = samples; pendingR = rate;
            if (decBusy) return;
            decBusy = true;
        }
        try { decExec.execute(this::drain); }
        catch (Throwable t) { synchronized (decLock) { decBusy = false; if (pendingS != null) java.util.Arrays.fill(pendingS, 0f); pendingS = null; } }
    }

    private void drain() {
        while (true) {
            float[] s; int r;
            synchronized (decLock) {
                s = pendingS; r = pendingR; pendingS = null;
                if (s == null) { decBusy = false; return; }
            }
            VoiceTemplates tv = tpl; String tg = target;
            if (tv != null && tg != null) {
                boolean accepted = false;
                try {
                    VoiceTemplates.Result mr = tv.match(s, tg);
                    lastFeats = autoLearn ? mr.feats : null;
                    accepted = mr.ok;
                    cb.onTemplate(mr.ok, tg, mr.info);
                } catch (Throwable ignored) { lastFeats = null; }
                if (accepted) { java.util.Arrays.fill(s, 0f); continue; }   // تم القبول بالقوالب: لا حاجة لـ Whisper
            } else lastFeats = null;
            String text = "";
            boolean failed = false;
            try {
                synchronized (recLock) {
                    OfflineRecognizer rc = getRec();
                    OfflineStream st = rc.createStream();
                    try { st.acceptWaveform(s, r); rc.decode(st); text = rc.getResult(st).getText(); }
                    finally { st.release(); }
                }
            } catch (Throwable t) {
                failed = true;
                cb.onError("تعذّر التعرف على الصوت: " + t.getClass().getSimpleName() + " " + t.getMessage());
            }
            java.util.Arrays.fill(s, 0f);
            if (failed) {
                synchronized (decLock) { decBusy = false; if (pendingS != null) java.util.Arrays.fill(pendingS, 0f); pendingS = null; }
                return;
            }
            if (text == null || text.trim().isEmpty()) cb.onNoSpeech(); else cb.onText(text);
        }
    }

    // ===== استماع مستمر: الميكروفون يبقى مفتوحًا طوال الجلسة (كان يُغلق ويُفتح بعد كل جملة فيضيع أول كلامك أو يفشل أحيانًا) =====
    private int sess = 0;                 // يُحمى بـ synchronized
    private boolean active = false;
    private volatile long muteUntil = 0L; // أثناء إعادة القط لا نلتقط (كي لا يسمع صوته هو)

    /** يكتم الالتقاط لمدة ms (0 = إلغاء الكتم فورًا). ينتهي تلقائيًا فلا يعلق أبدًا. */
    void mute(long ms) { muteUntil = ms <= 0 ? 0L : System.currentTimeMillis() + ms; }

    private synchronized boolean cur(int my) { return my == sess; }

    /** يبدأ جلسة استماع مستمرة؛ إن كانت تعمل فلا يفعل شيئًا. */
    synchronized void begin() {
        if (active) return;
        active = true;
        muteUntil = 0L;
        final int my = ++sess;
        exec.execute(() -> {
            try { runSession(my); }
            catch (Throwable t) { if (cur(my)) cb.onError("خطأ في الاستماع: " + t.getClass().getSimpleName() + " " + t.getMessage()); }
            finally {
                synchronized (OfflineAsr.this) { if (sess == my) active = false; }
                cb.onListening(false);
            }
        });
    }

    synchronized void stop() { active = false; sess++; lastFeats = null; }

    void shutdown() {
        stop();
        exec.shutdownNow();
        decExec.shutdownNow();
        synchronized (recLock) { try { if (rec != null) rec.release(); } catch (Throwable ignored) {} rec = null; }
    }

    private void runSession(final int my) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = null;
        for (int t = 0; t < 4 && cur(my); t++) {   // الجهاز قد لا يحرّر الميكروفون فورًا
            try { ar = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 16)); } catch (Throwable e) { ar = null; }
            if (ar != null && ar.getState() == AudioRecord.STATE_INITIALIZED) break;
            if (ar != null) { ar.release(); ar = null; }
            try { Thread.sleep(150); } catch (InterruptedException e) { return; }
        }
        if (ar == null) { if (cur(my)) cb.onError("تعذّر فتح الميكروفون."); return; }

        AutomaticGainControl agc = null;   // يرفع الأصوات الخافتة (أطفال/مسافة) على الأجهزة التي تدعمه
        try { if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(ar.getAudioSessionId()); if (agc != null) agc.setEnabled(true); } } catch (Throwable ignored) {}

        AcousticEchoCanceler aec = null;   // يمنع التقاط صوت القط نفسه من السماعة
        try { if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(ar.getAudioSessionId()); if (aec != null) aec.setEnabled(true); } } catch (Throwable ignored) {}

        final int pre = RATE * PREROLL_MS / 1000;
        float[] all = new float[RATE * (MAX_BUFFER_MS / 1000 + 2)];
        int len = 0;
        short[] buf = new short[CHUNK];
        double noise = 0.004;               // أرضية الضجيج: تُعاير في أول 0.4ث ثم تتبع الغرفة (تنخفض بسرعة وترتفع ببطء)
        int calib = 0; double calMin = 1e9;
        boolean speaking = false;
        int onRun = 0, offRun = 0, voicedMs = 0, silenceMs = 0, totalMs = 0, vowelMs = 0;
        double peak = 0;
        long lastLvl = 0; boolean wasMuted = false;

        try {
            ar.startRecording();
            cb.onListening(true);
            while (cur(my)) {
                int n = ar.read(buf, 0, CHUNK);
                if (n < 0) { cb.onError("مشكلة في الميكروفون."); return; }
                if (n == 0) continue;

                if (System.currentTimeMillis() < muteUntil) {      // القط يتكلم: تجاهل وابدأ من جديد
                    len = 0; speaking = false; onRun = 0; offRun = 0; voicedMs = 0; silenceMs = 0; totalMs = 0; vowelMs = 0; peak = 0;
                    if (!wasMuted) { cb.onLevel(-2); wasMuted = true; }
                    continue;
                }
                wasMuted = false;

                double sum = 0; int zc = 0; short prev = buf[0];
                for (int i = 0; i < n; i++) { float f = buf[i] / 32768f; sum += f * f; if (len < all.length) all[len++] = f; if (i > 0 && ((buf[i] >= 0) != (prev >= 0))) zc++; prev = buf[i]; }
                double rms = Math.sqrt(sum / n);
                double zcr = zc / (double) n;
                int ms = n * 1000 / RATE;
                long nowL = System.currentTimeMillis();
                if (nowL - lastLvl >= 90) { lastLvl = nowL; cb.onLevel((float) Math.max(-2, Math.min(10, -2 + rms * 120))); }

                if (calib < 8) {                                // معايرة الضجيج: أقل مستوى في أول 8 مقاطع (يتحمل بدء الكلام مبكرًا)
                    calMin = Math.min(calMin, rms); calib++;
                    if (calib == 8) noise = Math.max(0.002, calMin * 1.2);
                    if (len > pre) { System.arraycopy(all, len - pre, all, 0, pre); len = pre; }
                    continue;
                }
                double on = Math.max(onMin, noise * noiseMul);     // عتبة أعلى قليلًا: الضجيج العابر لا يبدأ التقاطًا
                double off = Math.max(0.004, noise * 1.8);

                if (!speaking) {
                    noise = rms < noise ? noise * 0.9 + rms * 0.1 : noise * 0.98 + rms * 0.02;
                    if (rms > on) { onRun++; offRun = 0; } else if (onRun > 0 && ++offRun <= 1) { /* نتحمل مقطعًا خافتًا واحدًا داخل الكلمة */ } else { onRun = 0; offRun = 0; }
                    if (onRun >= startChunks) { speaking = true; voicedMs = onRun * ms; silenceMs = 0; totalMs = onRun * ms; peak = rms; vowelMs = zcr < VOWEL_ZCR ? onRun * ms / 2 : 0; }
                    else if (len > pre) { System.arraycopy(all, len - pre, all, 0, pre); len = pre; }
                } else {
                    totalMs += ms;
                    peak = Math.max(peak * 0.995, rms);
                    if (rms > Math.max(off, peak * 0.15)) { voicedMs += ms; silenceMs = 0; if (zcr < VOWEL_ZCR) vowelMs += ms; } else silenceMs += ms;
                    if (silenceMs >= endSilenceMs || totalMs >= maxUtteranceMs || len >= all.length) {
                        // مرشّح قبل Whisper: صوت كافٍ، نسبته معقولة من المقطع، وفيه حرف علة. غير ذلك (طرقة، سعال، حفيف) يُرمى بلا فك ترميز.
                        boolean speechLike = voicedMs >= minVoicedMs && voicedMs >= totalMs * MIN_VOICED_RATIO && vowelMs >= minVowelMs;
                        if (speechLike) {
                            int end = Math.max(1, len - Math.max(0, silenceMs - 150) * RATE / 1000);
                            float[] samples = new float[end];
                            System.arraycopy(all, 0, samples, 0, end);
                            cb.onSamples(samples, RATE);
                        }
                        java.util.Arrays.fill(all, 0f);                        // لا يبقى تسجيل في المخزن
                        len = 0; speaking = false; onRun = 0; offRun = 0; voicedMs = 0; silenceMs = 0; totalMs = 0; vowelMs = 0; peak = 0;
                    }
                }
            }
        } finally {
            java.util.Arrays.fill(all, 0f);
            try { ar.stop(); } catch (Throwable ignored) {}
            try { if (agc != null) agc.release(); } catch (Throwable ignored) {}
            try { if (aec != null) aec.release(); } catch (Throwable ignored) {}
            ar.release();
        }
    }
}

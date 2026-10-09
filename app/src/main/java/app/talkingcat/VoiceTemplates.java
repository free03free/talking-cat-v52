package app.talkingcat;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * مطابقة صوت بصوت (بدون تحويل إلى نص): قوالب MFCC + DTW.
 * - يسجّل شخص كبير كل عنصر مرتين أو أكثر (حتى 6 أنطاق، لكل طريقة نطق صحيحة)؛ يُولَّد من كل تسجيل نسخ بنبرات أعلى لتقريب صوت الطفل (إعادة تعيين عينات).
 * - أثناء التدريب إن كان الهدف الحالي هو الأقرب بهامش واضح يُقبل فورًا دون Whisper؛ وإلا يُترك القرار لـ Whisper كما كان.
 * - عند نجاح الطفل عبر Whisper تُضاف بصمة صوته (ميزات رقمية فقط، لا تسجيل صوتي) كقالب تلقائي، فيتعلم التطبيق صوت كل طفل.
 */
final class VoiceTemplates {
    static final int RATE = 16000, FRAME = 400, HOP = 160, NFFT = 512, NMEL = 26, NCEP = 12;
    private static final float[] AUG = {0.92f, 1.0f, 1.15f, 1.30f};
    private static final int MAX_ADULT_TAKES = 6, MAX_AUTO = 8, MIN_KEYS = 5, MAX_FRAMES = 300;
    // صرامة القبول (0 متساهلة، 1 عادية، 2 صارمة): كلها لا تفعل إلا إرسال القرار إلى Whisper عند الشك
    private static final double[] MARGIN = {1.12, 1.25, 1.40};   // أقرب من أي عنصر آخر بهذه النسبة
    private static final double[] GATE_MUL = {3.0, 2.2, 1.6};    // المسافة المطلقة مقارنة بتشتت التسجيلات نفسها
    private static final double[] DUR_HI = {1.5, 1.35, 1.2};     // أطول مدة مقبولة نسبةً لأطول قالب
    private static final double[] DUR_LO = {0.6, 0.7, 0.8};      // أقصر مدة مقبولة نسبةً لأقصر قالب
    private volatile int strict = 1;
    private volatile double warnRatio = 1.25;                   // حد تحذير التشابه عند التسجيل (لا يؤثر على قبول الطفل)
    void setWarnRatio(double r) { warnRatio = Math.max(1.0, Math.min(2.0, r)); }
    void setStrict(int s) { strict = Math.max(0, Math.min(2, s)); }
    private double calibG = -1;                                 // تشتت التسجيلات المتكررة للعنصر نفسه (وسيط)

    static final class Tpl { final byte kind; final int grp; final float[][] f; Tpl(byte k, int g, float[][] f) { this.kind = k; this.grp = g; this.f = f; } }
    static final class Result { boolean ok, ready, synth; String info = "", oKey = ""; float[][] feats; double dt = -1, other = -1, ratio = 0; }

    private final File file;
    private final File dir;                       // معاينة تسجيلات الكبار (PCM 16 بت) لكل نطق
    private final Map<String, List<Tpl>> map = new HashMap<>();
    private final Map<String, List<Tpl>> synth = new HashMap<>();   // نماذج من صوت نطق القط للعناصر التي لا تسجيل لها (في الذاكرة فقط)
    private volatile boolean useModels = true;
    void setUseModels(boolean b) { useModels = b; }
    private final double[] win = new double[FRAME];
    private final double[][] melW = new double[NMEL][NFFT / 2 + 1];
    private final double[][] dct = new double[NCEP][NMEL];

    VoiceTemplates(Context ctx) {
        file = new File(ctx.getFilesDir(), "templates.bin");
        dir = new File(ctx.getFilesDir(), "enroll_audio");
        dir.mkdirs();
        for (int i = 0; i < FRAME; i++) win[i] = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (FRAME - 1));
        double lo = mel(80), hi = mel(7600);
        double[] pts = new double[NMEL + 2];
        for (int i = 0; i < pts.length; i++) pts[i] = Math.floor((NFFT + 1) * inv(lo + (hi - lo) * i / (NMEL + 1)) / RATE);
        for (int m = 1; m <= NMEL; m++)
            for (int k = 0; k <= NFFT / 2; k++) {
                if (k >= pts[m - 1] && k <= pts[m]) melW[m - 1][k] = (k - pts[m - 1]) / Math.max(1, pts[m] - pts[m - 1]);
                else if (k > pts[m] && k <= pts[m + 1]) melW[m - 1][k] = (pts[m + 1] - k) / Math.max(1, pts[m + 1] - pts[m]);
            }
        for (int c = 0; c < NCEP; c++) for (int m = 0; m < NMEL; m++) dct[c][m] = Math.cos(Math.PI * (c + 1) * (m + 0.5) / NMEL);
        load();
    }

    private static double mel(double f) { return 2595 * Math.log10(1 + f / 700); }
    private static double inv(double m) { return 700 * (Math.pow(10, m / 2595) - 1); }

    // ===== الميزات =====
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
                    double ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr;
                }
            }
        }
    }

    /** MFCC (c1..c12) بعد قص الصمت وتطبيع المتوسط والتباين. null إن كان المقطع قصيرًا أو صامتًا. */
    float[][] mfcc(float[] x) {
        if (x == null || x.length < FRAME + HOP * 8) return null;
        int nf = Math.min(600, (x.length - FRAME) / HOP + 1);
        float[][] c = new float[nf][NCEP];
        double[] le = new double[nf];
        double[] re = new double[NFFT], im = new double[NFFT], mel = new double[NMEL];
        for (int f = 0; f < nf; f++) {
            int o = f * HOP;
            Arrays.fill(re, 0); Arrays.fill(im, 0);
            for (int i = 0; i < FRAME; i++) { double prev = (o + i) > 0 ? x[o + i - 1] : 0; re[i] = (x[o + i] - 0.97 * prev) * win[i]; }
            fft(re, im);
            double tot = 0;
            double[] p = new double[NFFT / 2 + 1];
            for (int k = 0; k <= NFFT / 2; k++) { p[k] = re[k] * re[k] + im[k] * im[k]; tot += p[k]; }
            le[f] = Math.log(tot + 1e-10);
            for (int m = 0; m < NMEL; m++) { double s = 0; for (int k = 0; k <= NFFT / 2; k++) s += melW[m][k] * p[k]; mel[m] = Math.log(s + 1e-10); }
            for (int q = 0; q < NCEP; q++) { double s = 0; for (int m = 0; m < NMEL; m++) s += dct[q][m] * mel[m]; c[f][q] = (float) s; }
        }
        double mx = -1e9; for (double v : le) mx = Math.max(mx, v);
        if (mx < -12) return null;                       // صمت تقريبًا
        int a = 0, b = nf - 1;
        while (a < nf && le[a] < mx - 4.5) a++;
        while (b > a && le[b] < mx - 4.5) b--;
        a = Math.max(0, a - 3); b = Math.min(nf - 1, b + 3);
        int n = Math.min(MAX_FRAMES, b - a + 1);
        if (n < 8) return null;
        float[][] out = new float[n][NCEP];
        for (int i = 0; i < n; i++) out[i] = c[a + i];
        for (int q = 0; q < NCEP; q++) {
            double mean = 0; for (int i = 0; i < n; i++) mean += out[i][q]; mean /= n;
            double var = 0; for (int i = 0; i < n; i++) { double d = out[i][q] - mean; var += d * d; }
            double sd = Math.max(1e-2, Math.sqrt(var / n));
            for (int i = 0; i < n; i++) out[i][q] = (float) ((out[i][q] - mean) / sd);
        }
        return out;
    }

    private static float[] resample(float[] x, float f) {
        int n = (int) (x.length / f);
        float[] y = new float[n];
        for (int i = 0; i < n; i++) {
            double p = i * (double) f; int i0 = (int) p; float fr = (float) (p - i0);
            float a = x[Math.min(i0, x.length - 1)], b = i0 + 1 < x.length ? x[i0 + 1] : a;
            y[i] = a + (b - a) * fr;
        }
        return y;
    }

    private static double dtw(float[][] a, float[][] b) {
        int n = a.length, m = b.length;
        if (n > 2.2 * m || m > 2.2 * n) return 1e9;
        int band = Math.max(Math.abs(n - m) + 8, (int) (0.3 * Math.max(n, m)));
        final double INF = 1e18;
        double[] prev = new double[m + 1], cur = new double[m + 1];
        Arrays.fill(prev, INF); prev[0] = 0;
        for (int i = 1; i <= n; i++) {
            Arrays.fill(cur, INF);
            int c0 = (int) Math.round((double) i * m / n);
            int lo = Math.max(1, c0 - band), hi = Math.min(m, c0 + band);
            float[] x = a[i - 1];
            for (int j = lo; j <= hi; j++) {
                float[] y = b[j - 1];
                double s = 0; for (int q = 0; q < NCEP; q++) { double d = x[q] - y[q]; s += d * d; }
                double c = Math.sqrt(s / NCEP);
                double best = prev[j - 1] + 2 * c, v = prev[j] + c;
                if (v < best) best = v;
                v = cur[j - 1] + c; if (v < best) best = v;
                cur[j] = best;
            }
            double[] t = prev; prev = cur; cur = t;
        }
        return prev[m] / (n + m);
    }

    // ===== المطابقة =====
    private boolean hasAdult(String key) {
        List<Tpl> l = map.get(key);
        if (l != null) for (Tpl t : l) if (t.kind == 0) return true;
        return false;
    }

    /** قوالب العنصر الفعلية: تسجيلات الكبار (+ بصمات الطفل)، وإن لم يوجد تسجيل كبار فنموذج صوت القط معها. */
    private List<Tpl> eff(String key) {
        List<Tpl> r = map.get(key);
        if (useModels && !hasAdult(key)) {
            List<Tpl> sy = synth.get(key);
            if (sy != null) { List<Tpl> o = new ArrayList<>(sy); if (r != null) o.addAll(r); return o; }
        }
        return r == null ? new ArrayList<Tpl>() : r;
    }

    private java.util.Set<String> allKeys() {
        java.util.Set<String> k = new java.util.HashSet<>(map.keySet());
        if (useModels) k.addAll(synth.keySet());
        return k;
    }

    synchronized boolean ready() { int k = 0; for (String key : allKeys()) if (eff(key).size() >= 2) k++; return k >= MIN_KEYS; }

    /** يحسب ميزات المقطع دائمًا (للتعلّم التلقائي)، ويقرر القبول إن وُجدت قوالب كافية للهدف. */
    Result match(float[] samples, String target) {
        Result r = new Result();
        r.feats = mfcc(samples);
        if (r.feats == null) { r.info = "مقطع قصير أو صامت"; return r; }
        synchronized (this) {
            List<Tpl> tl = eff(target);
            if (tl.size() < 2) { r.info = "لا قوالب للهدف"; return r; }
            if (!ready()) { r.info = "قوالب غير كافية"; return r; }
            int sv = strict;
            boolean synT = useModels && !hasAdult(target) && synth.containsKey(target);   // الهدف يعتمد على صوت القط لا على تسجيل
            r.synth = synT;
            double dt = 1e9, other = 1e9; String oKey = "";
            List<Double> bests = new ArrayList<>();
            for (String key : allKeys()) {
                double best = 1e9;
                for (Tpl t : eff(key)) best = Math.min(best, dtw(r.feats, t.f));
                if (key.equals(target)) dt = best; else { bests.add(best); if (best < other) { other = best; oKey = key; } }
            }
            double ratio = other / Math.max(1e-9, dt);
            r.ready = true; r.dt = dt; r.other = other; r.oKey = oKey; r.ratio = ratio;
            // مدة الكلمة: «اثنا عشر» أطول بكثير من «عشرة»، فنرفض ما يخرج عن مدى قوالب الهدف
            int minL = Integer.MAX_VALUE, maxL = 0;
            for (Tpl t : tl) { minL = Math.min(minL, t.f.length); maxL = Math.max(maxL, t.f.length); }
            int n = r.feats.length;
            double hi = DUR_HI[sv] * (synT ? 1.25 : 1.0), lo = DUR_LO[sv] * (synT ? 0.8 : 1.0);   // الطفل يختلف إيقاعه عن القط، فنتساهل بالمدة
            boolean durOk = n <= maxL * hi && n >= minL * lo;
            double g = calib(), med = 1e9;
            boolean absOk;
            if (synT) {                                  // لا معايرة لنموذج القط: يجب أن يكون أقرب بوضوح من وسيط بقية العناصر
                java.util.Collections.sort(bests);
                med = bests.isEmpty() ? 1e9 : bests.get(bests.size() / 2);
                absOk = dt <= med * 0.9;
            } else absOk = g < 0 || dt <= g * GATE_MUL[sv];
            double need = MARGIN[sv];
            if (synT) need = Math.max(MARGIN[sv] * 1.15, 1.30);   // نموذج القط أقل دقة من تسجيل بشري: هامش أشد، وإلا يقرر Whisper
            boolean marOk = dt < 1e8 && ratio >= need;
            r.ok = marOk && durOk && absOk;
            java.util.Locale L = java.util.Locale.US;
            r.info = (synT ? "نموذج القط | " : "") + (r.ok ? "قبول" : "لا") + " | الهدف " + String.format(L, "%.2f", dt) + " مقابل " + oKey + " " + String.format(L, "%.2f", other)
                    + " (نسبة " + String.format(L, "%.2f/%.2f", ratio, need) + (marOk ? " ✓" : " ✗") + ") مدة " + n + "/" + minL + "-" + maxL + (durOk ? " ✓" : " ✗")
                    + (synT ? " وسيط " + String.format(L, "%.2f/%.2f", dt, med * 0.9) + (absOk ? " ✓" : " ✗")
                            : " مطلق " + (g < 0 ? "—" : String.format(L, "%.2f/%.2f", dt, g * GATE_MUL[sv]) + (absOk ? " ✓" : " ✗")));
            return r;
        }
    }

    // ===== نماذج من صوت نطق القط =====
    private static float[] to16k(float[] x, int rate) {
        if (rate == RATE) return x;
        float f = rate / (float) RATE;
        float[] src = x;
        if (f > 1.5f) {                                   // مرشح متوسط بسيط قبل خفض التردد لتقليل التداخل
            int w = Math.round(f); src = new float[x.length]; double acc = 0;
            for (int i = 0; i < x.length; i++) { acc += x[i]; if (i >= w) acc -= x[i - w]; src[i] = (float) (acc / Math.min(w, i + 1)); }
        }
        return resample(src, f);
    }

    /** يبني نموذجًا من عينات نطق القط لعنصر (بنبرات متعددة كالتسجيل البشري). لا يُحفظ على القرص. */
    void setSynth(String key, float[] pcm, int rate) {
        if (pcm == null || pcm.length < rate / 5) return;
        float[] x = to16k(pcm, rate);
        List<Tpl> l = new ArrayList<>();
        for (float f : AUG) { float[][] ft = mfcc(f == 1.0f ? x : resample(x, f)); if (ft != null) l.add(new Tpl((byte) 2, 1, ft)); }
        if (l.size() < 2) return;
        synchronized (this) { synth.put(key, l); }
    }

    synchronized void clearSynth() { synth.clear(); }
    synchronized boolean needsModel(String key) { return !hasAdult(key) && !synth.containsKey(key); }

    /** تشتت العنصر نفسه: أصغر مسافة بين تسجيلين مختلفين لكل عنصر، ثم الوسيط. -1 إن لم تكفِ البيانات. */
    private double calib() {
        if (calibG >= 0) return calibG;
        List<Double> v = new ArrayList<>();
        for (List<Tpl> l : map.values()) {
            double best = 1e9; int cnt = 0;
            for (int i = 0; i < l.size(); i++) for (int j = i + 1; j < l.size(); j++) {
                Tpl a = l.get(i), b = l.get(j);
                if (a.kind != 0 || b.kind != 0 || a.grp == b.grp) continue;
                if (Math.abs(a.f.length - b.f.length) > Math.max(a.f.length, b.f.length) / 6) continue;   // نفس النبرة تقريبًا
                best = Math.min(best, dtw(a.f, b.f)); cnt++;
            }
            if (cnt > 0 && best < 1e8) v.add(best);
        }
        if (v.size() < 3) return -1;
        java.util.Collections.sort(v);
        calibG = v.get(v.size() / 2);
        return calibG;
    }

    // ===== التسجيل (للكبار) والتعلّم =====
    private static int takeCount(List<Tpl> l) {
        java.util.HashSet<Integer> g = new java.util.HashSet<>();
        for (Tpl t : l) if (t.kind == 0) g.add(t.grp);
        return g.size();
    }

    private File pcmFile(String key, int grp) { return new File(dir, key.replace(':', '_') + "_" + grp + ".pcm"); }

    private void deletePcm(String key, int grp) {
        if (grp > 0) { pcmFile(key, grp).delete(); return; }
        File[] fs = dir.listFiles(); String pre = key.replace(':', '_') + "_";
        if (fs != null) for (File f : fs) if (f.getName().startsWith(pre)) f.delete();
    }

    /** قص الصمت من الطرفين (مع هامش 150م.ث) وتحويل إلى 16 بت للمعاينة. null إن كان صامتًا. */
    private static short[] trimPcm(float[] x) {
        int fr = RATE / 100, nf = x.length / fr;
        if (nf < 5) return null;
        double[] e = new double[nf]; double mx = 0;
        for (int f = 0; f < nf; f++) { double s = 0; for (int i = 0; i < fr; i++) { double v = x[f * fr + i]; s += v * v; } e[f] = Math.sqrt(s / fr); mx = Math.max(mx, e[f]); }
        if (mx < 1e-4) return null;
        int a = 0, b = nf - 1; double th = mx * 0.08;
        while (a < nf && e[a] < th) a++;
        while (b > a && e[b] < th) b--;
        a = Math.max(0, a - 15); b = Math.min(nf - 1, b + 15);
        int from = a * fr, to = Math.min(x.length, (b + 1) * fr);
        short[] out = new short[Math.max(0, to - from)];
        for (int i = 0; i < out.length; i++) out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(x[from + i] * 32767f)));
        return out;
    }

    private void writePcm(String key, int grp, short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(pcmFile(key, grp))))) {
            for (short v : pcm) o.writeShort(v);
        } catch (Throwable ignored) {}
    }

    /** تسجيل نطق محفوظ للمعاينة، أو null إن لم يوجد (تسجيلات الإصدارات القديمة). */
    synchronized short[] loadPreview(String key, int grp) {
        File f = pcmFile(key, grp);
        if (!f.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            short[] out = new short[(int) (f.length() / 2)];
            for (int i = 0; i < out.length; i++) out[i] = in.readShort();
            return out;
        } catch (Throwable t) { return null; }
    }

    /** يضيف نطقًا جديدًا لعنصر (مع نسخ مُحوَّلة النبرة) ويحفظ تسجيله للمعاينة. يعيد رقم النطق (>0) أو 0 إن كان التسجيل ضعيفًا. */
    synchronized int addEnroll(String key, float[] samples) {
        float[][] base = mfcc(samples);
        if (base == null) return 0;
        List<Tpl> l = map.get(key); if (l == null) { l = new ArrayList<>(); map.put(key, l); }
        if (takeCount(l) >= MAX_ADULT_TAKES) {               // الواجهة تمنع هذا؛ وللأمان نستبدل أقدم نطق
            int old = Integer.MAX_VALUE; for (Tpl q : l) if (q.kind == 0) old = Math.min(old, q.grp);
            final int og = old; Iterator<Tpl> it = l.iterator();
            while (it.hasNext()) { Tpl q = it.next(); if (q.kind == 0 && q.grp == og) it.remove(); }
            deletePcm(key, og);
        }
        int grpId = 0; for (Tpl q : l) if (q.kind == 0) grpId = Math.max(grpId, q.grp);
        grpId++;
        for (float f : AUG) {
            float[][] ft = f == 1.0f ? base : mfcc(resample(samples, f));
            if (ft != null) l.add(new Tpl((byte) 0, grpId, ft));
        }
        writePcm(key, grpId, trimPcm(samples));
        calibG = -1;
        save();
        return grpId;
    }

    /** يحذف نطقًا واحدًا (grp > 0) أو كل تسجيلات الكبار لعنصر (grp <= 0). بصمات الطفل التلقائية لا تُمسّ. */
    synchronized boolean removeTake(String key, int grp) {
        List<Tpl> l = map.get(key);
        if (l == null) return false;
        Iterator<Tpl> it = l.iterator();
        while (it.hasNext()) { Tpl t = it.next(); if (t.kind == 0 && (grp <= 0 || t.grp == grp)) it.remove(); }
        deletePcm(key, grp);
        if (l.isEmpty()) map.remove(key);
        calibG = -1;
        save();
        return true;
    }

    /** [{"g":1,"a":true},...] أنطاق الكبار المسجّلة للعنصر؛ a = توجد معاينة. */
    synchronized String takesJson(String key) {
        java.util.TreeSet<Integer> gs = new java.util.TreeSet<>();
        List<Tpl> l = map.get(key);
        if (l != null) for (Tpl t : l) if (t.kind == 0) gs.add(t.grp);
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (int g : gs) { if (!first) sb.append(','); first = false; sb.append("{\"g\":").append(g).append(",\"a\":").append(pcmFile(key, g).isFile()).append('}'); }
        return sb.append(']').toString();
    }

    synchronized void addAuto(String key, float[][] feats) {
        if (feats == null) return;
        List<Tpl> l = map.get(key); if (l == null) { l = new ArrayList<>(); map.put(key, l); }
        int n = 0; for (Tpl t : l) if (t.kind == 1) n++;
        if (n >= MAX_AUTO) { Iterator<Tpl> it = l.iterator(); while (it.hasNext()) if (it.next().kind == 1) { it.remove(); break; } }
        l.add(new Tpl((byte) 1, 0, feats));
        save();
    }

    synchronized void clear(boolean autoOnly) {
        if (autoOnly) {
            for (List<Tpl> l : map.values()) { Iterator<Tpl> it = l.iterator(); while (it.hasNext()) if (it.next().kind == 1) it.remove(); }
        } else {
            map.clear();
            File[] fs = dir.listFiles(); if (fs != null) for (File f : fs) f.delete();
        }
        calibG = -1;
        save();
    }

    /** {"l:3":2,...} عدد أنطاق الكبار لكل عنصر، و«auto» عدد القوالب التلقائية. */
    synchronized String statusJson() {
        StringBuilder sb = new StringBuilder("{");
        int auto = 0;
        for (Map.Entry<String, List<Tpl>> e : map.entrySet()) {
            int c = takeCount(e.getValue());
            if (c > 0) sb.append('"').append(e.getKey()).append("\":").append(c).append(',');
            for (Tpl t : e.getValue()) if (t.kind == 1) auto++;
        }
        sb.append("\"auto\":").append(auto).append(",\"synthKeys\":[");
        boolean f1 = true;
        if (useModels) for (String k : synth.keySet()) if (!hasAdult(k)) { if (!f1) sb.append(','); f1 = false; sb.append('"').append(k).append('"'); }
        sb.append("]}");
        return sb.toString();
    }

    interface LevelCb { void level(float v); }

    static float[] record(int ms) { return record(ms, false, null); }

    /**
     * يسجّل حتى ms ميلي ثانية من الميكروفون (حاجز) ويتجاهل أول 250م.ث (صوت النقر).
     * إن كان autoStop يتوقف بعد انتهاء الكلام بنحو 0.8 ثانية من الصمت، فلا ينتظر الزمن كله.
     * cb يُستدعى بمستوى الصوت (0..1) نحو 10 مرات في الثانية. null إن تعذّر الفتح.
     */
    static float[] record(int ms, boolean autoStop, LevelCb cb) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = null;
        for (int t = 0; t < 6; t++) {
            try { ar = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192)); } catch (Throwable e) { ar = null; }
            if (ar != null && ar.getState() == AudioRecord.STATE_INITIALIZED) break;
            if (ar != null) { ar.release(); ar = null; }
            try { Thread.sleep(200); } catch (InterruptedException e) { return null; }
        }
        if (ar == null) return null;
        AutomaticGainControl agc = null;
        try { if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(ar.getAudioSessionId()); if (agc != null) agc.setEnabled(true); } } catch (Throwable ignored) {}
        int total = RATE * ms / 1000, skip = RATE / 4;
        float[] out = new float[Math.max(0, total - skip)];
        short[] buf = new short[800];
        int pos = 0;
        try {
            ar.startRecording();
            int got = 0, buffers = 0;
            double noise = -1, thr = 0; int speechBuf = 0, quietRun = 0;
            while (got < total) {
                int n = ar.read(buf, 0, buf.length);
                if (n < 0) return null;
                double sum = 0;
                for (int i = 0; i < n && got < total; i++, got++) { sum += (double) buf[i] * buf[i]; if (got >= skip && pos < out.length) out[pos++] = buf[i] / 32768f; }
                double rms = n > 0 ? Math.sqrt(sum / n) / 32768.0 : 0;
                buffers++;
                if (cb != null && buffers % 2 == 0) { try { cb.level((float) Math.min(1.0, rms * 12)); } catch (Throwable ignored) {} }
                if (got <= skip) continue;
                if (noise < 0) { noise = rms; thr = Math.max(0.015, noise * 3.0); continue; }   // أول جزء بعد النقر = ضجيج الغرفة
                if (rms > thr) { speechBuf++; quietRun = 0; } else if (speechBuf > 0) quietRun++;
                else noise = Math.min(noise, rms);
                if (autoStop && speechBuf >= 3 && quietRun >= 16) break;                      // 16 × 50م.ث = 0.8 ثانية صمت
            }
        } finally {
            try { ar.stop(); } catch (Throwable ignored) {}
            try { if (agc != null) agc.release(); } catch (Throwable ignored) {}
            ar.release();
        }
        return pos == out.length ? out : Arrays.copyOf(out, pos);
    }

    // ===== التشابه بين العناصر =====
    /**
     * يقارن أحدث نطق مسجّل لعنصر بالعناصر الأخرى. {"own":x,"key":"l:3","d":y,"ratio":r,"warn":true}
     * own = أصغر مسافة بينه وبين أنطاقه الأخرى (-1 إن كان نطقًا وحيدًا)، d = أقرب عنصر آخر.
     * التحذير يعني أن النسبة أقل من الحد الذي يختاره المستخدم (الافتراضي 1.25).
     */
    String confusionJson(String key) {
        List<Tpl> mine = new ArrayList<>(), mineOther = new ArrayList<>();
        List<Map.Entry<String, List<Tpl>>> others = new ArrayList<>();
        synchronized (this) {
            List<Tpl> l = map.get(key);
            if (l == null) return "{}";
            int newest = 0; for (Tpl t : l) if (t.kind == 0) newest = Math.max(newest, t.grp);
            if (newest == 0) return "{}";
            for (Tpl t : l) if (t.kind == 0) { if (t.grp == newest) mine.add(t); else mineOther.add(t); }
            for (Map.Entry<String, List<Tpl>> e : map.entrySet()) if (!e.getKey().equals(key)) {
                List<Tpl> c = new ArrayList<>(); for (Tpl t : e.getValue()) if (t.kind == 0) c.add(t);
                if (!c.isEmpty()) others.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), c));
            }
        }
        double own = 1e9;
        for (Tpl a : mine) for (Tpl b : mineOther) own = Math.min(own, dtw(a.f, b.f));
        double best = 1e9; String bk = "";
        for (Map.Entry<String, List<Tpl>> e : others) {
            double d = 1e9;
            for (Tpl a : mine) for (Tpl b : e.getValue()) d = Math.min(d, dtw(a.f, b.f));
            if (d < best) { best = d; bk = e.getKey(); }
        }
        java.util.Locale L = java.util.Locale.US;
        boolean hasOwn = own < 1e8, hasOth = best < 1e8;
        double ratio = hasOwn && hasOth ? best / Math.max(1e-9, own) : 0;
        double th = warnRatio;
        boolean warn = hasOwn && hasOth && ratio < th;
        return "{\"own\":" + (hasOwn ? String.format(L, "%.3f", own) : "-1") + ",\"key\":\"" + bk + "\",\"d\":" + (hasOth ? String.format(L, "%.3f", best) : "-1")
                + ",\"ratio\":" + String.format(L, "%.3f", ratio) + ",\"th\":" + String.format(L, "%.2f", th) + ",\"warn\":" + warn + "}";
    }

    // ===== نسخة احتياطية =====
    private static final java.util.regex.Pattern PCM_NAME = java.util.regex.Pattern.compile("^[a-z]_\\d{1,4}_\\d{1,4}\\.pcm$");

    /** يكتب كل القوالب وتسجيلات المعاينة في ملف zip واحد. false إن لم توجد قوالب. */
    synchronized boolean exportTo(java.io.OutputStream os) throws java.io.IOException {
        if (map.isEmpty() || !file.isFile()) return false;
        java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(os);
        byte[] buf = new byte[16384];
        z.putNextEntry(new java.util.zip.ZipEntry("templates.bin"));
        try (FileInputStream in = new FileInputStream(file)) { int n; while ((n = in.read(buf)) > 0) z.write(buf, 0, n); }
        z.closeEntry();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) {
            if (!PCM_NAME.matcher(f.getName()).matches()) continue;
            z.putNextEntry(new java.util.zip.ZipEntry("enroll_audio/" + f.getName()));
            try (FileInputStream in = new FileInputStream(f)) { int n; while ((n = in.read(buf)) > 0) z.write(buf, 0, n); }
            z.closeEntry();
        }
        z.finish(); z.flush();
        return true;
    }

    private static byte[] readLimited(java.io.InputStream in, int limit) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[16384]; int n, tot = 0;
        while ((n = in.read(b)) > 0) { tot += n; if (tot > limit) throw new java.io.IOException("big"); bo.write(b, 0, n); }
        return bo.toByteArray();
    }

    /** يستبدل القوالب الحالية بمحتوى نسخة احتياطية بعد التحقق منها. false إن كان الملف غير صالح (ولا يتغير شيء). */
    synchronized boolean importFrom(java.io.InputStream is) {
        try {
            java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(is);
            byte[] bin = null; Map<String, byte[]> pcms = new HashMap<>();
            java.util.zip.ZipEntry e; int count = 0;
            while ((e = z.getNextEntry()) != null) {
                if (++count > 3000) return false;
                String nm = e.getName();
                if (nm.equals("templates.bin")) bin = readLimited(z, 80 << 20);
                else if (nm.startsWith("enroll_audio/") && PCM_NAME.matcher(nm.substring(13)).matches()) pcms.put(nm.substring(13), readLimited(z, 4 << 20));
            }
            if (bin == null) return false;
            File tmp = new File(file.getParentFile(), "templates.bin.new");
            try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(bin); }
            Map<String, List<Tpl>> m = parse(tmp);
            if (m == null || m.isEmpty()) { tmp.delete(); return false; }
            File[] old = dir.listFiles(); if (old != null) for (File f : old) f.delete();
            dir.mkdirs();
            for (Map.Entry<String, byte[]> pe : pcms.entrySet()) try (FileOutputStream fo = new FileOutputStream(new File(dir, pe.getKey()))) { fo.write(pe.getValue()); }
            file.delete();
            if (!tmp.renameTo(file)) { try (FileOutputStream fo = new FileOutputStream(file)) { fo.write(bin); } tmp.delete(); }
            map.clear(); map.putAll(m); calibG = -1;
            return true;
        } catch (Throwable t) { return false; }
    }

    // ===== التخزين =====
    private synchronized void save() {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            o.writeInt(2);
            o.writeInt(map.size());
            for (Map.Entry<String, List<Tpl>> e : map.entrySet()) {
                o.writeUTF(e.getKey());
                o.writeInt(takeCount(e.getValue()));
                o.writeInt(e.getValue().size());
                for (Tpl t : e.getValue()) {
                    o.writeByte(t.kind); o.writeInt(t.grp); o.writeInt(t.f.length);
                    for (float[] fr : t.f) for (float v : fr) o.writeFloat(v);
                }
            }
        } catch (Throwable ignored) {}
    }

    private synchronized void load() {
        Map<String, List<Tpl>> m = parse(file);
        if (m != null) map.putAll(m);
    }

    /** يقرأ ملف قوالب؛ null إن كان تالفًا أو غير معروف الإصدار. */
    private static Map<String, List<Tpl>> parse(File f) {
        if (f == null || !f.isFile()) return null;
        Map<String, List<Tpl>> out = new HashMap<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            int ver = in.readInt(); if (ver != 1 && ver != 2) return null;
            int nk = in.readInt();
            if (nk < 0 || nk > 500) return null;
            for (int k = 0; k < nk; k++) {
                String key = in.readUTF(); in.readInt(); int nt = in.readInt();
                if (nt < 0 || nt > 400) return null;
                List<Tpl> l = new ArrayList<>();
                for (int i = 0; i < nt; i++) {
                    byte kind = in.readByte(); int grp = ver >= 2 ? in.readInt() : (kind == 0 ? i / AUG.length + 1 : 0); int n = in.readInt();
                    if (n < 1 || n > MAX_FRAMES * 2) return null;
                    float[][] fr = new float[n][NCEP];
                    for (int a = 0; a < n; a++) for (int q = 0; q < NCEP; q++) fr[a][q] = in.readFloat();
                    l.add(new Tpl(kind, grp, fr));
                }
                out.put(key, l);
            }
            return out;
        } catch (Throwable t) { return null; }
    }
}

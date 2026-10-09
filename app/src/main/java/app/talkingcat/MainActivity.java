package app.talkingcat;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.Uri;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** الواجهة التعليمية: الأرقام والحروف والألوان مع النطق العربي المحلي. */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.local";
    private WebView web;
    private EchoEngine engine;
    private OfflineAsr asr;
    private VoiceTemplates tpl;
    private volatile boolean enrolling = false;
    private boolean listening = false;
    private boolean repeatMode = false; // جلسة التدريب فعّالة
    private volatile float speed = 0.95f;
    private volatile float volume = 1.0f;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int errCount = 0;
    private long lastLvl = 0;
    private static final int REQ_AUDIO = 42;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.setBackgroundColor(0xFFFFF4DC);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        engine = new EchoEngine(this, new EchoEngine.Callback() {
            @Override public void emit(String type, String payload) {
                runJs("window.onNativeEvent && window.onNativeEvent(" + org.json.JSONObject.quote(type) + "," + org.json.JSONObject.quote(payload) + ");");
            }
            @Override public void report(String title, String msg) { toastJs(title + ": " + msg); }
            @Override public void toast(String msg) { toastJs(msg); }
        });
        engine.prepare();
        asr = new OfflineAsr(this, new OfflineAsr.Listener() {
            @Override public void onListening(boolean on) { runJs("window.onNativeListening && window.onNativeListening(" + on + ");"); }
            @Override public void onLevel(float level) { runJs("window.onNativeLevel && window.onNativeLevel(" + level + ");"); }
            @Override public void onSamples(float[] smp, int rate) { h.post(() -> handleSamples(smp, rate)); }
            @Override public void onText(String text) { h.post(() -> handleText(text)); }
            @Override public void onNoSpeech() { h.post(() -> handleNoSpeech()); }
            @Override public void onTemplate(boolean ok, String key, String info) { h.post(() -> handleTemplate(ok, key, info)); }
            @Override public void onError(String msg) { h.post(() -> endSession(msg)); }
        });
        tpl = new VoiceTemplates(this);
        asr.setTemplates(tpl);
        asr.prepareTraining(null);   // تحميل Whisper وتسخينه في الخلفية عند فتح التطبيق، فلا ينتظر الطفل عند أول ضغطة

        web.addJavascriptInterface(new Bridge(), "TomNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return serve(r);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !HOST.equals(r.getUrl().getHost());
            }
        });
        web.loadUrl("https://" + HOST + "/index.html");
    }

    private class Bridge {
        @JavascriptInterface public void say(String text, double pitchFactor, int delayMs) {
            engine.say(text, (float)pitchFactor, delayMs, volume);
        }
        @JavascriptInterface public void startTraining() { runOnUiThread(() -> { repeatMode = true; errCount = 0; MainActivity.this.startListening(); }); }
        @JavascriptInterface public void stopTraining() { runOnUiThread(() -> { MainActivity.this.stopListening(); engine.stop(); }); }
        @JavascriptInterface public void setAsr(int sens, int minVoiced, int vowel, int endMs, int maxMs, int startMs) { if (asr != null) asr.setParams(sens, minVoiced, vowel, endMs, maxMs, startMs); }
        @JavascriptInterface public void setAsrEngine(String e) { if (asr != null) asr.setEngine(e); }
        @JavascriptInterface public void setTarget(String key) { if (asr != null) asr.setTarget(key); }
        @JavascriptInterface public void setTplStrict(int v) { if (asr != null) asr.setStrict(v); }
        @JavascriptInterface public void setTplWarn(int hundredths) { if (tpl != null) tpl.setWarnRatio(hundredths / 100.0); }
        @JavascriptInterface public void setAutoLearn(boolean on) { if (asr != null) asr.setAutoLearn(on); }
        @JavascriptInterface public void confirmLast(String key) { if (asr != null) asr.confirm(key); }
        @JavascriptInterface public String templateStatus() { return tpl == null ? "{}" : tpl.statusJson(); }
        @JavascriptInterface public void templatesClear(boolean autoOnly) { if (tpl != null) { tpl.clear(autoOnly); if (!autoOnly) MainActivity.this.scheduleModels(); } }
        @JavascriptInterface public void setModelTexts(String json) { MainActivity.this.setModelTexts(json); }
        @JavascriptInterface public void setTplModel(boolean on) { MainActivity.this.setUseModels(on); }
        @JavascriptInterface public void enrollRecord(String key) { runOnUiThread(() -> MainActivity.this.startCapture(key, false)); }
        @JavascriptInterface public String templateTakes(String key) { return tpl == null ? "[]" : tpl.takesJson(key); }
        @JavascriptInterface public void enrollDelete(String key, int grp) { if (tpl != null) { tpl.removeTake(key, grp); MainActivity.this.scheduleModels(); } }
        @JavascriptInterface public void enrollPlay(String key, int grp) { MainActivity.this.playTake(key, grp); }
        @JavascriptInterface public void enrollPlayStop() { MainActivity.this.stopPlayTake(); }
        @JavascriptInterface public void enrollTest(String key) { runOnUiThread(() -> MainActivity.this.startCapture(key, true)); }
        @JavascriptInterface public void enrollCheck(String key) { MainActivity.this.checkConfusion(key); }
        @JavascriptInterface public void backupExport() { runOnUiThread(() -> MainActivity.this.pickBackup(true)); }
        @JavascriptInterface public void backupImport() { runOnUiThread(() -> MainActivity.this.pickBackup(false)); }
        @JavascriptInterface public void setBusy(boolean on) { if (engine != null) engine.setPrefetchPaused(on); }
        @JavascriptInterface public void muteMic(int ms) { if (asr != null) asr.mute(ms); }
        @JavascriptInterface public void setSpeed(double v) { speed = (float) Math.max(0.6, Math.min(1.2, v)); }
        @JavascriptInterface public void setSystemVoice(boolean on) { engine.setUseSystem(on); MainActivity.this.voiceChanged(on ? 1 : 0, -2); }
        @JavascriptInterface public void setVoice(int v) { engine.setVoice(v); MainActivity.this.voiceChanged(-2, v); }
        @JavascriptInterface public void setSteps(int v) { engine.setSteps(v); }
        @JavascriptInterface public void prefetch(String json) {
            try {
                org.json.JSONArray a = new org.json.JSONArray(json);
                String[] t = new String[a.length()]; float[] sp = new float[a.length()];
                for (int i = 0; i < a.length(); i++) { org.json.JSONArray it = a.getJSONArray(i); t[i] = it.getString(0); sp[i] = (float) it.getDouble(1); }
                engine.prefetch(t, sp);
            } catch (Throwable ignored) {}
        }
        @JavascriptInterface public void setVolume(double v) { volume = (float) Math.max(0.0, Math.min(1.0, v)); }
    }

    private WebResourceResponse serve(WebResourceRequest r) {
        Uri u=r.getUrl();
        if(!HOST.equals(u.getHost())) return notFound();
        String path=u.getPath();
        if(path==null||path.equals("/")) path="/index.html";
        path=path.substring(1);
        if(path.contains("..")) return notFound();
        byte[] data;
        try{data=readAsset(path);}catch(IOException e){return notFound();}
        String mime=mimeOf(path);
        String enc=(mime.startsWith("text/")||mime.contains("json")||mime.contains("svg"))?"utf-8":null;
        Map<String,String> h=new HashMap<>();
        h.put("Cache-Control","no-cache");
        h.put("Content-Length",String.valueOf(data.length));
        return new WebResourceResponse(mime,enc,200,"OK",h,new ByteArrayInputStream(data));
    }

    private byte[] readAsset(String path)throws IOException{
        try(InputStream in=getAssets().open(path)){
            ByteArrayOutputStream out=new ByteArrayOutputStream(Math.max(1024,in.available()));
            byte[] buf=new byte[16384];int n;
            while((n=in.read(buf))>0)out.write(buf,0,n);
            return out.toByteArray();
        }
    }
    private WebResourceResponse notFound(){
        return new WebResourceResponse("text/plain","utf-8",404,"Not Found",
                new HashMap<String,String>(),new ByteArrayInputStream(new byte[0]));
    }
    private static String mimeOf(String p){
        String s=p.toLowerCase(Locale.ROOT);
        if(s.endsWith(".html"))return"text/html";
        if(s.endsWith(".js"))return"text/javascript";
        if(s.endsWith(".css"))return"text/css";
        if(s.endsWith(".json"))return"application/json";
        if(s.endsWith(".png"))return"image/png";
        if(s.endsWith(".webp"))return"image/webp";
        if(s.endsWith(".jpg")||s.endsWith(".jpeg"))return"image/jpeg";
        if(s.endsWith(".svg"))return"image/svg+xml";
        return"application/octet-stream";
    }
    @Override protected void onPause(){ stopListening(); runJs("window.onNativeSession && window.onNativeSession(false,'');"); engine.stop(); web.onPause(); super.onPause(); }
    @Override protected void onResume(){super.onResume();web.onResume();}
    @Override protected void onDestroy(){ stopListening(); if(asr!=null)asr.shutdown(); if(engine!=null)engine.shutdown();web.destroy();super.onDestroy();}

    private void toastJs(String msg) {
        runJs("window.onNativeToast && window.onNativeToast(" + org.json.JSONObject.quote(msg) + ");");
    }

    /** ينهي جلسة الاستماع ويخبر الواجهة بالسبب. */
    private void endSession(String msg) {
        keepAwake(false);
        listening = false;
        repeatMode = false;
        runJs("window.onNativeListening && window.onNativeListening(false);");
        runJs("window.onNativeSession && window.onNativeSession(false," + org.json.JSONObject.quote(msg) + ");");
    }

    private void retryLater(int ms) {
        h.postDelayed(() -> { if (repeatMode && !listening) startListening(); }, ms);
    }

    /** الشاشة تبقى مضاءة أثناء الاستماع/التدريب كي لا تنطفئ وتقطع الجلسة. */
    private void keepAwake(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void startListening() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        keepAwake(true);
        beginTraining();
    }

    /** وضع التدريب: يجهّز نموذج Whisper (أول مرة فقط) ثم يبدأ الاستماع المستمر. */
    private void beginTraining() {
        if (asr.recWarm()) { listening = true; asr.begin(); return; }   // النموذج محمَّل ومسخَّن: ابدأ فورًا
        runJs("window.onNativeTrainStatus && window.onNativeTrainStatus('prep');");
        asr.prepareTraining(() -> h.post(() -> {
            if (!repeatMode) return;
            if (!asr.trainingReady()) { endSession("نموذج التعرف على الكلام غير موجود داخل التطبيق. أعد بناء المشروع ليُنزَّل النموذج (fetch-engine.sh)."); return; }
            listening = true;
            asr.begin();
        }));
    }

    private void stopListening() {
        keepAwake(false);
        listening = false;
        repeatMode = false;
        h.removeCallbacksAndMessages(null);
        if (asr != null) asr.stop();
        runJs("window.onNativeListening && window.onNativeListening(false);");
    }

    private void handleNoSpeech() {
        // لا شيء مفهوم: نعيد الواجهة إلى «قل ما تراه» بصمت فلا تبقى على «لحظة…»
        if (!repeatMode) return;
        runJs("window.onNativeTrainStatus && window.onNativeTrainStatus('empty');");
    }

    private void handleSamples(float[] smp, int rate) {
        if (!repeatMode) { asr.mute(0); return; }

        // حاجز صارم ضد التداخل: بمجرد انتهاء التقاط جملة الطفل نوقف قبول أي صوت
        // جديد أثناء فك Whisper. سابقًا كان الميكروفون يبقى مفتوحًا، فيمكن أن
        // تدخل جملة ثانية أو صوت القط إلى طابور التعرف قبل ظهور النتيجة.
        asr.mute(15000);
        runJs("window.onNativeTrainStatus && window.onNativeTrainStatus('think');");
        asr.decode(smp, rate);
    }

    /** النص يذهب للمطابقة فقط ولا يُعرض ولا يُحفظ. */
    private void handleText(String text) {
        if (!repeatMode) return;
        runJs("window.onNativeHeard && window.onNativeHeard(" + org.json.JSONObject.quote(text) + ");");
    }


    private void handleTemplate(boolean ok, String key, String info) {
        if (!repeatMode) return;
        runJs("window.onNativeTemplate && window.onNativeTemplate(" + ok + "," + org.json.JSONObject.quote(key) + "," + org.json.JSONObject.quote(info) + ");");
    }

    /** تسجيل نطق لعنصر (للكبار) أو اختباره دون حفظ. يتوقف تلقائيًا بعد انتهاء الكلام (حتى 4 ثوانٍ). */
    private void startCapture(final String key, final boolean test) {
        if (enrolling) return;
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            enrollJs(key, "perm", 0);
            return;
        }
        stopListening();                 // نغلق جلسة التدريب إن كانت تعمل ليتحرر الميكروفون
        stopPlayTake();
        enrolling = true;
        enrollJs(key, "rec", 0);
        new Thread(() -> {
            String st = "err"; int takes = 0; String testJson = null; String conf = null;
            try {
                float[] smp = VoiceTemplates.record(4000, true, v -> runJs("window.onNativeLevel2 && window.onNativeLevel2(" + v + ");"));
                if (smp != null) {
                    if (test) {
                        VoiceTemplates.Result r = tpl.match(smp, key);
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("ok", r.ok); o.put("ready", r.ready); o.put("synth", r.synth); o.put("info", r.info);
                        o.put("dt", r.dt); o.put("other", r.other); o.put("ratio", r.ratio); o.put("oKey", r.oKey);
                        o.put("silent", r.feats == null);
                        testJson = o.toString(); st = "tested";
                    } else {
                        st = tpl.addEnroll(key, smp) > 0 ? "ok" : "weak";
                        if (st.equals("ok")) {
                            try { takes = new org.json.JSONObject(tpl.statusJson()).optInt(key, 0); } catch (Throwable ignored) {}
                            conf = tpl.confusionJson(key);
                        }
                    }
                    java.util.Arrays.fill(smp, 0f);
                }
            } catch (Throwable ignored) {}
            enrolling = false;
            runJs("window.onNativeLevel2 && window.onNativeLevel2(0);");
            if (testJson != null) runJs("window.onNativeTest && window.onNativeTest(" + org.json.JSONObject.quote(key) + "," + testJson + ");");
            else enrollJs(key, st, takes);
            if (conf != null) runJs("window.onNativeConfusion && window.onNativeConfusion(" + org.json.JSONObject.quote(key) + "," + conf + ");");
        }).start();
    }

    /** فحص التشابه عند الطلب (في خيط خلفي). */
    private void checkConfusion(final String key) {
        new Thread(() -> {
            String c = "{}";
            try { c = tpl.confusionJson(key); } catch (Throwable ignored) {}
            runJs("window.onNativeConfusion && window.onNativeConfusion(" + org.json.JSONObject.quote(key) + "," + c + ");");
        }).start();
    }

    // ===== نسخة احتياطية عبر منتقي ملفات النظام (لا تحتاج صلاحيات تخزين) =====
    private static final int REQ_EXPORT = 51, REQ_IMPORT = 52;

    private void pickBackup(boolean export) {
        try {
            if (export) {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                i.setType("application/zip");
                i.putExtra(android.content.Intent.EXTRA_TITLE, "talking-cat-templates.zip");
                startActivityForResult(i, REQ_EXPORT);
            } else {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_IMPORT);
            }
        } catch (Throwable t) { backupJs(export ? "export" : "import", false, "تعذّر فتح منتقي الملفات."); }
    }

    private void backupJs(String kind, boolean ok, String msg) {
        runJs("window.onNativeBackup && window.onNativeBackup(" + org.json.JSONObject.quote(kind) + "," + ok + "," + org.json.JSONObject.quote(msg) + ");");
    }

    @Override protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXPORT && req != REQ_IMPORT) return;
        if (res != RESULT_OK || data == null || data.getData() == null) return;   // أُلغي
        final Uri uri = data.getData(); final boolean export = req == REQ_EXPORT;
        new Thread(() -> {
            boolean ok = false; String msg;
            try {
                if (export) {
                    try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                        ok = os != null && tpl.exportTo(os);
                    }
                    msg = ok ? "تم حفظ النسخة الاحتياطية." : "لا توجد قوالب لتصديرها.";
                } else {
                    try (InputStream is = getContentResolver().openInputStream(uri)) {
                        ok = is != null && tpl.importFrom(is);
                    }
                    msg = ok ? "تم استيراد القوالب." : "الملف غير صالح؛ لم يتغير شيء.";
                    if (ok) scheduleModels();
                }
            } catch (Throwable t) { msg = export ? "تعذّر حفظ الملف." : "تعذّر قراءة الملف."; }
            backupJs(export ? "export" : "import", ok, msg);
        }).start();
    }

    // ===== نماذج من صوت نطق القط للعناصر غير المسجّلة =====
    private volatile java.util.Map<String, String> modelTexts = new java.util.HashMap<>();
    private volatile boolean useModels = true, modelsAgain = false;
    private final java.util.concurrent.atomic.AtomicBoolean modelsRun = new java.util.concurrent.atomic.AtomicBoolean(false);
    private int lastSys = -1, lastVoice = -1;

    private void setModelTexts(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            java.util.Map<String, String> m = new java.util.HashMap<>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); String v = o.optString(k, ""); if (!v.isEmpty()) m.put(k, v); }
            modelTexts = m;
        } catch (Throwable ignored) { return; }
        scheduleModels();
    }

    private void setUseModels(boolean on) {
        useModels = on;
        if (tpl != null) tpl.setUseModels(on);
        if (on) scheduleModels();
    }

    /** تغيّر الصوت (أو صوت النظام): النماذج القديمة لم تعد تمثّل ما يسمعه الطفل، فنعيد بناءها. */
    private synchronized void voiceChanged(int sys, int voice) {
        boolean ch = false;
        if (sys != -2 && sys != lastSys) { lastSys = sys; ch = true; }
        if (voice != -2 && voice != lastVoice) { lastVoice = voice; ch = true; }
        if (ch && tpl != null) { tpl.clearSynth(); scheduleModels(); }
    }

    /** يبني في خيط خلفي هادئ نموذجًا لكل عنصر بلا تسجيل. يتأخر أثناء نطق القط، ويعيد المحاولة إن لم يجهز الصوت بعد. */
    private void scheduleModels() {
        if (tpl == null || engine == null || !useModels || modelTexts.isEmpty()) return;
        modelsAgain = true;
        if (!modelsRun.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            int attempts = 0;
            try {
                while (modelsAgain && attempts < 8) {
                    modelsAgain = false;
                    boolean failed = false;
                    for (java.util.Map.Entry<String, String> e : new java.util.ArrayList<>(modelTexts.entrySet())) {
                        if (!useModels) break;
                        if (!tpl.needsModel(e.getKey())) continue;
                        int guard = 0;
                        while (engine.busy() && guard++ < 100) Thread.sleep(300);
                        int[] rate = {22050};
                        float[] smp = engine.modelSamples(e.getValue(), speed, rate);
                        if (smp == null) { failed = true; break; }
                        tpl.setSynth(e.getKey(), smp, rate[0]);
                        Thread.sleep(40);
                    }
                    if (failed) { attempts++; Thread.sleep(5000); modelsAgain = true; }
                }
            } catch (Throwable ignored) {}
            modelsRun.set(false);
            if (modelsAgain && useModels && attempts < 8) scheduleModels();
        }, "tpl-models");
        t.setPriority(Thread.MIN_PRIORITY); t.setDaemon(true); t.start();
    }

    private volatile android.media.AudioTrack playTrack;
    private volatile int playToken = 0;

    /** معاينة نطق مسجَّل للكبار. تنتهي بنداء window.onNativePlay(false). */
    private void playTake(final String key, final int grp) {
        stopPlayTake();
        final int token = ++playToken;
        new Thread(() -> {
            android.media.AudioTrack t = null;
            try {
                short[] pcm = tpl == null ? null : tpl.loadPreview(key, grp);
                if (pcm == null || pcm.length == 0) { runJs("window.onNativePlay && window.onNativePlay(false,'none');"); return; }
                t = new android.media.AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(VoiceTemplates.RATE)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.length * 2).setTransferMode(android.media.AudioTrack.MODE_STATIC).build();
                t.write(pcm, 0, pcm.length);
                playTrack = t;
                t.play();
                long end = System.currentTimeMillis() + pcm.length * 1000L / VoiceTemplates.RATE + 150;
                while (System.currentTimeMillis() < end && token == playToken) Thread.sleep(40);
            } catch (Throwable ignored) {
            } finally {
                if (t != null) { try { t.stop(); } catch (Throwable ignored) {} try { t.release(); } catch (Throwable ignored) {} }
                if (playTrack == t) playTrack = null;
                if (token == playToken) runJs("window.onNativePlay && window.onNativePlay(false,'done');");
            }
        }).start();
    }

    private void stopPlayTake() {
        playToken++;
        android.media.AudioTrack t = playTrack;
        if (t != null) { try { t.stop(); } catch (Throwable ignored) {} }
    }

    private void enrollJs(String key, String st, int takes) {
        runJs("window.onNativeEnroll && window.onNativeEnroll(" + org.json.JSONObject.quote(key) + "," + org.json.JSONObject.quote(st) + "," + takes + ");");
    }

    private void runJs(String js) { if (web != null) web.post(() -> web.evaluateJavascript(js, null)); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_AUDIO) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) { if (repeatMode) startListening(); }
        else endSession("لم تُمنح صلاحية الميكروفون. فعّلها من إعدادات الجهاز ← التطبيقات ← قط متكلم ← الأذونات.");
    }
}

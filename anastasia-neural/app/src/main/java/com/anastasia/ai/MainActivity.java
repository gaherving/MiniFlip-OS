package com.anastasia.ai;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.*;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.webkit.*;
import android.widget.Toast;
import android.database.Cursor;
import org.json.JSONObject;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final String MODEL_NAME = "Qwen_Qwen3-4B-Q4_K_M.gguf";
    private static final String MODEL_URL = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-GGUF/resolve/main/Qwen_Qwen3-4B-Q4_K_M.gguf?download=true";
    private static final long MODEL_READY_BYTES = 2_200_000_000L;
    private static final int VOICE_REQ = 9021;

    private WebView web;
    private TextToSpeech tts;
    private final ExecutorService brainExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean modelLoaded = false;
    private volatile boolean modelLoading = false;
    private final InternalBuilderServer builderServer = new InternalBuilderServer(18765);
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("anastasia.native", MODE_PRIVATE);
        builderServer.start();
        setupTts();
        setupWeb();
        brainExecutor.execute(() -> {
            try { NativeBrain.nativeInit(); } catch (Throwable ignored) {}
            if (modelFile().length() >= MODEL_READY_BYTES) ensureBrainLoaded();
            else maybeStartModelDownload(false);
        });
    }

    private void setupWeb() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AnastasiaNative");
        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(i);
            } catch (Throwable e) {
                Toast.makeText(this, "No pude abrir la descarga: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("es", "MX"));
                tts.setSpeechRate(1.0f);
                tts.setPitch(1.0f);
            }
        });
    }

    private File modelFile() {
        File root = getExternalFilesDir(null);
        if (root == null) root = getFilesDir();
        File dir = new File(root, "models");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, MODEL_NAME);
    }

    private boolean isUnmetered() {
        try {
            ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (Throwable e) { return false; }
    }

    private synchronized void maybeStartModelDownload(boolean allowMetered) {
        File f = modelFile();
        if (f.length() >= MODEL_READY_BYTES) return;
        long old = prefs.getLong("brainDownloadId", -1);
        DownloadManager dm = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) return;
        if (old > 0) {
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(old))) {
                if (c != null && c.moveToFirst()) {
                    int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PENDING || st == DownloadManager.STATUS_PAUSED) return;
                    if (st == DownloadManager.STATUS_SUCCESSFUL && f.length() >= MODEL_READY_BYTES) return;
                }
            } catch (Throwable ignored) {}
        }
        if (!allowMetered && !isUnmetered()) return;
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(MODEL_URL));
            req.setTitle("Anastasia · descargando cerebro neuronal");
            req.setDescription("Modelo conversacional local 4B");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (!allowMetered) req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
            req.setAllowedOverMetered(allowMetered);
            req.setAllowedOverRoaming(false);
            req.setDestinationInExternalFilesDir(this, null, "models/" + MODEL_NAME);
            long id = dm.enqueue(req);
            prefs.edit().putLong("brainDownloadId", id).apply();
        } catch (Throwable ignored) {}
    }

    private String brainStatusJson() {
        try {
            File f = modelFile();
            if (f.length() >= MODEL_READY_BYTES) {
                return new JSONObject().put("state", modelLoaded ? "ready" : "downloaded")
                        .put("bytes", f.length()).put("total", f.length()).put("percent", 100)
                        .put("model", "Qwen3 4B local").toString();
            }
            long id = prefs.getLong("brainDownloadId", -1);
            DownloadManager dm = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            if (id > 0 && dm != null) {
                try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                    if (c != null && c.moveToFirst()) {
                        int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long sofar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        int pc = total > 0 ? (int)Math.min(100, sofar * 100L / total) : 0;
                        String name = st == DownloadManager.STATUS_RUNNING ? "downloading" : st == DownloadManager.STATUS_PENDING ? "pending" : st == DownloadManager.STATUS_PAUSED ? "paused" : st == DownloadManager.STATUS_FAILED ? "failed" : "waiting";
                        return new JSONObject().put("state", name).put("bytes", sofar).put("total", total).put("percent", pc).put("model", "Qwen3 4B local").toString();
                    }
                }
            }
            return new JSONObject().put("state", isUnmetered() ? "not_started" : "waiting_wifi")
                    .put("percent", 0).put("model", "Qwen3 4B local").toString();
        } catch (Throwable e) {
            return "{\"state\":\"error\",\"percent\":0}";
        }
    }

    private synchronized boolean ensureBrainLoaded() {
        if (modelLoaded) return true;
        if (modelLoading) return false;
        File f = modelFile();
        if (f.length() < MODEL_READY_BYTES) return false;
        modelLoading = true;
        try {
            int rc = NativeBrain.nativeLoad(f.getAbsolutePath());
            modelLoaded = rc == 0;
            return modelLoaded;
        } catch (Throwable e) {
            return false;
        } finally { modelLoading = false; }
    }

    private void js(String code) {
        if (web == null) return;
        web.post(() -> web.evaluateJavascript(code, null));
    }

    private void deliverBrain(String id, String answer, String error) {
        String code = "window.__nativeBrainResult && window.__nativeBrainResult(" + JSONObject.quote(id) + "," +
                (answer == null ? "null" : JSONObject.quote(answer)) + "," +
                (error == null ? "null" : JSONObject.quote(error)) + ");";
        js(code);
    }

    public final class Bridge {
        @JavascriptInterface public String brainStatus() {
            String result = brainStatusJson();
            File f = modelFile();
            if (f.length() >= MODEL_READY_BYTES && !modelLoaded && !modelLoading) brainExecutor.execute(MainActivity.this::ensureBrainLoaded);
            return result;
        }

        @JavascriptInterface public void requestBrainDownload() {
            brainExecutor.execute(() -> maybeStartModelDownload(true));
        }

        @JavascriptInterface public String modelInfo() {
            try { return NativeBrain.nativeModelInfo(); } catch (Throwable e) { return "cerebro neuronal"; }
        }

        @JavascriptInterface public void askAsync(String prompt, String requestId) {
            brainExecutor.execute(() -> {
                if (modelFile().length() < MODEL_READY_BYTES) {
                    maybeStartModelDownload(false);
                    deliverBrain(requestId, null, "El cerebro neuronal aún se está descargando. CodeBrain puede seguir trabajando mientras termina.");
                    return;
                }
                if (!ensureBrainLoaded()) {
                    deliverBrain(requestId, null, "No pude cargar el cerebro neuronal local.");
                    return;
                }
                try {
                    String out = NativeBrain.generate(prompt, 520, 0.72f).trim();
                    if (out.isEmpty()) throw new IllegalStateException("respuesta vacía");
                    deliverBrain(requestId, out, null);
                } catch (Throwable e) {
                    deliverBrain(requestId, null, "Error neuronal: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface public void speak(String text) {
            runOnUiThread(() -> {
                if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "anastasia");
            });
        }

        @JavascriptInterface public void stopSpeaking() {
            runOnUiThread(() -> { if (tts != null) tts.stop(); });
        }

        @JavascriptInterface public void listen() {
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 9022);
                        Toast.makeText(MainActivity.this, "Autoriza el micrófono y vuelve a tocarlo para hablar con Anastasia.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX");
                    i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla con Anastasia");
                    startActivityForResult(i, VOICE_REQ);
                } catch (Throwable e) {
                    js("window.__nativeSpeechError && window.__nativeSpeechError(" + JSONObject.quote(e.getMessage()) + ");");
                }
            });
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_REQ && resultCode == RESULT_OK && data != null) {
            ArrayList<String> xs = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (xs != null && !xs.isEmpty()) {
                js("window.__nativeSpeechResult && window.__nativeSpeechResult(" + JSONObject.quote(xs.get(0)) + ");");
            }
        }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) {}
        try { NativeBrain.nativeUnload(); } catch (Throwable ignored) {}
        brainExecutor.shutdownNow();
        super.onDestroy();
    }
}

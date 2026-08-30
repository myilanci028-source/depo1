package com.mubel.kantar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int PICK_LOGO = 501;
    private WebView web;
    private final List<WebView> printViews = new ArrayList<>();
    private TcpClient tcpClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(17,18,20));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setDefaultTextEncodingName("UTF-8");
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                return "app".equals(u.getScheme());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                js("(function(){var v=document.querySelectorAll('.ver');for(var i=0;i<v.length;i++){v[i].textContent='v2.4 · Android · DENSİ CX Native Parser';}})();");
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void js(String script) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(script, null); });
    }

    private String q(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    public class Bridge {
        @JavascriptInterface public void connect(String host, int port) {
            disconnect();
            tcpClient = new TcpClient(host, port);
            tcpClient.start();
        }
        @JavascriptInterface public void disconnect() {
            if (tcpClient != null) { tcpClient.stopClient(); tcpClient = null; }
            js("window.MUBEL&&window.MUBEL.nativeStatus('KAPALI','');");
        }
        @JavascriptInterface public void pickLogo() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(i, PICK_LOGO);
            });
        }
        @JavascriptInterface public void printHtml(String html, String mode) {
            runOnUiThread(() -> openPrint(html, mode));
        }
        @JavascriptInterface public String appVersion() { return "2.4.0"; }
    }

    private class TcpClient extends Thread {
        private final String host;
        private final int port;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private Socket socket;
        private final Pattern tokenPattern = Pattern.compile("=([0-9]{8}|[0-9]{7}[+\\-])");
        private String tail = "";
        private boolean waitingWeight = false;
        private Integer lastWeight = null;
        private int sameCount = 0;

        TcpClient(String host, int port) {
            this.host = host;
            this.port = port;
        }

        void stopClient() {
            running.set(false);
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }

        private Integer decodeToken(String token) {
            try {
                if (token.matches("[0-9]{8}")) {
                    return Integer.parseInt(new StringBuilder(token).reverse().toString());
                }
                if (token.matches("[0-9]{7}[+\\-]")) {
                    char sign = token.charAt(7);
                    int v = Integer.parseInt(new StringBuilder(token.substring(0, 7)).reverse().toString());
                    return sign == '-' ? -v : v;
                }
            } catch (Exception ignored) {}
            return null;
        }

        private String syntheticFrame(int kg) {
            String normal = String.format(Locale.US, "%07d", Math.abs(kg));
            String reversed = new StringBuilder(normal).reverse().toString();
            return "=" + reversed + (kg < 0 ? "-" : "0");
        }

        private void sendWeight(int kg) {
            if (kg < 0 || kg > 10000 || (kg % 2 != 0)) return;

            if (lastWeight != null && lastWeight == kg) sameCount++;
            else { lastWeight = kg; sameCount = 1; }

            String frame = syntheticFrame(kg);
            String b64 = Base64.encodeToString(frame.getBytes(StandardCharsets.ISO_8859_1), Base64.NO_WRAP);
            js("window.MUBEL&&window.MUBEL.onRawB64(" + q(b64) + ");");
            js("(function(){var e=document.getElementById('frameInfo');if(e)e.textContent='Native DENSİ: " + kg + " kg · paket 1 sonrası ilk alan · tekrar " + sameCount + "';})();");
        }

        private void parseChunk(byte[] data, int n) {
            String chunk = new String(data, 0, n, StandardCharsets.ISO_8859_1);
            tail += chunk;
            if (tail.length() > 4096) tail = tail.substring(tail.length() - 4096);

            Matcher m = tokenPattern.matcher(tail);
            int consumed = 0;
            while (m.find()) {
                consumed = m.end();
                Integer v = decodeToken(m.group(1));
                if (v == null) continue;

                if (v == 1) {
                    waitingWeight = true;
                    continue;
                }

                if (waitingWeight) {
                    waitingWeight = false;
                    sendWeight(v);
                }
            }

            if (consumed > 0) tail = tail.substring(consumed);
            else if (tail.length() > 80) {
                int p = tail.lastIndexOf('=');
                tail = p >= 0 ? tail.substring(p) : tail.substring(tail.length() - 16);
            }
        }

        @Override public void run() {
            try {
                js("window.MUBEL&&window.MUBEL.nativeStatus('BAĞLANIYOR'," + q(host + ":" + port) + ");");
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 5000);
                socket.setSoTimeout(1500);
                js("window.MUBEL&&window.MUBEL.nativeStatus('BAĞLI'," + q(host + ":" + port) + ");");

                InputStream in = socket.getInputStream();
                byte[] buf = new byte[4096];
                while (running.get()) {
                    try {
                        int n = in.read(buf);
                        if (n < 0) break;
                        if (n == 0) continue;
                        parseChunk(buf, n);
                    } catch (java.net.SocketTimeoutException ignored) {}
                }
            } catch (Exception e) {
                js("window.MUBEL&&window.MUBEL.nativeStatus('HATA'," + q(e.getClass().getSimpleName() + ": " + e.getMessage()) + ");");
            } finally {
                stopClient();
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_LOGO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) throw new Exception("Dosya açılamadı");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                byte[] bytes = out.toByteArray();
                String type = getContentResolver().getType(uri);
                if (type == null || !type.startsWith("image/")) type = "image/png";
                String dataUrl = "data:" + type + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
                js("window.MUBEL&&window.MUBEL.setLogo(" + q(dataUrl) + ");");
            } catch (Exception e) {
                js("window.MUBEL&&window.MUBEL.toast(" + q("Logo okunamadı: " + e.getMessage()) + ");");
            }
        }
    }

    private void openPrint(String html, String mode) {
        final WebView pv = new WebView(this);
        printViews.add(pv);
        WebSettings ps = pv.getSettings();
        ps.setJavaScriptEnabled(false);
        ps.setDefaultTextEncodingName("UTF-8");
        pv.setWebViewClient(new WebViewClient() {
            private boolean done = false;
            @Override public void onPageFinished(WebView view, String url) {
                if (done) return;
                done = true;
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                String jobName = mode != null && mode.startsWith("70") ? "MUBEL KANTAR 70mm" : "MUBEL KANTAR A4";
                PrintDocumentAdapter adapter = pv.createPrintDocumentAdapter(jobName);
                PrintAttributes.Builder b = new PrintAttributes.Builder()
                        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                        .setResolution(new PrintAttributes.Resolution("mubel", "MUBEL", 300, 300));
                if (mode != null && mode.startsWith("70")) {
                    PrintAttributes.MediaSize custom = new PrintAttributes.MediaSize("MUBEL70", "70 mm Kantar", 2756, 7874);
                    b.setMediaSize(custom);
                    b.setMinMargins(new PrintAttributes.Margins(120,120,120,120));
                } else {
                    b.setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape());
                    b.setMinMargins(new PrintAttributes.Margins(250,250,250,250));
                }
                pm.print(jobName, adapter, b.build());
                pv.postDelayed(() -> { printViews.remove(pv); pv.destroy(); }, 60000);
            }
        });
        pv.loadDataWithBaseURL("https://mubel.local/", html, "text/html", "UTF-8", null);
    }

    @Override protected void onDestroy() {
        if (tcpClient != null) tcpClient.stopClient();
        if (web != null) web.destroy();
        for (WebView v : printViews) {
            try { v.destroy(); } catch (Exception ignored) {}
        }
        printViews.clear();
        super.onDestroy();
    }
}

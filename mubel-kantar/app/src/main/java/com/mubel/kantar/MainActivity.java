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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
                try {
                    view.evaluateJavascript(
                            "(function(){document.querySelectorAll('.ver').forEach(function(e){e.textContent=e.textContent.replace(/v2\\.[0-9]+/g,'v2.9');});})();",
                            null);
                } catch (Exception ignored) {}
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
            runOnUiThread(() -> openPrint(prepareHtmlForV29(html, mode), mode));
        }
        @JavascriptInterface public String appVersion() { return "2.9.0"; }
    }

    private class TcpClient extends Thread {
        private final String host; private final int port;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private Socket socket;
        TcpClient(String host, int port) { this.host = host; this.port = port; }
        void stopClient() {
            running.set(false);
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
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
                        byte[] data = new byte[n];
                        System.arraycopy(buf, 0, data, 0, n);
                        String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
                        js("window.MUBEL&&window.MUBEL.onRawB64(" + q(b64) + ");");
                    } catch (java.net.SocketTimeoutException ignored) {}
                }
            } catch (Exception e) {
                js("window.MUBEL&&window.MUBEL.nativeStatus('HATA'," + q(e.getClass().getSimpleName() + ": " + e.getMessage()) + ");");
            } finally { stopClient(); }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_LOGO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) throw new Exception("Dosya açılamadı");
                byte[] buf = new byte[8192]; int n;
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

    // V2.9: A4 sağ üst iletişim bloğu artık ayarlardan bağımsız ve garanti şekilde basılır.
    // Düzen: TARTIM RAPORU > Fiş > Tarih > Telefon > Web.
    private String prepareHtmlForV29(String html, String mode) {
        if (html == null) return "";

        String out = html
                .replace("MUBEL KANTAR v2.7", "MUBEL KANTAR v2.9")
                .replace("MUBEL KANTAR v2.8", "MUBEL KANTAR v2.9");

        if (mode != null && mode.startsWith("70")) return out;

        final String phone = "0530 962 67 93";
        final String website = "https://www.yilancioglu.com.tr/";

        // A4 sol firma bloğunda varsa iletişim satırlarını kaldır; sadece sağ üstte kalsın.
        out = out.replace("<br>" + phone, "");
        out = out.replace("<br>" + website, "");

        int titlePos = out.indexOf("TARTIM RAPORU");
        if (titlePos < 0) return out;

        int smallStart = out.indexOf("<small", titlePos);
        if (smallStart < 0) return out;
        int smallOpenEnd = out.indexOf('>', smallStart);
        if (smallOpenEnd < 0) return out;
        int smallEnd = out.indexOf("</small>", smallOpenEnd);
        if (smallEnd < 0) return out;

        String currentRightBlock = out.substring(smallOpenEnd + 1, smallEnd);
        if (currentRightBlock.contains(phone) || currentRightBlock.contains("yilancioglu.com.tr")) {
            return out;
        }

        String contact = "<br><span class=\"reportContactV29\" "
                + "style=\"display:inline-block;margin-top:5px;font-size:9px;font-weight:600;"
                + "line-height:1.35;text-align:right;white-space:nowrap\">"
                + phone + "<br>" + website + "</span>";

        return out.substring(0, smallEnd) + contact + out.substring(smallEnd);
    }

    // Sahada doğrulanan sade Android WebView yazdırma yolu korunur.
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
                try {
                    PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                    if (pm == null) throw new IllegalStateException("Android yazdırma servisi bulunamadı");
                    boolean is70 = mode != null && mode.startsWith("70");
                    String jobName = is70 ? "MUBEL KANTAR 70mm" : "MUBEL KANTAR A4";
                    PrintDocumentAdapter adapter = pv.createPrintDocumentAdapter(jobName);
                    PrintAttributes.Builder b = new PrintAttributes.Builder()
                            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                            .setResolution(new PrintAttributes.Resolution("mubel", "MUBEL", 300, 300));
                    if (is70) {
                        PrintAttributes.MediaSize custom = new PrintAttributes.MediaSize("MUBEL70", "70 mm Kantar", 2756, 7874);
                        b.setMediaSize(custom);
                        b.setMinMargins(new PrintAttributes.Margins(120,120,120,120));
                    } else {
                        b.setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape());
                        b.setMinMargins(new PrintAttributes.Margins(250,250,250,250));
                    }
                    pm.print(jobName, adapter, b.build());
                    js("window.MUBEL_PRINT_NATIVE_OK&&window.MUBEL_PRINT_NATIVE_OK();");
                    pv.postDelayed(() -> { printViews.remove(pv); try { pv.destroy(); } catch (Exception ignored) {} }, 60000);
                } catch (Exception e) {
                    js("window.MUBEL&&window.MUBEL.toast(" + q("Yazdırma önizlemesi açılamadı: " + e.getMessage()) + ");");
                }
            }
        });
        pv.loadDataWithBaseURL("https://mubel.local/", html, "text/html", "UTF-8", null);
    }

    @Override protected void onDestroy() {
        if (tcpClient != null) tcpClient.stopClient();
        if (web != null) web.destroy();
        for (WebView v : printViews) { try { v.destroy(); } catch (Exception ignored) {} }
        printViews.clear(); super.onDestroy();
    }
}

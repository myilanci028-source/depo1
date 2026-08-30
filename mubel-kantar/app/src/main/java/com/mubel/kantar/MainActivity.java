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
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void js(String script) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(script, null);
        });
    }

    private String q(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    public class Bridge {
        @JavascriptInterface
        public void connect(String host, int port) {
            disconnect();
            tcpClient = new TcpClient(host, port);
            tcpClient.start();
        }

        @JavascriptInterface
        public void disconnect() {
            if (tcpClient != null) {
                tcpClient.stopClient();
                tcpClient = null;
            }
            js("window.MUBEL&&window.MUBEL.nativeStatus('KAPALI','');");
        }

        @JavascriptInterface
        public void pickLogo() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(i, PICK_LOGO);
            });
        }

        @JavascriptInterface
        public void printHtml(String html, String mode) {
            if (html == null || html.trim().isEmpty()) {
                js("window.MUBEL&&window.MUBEL.toast('Yazdırılacak belge boş');");
                return;
            }
            runOnUiThread(() -> {
                try {
                    openPrint(html, mode);
                } catch (Exception e) {
                    js("window.MUBEL&&window.MUBEL.toast(" + q("Yazdırma açılamadı: " + e.getMessage()) + ");");
                }
            });
        }

        @JavascriptInterface
        public String appVersion() {
            return "2.7.0";
        }
    }

    private class TcpClient extends Thread {
        private final String host;
        private final int port;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private Socket socket;

        TcpClient(String host, int port) {
            this.host = host;
            this.port = port;
        }

        void stopClient() {
            running.set(false);
            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {}
        }

        @Override
        public void run() {
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
            } finally {
                stopClient();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_LOGO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            try (InputStream in = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
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
        final AtomicBoolean printStarted = new AtomicBoolean(false);
        printViews.add(pv);

        WebSettings ps = pv.getSettings();
        ps.setJavaScriptEnabled(true);
        ps.setDomStorageEnabled(true);
        ps.setAllowFileAccess(true);
        ps.setAllowContentAccess(true);
        ps.setDefaultTextEncodingName("UTF-8");

        pv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Eski sürüm callback'i beklediği için bazı Android cihazlarda burada takılıyordu.
                // Artık sığdırma JS'i ateşleniyor ama yazdırma onun callback'ine bağlı değil.
                pv.postDelayed(() -> {
                    try {
                        pv.evaluateJavascript(buildFitJavascript(mode), null);
                    } catch (Exception ignored) {}
                }, 120);
                pv.postDelayed(() -> startPrintJobOnce(pv, mode, printStarted), 520);
            }
        });

        pv.loadDataWithBaseURL("https://mubel.local/", html, "text/html", "UTF-8", null);

        // onPageFinished bazı WebView sürümlerinde gelmezse dahi önizleme açılacak güvenlik ağı.
        pv.postDelayed(() -> startPrintJobOnce(pv, mode, printStarted), 1800);
    }

    private void startPrintJobOnce(WebView pv, String mode, AtomicBoolean printStarted) {
        if (!printStarted.compareAndSet(false, true)) return;
        try {
            startPrintJob(pv, mode);
        } catch (Exception e) {
            printStarted.set(false);
            js("window.MUBEL&&window.MUBEL.toast(" + q("Yazdırma önizlemesi açılamadı: " + e.getMessage()) + ");");
        }
    }

    private String buildFitJavascript(String mode) {
        boolean is70 = mode != null && mode.startsWith("70");
        String width = is70 ? "62mm" : "279mm";
        String height = "192mm";
        String page = is70 ? "70mm 200mm" : "A4 landscape";
        String margin = is70 ? "3mm" : "5mm";
        String minFont = is70 ? "5" : "6";
        return "(function(){try{" +
                "var b=document.body,h=document.head;" +
                "if(!b||b.getAttribute('data-mubel-fit')==='1')return 'ready';" +
                "b.setAttribute('data-mubel-fit','1');" +
                "var st=document.createElement('style');" +
                "st.textContent='@page{size:" + page + ";margin:" + margin + "}' +" +
                "'html,body{margin:0!important;padding:0!important;width:" + width + "!important;height:" + height + "!important;overflow:hidden!important;}' +" +
                "'.mubelFitPage{position:relative!important;width:" + width + "!important;height:" + height + "!important;overflow:hidden!important;}' +" +
                "'.mubelFitContent{position:absolute!important;left:0!important;top:0!important;width:100%!important;transform-origin:top left!important;}' +" +
                "'.mubelFitContent .note,.mubelFitContent p,.mubelFitContent .item,.mubelFitContent .row b,.mubelFitContent .route,.mubelFitContent .foot,.mubelFitContent .verify{overflow-wrap:anywhere!important;word-break:break-word!important;}';" +
                "h.appendChild(st);" +
                "var pg=document.createElement('div'),ct=document.createElement('div');" +
                "pg.className='mubelFitPage';ct.className=(b.className?b.className+' ':'')+'mubelFitContent';" +
                "while(b.firstChild)ct.appendChild(b.firstChild);" +
                "pg.appendChild(ct);b.appendChild(pg);" +
                "var n=ct.querySelector('.note')||ct.querySelector('p');" +
                "if(n){var fs=parseFloat(getComputedStyle(n).fontSize)||12;" +
                "while(ct.scrollHeight>pg.clientHeight&&fs>" + minFont + "){fs=Math.max(" + minFont + ",fs-0.5);n.style.fontSize=fs+'px';}}" +
                "var pw=pg.clientWidth,ph=pg.clientHeight,cw=Math.max(ct.scrollWidth,ct.offsetWidth),ch=Math.max(ct.scrollHeight,ct.offsetHeight);" +
                "var sc=Math.min(1,pw/Math.max(1,cw),ph/Math.max(1,ch));" +
                "if(!isFinite(sc)||sc<=0)sc=1;ct.style.transform='scale('+sc+')';" +
                "return sc.toFixed(4);" +
                "}catch(e){return 'err:'+e.message;}})()";
    }

    private void startPrintJob(WebView pv, String mode) {
        if (isFinishing() || pv == null) return;
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (pm == null) throw new IllegalStateException("Android yazdırma servisi bulunamadı");

        boolean is70 = mode != null && mode.startsWith("70");
        String jobName = is70 ? "MUBEL KANTAR 70mm" : "MUBEL KANTAR A4";
        PrintDocumentAdapter adapter = pv.createPrintDocumentAdapter(jobName);
        PrintAttributes.Builder b = new PrintAttributes.Builder()
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .setResolution(new PrintAttributes.Resolution("mubel", "MUBEL", 300, 300));

        if (is70) {
            PrintAttributes.MediaSize custom = new PrintAttributes.MediaSize(
                    "MUBEL70", "70 mm Kantar", 2756, 7874);
            b.setMediaSize(custom);
            b.setMinMargins(new PrintAttributes.Margins(80, 80, 80, 80));
        } else {
            b.setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape());
            b.setMinMargins(new PrintAttributes.Margins(180, 180, 180, 180));
        }

        pm.print(jobName, adapter, b.build());
        pv.postDelayed(() -> {
            printViews.remove(pv);
            try {
                pv.destroy();
            } catch (Exception ignored) {}
        }, 60000);
    }

    @Override
    protected void onDestroy() {
        if (tcpClient != null) tcpClient.stopClient();
        if (web != null) web.destroy();
        for (WebView v : printViews) {
            try {
                v.destroy();
            } catch (Exception ignored) {}
        }
        printViews.clear();
        super.onDestroy();
    }
}

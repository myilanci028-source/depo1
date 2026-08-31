package com.mubel.kantar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;

public class MainActivity extends Activity {
    private static final int PICK_LOGO = 501;
    private static final String PREF_MAIL = "mubel_mail_v210";
    private static final String DEFAULT_RECIPIENT = "yilancioglu_merkez@yilancioglu.com.tr";
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
                            "(function(){document.querySelectorAll('.ver').forEach(function(e){e.textContent=e.textContent.replace(/v2\\.[0-9]+/g,'v2.10');});})();",
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
            runOnUiThread(() -> openPrint(prepareHtmlForV210(html, mode), mode));
        }

        @JavascriptInterface public boolean saveMailSettings(String json) {
            try {
                JSONObject o = new JSONObject(json == null ? "{}" : json);
                SharedPreferences p = getSharedPreferences(PREF_MAIL, MODE_PRIVATE);
                SharedPreferences.Editor e = p.edit();
                e.putString("provider", o.optString("provider", "guzel"));
                e.putString("host", o.optString("host", ""));
                e.putInt("port", o.optInt("port", 465));
                e.putString("security", o.optString("security", "ssl"));
                e.putString("sender", o.optString("sender", ""));
                e.putString("username", o.optString("username", ""));
                e.putString("fromName", o.optString("fromName", "YILANCIOĞLU KANTAR"));
                e.putString("defaultRecipient", o.optString("defaultRecipient", DEFAULT_RECIPIENT));
                String pass = o.optString("password", "");
                if (!pass.isEmpty()) e.putString("password", pass);
                e.apply();
                return true;
            } catch (Exception ex) {
                return false;
            }
        }

        @JavascriptInterface public String getMailSettings() {
            try {
                SharedPreferences p = getSharedPreferences(PREF_MAIL, MODE_PRIVATE);
                JSONObject o = new JSONObject();
                o.put("provider", p.getString("provider", "guzel"));
                o.put("host", p.getString("host", "mail.yilancioglu.com.tr"));
                o.put("port", p.getInt("port", 465));
                o.put("security", p.getString("security", "ssl"));
                o.put("sender", p.getString("sender", ""));
                o.put("username", p.getString("username", ""));
                o.put("fromName", p.getString("fromName", "YILANCIOĞLU KANTAR"));
                o.put("defaultRecipient", p.getString("defaultRecipient", DEFAULT_RECIPIENT));
                o.put("hasPassword", !p.getString("password", "").isEmpty());
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface public void sendMailPdf(String html, String recipient, String subject, String body, String fisNo) {
            runOnUiThread(() -> createPdfAndSend(prepareHtmlForV210(html, "A4"), recipient, subject, body, fisNo));
        }

        @JavascriptInterface public String appVersion() { return "2.10.0"; }
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

    private String prepareHtmlForV210(String html, String mode) {
        if (html == null) return "";
        String out = html
                .replace("MUBEL KANTAR v2.7", "MUBEL KANTAR v2.10")
                .replace("MUBEL KANTAR v2.8", "MUBEL KANTAR v2.10")
                .replace("MUBEL KANTAR v2.9", "MUBEL KANTAR v2.10");
        if (mode != null && mode.startsWith("70")) return out;

        final String phone = "0530 962 67 93";
        final String website = "https://www.yilancioglu.com.tr/";
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

        String right = out.substring(smallOpenEnd + 1, smallEnd);
        if (!right.contains(phone)) {
            String contact = "<br><span style=\"display:inline-block;margin-top:5px;font-size:9px;font-weight:600;line-height:1.35;text-align:right;white-space:nowrap\">"
                    + phone + "<br>" + website + "</span>";
            out = out.substring(0, smallEnd) + contact + out.substring(smallEnd);
        }
        return out;
    }

    private PrintAttributes a4Attributes() {
        return new PrintAttributes.Builder()
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .setResolution(new PrintAttributes.Resolution("mubel", "MUBEL", 300, 300))
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                .setMinMargins(new PrintAttributes.Margins(250,250,250,250))
                .build();
    }

    private void createPdfAndSend(String html, String recipient, String subject, String body, String fisNo) {
        if (recipient == null || !recipient.contains("@")) {
            js("window.MUBEL_MAIL_STATUS&&window.MUBEL_MAIL_STATUS('HATA','Geçerli alıcı e-posta adresi giriniz.');");
            return;
        }
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
                    File dir = new File(getCacheDir(), "mailpdf");
                    if (!dir.exists()) dir.mkdirs();
                    String safeFis = sanitizeFileName(fisNo == null ? "kantar" : fisNo);
                    File pdf = new File(dir, "MUBEL_KANTAR_" + safeFis + "_" + System.currentTimeMillis() + ".pdf");
                    PrintDocumentAdapter adapter = pv.createPrintDocumentAdapter("MUBEL KANTAR A4");
                    PrintAttributes attrs = a4Attributes();
                    adapter.onLayout(null, attrs, new CancellationSignal(), new PrintDocumentAdapter.LayoutResultCallback() {
                        @Override public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                            try {
                                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pdf,
                                        ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE);
                                adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, pfd, new CancellationSignal(), new PrintDocumentAdapter.WriteResultCallback() {
                                    @Override public void onWriteFinished(PageRange[] pages) {
                                        try { pfd.close(); } catch (Exception ignored) {}
                                        cleanupPrintView(pv, 1500);
                                        new Thread(() -> sendSmtp(pdf, recipient, subject, body, safeFis)).start();
                                    }
                                    @Override public void onWriteFailed(CharSequence error) {
                                        try { pfd.close(); } catch (Exception ignored) {}
                                        cleanupPrintView(pv, 500);
                                        mailError("PDF oluşturulamadı: " + (error == null ? "bilinmeyen hata" : error));
                                    }
                                    @Override public void onWriteCancelled() {
                                        try { pfd.close(); } catch (Exception ignored) {}
                                        cleanupPrintView(pv, 500);
                                        mailError("PDF oluşturma iptal edildi.");
                                    }
                                });
                            } catch (Exception e) {
                                cleanupPrintView(pv, 500);
                                mailError("PDF dosyası açılamadı: " + e.getMessage());
                            }
                        }
                        @Override public void onLayoutFailed(CharSequence error) {
                            cleanupPrintView(pv, 500);
                            mailError("PDF yerleşimi hazırlanamadı: " + (error == null ? "bilinmeyen hata" : error));
                        }
                        @Override public void onLayoutCancelled() {
                            cleanupPrintView(pv, 500);
                            mailError("PDF hazırlama iptal edildi.");
                        }
                    }, null);
                } catch (Exception e) {
                    cleanupPrintView(pv, 500);
                    mailError("PDF hazırlanamadı: " + e.getMessage());
                }
            }
        });
        pv.loadDataWithBaseURL("https://mubel.local/", html, "text/html", "UTF-8", null);
    }

    private void sendSmtp(File pdf, String recipient, String subject, String body, String safeFis) {
        SharedPreferences p = getSharedPreferences(PREF_MAIL, MODE_PRIVATE);
        String host = p.getString("host", "mail.yilancioglu.com.tr");
        int port = p.getInt("port", 465);
        String security = p.getString("security", "ssl");
        String sender = p.getString("sender", "");
        String username = p.getString("username", "");
        String password = p.getString("password", "");
        String fromName = p.getString("fromName", "YILANCIOĞLU KANTAR");

        if (host.isEmpty() || sender.isEmpty() || username.isEmpty() || password.isEmpty()) {
            mailError("Admin > Ayarlar bölümünde SMTP sunucu, gönderen adresi, kullanıcı adı ve şifreyi kaydedin.");
            return;
        }

        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", String.valueOf(port));
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.connectiontimeout", "15000");
            props.put("mail.smtp.timeout", "25000");
            props.put("mail.smtp.writetimeout", "25000");
            if ("ssl".equalsIgnoreCase(security)) {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.socketFactory.port", String.valueOf(port));
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                props.put("mail.smtp.socketFactory.fallback", "false");
            } else if ("starttls".equalsIgnoreCase(security)) {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }

            Session session = Session.getInstance(props, new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(sender, fromName, "UTF-8"));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient, false));
            message.setSubject(subject == null ? "Kantar Fişi" : subject, "UTF-8");

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(body == null ? "Merhaba;\nKantar fişiniz ektedir." : body, "UTF-8");

            MimeBodyPart pdfPart = new MimeBodyPart();
            pdfPart.attachFile(pdf);
            pdfPart.setFileName(MimeUtility.encodeText("Kantar_Fisi_" + safeFis + ".pdf", "UTF-8", null));
            pdfPart.setHeader("Content-Type", "application/pdf; name=\"Kantar_Fisi_" + safeFis + ".pdf\"");

            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(textPart);
            multipart.addBodyPart(pdfPart);
            message.setContent(multipart);
            message.saveChanges();
            Transport.send(message);

            try { pdf.delete(); } catch (Exception ignored) {}
            js("window.MUBEL_MAIL_STATUS&&window.MUBEL_MAIL_STATUS('OK','PDF kantar fişi başarıyla " + escapeJsText(recipient) + " adresine gönderildi.');");
        } catch (Exception e) {
            mailError("Mail gönderilemedi: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private String sanitizeFileName(String s) {
        String v = s == null ? "kantar" : s.replaceAll("[^A-Za-z0-9._-]", "_");
        return v.isEmpty() ? "kantar" : v;
    }

    private String escapeJsText(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ");
    }

    private void mailError(String message) {
        js("window.MUBEL_MAIL_STATUS&&window.MUBEL_MAIL_STATUS('HATA'," + q(message) + ");");
    }

    private void cleanupPrintView(WebView pv, long delay) {
        runOnUiThread(() -> pv.postDelayed(() -> {
            printViews.remove(pv);
            try { pv.destroy(); } catch (Exception ignored) {}
        }, delay));
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
                    cleanupPrintView(pv, 60000);
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

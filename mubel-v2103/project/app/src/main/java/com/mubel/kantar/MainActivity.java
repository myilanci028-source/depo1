package com.mubel.kantar;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

public class MainActivity extends Activity {
    private static final int REQ_LOGO = 4104;
    private static final String DEFAULT_MAIL = "yilancioglu_merkez@yilancioglu.com.tr";
    private static final int A4_CSS_WIDTH = 1123;
    private static final int A4_CSS_HEIGHT = 794;
    private static final int A4_PDF_WIDTH = 842;
    private static final int A4_PDF_HEIGHT = 595;

    private FrameLayout root;
    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final AtomicInteger connectionGeneration = new AtomicInteger(0);
    private final AtomicBoolean mailBusy = new AtomicBoolean(false);
    private volatile Socket scaleSocket;
    private SharedPreferences mailPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Mail PDF'sinde tüm A4 dokümanın görünmeyen kısmının da Canvas'a çizilebilmesini sağlar.
        WebView.enableSlowWholeDocumentDraw();
        mailPrefs = getSharedPreferences("mubel_mail_v2104", MODE_PRIVATE);
        root = new FrameLayout(this);
        web = new WebView(this);
        configureWebView(web);
        web.addJavascriptInterface(new AndroidBridge(), "Android");
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void configureWebView(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setDefaultTextEncodingName("UTF-8");
        w.setBackgroundColor(Color.rgb(15,16,18));
        w.setWebChromeClient(new WebChromeClient());
        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (view == web) {
                    view.evaluateJavascript("document.querySelectorAll('.ver').forEach(function(e){e.textContent=e.textContent.replace(/v2\\.10\\.3/g,'v2.10.4');});", null);
                }
            }
        });
    }

    private void js(String script) {
        main.post(() -> {
            if (web != null) web.evaluateJavascript(script, null);
        });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    private void scaleStatus(String status, String detail) {
        js("window.MUBEL&&MUBEL.nativeStatus(" + q(status) + "," + q(detail) + ");");
    }

    private void mailStatus(String status, String message) {
        js("window.MUBEL_MAIL_STATUS&&window.MUBEL_MAIL_STATUS(" + q(status) + "," + q(message) + ");");
    }

    private void testStatus(String status, String message) {
        js("window.MUBEL_MAIL_TEST_STATUS&&window.MUBEL_MAIL_TEST_STATUS(" + q(status) + "," + q(message) + ");");
    }

    private void disconnectScale(boolean notify) {
        connectionGeneration.incrementAndGet();
        Socket s = scaleSocket;
        scaleSocket = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
        if (notify) scaleStatus("KAPALI", "Bağlantı kesildi");
    }

    private void connectScale(final String host, final int port) {
        disconnectScale(false);
        final int generation = connectionGeneration.incrementAndGet();
        scaleStatus("BAĞLANIYOR", host + ":" + port);
        io.execute(() -> {
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(host, port), 5500);
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                if (generation != connectionGeneration.get()) {
                    try { s.close(); } catch (Exception ignored) {}
                    return;
                }
                scaleSocket = s;
                scaleStatus("BAĞLI", host + ":" + port);
                InputStream in = s.getInputStream();
                byte[] buf = new byte[2048];
                while (generation == connectionGeneration.get() && !s.isClosed()) {
                    int read = in.read(buf);
                    if (read < 0) break;
                    if (read == 0) continue;
                    String b64 = Base64.encodeToString(buf, 0, read, Base64.NO_WRAP);
                    js("window.MUBEL&&MUBEL.onRawB64(" + q(b64) + ");");
                }
                if (generation == connectionGeneration.get()) scaleStatus("KAPALI", "Kantar bağlantısı kapandı");
            } catch (Exception e) {
                if (generation == connectionGeneration.get()) scaleStatus("HATA", friendly(e));
            } finally {
                if (s != null) try { s.close(); } catch (Exception ignored) {}
                if (scaleSocket == s) scaleSocket = null;
            }
        });
    }

    private String friendly(Throwable e) {
        String m = e == null ? "Bilinmeyen hata" : e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return m;
    }

    private JSONObject readMailConfig() {
        JSONObject o = new JSONObject();
        try {
            o.put("provider", mailPrefs.getString("provider", "guzel"));
            o.put("host", mailPrefs.getString("host", "mt-compile.guzelhosting.com"));
            o.put("port", mailPrefs.getInt("port", 465));
            o.put("security", mailPrefs.getString("security", "ssl"));
            o.put("sender", mailPrefs.getString("sender", DEFAULT_MAIL));
            o.put("username", mailPrefs.getString("username", DEFAULT_MAIL));
            o.put("fromName", mailPrefs.getString("fromName", "YILANCIOĞLU KANTAR"));
            o.put("defaultRecipient", mailPrefs.getString("defaultRecipient", DEFAULT_MAIL));
            o.put("hasPassword", !mailPrefs.getString("password", "").isEmpty());
        } catch (Exception ignored) {}
        return o;
    }

    private boolean saveMailConfig(String json) {
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences.Editor ed = mailPrefs.edit();
            ed.putString("provider", o.optString("provider", "guzel"));
            ed.putString("host", o.optString("host", "mt-compile.guzelhosting.com").trim());
            ed.putInt("port", o.optInt("port", 465));
            ed.putString("security", o.optString("security", "ssl"));
            ed.putString("sender", o.optString("sender", DEFAULT_MAIL).trim());
            ed.putString("username", o.optString("username", DEFAULT_MAIL).trim());
            ed.putString("fromName", o.optString("fromName", "YILANCIOĞLU KANTAR").trim());
            ed.putString("defaultRecipient", o.optString("defaultRecipient", DEFAULT_MAIL).trim());
            String pass = o.optString("password", "");
            if (!pass.isEmpty()) ed.putString("password", pass);
            return ed.commit();
        } catch (Exception e) {
            return false;
        }
    }

    private MailAccount account() throws Exception {
        MailAccount a = new MailAccount();
        a.host = mailPrefs.getString("host", "mt-compile.guzelhosting.com");
        a.port = mailPrefs.getInt("port", 465);
        a.security = mailPrefs.getString("security", "ssl");
        a.sender = mailPrefs.getString("sender", DEFAULT_MAIL);
        a.username = mailPrefs.getString("username", DEFAULT_MAIL);
        a.password = mailPrefs.getString("password", "");
        a.fromName = mailPrefs.getString("fromName", "YILANCIOĞLU KANTAR");
        if (a.host.trim().isEmpty() || a.port <= 0 || a.sender.trim().isEmpty() || a.username.trim().isEmpty()) {
            throw new Exception("SMTP ayarları eksik");
        }
        if (a.password.isEmpty()) throw new Exception("SMTP şifresi kayıtlı değil");
        return a;
    }

    private Session sessionFor(final MailAccount a) {
        Properties p = new Properties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.host", a.host);
        p.put("mail.smtp.port", String.valueOf(a.port));
        p.put("mail.smtp.connectiontimeout", "8000");
        p.put("mail.smtp.timeout", "12000");
        p.put("mail.smtp.writetimeout", "12000");
        p.put("mail.smtp.quitwait", "false");
        if ("ssl".equalsIgnoreCase(a.security)) {
            p.put("mail.smtp.ssl.enable", "true");
            p.put("mail.smtp.ssl.checkserveridentity", "true");
        } else if ("starttls".equalsIgnoreCase(a.security)) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        return Session.getInstance(p, new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(a.username, a.password);
            }
        });
    }

    private MimeMessage baseMessage(Session session, MailAccount a, String to, String subject) throws Exception {
        MimeMessage m = new MimeMessage(session);
        m.setFrom(new InternetAddress(a.sender, a.fromName, "UTF-8"));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
        m.setSubject(subject, "UTF-8");
        m.setSentDate(new Date());
        return m;
    }

    private void sendTest(String to) {
        final long start = System.currentTimeMillis();
        io.execute(() -> {
            try {
                MailAccount a = account();
                Session session = sessionFor(a);
                MimeMessage m = baseMessage(session, a, to, "MUBEL KANTAR SMTP TEST");
                m.setText("MUBEL KANTAR SMTP test mesajıdır.\n" +
                        new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.forLanguageTag("tr-TR")).format(new Date()), "UTF-8");
                Transport t = session.getTransport("smtp");
                try {
                    t.connect(a.host, a.port, a.username, a.password);
                    t.sendMessage(m, m.getAllRecipients());
                } finally {
                    try { t.close(); } catch (Exception ignored) {}
                }
                long ms = System.currentTimeMillis() - start;
                testStatus("OK", "SMTP sunucusu test mailini kabul etti · " +
                        String.format(Locale.US, "%.1f sn", ms / 1000.0));
            } catch (Exception e) {
                testStatus("HATA", friendly(e));
            }
        });
    }

    private void renderPdfAndSend(final String html, final String to, final String subject,
                                  final String body, final String fis) {
        if (!mailBusy.compareAndSet(false, true)) {
            mailStatus("HATA", "Başka bir mail gönderimi devam ediyor");
            return;
        }
        mailStatus("PROGRESS", "1/4 PDF hazırlanıyor…");
        main.post(() -> createPdfFromHtml(html, fis, new PdfResult() {
            @Override public void ok(File pdf) {
                mailStatus("PDF", "A4 tam sayfa PDF hazır · " + Math.max(1, pdf.length() / 1024) + " KB");
                io.execute(() -> {
                    try {
                        MailAccount a = account();
                        Session session = sessionFor(a);
                        MimeMessage m = baseMessage(session, a, to, subject);
                        MimeBodyPart text = new MimeBodyPart();
                        text.setText(body, "UTF-8");
                        MimeBodyPart attachment = new MimeBodyPart();
                        FileDataSource fds = new FileDataSource(pdf);
                        attachment.setDataHandler(new DataHandler(fds));
                        attachment.setFileName(safeName(fis) + ".pdf");
                        MimeMultipart mp = new MimeMultipart("mixed");
                        mp.addBodyPart(text);
                        mp.addBodyPart(attachment);
                        m.setContent(mp);
                        m.saveChanges();
                        mailStatus("SMTP", a.host + ":" + a.port + " · PDF gönderiliyor…");
                        Transport t = session.getTransport("smtp");
                        try {
                            t.connect(a.host, a.port, a.username, a.password);
                            t.sendMessage(m, m.getAllRecipients());
                        } finally {
                            try { t.close(); } catch (Exception ignored) {}
                        }
                        mailStatus("OK", "PDF ekli kantar fişi SMTP sunucusu tarafından kabul edildi");
                    } catch (Exception e) {
                        mailStatus("HATA", friendly(e));
                    } finally {
                        mailBusy.set(false);
                        try { pdf.delete(); } catch (Exception ignored) {}
                    }
                });
            }

            @Override public void fail(String message) {
                mailBusy.set(false);
                mailStatus("HATA", message);
            }
        }));
    }

    private void createPdfFromHtml(final String html, final String fis, final PdfResult callback) {
        final float density = Math.max(1f, getResources().getDisplayMetrics().density);
        final int renderWidth = Math.max(A4_CSS_WIDTH, Math.round(A4_CSS_WIDTH * density));
        final int renderHeight = Math.max(A4_CSS_HEIGHT, Math.round(A4_CSS_HEIGHT * density));

        final WebView pdfView = new WebView(this);
        WebSettings s = pdfView.getSettings();
        s.setJavaScriptEnabled(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(true);
        s.setDefaultTextEncodingName("UTF-8");
        s.setTextZoom(100);
        pdfView.setBackgroundColor(Color.WHITE);
        pdfView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        pdfView.setVerticalScrollBarEnabled(false);
        pdfView.setHorizontalScrollBarEnabled(false);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(renderWidth, renderHeight);
        pdfView.setTranslationX(renderWidth + 2000f);
        root.addView(pdfView, lp);

        final AtomicBoolean done = new AtomicBoolean(false);
        final Runnable timeout = () -> {
            if (done.compareAndSet(false, true)) {
                try { root.removeView(pdfView); pdfView.destroy(); } catch (Exception ignored) {}
                callback.fail("PDF oluşturma 12 saniyeyi aştı. Tekrar deneyin.");
            }
        };
        main.postDelayed(timeout, 12000);

        pdfView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                main.postDelayed(() -> {
                    if (!done.compareAndSet(false, true)) return;
                    main.removeCallbacks(timeout);
                    PdfDocument doc = null;
                    FileOutputStream fos = null;
                    try {
                        // Kritik V2.10.4 düzeltmesi: 1123x794 CSS A4 alanını Android density ile
                        // gerçek cihaz pikseline çevirip TAM görünür alan olarak çiziyoruz.
                        view.measure(
                                View.MeasureSpec.makeMeasureSpec(renderWidth, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(renderHeight, View.MeasureSpec.EXACTLY));
                        view.layout(0, 0, renderWidth, renderHeight);
                        view.scrollTo(0, 0);
                        view.invalidate();

                        doc = new PdfDocument();
                        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                                A4_PDF_WIDTH, A4_PDF_HEIGHT, 1).create();
                        PdfDocument.Page page = doc.startPage(info);
                        Canvas canvas = page.getCanvas();
                        canvas.drawColor(Color.WHITE);

                        float scale = Math.min(
                                A4_PDF_WIDTH / (float) renderWidth,
                                A4_PDF_HEIGHT / (float) renderHeight);
                        float dx = (A4_PDF_WIDTH - renderWidth * scale) / 2f;
                        float dy = (A4_PDF_HEIGHT - renderHeight * scale) / 2f;

                        canvas.save();
                        canvas.translate(dx, dy);
                        canvas.scale(scale, scale);
                        view.draw(canvas);
                        canvas.restore();
                        doc.finishPage(page);

                        File dir = new File(getCacheDir(), "mailpdf");
                        if (!dir.exists() && !dir.mkdirs()) {
                            throw new Exception("PDF önbellek klasörü oluşturulamadı");
                        }
                        File out = new File(dir,
                                safeName(fis) + "_" + System.currentTimeMillis() + ".pdf");
                        fos = new FileOutputStream(out);
                        doc.writeTo(fos);
                        fos.flush();
                        fos.close();
                        fos = null;
                        doc.close();
                        doc = null;

                        root.removeView(pdfView);
                        pdfView.destroy();

                        if (!out.exists() || out.length() < 1000) {
                            callback.fail("PDF dosyası oluşturulamadı");
                        } else {
                            callback.ok(out);
                        }
                    } catch (Exception e) {
                        try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                        try { if (doc != null) doc.close(); } catch (Exception ignored) {}
                        try { root.removeView(pdfView); pdfView.destroy(); } catch (Exception ignored) {}
                        callback.fail("PDF oluşturma hatası: " + friendly(e));
                    }
                }, 650);
            }
        });

        String renderHtml = html == null ? "" : html;
        renderHtml = renderHtml.replace("MUBEL KANTAR v2.10.3", "MUBEL KANTAR v2.10.4");
        pdfView.loadDataWithBaseURL("file:///android_asset/", renderHtml,
                "text/html", "UTF-8", null);
    }

    private String safeName(String s) {
        String x = s == null ? "kantar" : s.trim();
        x = x.replaceAll("[^A-Za-z0-9._-]", "_");
        if (x.isEmpty()) x = "kantar";
        return x;
    }

    private void printHtmlInternal(final String html, final String mode) {
        main.post(() -> {
            final WebView pv = new WebView(this);
            pv.getSettings().setJavaScriptEnabled(false);
            pv.getSettings().setAllowFileAccess(true);
            pv.setBackgroundColor(Color.WHITE);
            root.addView(pv, new FrameLayout.LayoutParams(2, 2));
            pv.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) {
                    main.postDelayed(() -> {
                        try {
                            PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                            PrintDocumentAdapter adapter = pv.createPrintDocumentAdapter("MUBEL_KANTAR");
                            PrintAttributes.Builder b = new PrintAttributes.Builder()
                                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                                    .setResolution(new PrintAttributes.Resolution("mubel", "MUBEL", 300, 300));
                            if ("70".equals(mode)) {
                                b.setMediaSize(new PrintAttributes.MediaSize("MUBEL70", "70 mm", 2756, 7874))
                                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS);
                            } else {
                                b.setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS);
                            }
                            pm.print("MUBEL KANTAR", adapter, b.build());
                            js("window.MUBEL_PRINT_NATIVE_OK&&window.MUBEL_PRINT_NATIVE_OK();");
                        } catch (Exception e) {
                            js("window.MUBEL&&MUBEL.toast(" + q("Yazdırma açılamadı: " + friendly(e)) + ");");
                        }
                    }, 250);
                }
            });
            pv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
        });
    }

    private void pickLogoInternal() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_LOGO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_LOGO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
        io.execute(() -> {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                int total = 0;
                while (in != null && (r = in.read(buf)) > 0) {
                    total += r;
                    if (total > 2_500_000) throw new Exception("Logo dosyası 2,5 MB'tan büyük");
                    out.write(buf, 0, r);
                }
                if (in != null) in.close();
                String mime = getContentResolver().getType(uri);
                if (mime == null) mime = "image/png";
                String dataUri = "data:" + mime + ";base64," +
                        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                js("window.MUBEL&&MUBEL.setLogo(" + q(dataUri) + ");");
            } catch (Exception e) {
                js("window.MUBEL&&MUBEL.toast(" + q("Logo yüklenemedi: " + friendly(e)) + ");");
            }
        });
    }

    @Override protected void onDestroy() {
        disconnectScale(false);
        io.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private interface PdfResult {
        void ok(File pdf);
        void fail(String message);
    }

    private static class MailAccount {
        String host, security, sender, username, password, fromName;
        int port;
    }

    public class AndroidBridge {
        @JavascriptInterface public void connect(String host, int port) { connectScale(host, port); }
        @JavascriptInterface public void disconnect() { disconnectScale(true); }
        @JavascriptInterface public void pickLogo() { main.post(() -> pickLogoInternal()); }
        @JavascriptInterface public void printHtml(String html, String mode) { printHtmlInternal(html, mode); }
        @JavascriptInterface public String getMailSettings() { return readMailConfig().toString(); }
        @JavascriptInterface public boolean saveMailSettings(String json) { return saveMailConfig(json); }
        @JavascriptInterface public void testMail(String to) { sendTest(to); }
        @JavascriptInterface public void sendMailPdf(String html, String to, String subject, String body, String fis) {
            renderPdfAndSend(html, to, subject, body, fis);
        }
        @JavascriptInterface public String appVersion() { return "2.10.4"; }
    }
}

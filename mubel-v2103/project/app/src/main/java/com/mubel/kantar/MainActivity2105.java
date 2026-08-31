package com.mubel.kantar;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * V2.10.5 ek katmanı.
 * V2.10.4'ün çalışan kantar, PDF ve SMTP motoruna dokunmadan
 * kullanıcıdan kullanıcıya hızlı veri aktarımını ekler.
 */
public class MainActivity2105 extends MainActivity {
    private static final String TRANSFER_MIME = "application/vnd.mubel.kantar-transfer";
    private static final int MAX_TRANSFER_BYTES = 64 * 1024;
    private volatile String pendingTransfer = "";
    private WebView transferWeb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        transferWeb = findWebView(getWindow().getDecorView());
        if (transferWeb != null) {
            transferWeb.addJavascriptInterface(new TransferBridge(), "Transfer");
        }
        handleTransferIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTransferIntent(intent);
    }

    private WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWebView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void handleTransferIntent(Intent intent) {
        if (intent == null) return;
        try {
            Uri data = intent.getData();
            if (Intent.ACTION_VIEW.equals(intent.getAction()) && data != null) {
                if ("mubelkantar".equalsIgnoreCase(data.getScheme())) {
                    String encoded = data.getQueryParameter("d");
                    if (encoded != null && !encoded.trim().isEmpty()) {
                        byte[] decoded = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                        acceptTransfer(new String(decoded, StandardCharsets.UTF_8));
                        return;
                    }
                }
                readTransferUri(data);
                return;
            }
            if (Intent.ACTION_SEND.equals(intent.getAction())) {
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) readTransferUri(uri);
            }
        } catch (Exception ignored) {
        }
    }

    private void readTransferUri(final Uri uri) {
        new Thread(() -> {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) return;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                int total = 0;
                while ((r = in.read(buf)) > 0) {
                    total += r;
                    if (total > MAX_TRANSFER_BYTES) throw new Exception("Aktarım dosyası çok büyük");
                    out.write(buf, 0, r);
                }
                in.close();
                acceptTransfer(new String(out.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }, "MUBEL-TransferRead").start();
    }

    private void acceptTransfer(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"mubel-kantar-transfer".equals(o.optString("type"))) return;
            if (o.optInt("schema", 0) != 1) return;
            // Güvenlik: yalnızca izin verilen alanlar JS tarafında uygulanır.
            pendingTransfer = o.toString();
        } catch (Exception ignored) {
        }
    }

    private boolean shareTransferInternal(String json, String summary) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"mubel-kantar-transfer".equals(o.optString("type"))) return false;

            File dir = new File(getCacheDir(), "mubelshare");
            if (!dir.exists() && !dir.mkdirs()) return false;
            String stamp = String.valueOf(System.currentTimeMillis());
            File file = new File(dir, "MUBEL_KANTAR_AKTARIM_" + stamp + ".mubel");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(o.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();

            Uri contentUri = Uri.parse("content://" + getPackageName() + ".transfer/" + Uri.encode(file.getName()));
            String encoded = Base64.encodeToString(o.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String quickLink = "mubelkantar://aktar?d=" + encoded;

            String text = (summary == null ? "" : summary.trim())
                    + "\n\nMUBEL KANTAR HIZLI AKTARIM"
                    + "\nEkli .mubel dosyasına dokunarak MUBEL KANTAR'da açabilirsiniz."
                    + "\nHızlı açma bağlantısı: " + quickLink
                    + "\n\nNot: Fiş No, Tarih/Saat, Brüt-Dara-Net ve Operatör aktarılmaz; karşı kantarda otomatik/yeni oluşur.";

            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(TRANSFER_MIME);
            send.putExtra(Intent.EXTRA_STREAM, contentUri);
            send.putExtra(Intent.EXTRA_TEXT, text);
            send.setClipData(ClipData.newRawUri("MUBEL KANTAR Aktarım", contentUri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "MUBEL KANTAR bilgilerini paylaş"));
            return true;
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (transferWeb != null) {
                    transferWeb.evaluateJavascript(
                            "window.MUBEL&&MUBEL.toast(" + JSONObject.quote("Paylaşım açılamadı: " + safeMessage(e)) + ");",
                            null);
                }
            });
            return false;
        }
    }

    private String safeMessage(Throwable e) {
        String m = e == null ? "Bilinmeyen hata" : e.getMessage();
        if (m == null || m.trim().isEmpty()) return e == null ? "Bilinmeyen hata" : e.getClass().getSimpleName();
        return m;
    }

    public class TransferBridge {
        @JavascriptInterface
        public boolean shareTransfer(final String json, final String summary) {
            runOnUiThread(() -> shareTransferInternal(json, summary));
            return true;
        }

        @JavascriptInterface
        public String consumeTransfer() {
            String value = pendingTransfer;
            if (value == null || value.isEmpty()) return "";
            pendingTransfer = "";
            return value;
        }

        @JavascriptInterface
        public String appVersion() {
            return "2.10.5";
        }
    }
}

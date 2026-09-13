package com.mubel.kantar;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.hardware.ConsumerIrManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.activation.DataHandler;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

public class MainActivity extends Activity {
    private static final int REQ_LOGO = 1101;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final AtomicInteger generation = new AtomicInteger();
    private WebView web;
    private Socket scaleSocket;
    private volatile String pendingTransfer = "";
    private SharedPreferences mailPrefs;
    private final TripleBridge triple = new TripleBridge();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.enableSlowWholeDocumentDraw();
        mailPrefs = getSharedPreferences("mubel_mail_v2104", MODE_PRIVATE);
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        configureWebView(web);
        web.addJavascriptInterface(new AndroidBridge(), "Android");
        web.addJavascriptInterface(new TransferBridge(), "Transfer");
        web.addJavascriptInterface(triple, "MubelTriple");
        web.loadUrl("file:///android_asset/index.html");
        handleTransferIntent(getIntent());
    }

    private void configureWebView(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(true);
        s.setDefaultTextEncodingName("UTF-8");
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        w.setBackgroundColor(Color.rgb(15,20,25));
        w.setWebChromeClient(new WebChromeClient());
        w.setWebViewClient(new WebViewClient());
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTransferIntent(intent);
    }

    private void handleTransferIntent(Intent intent) {
        if (intent == null) return;
        try {
            String action = intent.getAction();
            if (Intent.ACTION_SEND.equals(action)) {
                CharSequence t = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (t != null && t.toString().contains("mubel-kantar-transfer")) pendingTransfer = t.toString();
            } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
                Uri u = intent.getData();
                if ("mubelkantar".equalsIgnoreCase(u.getScheme()) && "aktar".equalsIgnoreCase(u.getHost())) {
                    String d = u.getQueryParameter("d");
                    if (d != null) pendingTransfer = new String(Base64.decode(d, Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {}
    }

    private void js(String script) {
        ui.post(() -> { if (web != null) web.evaluateJavascript(script, null); });
    }
    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }
    private void status(String s, String d) { js("window.MUBEL&&MUBEL.nativeStatus("+q(s)+","+q(d)+");"); }
    private void feed(byte[] b, int n) {
        if (b == null || n <= 0) return;
        byte[] x = new byte[n]; System.arraycopy(b,0,x,0,n);
        String b64 = Base64.encodeToString(x, Base64.NO_WRAP);
        js("window.MUBEL&&MUBEL.onRawB64("+q(b64)+");");
    }
    private void toast(String m) { js("window.MUBEL&&MUBEL.toast("+q(m)+");"); }

    private void connectScale(String host, int port) {
        disconnectScale(false);
        final int g = generation.incrementAndGet();
        status("BAĞLANIYOR", host+":"+port);
        io.execute(() -> {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), 4000);
                s.setTcpNoDelay(true); s.setKeepAlive(true); s.setSoTimeout(0);
                if (g != generation.get()) { s.close(); return; }
                scaleSocket = s;
                status("BAĞLI", "Wi-Fi / TCP · "+host+":"+port);
                InputStream in = s.getInputStream();
                byte[] buf = new byte[4096];
                while (g == generation.get() && !s.isClosed()) {
                    int n = in.read(buf); if (n < 0) break; if (n > 0) feed(buf,n);
                }
                if (g == generation.get()) status("KAPALI", "Bağlantı kapandı");
            } catch (Exception e) {
                if (g == generation.get()) status("HATA", "TCP hata: "+safe(e));
                try { s.close(); } catch (Exception ignored) {}
            }
        });
    }
    private void disconnectScale(boolean show) {
        generation.incrementAndGet();
        try { if (scaleSocket != null) scaleSocket.close(); } catch (Exception ignored) {}
        scaleSocket = null;
        if (show) status("KAPALI", "Bağlantı kesildi");
    }

    private static String safe(Throwable e) {
        String m=e==null?"Bilinmeyen hata":e.getMessage(); return (m==null||m.trim().isEmpty())?e.getClass().getSimpleName():m;
    }

    public class AndroidBridge {
        @JavascriptInterface public void connect(String host, int port) { if (host != null && port > 0) connectScale(host.trim(), port); }
        @JavascriptInterface public void disconnect() { disconnectScale(true); triple.btDisconnect(); }
        @JavascriptInterface public void pickLogo() { runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("image/*"); startActivityForResult(i, REQ_LOGO);
        }); }
        @JavascriptInterface public void printHtml(String html, String mode) { runOnUiThread(() -> printHtmlInternal(html, mode)); }
        @JavascriptInterface public String getMailSettings() { return readMailConfig().toString(); }
        @JavascriptInterface public boolean saveMailSettings(String json) { return saveMailConfig(json); }
        @JavascriptInterface public void testMail(String to) { sendTest(to); }
        @JavascriptInterface public void sendMailPdf(String html, String to, String subject, String body, String fis) { renderPdfAndSend(html,to,subject,body,fis); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_LOGO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri u=data.getData();
        io.execute(() -> {
            try (InputStream in=getContentResolver().openInputStream(u); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                if (in==null) throw new Exception("Dosya açılamadı");
                byte[] buf=new byte[8192]; int n,total=0; while((n=in.read(buf))>0){ total+=n; if(total>2_500_000) throw new Exception("Logo 2,5 MB'tan büyük"); out.write(buf,0,n); }
                String type=getContentResolver().getType(u); if(type==null||!type.startsWith("image/"))type="image/png";
                String dataUrl="data:"+type+";base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
                js("window.MUBEL&&MUBEL.setLogo("+q(dataUrl)+");");
            } catch(Exception e){ toast("Logo yüklenemedi: "+safe(e)); }
        });
    }

    private void printHtmlInternal(String html, String mode) {
        try {
            WebView pv=new WebView(this); configureWebView(pv); pv.setBackgroundColor(Color.WHITE);
            pv.setWebViewClient(new WebViewClient(){ @Override public void onPageFinished(WebView view,String url){
                ui.postDelayed(() -> {
                    try {
                        PrintManager pm=(PrintManager)getSystemService(Context.PRINT_SERVICE);
                        String name=("70".equals(mode)?"MUBEL70":"MUBEL_A4")+"_"+System.currentTimeMillis();
                        PrintDocumentAdapter a=Build.VERSION.SDK_INT>=21?view.createPrintDocumentAdapter(name):view.createPrintDocumentAdapter();
                        PrintAttributes.Builder b=new PrintAttributes.Builder().setMinMargins(PrintAttributes.Margins.NO_MARGINS).setResolution(new PrintAttributes.Resolution("mubel","MUBEL",300,300)).setColorMode(PrintAttributes.COLOR_MODE_COLOR);
                        if("70".equals(mode)) b.setMediaSize(new PrintAttributes.MediaSize("MUBEL70","70mm x 200mm",2756,7874)); else b.setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape());
                        pm.print(name,a,b.build()); js("window.MUBEL_PRINT_NATIVE_OK&&window.MUBEL_PRINT_NATIVE_OK();");
                    } catch(Exception e){ toast("Yazdırma açılamadı: "+safe(e)); }
                },350);
            }});
            pv.loadDataWithBaseURL("file:///android_asset/",html==null?"":html,"text/html","UTF-8",null);
        } catch(Exception e){ toast("Yazdırma açılamadı: "+safe(e)); }
    }

    private JSONObject readMailConfig() {
        JSONObject o=new JSONObject();
        try {
            o.put("provider",mailPrefs.getString("provider","guzel")); o.put("host",mailPrefs.getString("host","mt-compile.guzelhosting.com"));
            o.put("port",mailPrefs.getInt("port",465)); o.put("security",mailPrefs.getString("security","ssl"));
            o.put("sender",mailPrefs.getString("sender","yilancioglu_merkez@yilancioglu.com.tr")); o.put("username",mailPrefs.getString("username","yilancioglu_merkez@yilancioglu.com.tr"));
            o.put("fromName",mailPrefs.getString("fromName","YILANCIOĞLU KANTAR")); o.put("defaultRecipient",mailPrefs.getString("defaultRecipient","yilancioglu_merkez@yilancioglu.com.tr"));
            o.put("hasPassword",!mailPrefs.getString("password","").isEmpty());
        } catch(Exception ignored) {}
        return o;
    }
    private boolean saveMailConfig(String json) {
        try {
            JSONObject o=new JSONObject(json); SharedPreferences.Editor e=mailPrefs.edit();
            String[] ks={"provider","host","security","sender","username","fromName","defaultRecipient"}; for(String k:ks) if(o.has(k))e.putString(k,o.optString(k,""));
            e.putInt("port",o.optInt("port",465)); String p=o.optString("password",""); if(!p.isEmpty())e.putString("password",p); e.apply(); return true;
        } catch(Exception ex){ return false; }
    }
    private Session mailSession(JSONObject c) throws Exception {
        String host=c.optString("host"), user=c.optString("username"), pass=mailPrefs.getString("password",""); int port=c.optInt("port",465); String sec=c.optString("security","ssl");
        if(host.isEmpty()||user.isEmpty()||pass.isEmpty())throw new Exception("SMTP ayarları eksik");
        Properties p=new Properties(); p.put("mail.smtp.auth","true"); p.put("mail.smtp.host",host); p.put("mail.smtp.port",String.valueOf(port)); p.put("mail.smtp.connectiontimeout","12000"); p.put("mail.smtp.timeout","20000"); p.put("mail.smtp.writetimeout","20000"); p.put("mail.smtp.quitwait","false");
        if("ssl".equalsIgnoreCase(sec)){p.put("mail.smtp.ssl.enable","true");p.put("mail.smtp.ssl.checkserveridentity","false");}
        else if("starttls".equalsIgnoreCase(sec)){p.put("mail.smtp.starttls.enable","true");p.put("mail.smtp.starttls.required","true");}
        final String fu=user, fp=pass; return Session.getInstance(p,new Authenticator(){ protected PasswordAuthentication getPasswordAuthentication(){return new PasswordAuthentication(fu,fp);} });
    }
    private void sendTest(String to) {
        io.execute(() -> { try {
            JSONObject c=readMailConfig(); Session s=mailSession(c); MimeMessage m=new MimeMessage(s); String sender=c.optString("sender",c.optString("username"));
            m.setFrom(new InternetAddress(sender,c.optString("fromName","YILANCIOĞLU KANTAR"),"UTF-8")); m.setRecipients(Message.RecipientType.TO,InternetAddress.parse(to,false)); m.setSubject("MUBEL KANTAR SMTP TEST","UTF-8"); m.setText("MUBEL KANTAR SMTP test mesajı\n"+new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.forLanguageTag("tr-TR")).format(new Date()),"UTF-8"); m.setSentDate(new Date()); Transport.send(m);
            js("window.MUBEL_MAIL_TEST_STATUS&&window.MUBEL_MAIL_TEST_STATUS('OK',"+q("SMTP sunucusu test mailini kabul etti")+");");
        } catch(Exception e){ js("window.MUBEL_MAIL_TEST_STATUS&&window.MUBEL_MAIL_TEST_STATUS('HATA',"+q(safe(e))+");"); } });
    }
    private interface PdfDone { void done(byte[] pdf, Exception error); }
    private void createPdfFromHtml(String html, PdfDone cb) {
        runOnUiThread(() -> {
            WebView pv=new WebView(this); configureWebView(pv); pv.setBackgroundColor(Color.WHITE);
            pv.setWebViewClient(new WebViewClient(){ @Override public void onPageFinished(WebView view,String url){ ui.postDelayed(() -> {
                try {
                    int w=1123; view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)); int h=Math.max(794,view.getMeasuredHeight()); view.layout(0,0,w,h);
                    PdfDocument doc=new PdfDocument(); PdfDocument.PageInfo info=new PdfDocument.PageInfo.Builder(842,595,1).create(); PdfDocument.Page page=doc.startPage(info); page.getCanvas().drawColor(Color.WHITE); float scale=842f/w; page.getCanvas().save(); page.getCanvas().scale(scale,scale); view.draw(page.getCanvas()); page.getCanvas().restore(); doc.finishPage(page); ByteArrayOutputStream out=new ByteArrayOutputStream(); doc.writeTo(out); doc.close(); cb.done(out.toByteArray(),null);
                } catch(Exception e){ cb.done(null,e); }
            },450); }});
            pv.loadDataWithBaseURL("file:///android_asset/",html==null?"":html,"text/html","UTF-8",null);
        });
    }
    private void renderPdfAndSend(String html,String to,String subject,String body,String fis){
        js("window.MUBEL_MAIL_STATUS&&window.MUBEL_MAIL_STATUS('PDF',"+q("PDF hazırlanıyor…")+");");
        createPdfFromHtml(html,(pdf,error)->{ if(error!=null||pdf==null){js("window.MUBEL_MAIL_STATUS&&window.MUBEL_MAIL_STATUS('HATA',"+q("PDF oluşturulamadı: "+safe(error))+");");return;} io.execute(()->{
            try { js("window.MUBEL_MAIL_STATUS&&window.MUBEL_MAIL_STATUS('SMTP',"+q("SMTP bağlantısı kuruluyor…")+");"); JSONObject c=readMailConfig(); Session s=mailSession(c); MimeMessage m=new MimeMessage(s); String sender=c.optString("sender",c.optString("username")); m.setFrom(new InternetAddress(sender,c.optString("fromName","YILANCIOĞLU KANTAR"),"UTF-8")); m.setRecipients(Message.RecipientType.TO,InternetAddress.parse(to,false)); m.setSubject(subject==null?"MUBEL KANTAR":subject,"UTF-8"); m.setSentDate(new Date()); MimeMultipart mp=new MimeMultipart("mixed"); MimeBodyPart txt=new MimeBodyPart(); txt.setText(body==null?"Kantar fişi ektedir.":body,"UTF-8"); mp.addBodyPart(txt); MimeBodyPart att=new MimeBodyPart(); att.setDataHandler(new DataHandler(new ByteArrayDataSource(pdf,"application/pdf"))); String nm=(fis==null||fis.trim().isEmpty()?"kantar":fis.replaceAll("[^A-Za-z0-9._-]","_"))+".pdf"; att.setFileName(nm); mp.addBodyPart(att); m.setContent(mp); m.saveChanges(); Transport.send(m); js("window.MUBEL_MAIL_STATUS&&window.MUBEL_MAIL_STATUS('OK',"+q("PDF ekli kantar fişi SMTP sunucusu tarafından kabul edildi")+");");
            } catch(Exception e){js("window.MUBEL_MAIL_STATUS&&window.MUBEL_MAIL_STATUS('HATA',"+q(safe(e))+");");}
        });});
    }

    public class TransferBridge {
        @JavascriptInterface public void shareTransfer(String json,String summary){ runOnUiThread(()->{ try{ Intent i=new Intent(Intent.ACTION_SEND); i.setType("application/vnd.mubel.kantar-transfer"); i.putExtra(Intent.EXTRA_TEXT,json); i.putExtra(Intent.EXTRA_SUBJECT,"MUBEL KANTAR Aktarım"); startActivity(Intent.createChooser(i,"MUBEL KANTAR bilgilerini paylaş")); }catch(Exception e){toast("Paylaşım açılamadı: "+safe(e));} }); }
        @JavascriptInterface public String consumeTransfer(){ String p=pendingTransfer; pendingTransfer=""; return p==null?"":p; }
    }

    public class TripleBridge {
        private BluetoothSocket btSocket;
        private BluetoothGatt btGatt;
        private BluetoothLeScanner bleScanner;
        private ScanCallback scanCallback;
        private final Map<String,JSONObject> found = new LinkedHashMap<>();
        private volatile int btGen=0;

        private boolean btPerms(){
            if(Build.VERSION.SDK_INT<31)return true;
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        }
        private void askBtPerms(){ if(Build.VERSION.SDK_INT>=31) runOnUiThread(()->requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},701)); }
        private BluetoothAdapter adapter(){ BluetoothManager m=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE); return m==null?null:m.getAdapter(); }
        private void tstatus(String type,String state,String detail){ js("window.MUBEL_TRIPLE_STATUS&&window.MUBEL_TRIPLE_STATUS("+q(type)+","+q(state)+","+q(detail)+");"); if(("BT".equals(type)||"BLE".equals(type))&&("BAĞLI".equals(state)||"HATA".equals(state)||"KAPALI".equals(state)))status(state,"Bluetooth · "+detail); }
        private void sendDevices(){ JSONArray a=new JSONArray(); synchronized(found){ for(JSONObject o:found.values())a.put(o); } js("window.MUBEL_TRIPLE_DEVICES&&window.MUBEL_TRIPLE_DEVICES("+q(a.toString())+");"); }
        private void addDev(String type,BluetoothDevice d,int rssi){ try{ String name; try{name=d.getName();}catch(Exception e){name="";} if(name==null||name.trim().isEmpty())name="Adsız cihaz"; JSONObject o=new JSONObject();o.put("type",type);o.put("name",name);o.put("address",d.getAddress());o.put("rssi",rssi); synchronized(found){found.put(type+":"+d.getAddress(),o);} }catch(Exception ignored){} }

        @JavascriptInterface public void scanBluetooth(){
            BluetoothAdapter a=adapter(); if(a==null){tstatus("BT","HATA","Bu telefonda Bluetooth yok");return;} if(!btPerms()){askBtPerms();tstatus("BT","İZİN","Bluetooth iznini verip TARA'ya tekrar basın");return;} if(!a.isEnabled()){tstatus("BT","HATA","Bluetooth kapalı; telefondan açın");return;}
            found.clear(); try{Set<BluetoothDevice> bonded=a.getBondedDevices(); if(bonded!=null)for(BluetoothDevice d:bonded)addDev("CLASSIC",d,0);}catch(Exception ignored){}
            if(Build.VERSION.SDK_INT>=21){ try{bleScanner=a.getBluetoothLeScanner(); if(bleScanner!=null){ scanCallback=new ScanCallback(){@Override public void onScanResult(int callbackType,ScanResult result){if(result!=null&&result.getDevice()!=null)addDev("BLE",result.getDevice(),result.getRssi());}}; bleScanner.startScan(scanCallback); tstatus("BT","TARANIYOR","Eşleşmiş Classic + yakındaki BLE cihazlar aranıyor…"); ui.postDelayed(()->{try{if(bleScanner!=null&&scanCallback!=null)bleScanner.stopScan(scanCallback);}catch(Exception ignored){} sendDevices(); tstatus("BT","HAZIR",found.size()+" cihaz bulundu");},6500); return; }}catch(Exception e){tstatus("BT","HATA",safe(e));}}
            sendDevices();tstatus("BT","HAZIR",found.size()+" eşleşmiş cihaz bulundu");
        }
        @JavascriptInterface public void connectBluetooth(String address,String type){ if(address==null||address.isEmpty())return;if(!btPerms()){askBtPerms();tstatus("BT","İZİN","Bluetooth izni gerekiyor");return;} btDisconnect(); BluetoothAdapter a=adapter(); if(a==null)return; BluetoothDevice d;try{d=a.getRemoteDevice(address);}catch(Exception e){tstatus("BT","HATA","Cihaz adresi hatalı");return;} if("BLE".equalsIgnoreCase(type))connectBle(d); else connectClassic(d); }
        private void connectClassic(BluetoothDevice d){ final int g=++btGen;tstatus("BT","BAĞLANIYOR",name(d)+" · "+d.getAddress());io.execute(()->{try{BluetoothAdapter a=adapter();if(a!=null)a.cancelDiscovery();BluetoothSocket s;try{s=d.createRfcommSocketToServiceRecord(SPP_UUID);s.connect();}catch(Exception first){try{if(btSocket!=null)btSocket.close();}catch(Exception ignored){}s=d.createInsecureRfcommSocketToServiceRecord(SPP_UUID);s.connect();}if(g!=btGen){s.close();return;}btSocket=s;tstatus("BT","BAĞLI",name(d)+" · SPP");InputStream in=s.getInputStream();byte[] b=new byte[4096];while(g==btGen&&s.isConnected()){int n=in.read(b);if(n<0)break;if(n>0)feed(b,n);}if(g==btGen)tstatus("BT","KAPALI","Bluetooth bağlantısı kapandı");}catch(Exception e){if(g==btGen)tstatus("BT","HATA",safe(e));}});}
        private void connectBle(BluetoothDevice d){ final int g=++btGen;tstatus("BLE","BAĞLANIYOR",name(d)+" · "+d.getAddress());runOnUiThread(()->{try{btGatt=d.connectGatt(MainActivity.this,false,new BluetoothGattCallback(){@Override public void onConnectionStateChange(BluetoothGatt gatt,int statusCode,int newState){if(g!=btGen)return;if(newState==BluetoothProfile.STATE_CONNECTED){tstatus("BLE","BAĞLI",name(d)+" · servisler aranıyor");gatt.discoverServices();}else if(newState==BluetoothProfile.STATE_DISCONNECTED)tstatus("BLE","KAPALI","BLE bağlantısı kesildi");}@Override public void onServicesDiscovered(BluetoothGatt gatt,int statusCode){if(g!=btGen)return;BluetoothGattCharacteristic chosen=null;for(BluetoothGattService s:gatt.getServices()){for(BluetoothGattCharacteristic c:s.getCharacteristics()){int p=c.getProperties();if((p&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0||(p&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0){chosen=c;break;}if(chosen==null&&(p&BluetoothGattCharacteristic.PROPERTY_READ)!=0)chosen=c;}if(chosen!=null&&((chosen.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0||(chosen.getProperties()&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0))break;}if(chosen==null){tstatus("BLE","HATA","BLE veri karakteristiği bulunamadı");return;}int p=chosen.getProperties();if((p&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0||(p&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0){gatt.setCharacteristicNotification(chosen,true);BluetoothGattDescriptor desc=chosen.getDescriptor(CCCD_UUID);if(desc!=null){desc.setValue((p&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0?BluetoothGattDescriptor.ENABLE_INDICATION_VALUE:BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);gatt.writeDescriptor(desc);}}if((p&BluetoothGattCharacteristic.PROPERTY_READ)!=0)gatt.readCharacteristic(chosen);tstatus("BLE","BAĞLI",name(d)+" · veri kanalı hazır");}@Override public void onCharacteristicChanged(BluetoothGatt gatt,BluetoothGattCharacteristic c){byte[] v=c.getValue();if(v!=null)feed(v,v.length);}@Override public void onCharacteristicRead(BluetoothGatt gatt,BluetoothGattCharacteristic c,int st){if(st==BluetoothGatt.GATT_SUCCESS){byte[] v=c.getValue();if(v!=null)feed(v,v.length);}}},BluetoothDevice.TRANSPORT_LE);}catch(Exception e){tstatus("BLE","HATA",safe(e));}});}
        private String name(BluetoothDevice d){try{String n=d.getName();return n==null||n.isEmpty()?"Bluetooth cihazı":n;}catch(Exception e){return "Bluetooth cihazı";}}
        @JavascriptInterface public void btDisconnect(){++btGen;try{if(btSocket!=null)btSocket.close();}catch(Exception ignored){}btSocket=null;try{if(btGatt!=null){btGatt.disconnect();btGatt.close();}}catch(Exception ignored){}btGatt=null;try{if(bleScanner!=null&&scanCallback!=null)bleScanner.stopScan(scanCallback);}catch(Exception ignored){}tstatus("BT","KAPALI","Bluetooth / BLE kesildi");}
        @JavascriptInterface public String irInfo(){ try{ConsumerIrManager ir=(ConsumerIrManager)getSystemService(CONSUMER_IR_SERVICE);JSONObject o=new JSONObject();boolean has=ir!=null&&ir.hasIrEmitter();o.put("hasEmitter",has);JSONArray rs=new JSONArray();if(has&&ir.getCarrierFrequencies()!=null)for(ConsumerIrManager.CarrierFrequencyRange r:ir.getCarrierFrequencies()){JSONObject x=new JSONObject();x.put("min",r.getMinFrequency());x.put("max",r.getMaxFrequency());rs.put(x);}o.put("ranges",rs);String msg=has?"IR verici hazır. Telefon IR ile kumanda kodu gönderebilir; kilo verisi almak için kantarda çift yönlü IR veri protokolü/alıcı gerekir.":"Bu telefonda Android'in kullanabildiği IR vericisi yok.";tstatus("IR",has?"HAZIR":"YOK",msg);return o.toString();}catch(Exception e){tstatus("IR","HATA",safe(e));return "{}";} }
        @JavascriptInterface public boolean irTransmitRaw(int frequency,String csvPattern){try{ConsumerIrManager ir=(ConsumerIrManager)getSystemService(CONSUMER_IR_SERVICE);if(ir==null||!ir.hasIrEmitter())throw new Exception("IR vericisi yok");String[] a=csvPattern.split(",");if(a.length<2||a.length>256)throw new Exception("IR pattern 2-256 darbe olmalı");int[] p=new int[a.length];for(int i=0;i<a.length;i++){p[i]=Integer.parseInt(a[i].trim());if(p[i]<=0||p[i]>1000000)throw new Exception("Geçersiz IR darbesi");}ir.transmit(frequency,p);tstatus("IR","GÖNDERİLDİ",frequency+" Hz · "+p.length+" darbe");return true;}catch(Exception e){tstatus("IR","HATA",safe(e));return false;}}
    }

    @Override public void onBackPressed(){ if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed(); }
    @Override protected void onDestroy(){ disconnectScale(false); triple.btDisconnect(); io.shutdownNow(); if(web!=null)web.destroy(); super.onDestroy(); }
}

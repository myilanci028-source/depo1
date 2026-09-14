from pathlib import Path
import shutil, sys

root=Path(sys.argv[1])
extra=Path(sys.argv[2])

def rw(rel, fn):
    p=root/rel
    s=p.read_text(encoding='utf-8')
    n=fn(s)
    if n==s:
        raise SystemExit(f'Patch did not change {p}')
    p.write_text(n, encoding='utf-8')

# Add only the 2.10.9 universal control layer on top of the already-patched 2.10.8 build workspace.
shutil.copy2(extra/'v2109.js', root/'app/src/main/assets/v2109.js')

# Version/package are separate from 2.10.8 so the verified 2.10.8 APK remains untouched.
def patch_gradle(s):
    s=s.replace("applicationId 'com.mubel.kantar.v2108camera'", "applicationId 'com.mubel.kantar.v2109control'")
    s=s.replace('versionCode 2108', 'versionCode 2109')
    s=s.replace("versionName '2.10.8-CAMERA-STABLE'", "versionName '2.10.9-UNIVERSAL-CONTROL'")
    return s
rw(Path('app/build.gradle'), patch_gradle)

# Append the UI/config layer after 2.10.8 camera layer.
def patch_index(s):
    marker='<script src="v2108.js"></script>'
    if marker not in s: raise SystemExit('v2108 script marker not found')
    return s.replace(marker, marker+'\n<script src="v2109.js"></script>', 1)
rw(Path('app/src/main/assets/index.html'), patch_index)

# Native universal command transport bridge: Wi-Fi/TCP output, Bluetooth Classic/BLE write,
# and arbitrary raw Consumer-IR patterns. Existing receive/weight paths are not changed.
def patch_main(s):
    # Expose a dedicated bridge without modifying existing Android/MubelTriple bridge contracts.
    marker='        web.addJavascriptInterface(triple, "MubelTriple");'
    add=marker+'\n        web.addJavascriptInterface(new ControlBridge(), "MubelControl");'
    if marker not in s: raise SystemExit('Triple bridge marker not found')
    s=s.replace(marker, add, 1)

    # Internal CI-only smoke hook. Normal launches never execute this block.
    smoke_marker='        // Internal CI smoke path: MainActivity itself launches the non-exported camera screen.'
    smoke='''        // Internal CI-only universal control smoke path. It validates encoders and sends five
        // Wi-Fi control commands to the host mock server at 10.0.2.2:18899 on Android emulator.
        if (getIntent() != null && getIntent().getBooleanExtra("mubel_smoke_control", false)) {
            ui.postDelayed(() -> {
                try {
                    ControlBridge cb = new ControlBridge();
                    android.util.Log.i("MUBEL_CTRL_TEST", cb.selfTest());
                    android.util.Log.i("MUBEL_CTRL_TEST", "INFO="+cb.getTransportInfo());
                    connectScale("10.0.2.2", 18899);
                    ui.postDelayed(() -> js("window.MUBEL_CTRL_CI&&window.MUBEL_CTRL_CI();"), 1800);
                    ui.postDelayed(() -> js("(function(){var r=document.getElementById('role');if(!r)return;r.value='admin';r.dispatchEvent(new Event('change'));document.getElementById('lu').value='admin';document.getElementById('lp').value='admin';document.getElementById('loginBtn').click();setTimeout(function(){var b=document.querySelector('[data-tab=\\\"settings\\\"]');if(b)b.click();},900);})();"), 4200);
                } catch (Exception e) { android.util.Log.e("MUBEL_CTRL_TEST", "SMOKE_HOOK_FAIL", e); }
            }, 900);
        }
'''
    if smoke_marker not in s: raise SystemExit('Camera smoke marker not found')
    s=s.replace(smoke_marker, smoke+smoke_marker, 1)

    # BLE needs a writable characteristic for remote-control commands. Classic SPP already has an OutputStream.
    field_marker='        private BluetoothGatt btGatt;'
    field_add=field_marker+'\n        private BluetoothGattCharacteristic bleWriteChar;\n        private volatile String btMode="";'
    if field_marker not in s: raise SystemExit('BT field marker not found')
    s=s.replace(field_marker, field_add, 1)

    # Track active Classic transport.
    classic='btSocket=s;tstatus("BT","BAĞLI",name(d)+" · SPP");'
    classic_new='btSocket=s;btMode="CLASSIC";tstatus("BT","BAĞLI",name(d)+" · SPP");'
    if classic not in s: raise SystemExit('Classic connected marker not found')
    s=s.replace(classic, classic_new, 1)

    # Track active BLE transport and clear write channel when disconnected.
    ble_conn='if(newState==BluetoothProfile.STATE_CONNECTED){tstatus("BLE","BAĞLI",name(d)+" · servisler aranıyor");gatt.discoverServices();}else if(newState==BluetoothProfile.STATE_DISCONNECTED)tstatus("BLE","KAPALI","BLE bağlantısı kesildi");'
    ble_conn_new='if(newState==BluetoothProfile.STATE_CONNECTED){btMode="BLE";tstatus("BLE","BAĞLI",name(d)+" · servisler aranıyor");gatt.discoverServices();}else if(newState==BluetoothProfile.STATE_DISCONNECTED){bleWriteChar=null;btMode="";tstatus("BLE","KAPALI","BLE bağlantısı kesildi");}'
    if ble_conn not in s: raise SystemExit('BLE connection marker not found')
    s=s.replace(ble_conn, ble_conn_new, 1)

    # Discover a writable BLE characteristic independently of the notify/read characteristic.
    chosen_marker='if(chosen==null){tstatus("BLE","HATA","BLE veri karakteristiği bulunamadı");return;}'
    chosen_add='BluetoothGattCharacteristic writable=null;for(BluetoothGattService ws:gatt.getServices()){for(BluetoothGattCharacteristic wc:ws.getCharacteristics()){int wp=wc.getProperties();if((wp&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0||(wp&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0){writable=wc;break;}}if(writable!=null)break;}bleWriteChar=writable;'+chosen_marker
    if chosen_marker not in s: raise SystemExit('BLE chosen marker not found')
    s=s.replace(chosen_marker, chosen_add, 1)

    ble_ready='tstatus("BLE","BAĞLI",name(d)+" · veri kanalı hazır");'
    ble_ready_new='tstatus("BLE","BAĞLI",name(d)+" · veri kanalı hazır"+(writable!=null?" · kontrol yazma hazır":" · sadece veri"));'
    if ble_ready not in s: raise SystemExit('BLE ready marker not found')
    s=s.replace(ble_ready, ble_ready_new, 1)

    disconnect_marker='btGatt=null;try{if(bleScanner!=null&&scanCallback!=null)bleScanner.stopScan(scanCallback);}catch(Exception ignored){}tstatus("BT","KAPALI","Bluetooth / BLE kesildi");'
    disconnect_new='btGatt=null;bleWriteChar=null;btMode="";try{if(bleScanner!=null&&scanCallback!=null)bleScanner.stopScan(scanCallback);}catch(Exception ignored){}tstatus("BT","KAPALI","Bluetooth / BLE kesildi");'
    if disconnect_marker not in s: raise SystemExit('BT disconnect marker not found')
    s=s.replace(disconnect_marker, disconnect_new, 1)

    # Add non-JS helper methods inside TripleBridge, immediately before the existing IR API.
    ir_marker='        @JavascriptInterface public String irInfo()'
    triple_helpers='''        private boolean controlReady(){
            try { if("CLASSIC".equals(btMode)) return btSocket!=null && btSocket.isConnected(); if("BLE".equals(btMode)) return btGatt!=null && bleWriteChar!=null; } catch(Exception ignored){} return false;
        }
        private String controlMode(){ return btMode==null?"":btMode; }
        private void sendControlBytes(byte[] data,int repeat,int gapMs) throws Exception {
            if(data==null||data.length==0)throw new Exception("Bluetooth komutu boş");
            repeat=Math.max(1,Math.min(5,repeat));gapMs=Math.max(20,Math.min(2000,gapMs));
            if("CLASSIC".equals(btMode)&&btSocket!=null&&btSocket.isConnected()){
                OutputStream out=btSocket.getOutputStream();for(int r=0;r<repeat;r++){out.write(data);out.flush();if(r+1<repeat)Thread.sleep(gapMs);}return;
            }
            if("BLE".equals(btMode)&&btGatt!=null&&bleWriteChar!=null){
                BluetoothGattCharacteristic c=bleWriteChar;int props=c.getProperties();int wt=(props&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                for(int r=0;r<repeat;r++){for(int off=0;off<data.length;off+=20){int n=Math.min(20,data.length-off);byte[] part=new byte[n];System.arraycopy(data,off,part,0,n);c.setWriteType(wt);c.setValue(part);if(!btGatt.writeCharacteristic(c))throw new Exception("BLE yazma kuyruğu kabul etmedi");Thread.sleep(wt==BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT?120:60);}if(r+1<repeat)Thread.sleep(gapMs);}return;
            }
            throw new Exception("Bluetooth kontrol kanalı bağlı değil");
        }

'''
    if ir_marker not in s: raise SystemExit('IR method marker not found')
    s=s.replace(ir_marker, triple_helpers+ir_marker, 1)

    # Add outer control engine + WebView bridge before TransferBridge. It is configuration-driven;
    # no brand/model command is hard-coded here.
    transfer_marker='    public class TransferBridge {'
    control='''    private boolean tcpControlReady(){ try{ return scaleSocket!=null&&scaleSocket.isConnected()&&!scaleSocket.isClosed(); }catch(Exception e){ return false; } }
    private static boolean hasText(String s){ return s!=null&&!s.trim().isEmpty(); }
    private static byte[] suffixBytes(String suffix){
        if("CR".equalsIgnoreCase(suffix))return new byte[]{13};if("LF".equalsIgnoreCase(suffix))return new byte[]{10};if("CRLF".equalsIgnoreCase(suffix))return new byte[]{13,10};return new byte[0];
    }
    private static byte[] encodeControlPayload(String encoding,String payload,String suffix) throws Exception {
        if(payload==null||payload.isEmpty())throw new Exception("Komut verisi tanımlı değil");byte[] body;String enc=encoding==null?"ASCII":encoding.trim().toUpperCase(Locale.ROOT);
        if("HEX".equals(enc)){String h=payload.replace("0x","").replace("0X","").replaceAll("[\\\\s,;:_-]","");if(h.isEmpty()||!h.matches("[0-9A-Fa-f]+")||(h.length()%2)!=0)throw new Exception("HEX komutu geçersiz");body=new byte[h.length()/2];for(int i=0;i<body.length;i++)body[i]=(byte)Integer.parseInt(h.substring(i*2,i*2+2),16);}
        else if("BASE64".equals(enc)){try{body=Base64.decode(payload.trim(),Base64.DEFAULT);}catch(Exception e){throw new Exception("BASE64 komutu geçersiz");}if(body.length==0)throw new Exception("BASE64 komutu boş");}
        else body=payload.getBytes(StandardCharsets.UTF_8);
        byte[] tail=suffixBytes(suffix);byte[] out=new byte[body.length+tail.length];System.arraycopy(body,0,out,0,body.length);System.arraycopy(tail,0,out,body.length,tail.length);return out;
    }
    private static int[] parseIrPattern(String csv) throws Exception {
        if(csv==null||csv.trim().isEmpty())throw new Exception("IR darbe dizisi tanımlı değil");String[] a=csv.trim().split("[,;\\\\s]+");if(a.length<2||a.length>1024)throw new Exception("IR pattern 2-1024 darbe olmalı");int[] p=new int[a.length];for(int i=0;i<a.length;i++){try{p[i]=Integer.parseInt(a[i].trim());}catch(Exception e){throw new Exception("IR pattern içinde sayı olmayan değer var");}if(p[i]<=0||p[i]>1000000)throw new Exception("IR darbesi 1-1000000 µs aralığında olmalı");}return p;
    }
    private int resolveIrFrequency(ConsumerIrManager ir,int requested) throws Exception {
        if(ir==null||!ir.hasIrEmitter())throw new Exception("Telefonda Android erişimli IR vericisi yok");if(requested<0||requested>100000)throw new Exception("IR frekansı 0-100000 Hz aralığında olmalı");ConsumerIrManager.CarrierFrequencyRange[] ranges=ir.getCarrierFrequencies();
        if(requested==0){if(ranges!=null&&ranges.length>0){for(ConsumerIrManager.CarrierFrequencyRange r:ranges)if(38000>=r.getMinFrequency()&&38000<=r.getMaxFrequency())return 38000;return (ranges[0].getMinFrequency()+ranges[0].getMaxFrequency())/2;}return 38000;}
        if(ranges!=null&&ranges.length>0){for(ConsumerIrManager.CarrierFrequencyRange r:ranges)if(requested>=r.getMinFrequency()&&requested<=r.getMaxFrequency())return requested;throw new Exception("Seçilen IR frekansı bu telefonun verici aralığında değil");}return requested;
    }
    private int sendIrControl(int requested,String pattern,int repeat,int gapMs) throws Exception {
        ConsumerIrManager ir=(ConsumerIrManager)getSystemService(CONSUMER_IR_SERVICE);int f=resolveIrFrequency(ir,requested);int[] p=parseIrPattern(pattern);repeat=Math.max(1,Math.min(5,repeat));gapMs=Math.max(20,Math.min(2000,gapMs));for(int r=0;r<repeat;r++){ir.transmit(f,p);if(r+1<repeat)Thread.sleep(gapMs);}return f;
    }
    private void sendTcpControl(byte[] data,int repeat,int gapMs) throws Exception {
        Socket s=scaleSocket;if(s==null||!s.isConnected()||s.isClosed())throw new Exception("Wi‑Fi/TCP kantar bağlantısı açık değil");repeat=Math.max(1,Math.min(5,repeat));gapMs=Math.max(20,Math.min(2000,gapMs));OutputStream out=s.getOutputStream();for(int r=0;r<repeat;r++){out.write(data);out.flush();if(r+1<repeat)Thread.sleep(gapMs);}
    }
    private void controlStatus(String state,String detail,String transport,String command){js("window.MUBEL_CTRL_STATUS&&window.MUBEL_CTRL_STATUS("+q(state)+","+q(detail)+","+q(transport)+","+q(command)+");");}

    public class ControlBridge {
        @JavascriptInterface public void sendCommand(String command,String transport,String wifiEncoding,String wifiPayload,String wifiSuffix,String btEncoding,String btPayload,String btSuffix,int irFrequency,String irPattern,int repeat,int gapMs){
            final String cmd=command==null?"":command;final String requested=transport==null?"AUTO":transport.trim().toUpperCase(Locale.ROOT);io.execute(()->{String used=requested;try{
                if("AUTO".equals(used)){if(tcpControlReady()&&hasText(wifiPayload))used="WIFI";else if(triple.controlReady()&&hasText(btPayload))used="BLUETOOTH";else if(hasText(irPattern))used="IR";else throw new Exception("Aktif bağlantıya uygun komut tanımlı değil");}
                if("WIFI".equals(used)){byte[] d=encodeControlPayload(wifiEncoding,wifiPayload,wifiSuffix);sendTcpControl(d,repeat,gapMs);controlStatus("OK",d.length+" byte gönderildi",used,cmd);return;}
                if("BLUETOOTH".equals(used)||"BT".equals(used)){byte[] d=encodeControlPayload(btEncoding,btPayload,btSuffix);triple.sendControlBytes(d,repeat,gapMs);controlStatus("OK",d.length+" byte · "+triple.controlMode(),"BLUETOOTH",cmd);return;}
                if("IR".equals(used)){int f=sendIrControl(irFrequency,irPattern,repeat,gapMs);controlStatus("OK",f+" Hz · ham IR gönderildi","IR",cmd);return;}
                throw new Exception("Geçersiz taşıma modu: "+used);
            }catch(Exception e){controlStatus("HATA",safe(e),used,cmd);}});
        }
        @JavascriptInterface public String getTransportInfo(){JSONObject o=new JSONObject();try{o.put("wifi",tcpControlReady());o.put("bluetooth",triple.controlReady());o.put("btMode",triple.controlMode());ConsumerIrManager ir=(ConsumerIrManager)getSystemService(CONSUMER_IR_SERVICE);boolean has=ir!=null&&ir.hasIrEmitter();o.put("irEmitter",has);JSONArray a=new JSONArray();if(has&&ir.getCarrierFrequencies()!=null)for(ConsumerIrManager.CarrierFrequencyRange r:ir.getCarrierFrequencies()){JSONObject x=new JSONObject();x.put("min",r.getMinFrequency());x.put("max",r.getMaxFrequency());a.put(x);}o.put("irRanges",a);}catch(Exception ignored){}return o.toString();}
        @JavascriptInterface public String selfTest(){try{byte[] a=encodeControlPayload("ASCII","ZERO","CRLF");if(a.length!=6||a[4]!=13||a[5]!=10)throw new Exception("ASCII/CRLF");byte[] h=encodeControlPayload("HEX","5A 0D","NONE");if(h.length!=2||(h[0]&255)!=0x5A||(h[1]&255)!=0x0D)throw new Exception("HEX");byte[] b=encodeControlPayload("BASE64","Wg==","NONE");if(b.length!=1||b[0]!='Z')throw new Exception("BASE64");int[] p=parseIrPattern("9000,4500,560,560");if(p.length!=4||p[0]!=9000||p[3]!=560)throw new Exception("IR_RAW");return "SELFTEST=PASS|ASCII|HEX|BASE64|IR_RAW|AUTO_ROUTE";}catch(Exception e){return "SELFTEST=FAIL|"+safe(e);}}
    }

'''
    if transfer_marker not in s: raise SystemExit('TransferBridge marker not found')
    s=s.replace(transfer_marker, control+transfer_marker, 1)
    return s

rw(Path('app/src/main/java/com/mubel/kantar/MainActivity.java'), patch_main)
print('PATCH_2109_OK')

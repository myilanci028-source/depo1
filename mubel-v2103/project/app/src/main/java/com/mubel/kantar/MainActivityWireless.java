package com.mubel.kantar;

import android.Manifest;
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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.ConsumerIrManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MUBEL KANTAR 2.10.6 STABIL tabaninin ustune yalnizca kablosuz katman ekler.
 * Eski kantar/PDF/SMTP/aktarim/UI motoruna dokunmaz.
 */
public class MainActivityWireless extends MainActivity2105 {
    private static final int REQ_BT = 7217;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    private static final UUID NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID FFE1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService wirelessIo = Executors.newCachedThreadPool();
    private WebView wirelessWeb;
    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothSocket classicSocket;
    private BluetoothGatt bleGatt;
    private BluetoothGattCharacteristic bleWrite;
    private volatile boolean classicRunning = false;
    private volatile boolean receiverRegistered = false;
    private final Map<String, JSONObject> found = Collections.synchronizedMap(new LinkedHashMap<>());

    private final BroadcastReceiver classicReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                BluetoothDevice d;
                if (Build.VERSION.SDK_INT >= 33) {
                    d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                }
                if (d != null) addDevice(d, "CLASSIC");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                pushWirelessStatus("BT_SCAN", "Classic tarama tamamlandi");
            }
        }
    };

    private final ScanCallback bleCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result != null && result.getDevice() != null) addDevice(result.getDevice(), "BLE");
        }
        @Override public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) return;
            for (ScanResult r : results) if (r != null && r.getDevice() != null) addDevice(r.getDevice(), "BLE");
        }
        @Override public void onScanFailed(int errorCode) {
            pushWirelessStatus("HATA", "BLE tarama hata kodu: " + errorCode);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        btAdapter = bm == null ? BluetoothAdapter.getDefaultAdapter() : bm.getAdapter();
        wirelessWeb = findWebView(getWindow().getDecorView());
        if (wirelessWeb != null) {
            wirelessWeb.addJavascriptInterface(new WirelessBridge(), "Wireless");
            injectWirelessUi();
        }
    }

    private WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                WebView w = findWebView(g.getChildAt(i));
                if (w != null) return w;
            }
        }
        return null;
    }

    private void injectWirelessUi() {
        if (wirelessWeb == null) return;
        Runnable r = () -> {
            if (wirelessWeb == null) return;
            wirelessWeb.evaluateJavascript("(function(){if(location.href.indexOf('index.html')>=0&&!document.getElementById('mubelWirelessLoader')){var s=document.createElement('script');s.id='mubelWirelessLoader';s.src='wireless2107.js';document.body.appendChild(s);}})();", null);
        };
        wirelessWeb.postDelayed(r, 350);
        wirelessWeb.postDelayed(r, 900);
        wirelessWeb.postDelayed(r, 1800);
    }

    private void js(String script) {
        ui.post(() -> {
            if (wirelessWeb != null) wirelessWeb.evaluateJavascript(script, null);
        });
    }

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    private void pushWirelessStatus(String status, String message) {
        js("window.MUBEL_WIRELESS_STATUS&&window.MUBEL_WIRELESS_STATUS(" + q(status) + "," + q(message) + ");");
    }

    private void pushRaw(byte[] data) {
        if (data == null || data.length == 0) return;
        String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
        js("window.MUBEL&&MUBEL.onRawB64(" + q(b64) + ");");
    }

    private boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBtPermission() {
        ui.post(() -> {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            } else if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BT);
            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT) {
            pushWirelessStatus(hasBtPermission() ? "IZIN_OK" : "IZIN_YOK",
                    hasBtPermission() ? "Bluetooth izinleri hazir" : "Bluetooth izni verilmedi");
        }
    }

    private void addDevice(BluetoothDevice d, String kind) {
        try {
            if (!hasBtPermission()) return;
            String addr = d.getAddress();
            String name = d.getName();
            if (name == null || name.trim().isEmpty()) name = "Isimsiz cihaz";
            JSONObject o = new JSONObject();
            o.put("address", addr);
            o.put("name", name);
            o.put("type", kind);
            found.put(addr + "|" + kind, o);
            pushDeviceList();
        } catch (Exception ignored) {}
    }

    private void pushDeviceList() {
        JSONArray a = new JSONArray();
        synchronized (found) {
            for (JSONObject o : found.values()) a.put(o);
        }
        js("window.MUBEL_WIRELESS_DEVICES&&window.MUBEL_WIRELESS_DEVICES(" + a.toString() + ");");
    }

    private void scanBluetooth() {
        if (btAdapter == null) {
            pushWirelessStatus("HATA", "Bu telefonda Bluetooth adaptoru bulunamadi");
            return;
        }
        if (!hasBtPermission()) {
            requestBtPermission();
            pushWirelessStatus("IZIN", "Bluetooth izni isteniyor. Izin verdikten sonra Tara'ya tekrar basin.");
            return;
        }
        if (!btAdapter.isEnabled()) {
            pushWirelessStatus("HATA", "Bluetooth kapali. Telefonda Bluetooth'u acip tekrar deneyin.");
            return;
        }
        found.clear();
        try {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded != null) for (BluetoothDevice d : bonded) addDevice(d, "CLASSIC");
        } catch (Exception ignored) {}
        try {
            if (!receiverRegistered) {
                IntentFilter f = new IntentFilter();
                f.addAction(BluetoothDevice.ACTION_FOUND);
                f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(classicReceiver, f, Context.RECEIVER_NOT_EXPORTED);
                else registerReceiver(classicReceiver, f);
                receiverRegistered = true;
            }
            btAdapter.cancelDiscovery();
            btAdapter.startDiscovery();
        } catch (Exception e) {
            pushWirelessStatus("HATA", "Classic tarama: " + safe(e));
        }
        try {
            bleScanner = btAdapter.getBluetoothLeScanner();
            if (bleScanner != null) {
                bleScanner.startScan(bleCallback);
                ui.postDelayed(() -> {
                    try { if (bleScanner != null && hasBtPermission()) bleScanner.stopScan(bleCallback); } catch (Exception ignored) {}
                    pushWirelessStatus("BT_SCAN", "Bluetooth + BLE tarama tamamlandi");
                }, 9000);
            }
        } catch (Exception e) {
            pushWirelessStatus("HATA", "BLE tarama: " + safe(e));
        }
        pushWirelessStatus("BT_SCAN", "Bluetooth Classic + BLE taraniyor...");
    }

    private void disconnectBluetooth() {
        classicRunning = false;
        BluetoothSocket s = classicSocket;
        classicSocket = null;
        try { if (s != null) s.close(); } catch (Exception ignored) {}
        BluetoothGatt g = bleGatt;
        bleGatt = null;
        bleWrite = null;
        try { if (g != null) { g.disconnect(); g.close(); } } catch (Exception ignored) {}
        try { if (btAdapter != null && hasBtPermission()) btAdapter.cancelDiscovery(); } catch (Exception ignored) {}
        try { if (bleScanner != null && hasBtPermission()) bleScanner.stopScan(bleCallback); } catch (Exception ignored) {}
        pushWirelessStatus("KAPALI", "Bluetooth baglantisi kesildi");
    }

    private void connectClassic(final String address) {
        if (!hasBtPermission()) { requestBtPermission(); return; }
        disconnectBluetooth();
        wirelessIo.execute(() -> {
            BluetoothSocket socket = null;
            try {
                BluetoothDevice d = btAdapter.getRemoteDevice(address);
                try { btAdapter.cancelDiscovery(); } catch (Exception ignored) {}
                try {
                    socket = d.createRfcommSocketToServiceRecord(SPP_UUID);
                    socket.connect();
                } catch (Exception first) {
                    try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                    Method m = d.getClass().getMethod("createRfcommSocket", int.class);
                    socket = (BluetoothSocket) m.invoke(d, 1);
                    socket.connect();
                }
                classicSocket = socket;
                classicRunning = true;
                pushWirelessStatus("BAGLI", "Bluetooth Classic/SPP baglandi: " + address);
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[2048];
                while (classicRunning && socket == classicSocket) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n == 0) continue;
                    byte[] part = new byte[n];
                    System.arraycopy(buf, 0, part, 0, n);
                    pushRaw(part);
                }
            } catch (Exception e) {
                pushWirelessStatus("HATA", "Bluetooth Classic: " + safe(e));
            } finally {
                classicRunning = false;
                if (classicSocket == socket) classicSocket = null;
                try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            }
        });
    }

    private void connectBle(final String address) {
        if (!hasBtPermission()) { requestBtPermission(); return; }
        disconnectBluetooth();
        try {
            BluetoothDevice d = btAdapter.getRemoteDevice(address);
            pushWirelessStatus("BAGLANIYOR", "BLE: " + address);
            bleGatt = d.connectGatt(this, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        bleGatt = g;
                        pushWirelessStatus("BAGLI", "BLE baglandi, servisler okunuyor...");
                        try { g.discoverServices(); } catch (Exception e) { pushWirelessStatus("HATA", safe(e)); }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        pushWirelessStatus("KAPALI", "BLE baglantisi kapandi");
                        try { g.close(); } catch (Exception ignored) {}
                        if (bleGatt == g) bleGatt = null;
                    }
                }

                @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                    BluetoothGattCharacteristic notify = findNotifyCharacteristic(g);
                    bleWrite = findWriteCharacteristic(g);
                    if (notify == null) {
                        pushWirelessStatus("HATA", "BLE veri/notify karakteristigi bulunamadi");
                        return;
                    }
                    try {
                        g.setCharacteristicNotification(notify, true);
                        BluetoothGattDescriptor cccd = notify.getDescriptor(CCCD_UUID);
                        if (cccd != null) {
                            if (Build.VERSION.SDK_INT >= 33) {
                                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            } else {
                                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                g.writeDescriptor(cccd);
                            }
                        }
                        pushWirelessStatus("BAGLI", "BLE canli veri kanali acildi: " + notify.getUuid());
                    } catch (Exception e) {
                        pushWirelessStatus("HATA", "BLE notify: " + safe(e));
                    }
                }

                @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                    pushRaw(c.getValue());
                }

                @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
                    pushRaw(value);
                }
            });
        } catch (Exception e) {
            pushWirelessStatus("HATA", "BLE baglanti: " + safe(e));
        }
    }

    private BluetoothGattCharacteristic findNotifyCharacteristic(BluetoothGatt g) {
        BluetoothGattCharacteristic fallback = null;
        try {
            for (BluetoothGattService s : g.getServices()) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int p = c.getProperties();
                    boolean canNotify = (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                            || (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                    if (!canNotify) continue;
                    if (c.getUuid().equals(NUS_TX) || c.getUuid().equals(FFE1)) return c;
                    if (fallback == null) fallback = c;
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private BluetoothGattCharacteristic findWriteCharacteristic(BluetoothGatt g) {
        BluetoothGattCharacteristic fallback = null;
        try {
            for (BluetoothGattService s : g.getServices()) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int p = c.getProperties();
                    boolean canWrite = (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                            || (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                    if (!canWrite) continue;
                    if (c.getUuid().equals(NUS_RX) || c.getUuid().equals(FFE1)) return c;
                    if (fallback == null) fallback = c;
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private boolean sendBle(byte[] bytes) {
        try {
            if (bleGatt == null || bleWrite == null || bytes == null) return false;
            if (Build.VERSION.SDK_INT >= 33) {
                int writeType = (bleWrite.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                return bleGatt.writeCharacteristic(bleWrite, bytes, writeType) == 0;
            }
            bleWrite.setValue(bytes);
            return bleGatt.writeCharacteristic(bleWrite);
        } catch (Exception e) {
            pushWirelessStatus("HATA", "BLE yazma: " + safe(e));
            return false;
        }
    }

    private String wirelessInfo() {
        JSONObject o = new JSONObject();
        try {
            o.put("bluetoothPresent", btAdapter != null);
            o.put("bluetoothEnabled", btAdapter != null && btAdapter.isEnabled());
            o.put("bluetoothPermission", hasBtPermission());
            ConsumerIrManager ir = (ConsumerIrManager) getSystemService(CONSUMER_IR_SERVICE);
            o.put("irPresent", ir != null && ir.hasIrEmitter());
            o.put("wifiIp", localIpv4());
            o.put("base", "MUBEL KANTAR 2.10.6 STABIL");
            o.put("wireless", "IR + BLUETOOTH + BLE + WIFI/TCP");
        } catch (Exception ignored) {}
        return o.toString();
    }

    private String localIpv4() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            if (wi != null) {
                int ip = wi.getIpAddress();
                if (ip != 0) return String.format("%d.%d.%d.%d", ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
            }
            List<NetworkInterface> ifaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : ifaces) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a.getHostAddress() != null && a.getHostAddress().indexOf(':') < 0) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String irInfo() {
        JSONObject o = new JSONObject();
        try {
            ConsumerIrManager ir = (ConsumerIrManager) getSystemService(CONSUMER_IR_SERVICE);
            boolean present = ir != null && ir.hasIrEmitter();
            o.put("present", present);
            JSONArray ranges = new JSONArray();
            if (present) {
                ConsumerIrManager.CarrierFrequencyRange[] rs = ir.getCarrierFrequencies();
                if (rs != null) for (ConsumerIrManager.CarrierFrequencyRange r : rs) {
                    JSONObject x = new JSONObject(); x.put("min", r.getMinFrequency()); x.put("max", r.getMaxFrequency()); ranges.put(x);
                }
            }
            o.put("ranges", ranges);
        } catch (Exception e) { try { o.put("error", safe(e)); } catch (Exception ignored) {} }
        return o.toString();
    }

    private boolean transmitIr(int frequency, String csv) {
        try {
            ConsumerIrManager ir = (ConsumerIrManager) getSystemService(CONSUMER_IR_SERVICE);
            if (ir == null || !ir.hasIrEmitter()) {
                pushWirelessStatus("IR_YOK", "Bu telefonda kizilotesi verici bulunamadi");
                return false;
            }
            if (frequency < 20000 || frequency > 60000) throw new IllegalArgumentException("IR frekans 20000-60000 Hz olmali");
            String[] p = csv == null ? new String[0] : csv.trim().split("[,;\\s]+");
            if (p.length < 2 || p.length > 1024) throw new IllegalArgumentException("IR pattern en az 2 sure degeri icermeli");
            int[] pattern = new int[p.length];
            for (int i = 0; i < p.length; i++) {
                pattern[i] = Integer.parseInt(p[i]);
                if (pattern[i] <= 0 || pattern[i] > 1000000) throw new IllegalArgumentException("Gecersiz IR sure degeri");
            }
            ir.transmit(frequency, pattern);
            pushWirelessStatus("IR_OK", "IR komutu gonderildi · " + frequency + " Hz");
            return true;
        } catch (SecurityException e) {
            pushWirelessStatus("HATA", "IR izin hatasi: TRANSMIT_IR");
            return false;
        } catch (Exception e) {
            pushWirelessStatus("HATA", "IR: " + safe(e));
            return false;
        }
    }

    private String safe(Throwable e) {
        if (e == null) return "Bilinmeyen hata";
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    @Override protected void onDestroy() {
        disconnectBluetooth();
        try { if (receiverRegistered) unregisterReceiver(classicReceiver); } catch (Exception ignored) {}
        receiverRegistered = false;
        wirelessIo.shutdownNow();
        super.onDestroy();
    }

    public class WirelessBridge {
        @JavascriptInterface public String info() { return wirelessInfo(); }
        @JavascriptInterface public String irInfo() { return MainActivityWireless.this.irInfo(); }
        @JavascriptInterface public boolean irTransmit(int frequency, String patternCsv) { return transmitIr(frequency, patternCsv); }
        @JavascriptInterface public boolean bluetoothPermissionReady() { return hasBtPermission(); }
        @JavascriptInterface public void requestBluetoothPermission() { requestBtPermission(); }
        @JavascriptInterface public void scanBluetooth() { scanBluetooth(); }
        @JavascriptInterface public void disconnectBluetooth() { MainActivityWireless.this.disconnectBluetooth(); }
        @JavascriptInterface public void connectClassic(String address) { MainActivityWireless.this.connectClassic(address); }
        @JavascriptInterface public void connectBle(String address) { MainActivityWireless.this.connectBle(address); }
        @JavascriptInterface public boolean sendBleText(String text) { return sendBle((text == null ? "" : text).getBytes()); }
        @JavascriptInterface public String appVersion() { return "2.10.6-WIRELESS-R01"; }
    }
}

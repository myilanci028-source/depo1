from pathlib import Path
import shutil, re, sys

root=Path(sys.argv[1])
extra=Path(sys.argv[2])

def rw(p, fn):
    p=root/p
    s=p.read_text(encoding='utf-8')
    n=fn(s)
    if n==s:
        raise SystemExit(f'Patch did not change {p}')
    p.write_text(n, encoding='utf-8')

# Add camera activity source without touching the 2.10.7 base copy until build workspace.
dst=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(extra/'CameraLiveActivity.java', dst)
shutil.copy2(extra/'v2108.js', root/'app/src/main/assets/v2108.js')

# Android 15/16 enforce edge-to-edge for modern targets. Keep title and controls clear of
# status/navigation bars while leaving the camera preview inside the safe visible area.
cam=dst.read_text(encoding='utf-8')
old='        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);'
new='''        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
            root.requestApplyInsets();
        } else {
            root.setFitsSystemWindows(true);
        }'''
if old not in cam: raise SystemExit('Camera root marker not found')
dst.write_text(cam.replace(old,new,1), encoding='utf-8')

# Build version + OCR dependency.
def patch_gradle(s):
    s=s.replace("applicationId 'com.mubel.kantar.v2107triple'", "applicationId 'com.mubel.kantar.v2108camera'")
    s=s.replace('versionCode 2107', 'versionCode 2108')
    s=s.replace("versionName '2.10.7-STABLE-TRIPLE'", "versionName '2.10.8-CAMERA-STABLE'")
    s=s.replace("implementation 'com.sun.mail:android-activation:1.6.7'", "implementation 'com.sun.mail:android-activation:1.6.7'\n    implementation 'com.google.mlkit:text-recognition:16.0.1'")
    return s
rw(Path('app/build.gradle'), patch_gradle)

# Camera permission/activity. IR/Bluetooth/Wi-Fi declarations remain exactly as inherited.
def patch_manifest(s):
    s=s.replace('<uses-permission android:name="android.permission.INTERNET"/>', '<uses-permission android:name="android.permission.INTERNET"/>\n    <uses-permission android:name="android.permission.CAMERA"/>')
    s=s.replace('<uses-feature android:name="android.hardware.consumerir" android:required="false"/>', '<uses-feature android:name="android.hardware.consumerir" android:required="false"/>\n    <uses-feature android:name="android.hardware.camera.any" android:required="false"/>')
    marker='        <activity\n            android:name=".MainActivity"'
    add='        <activity android:name=".CameraLiveActivity" android:exported="false" android:screenOrientation="portrait"/>\n        <activity\n            android:name=".MainActivity"'
    s=s.replace(marker, add)
    return s
rw(Path('app/src/main/AndroidManifest.xml'), patch_manifest)

# Append only the new camera layer to the existing stable web app.
def patch_index(s):
    return s.replace('<script src="v2107.js"></script>', '<script src="v2107.js"></script>\n<script src="v2108.js"></script>')
rw(Path('app/src/main/assets/index.html'), patch_index)

# Add a direct camera-weight injection that bypasses protocol scaling and leaves TCP/BT parsers untouched.
def patch_app(s):
    old="window.MUBEL={nativeStatus,onRawB64,setLogo,toast,data,findRecord,getRole:()=>userRole};loadCfg();refreshLocationLists()})();"
    new="function cameraWeight(v,frame){v=num(v);live=v;$('live').textContent=fmt(live);$('stable').textContent='STABİL';$('stable').className='stable ok';$('frameInfo').textContent='Son çözülen: '+fmt(v)+' kg · '+(frame||'KAMERA OCR');$('decoded').textContent='Çözülen: '+fmt(v)+' kg · '+(frame||'KAMERA OCR')}\nwindow.MUBEL={nativeStatus,onRawB64,setLogo,toast,data,findRecord,cameraWeight,getRole:()=>userRole};loadCfg();refreshLocationLists()})();"
    if old not in s: raise SystemExit('app.js export marker not found')
    return s.replace(old,new)
rw(Path('app/src/main/assets/app.js'), patch_app)

# MainActivity: add launch bridge, receive stable camera kg and an internal test-only launch extra.
def patch_main(s):
    s=s.replace('private static final int REQ_LOGO = 1101;', 'private static final int REQ_LOGO = 1101;\n    private static final int REQ_CAMERA = 1102;')

    create_marker='        handleTransferIntent(getIntent());\n    }'
    create_new='        handleTransferIntent(getIntent());\n        // Internal CI smoke path: MainActivity itself launches the non-exported camera screen.\n        // Normal users never see or trigger this unless the explicit intent extra is supplied.\n        if (getIntent() != null && getIntent().getBooleanExtra("mubel_smoke_camera", false)) {\n            ui.postDelayed(() -> {\n                try { startActivityForResult(new Intent(MainActivity.this, CameraLiveActivity.class), REQ_CAMERA); }\n                catch (Exception e) { toast("Kamera açılamadı: "+safe(e)); }\n            }, 700);\n        }\n    }'
    if create_marker not in s: raise SystemExit('onCreate marker not found')
    s=s.replace(create_marker, create_new, 1)

    old='        @JavascriptInterface public void pickLogo() { runOnUiThread(() -> {\n            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("image/*"); startActivityForResult(i, REQ_LOGO);\n        }); }'
    new='        @JavascriptInterface public void startCameraLive() { runOnUiThread(() -> { try { startActivityForResult(new Intent(MainActivity.this, CameraLiveActivity.class), REQ_CAMERA); } catch (Exception e) { toast("Kamera açılamadı: "+safe(e)); } }); }\n'+old
    if old not in s: raise SystemExit('AndroidBridge logo marker not found')
    s=s.replace(old,new)

    old2='        if (requestCode != REQ_LOGO || resultCode != RESULT_OK || data == null || data.getData() == null) return;\n        Uri u=data.getData();'
    new2='        if (requestCode == REQ_CAMERA) {\n            if (resultCode == RESULT_OK && data != null && data.hasExtra("kg")) {\n                double kg=data.getDoubleExtra("kg", Double.NaN);\n                if (!Double.isNaN(kg)) js("window.MUBEL&&MUBEL.cameraWeight("+kg+","+q("KAMERA OCR")+");");\n            }\n            return;\n        }\n        if (requestCode != REQ_LOGO || resultCode != RESULT_OK || data == null || data.getData() == null) return;\n        Uri u=data.getData();'
    if old2 not in s: raise SystemExit('onActivityResult marker not found')
    return s.replace(old2,new2)
rw(Path('app/src/main/java/com/mubel/kantar/MainActivity.java'), patch_main)

print('PATCH_2108_OK')

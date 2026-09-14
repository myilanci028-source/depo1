package com.mubel.kantar;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CameraLiveActivity extends Activity {
    private static final int REQ_CAMERA = 771;
    private static final long OCR_PERIOD_MS = 230L;
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d{1,7}(?:[.,]\\d{1,2})?");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService visionExec = Executors.newSingleThreadExecutor();
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final ArrayDeque<Double> samples = new ArrayDeque<>();

    private TextureView texture;
    private TextView weightText;
    private TextView stateText;
    private TextView detailText;
    private Button flashButton;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder previewBuilder;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private String cameraId;
    private Size previewSize = new Size(1280, 720);
    private boolean flashAvailable;
    private boolean torch;
    private boolean processing;
    private int exposure;
    private Range<Integer> exposureRange = new Range<>(0, 0);
    private double latestStable = Double.NaN;
    private Bitmap history1;
    private Bitmap history2;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else startCameraIfReady();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private GradientDrawable bg(int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(16));
        if (stroke != Color.TRANSPARENT) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String text) {
        Button b = new Button(this); b.setText(text); b.setTextColor(Color.WHITE); b.setTextSize(14);
        b.setAllCaps(false); b.setBackground(bg(Color.rgb(42,46,52), Color.rgb(75,79,86)));
        return b;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        texture = new TextureView(this);
        root.addView(texture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(new GuideOverlay(this), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.VERTICAL); top.setPadding(dp(16), dp(14), dp(16), dp(12));
        top.setBackgroundColor(0xB814171B);
        TextView title = label("MUBEL KANTAR · KAMERA CANLI VERİ", 19, Color.WHITE, true);
        TextView info = label("Göstergeyi turuncu çerçevenin içine getir. 50 Hz anti-flicker + 3 kare parlaklık birleştirme aktif.", 12, 0xFFD8D9DC, false);
        top.addView(title); top.addView(info);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(top, tp);

        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(16), dp(13), dp(16), dp(16));
        panel.setBackgroundColor(0xE815171B);
        stateText = label("KAMERA HAZIRLANIYOR", 13, 0xFFFF9800, true); stateText.setGravity(Gravity.CENTER);
        weightText = label("— kg", 54, Color.WHITE, true); weightText.setGravity(Gravity.CENTER);
        detailText = label("Rakamlar yanıp sönse bile birkaç kare birleştirilerek okunur.", 11, 0xFFB7BBC1, false); detailText.setGravity(Gravity.CENTER);
        panel.addView(stateText); panel.addView(weightText); panel.addView(detailText);

        LinearLayout row1 = new LinearLayout(this); row1.setOrientation(LinearLayout.HORIZONTAL); row1.setGravity(Gravity.CENTER); row1.setPadding(0, dp(10), 0, 0);
        flashButton = button("Flaş: KAPALI");
        Button minus = button("Pozlama −"); Button plus = button("Pozlama +");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(52), 1f); bp.setMargins(dp(4),0,dp(4),0);
        row1.addView(flashButton, bp); row1.addView(minus, bp); row1.addView(plus, bp); panel.addView(row1);

        LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL); row2.setPadding(0, dp(9), 0, 0);
        Button use = button("STABİL KG'Yİ KULLAN"); use.setBackground(bg(0xFFFF9800, 0xFFFFB74D));
        Button exit = button("ÇIKIŞ");
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(0, dp(56), 2f); big.setMargins(dp(4),0,dp(4),0);
        LinearLayout.LayoutParams small = new LinearLayout.LayoutParams(0, dp(56), 1f); small.setMargins(dp(4),0,dp(4),0);
        row2.addView(use, big); row2.addView(exit, small); panel.addView(row2);

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(panel, pp); setContentView(root);

        flashButton.setOnClickListener(v -> toggleTorch());
        minus.setOnClickListener(v -> changeExposure(-1)); plus.setOnClickListener(v -> changeExposure(1));
        exit.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        use.setOnClickListener(v -> returnStable());

        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { startCameraIfReady(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });
    }

    private void returnStable() {
        if (Double.isNaN(latestStable)) {
            stateText.setText("ÖNCE STABİL DEĞER BEKLEYİN"); return;
        }
        Intent r = new Intent(); r.putExtra("kg", latestStable); setResult(RESULT_OK, r); finish();
    }

    private void startCameraIfReady() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || !texture.isAvailable()) return;
        if (camera != null) return;
        startCameraThread();
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String fallback = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                if (fallback == null) fallback = id;
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { cameraId = id; break; }
            }
            if (cameraId == null) cameraId = fallback;
            if (cameraId == null) throw new Exception("Kamera bulunamadı");
            CameraCharacteristics c = cm.getCameraCharacteristics(cameraId);
            Boolean fa = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE); flashAvailable = fa != null && fa;
            Range<Integer> er = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE); if (er != null) exposureRange = er;
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) previewSize = chooseSize(map.getOutputSizes(SurfaceTexture.class));
            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { camera = d; createPreview(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); camera = null; setState("KAMERA KESİLDİ", true); }
                @Override public void onError(CameraDevice d, int error) { d.close(); camera = null; setState("KAMERA HATASI: "+error, true); }
            }, cameraHandler);
        } catch (Exception e) { setState("KAMERA AÇILAMADI: "+safe(e), true); }
    }

    private Size chooseSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280,720);
        Size best = sizes[0]; long target = 1280L * 720L, diff = Long.MAX_VALUE;
        for (Size s : sizes) { long px = (long)s.getWidth()*s.getHeight(); long d = Math.abs(px-target); if (d < diff) { diff=d; best=s; } }
        return best;
    }

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("MubelCamera"); cameraThread.start(); cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void createPreview() {
        try {
            SurfaceTexture st = texture.getSurfaceTexture(); if (st == null || camera == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(st);
            previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); previewBuilder.addTarget(surface);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            previewBuilder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ);
            camera.createCaptureSession(java.util.Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) { session=s; applyCapture(); main.post(ocrLoop); setState("OKUNUYOR · ANTİ-FLICKER AKTİF", false); }
                @Override public void onConfigureFailed(CameraCaptureSession s) { setState("KAMERA ÖNİZLEME HATASI", true); }
            }, cameraHandler);
        } catch (Exception e) { setState("KAMERA ÖNİZLEME HATASI: "+safe(e), true); }
    }

    private void applyCapture() {
        try {
            if (previewBuilder == null || session == null) return;
            previewBuilder.set(CaptureRequest.FLASH_MODE, torch && flashAvailable ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            int lo = exposureRange.getLower(), hi = exposureRange.getUpper(); exposure = Math.max(lo, Math.min(hi, exposure));
            previewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposure);
            session.setRepeatingRequest(previewBuilder.build(), null, cameraHandler);
        } catch (Exception e) { setState("KAMERA AYARI UYGULANAMADI", true); }
    }

    private void toggleTorch() {
        if (!flashAvailable) { setState("BU KAMERADA FLAŞ YOK", true); return; }
        torch = !torch; flashButton.setText(torch ? "Flaş: AÇIK" : "Flaş: KAPALI"); applyCapture();
    }
    private void changeExposure(int d) { exposure += d; applyCapture(); detailText.setText("Pozlama: "+exposure+" · Parlama varsa eksi, görüntü karanlıksa artı kullan."); }

    private final Runnable ocrLoop = new Runnable() {
        @Override public void run() {
            if (texture != null && texture.isAvailable() && !processing && camera != null) {
                processing = true;
                Bitmap frame = texture.getBitmap(960, 540);
                if (frame == null) processing=false; else visionExec.execute(() -> processFrame(frame));
            }
            main.postDelayed(this, OCR_PERIOD_MS);
        }
    };

    private void processFrame(Bitmap frame) {
        Bitmap crop = null, composite = null;
        try {
            int w=frame.getWidth(), h=frame.getHeight();
            int l=(int)(w*0.06), r=(int)(w*0.94), t=(int)(h*0.29), b=(int)(h*0.67);
            crop = Bitmap.createBitmap(frame, l, t, Math.max(1,r-l), Math.max(1,b-t));
            composite = brightestComposite(crop, history1, history2);
            Bitmap old2=history2; history2=history1; history1=crop.copy(Bitmap.Config.ARGB_8888,false); if(old2!=null)old2.recycle();
            InputImage img = InputImage.fromBitmap(composite,0);
            final Bitmap fFrame=frame, fCrop=crop, fComposite=composite;
            recognizer.process(img).addOnSuccessListener(this::handleText).addOnFailureListener(e -> main.post(() -> detailText.setText("OCR: "+safe(e))))
                    .addOnCompleteListener(task -> { try{fComposite.recycle();}catch(Exception ignored){} try{fCrop.recycle();}catch(Exception ignored){} try{fFrame.recycle();}catch(Exception ignored){} processing=false; });
        } catch(Exception e) {
            if(composite!=null)try{composite.recycle();}catch(Exception ignored){} if(crop!=null)try{crop.recycle();}catch(Exception ignored){} try{frame.recycle();}catch(Exception ignored){} processing=false;
        }
    }

    private Bitmap brightestComposite(Bitmap current, Bitmap a, Bitmap b) {
        int w=current.getWidth(), h=current.getHeight(), n=w*h;
        int[] c=new int[n], x=new int[n], y=new int[n], out=new int[n]; current.getPixels(c,0,w,0,0,w,h);
        boolean ha=a!=null&&a.getWidth()==w&&a.getHeight()==h, hb=b!=null&&b.getWidth()==w&&b.getHeight()==h;
        if(ha)a.getPixels(x,0,w,0,0,w,h); if(hb)b.getPixels(y,0,w,0,0,w,h);
        for(int i=0;i<n;i++){ int best=c[i], lum=lum(best); if(ha&&lum(x[i])>lum){best=x[i];lum=lum(best);} if(hb&&lum(y[i])>lum)best=y[i]; out[i]=best; }
        Bitmap z=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); z.setPixels(out,0,w,0,0,w,h); return z;
    }
    private int lum(int p){ return (Color.red(p)*3 + Color.green(p)*6 + Color.blue(p))/10; }

    private void handleText(Text text) {
        double best=Double.NaN; int bestScore=-1; String bestRaw="";
        for(Text.TextBlock block:text.getTextBlocks()) for(Text.Line line:block.getLines()) for(Text.Element el:line.getElements()) {
            String norm=normalize(el.getText()); Matcher m=NUMBER.matcher(norm);
            while(m.find()) {
                String s=m.group(); try {
                    double v=Double.parseDouble(s.replace(',','.')); if(Math.abs(v)>100000)continue;
                    android.graphics.Rect box=el.getBoundingBox(); int area=box==null?0:box.width()*box.height(); int score=area + s.replaceAll("\\D","").length()*10000;
                    if(score>bestScore){bestScore=score;best=v;bestRaw=el.getText();}
                } catch(Exception ignored){}
            }
        }
        if(Double.isNaN(best)) { main.post(() -> { stateText.setText("RAKAM ARANIYOR"); detailText.setText("Göstergeyi çerçeveye yaklaştır; parlama varsa flaşı kapat ve pozlamayı azalt."); }); return; }
        final double candidate=best; final String raw=bestRaw; main.post(() -> pushSample(candidate, raw));
    }

    private String normalize(String s) {
        if(s==null)return ""; return s.toUpperCase(Locale.ROOT).replace("O","0").replace("I","1").replace("L","1").replace("S","5").replace("B","8").replace("Z","2").replace(" ","");
    }

    private void pushSample(double v, String raw) {
        samples.addLast(v); while(samples.size()>9)samples.removeFirst();
        weightText.setText(format(v)+" kg"); stateText.setText("OKUNUYOR…"); detailText.setText("OCR: "+raw+" · Çoklu kare kontrol ediliyor");
        Map<Long,Integer> count=new HashMap<>(); for(Double x:samples){long k=Math.round(x*10.0);count.put(k,count.getOrDefault(k,0)+1);}
        long key=0; int n=0; for(Map.Entry<Long,Integer> e:count.entrySet())if(e.getValue()>n){n=e.getValue();key=e.getKey();}
        if(n>=3 && samples.size()>=4){ latestStable=key/10.0; weightText.setText(format(latestStable)+" kg"); stateText.setText("STABİL · KAMERA CANLI VERİ"); detailText.setText("3 kare parlaklık birleştirme + tekrar doğrulama: "+n+" eşleşme"); }
    }
    private String format(double v){ if(Math.abs(v-Math.rint(v))<0.049)return String.format(Locale.US,"%.0f",v); return String.format(Locale.US,"%.1f",v); }

    private void setState(String s, boolean error) { main.post(() -> { stateText.setText(s); stateText.setTextColor(error?0xFFFF5252:0xFFFF9800); }); }
    private String safe(Throwable e){String m=e==null?"":e.getMessage();return m==null||m.trim().isEmpty()?(e==null?"Hata":e.getClass().getSimpleName()):m;}

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);if(requestCode==REQ_CAMERA&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startCameraIfReady();else if(requestCode==REQ_CAMERA)setState("KAMERA İZNİ VERİLMEDİ",true);}
    @Override protected void onPause(){main.removeCallbacks(ocrLoop);closeCamera();super.onPause();}
    @Override protected void onResume(){super.onResume();if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCameraIfReady();}
    @Override protected void onDestroy(){main.removeCallbacks(ocrLoop);closeCamera();try{recognizer.close();}catch(Exception ignored){}visionExec.shutdownNow();if(history1!=null)history1.recycle();if(history2!=null)history2.recycle();super.onDestroy();}
    private void closeCamera(){try{if(session!=null)session.close();}catch(Exception ignored){}session=null;try{if(camera!=null)camera.close();}catch(Exception ignored){}camera=null;if(cameraThread!=null){cameraThread.quitSafely();cameraThread=null;cameraHandler=null;}}

    private class GuideOverlay extends View {
        Paint dim=new Paint(), line=new Paint(); GuideOverlay(Activity c){super(c);dim.setColor(0x66000000);line.setColor(0xFFFF9800);line.setStyle(Paint.Style.STROKE);line.setStrokeWidth(dp(3));}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float l=getWidth()*0.06f,r=getWidth()*0.94f,t=getHeight()*0.29f,b=getHeight()*0.67f;c.drawRect(0,0,getWidth(),t,dim);c.drawRect(0,b,getWidth(),getHeight(),dim);c.drawRect(0,t,l,b,dim);c.drawRect(r,t,getWidth(),b,dim);c.drawRoundRect(new RectF(l,t,r,b),dp(18),dp(18),line);}
    }
}

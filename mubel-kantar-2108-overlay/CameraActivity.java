package com.mubel.kantar;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MUBEL KANTAR 2.10.8 - camera live weight reader.
 *
 * Design goals for crane/industrial scale displays:
 *  - 50 Hz anti-banding request for Turkey/Europe mains lighting.
 *  - KEEP_ONLY_LATEST analysis so old frames never pile up.
 *  - Multi-frame voting: intermittent / multiplexed LED frames are ignored.
 *  - Torch, zoom, exposure and tap-to-focus controls.
 *  - Offline bundled ML Kit text recognition. No cloud connection is required.
 *  - The stable 2.10.7 parser remains authoritative; this Activity only sends a
 *    verified camera kg candidate back to MainActivity.
 */
public class CameraActivity extends ComponentActivity {
    private static final int REQ_CAMERA = 8108;
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d{1,6}(?:[\\.,]\\d{1,2})?");

    private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    private FrameLayout root;
    private PreviewView previewView;
    private TextView liveText;
    private TextView stateText;
    private TextView detailText;
    private Button torchButton;
    private Button stepButton;
    private Button evMinusButton;
    private Button evPlusButton;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private TextRecognizer recognizer;
    private ResultReceiver receiver;

    private boolean torchOn = false;
    private int stepKg = 2;
    private long lastAnalyzeMs = 0L;
    private long lastPublishMs = 0L;
    private double lastPublished = Double.NaN;

    private static final class Sample {
        final double value;
        final long time;
        final String source;
        Sample(double value, long time, String source) { this.value=value; this.time=time; this.source=source; }
    }

    private static final class Candidate {
        final double value;
        final String raw;
        final int score;
        Candidate(double value, String raw, int score) { this.value=value; this.raw=raw; this.score=score; }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { receiver = getIntent().getParcelableExtra("receiver"); } catch (Exception ignored) {}
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        buildUi();
        sendState("KAMERA AÇILIYOR", "Kamera izni ve arka kamera hazırlanıyor.");
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        GuideView guide = new GuideView(this);
        root.addView(guide, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(14), dp(18), dp(14), dp(8));
        top.setBackgroundColor(0xB8121418);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(top, topLp);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("MUBEL KANTAR · KAMERA CANLI VERİ", 19, Color.WHITE, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = button("KAPAT");
        close.setOnClickListener(v -> finishCamera());
        titleRow.addView(close);
        top.addView(titleRow);

        TextView sub = text("LED titreşim filtresi: 50 Hz + çoklu kare oylaması · Rakamları turuncu çerçeveye doldur", 12, 0xFFD4D7DC, false);
        sub.setPadding(0, dp(5), 0, 0);
        top.addView(sub);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(14), dp(10), dp(14), dp(18));
        bottom.setBackgroundColor(0xD9121418);
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(bottom, bottomLp);

        stateText = text("VERİ BEKLENİYOR", 13, 0xFFFF9800, true);
        bottom.addView(stateText);
        liveText = text("— kg", 42, Color.WHITE, true);
        liveText.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.addView(liveText);
        detailText = text("Kantar ekranına dokunursan odaklanır. Rakam yanıp sönse bile tam görünen kareler birleştirilir.", 12, 0xFFC4C8CE, false);
        detailText.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.addView(detailText);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);
        row1.setPadding(0, dp(8), 0, 0);
        torchButton = button("FLAŞ: KAPALI");
        torchButton.setOnClickListener(v -> toggleTorch());
        row1.addView(torchButton, weighted());
        Button zoomMinus = button("ZOOM −");
        zoomMinus.setOnClickListener(v -> changeZoom(false));
        row1.addView(zoomMinus, weighted());
        Button zoomPlus = button("ZOOM +");
        zoomPlus.setOnClickListener(v -> changeZoom(true));
        row1.addView(zoomPlus, weighted());
        bottom.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        row2.setPadding(0, dp(6), 0, 0);
        evMinusButton = button("PARLAKLIK −");
        evMinusButton.setOnClickListener(v -> changeExposure(-1));
        row2.addView(evMinusButton, weighted());
        evPlusButton = button("PARLAKLIK +");
        evPlusButton.setOnClickListener(v -> changeExposure(1));
        row2.addView(evPlusButton, weighted());
        stepButton = button("ADIM: 2 kg");
        stepButton.setOnClickListener(v -> cycleStep());
        row2.addView(stepButton, weighted());
        bottom.addView(row2);

        previewView.setOnTouchListener((v,e) -> {
            if (e.getAction()==MotionEvent.ACTION_UP) focusAt(e.getX(),e.getY());
            return true;
        });
        setContentView(root);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(3),0,dp(3),0);
        return lp;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this); b.setText(s); b.setTextColor(Color.WHITE); b.setTextSize(12);
        b.setAllCaps(false); b.setBackgroundColor(0xFF2B2F35); b.setPadding(dp(7),0,dp(7),0);
        return b;
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                cameraProvider.unbindAll();

                Preview.Builder pb = new Preview.Builder();
                try {
                    Camera2Interop.Extender<Preview> ex = new Camera2Interop.Extender<>(pb);
                    ex.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ);
                    ex.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                } catch (Exception ignored) {}
                Preview preview = pb.build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis.Builder ab = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280,720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);
                try {
                    Camera2Interop.Extender<ImageAnalysis> ex = new Camera2Interop.Extender<>(ab);
                    ex.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ);
                    ex.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                } catch (Exception ignored) {}
                ImageAnalysis analysis = ab.build();
                analysis.setAnalyzer(analyzerExecutor, this::analyze);

                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                boolean hasFlash = camera.getCameraInfo().hasFlashUnit();
                torchButton.setEnabled(hasFlash);
                torchButton.setText(hasFlash ? "FLAŞ: KAPALI" : "FLAŞ YOK");
                sendState("KAMERA HAZIR", "50 Hz anti-flicker açık. Ekranı turuncu çerçeveye yaklaştırın.");
            } catch (Exception e) {
                sendState("KAMERA HATASI", safe(e));
                stateText.setText("KAMERA AÇILAMADI");
                detailText.setText(safe(e));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy image) {
        long now = SystemClock.elapsedRealtime();
        if (now-lastAnalyzeMs < 120 || !processing.compareAndSet(false,true)) { image.close(); return; }
        lastAnalyzeMs = now;
        Image media = image.getImage();
        if (media == null) { processing.set(false); image.close(); return; }
        InputImage input = InputImage.fromMediaImage(media, image.getImageInfo().getRotationDegrees());
        recognizer.process(input)
                .addOnSuccessListener(text -> {
                    Candidate c = bestCandidate(text);
                    if (c != null) accept(c.value, "OCR · "+c.raw);
                    else runOnUiThread(() -> {
                        stateText.setText("RAKAM ARANIYOR");
                        detailText.setText("Ekranı çerçeveye daha çok doldurun. Gerekirse ZOOM + veya PARLAKLIK − kullanın.");
                    });
                })
                .addOnFailureListener(e -> sendState("OCR HATASI", safe(e)))
                .addOnCompleteListener(t -> { processing.set(false); image.close(); });
    }

    private Candidate bestCandidate(Text text) {
        Candidate best = null;
        for (Text.TextBlock block : text.getTextBlocks()) {
            best = better(best, candidate(block.getText(), block.getBoundingBox()));
            for (Text.Line line : block.getLines()) {
                best = better(best, candidate(line.getText(), line.getBoundingBox()));
                for (Text.Element el : line.getElements()) best = better(best, candidate(el.getText(), el.getBoundingBox()));
            }
        }
        return best;
    }

    private Candidate better(Candidate a, Candidate b) { return b!=null && (a==null || b.score>a.score) ? b : a; }

    private Candidate candidate(String raw, Rect box) {
        if (raw == null) return null;
        String s = raw.toUpperCase(Locale.ROOT).replace(" ", "").replace('O','0');
        if (s.indexOf('0')>=0 || s.matches(".*\\d.*")) {
            s = s.replace('I','1').replace('L','1').replace('|','1');
        }
        Matcher m = NUMBER.matcher(s);
        Candidate best = null;
        while (m.find()) {
            String token = m.group().replace(',','.');
            try {
                double v = Double.parseDouble(token);
                if (!Double.isFinite(v) || Math.abs(v)>100000) continue;
                int area = box==null ? 1 : Math.max(1,box.width()*box.height());
                int score = area + (s.contains("KG") ? 2_000_000 : 0) + Math.min(6,token.length())*1000;
                Candidate c = new Candidate(v, raw, score);
                best = better(best,c);
            } catch (Exception ignored) {}
        }
        return best;
    }

    private void accept(double rawValue, String source) {
        if (!Double.isFinite(rawValue)) return;
        double value = snap(rawValue);
        long now = SystemClock.elapsedRealtime();
        synchronized (samples) {
            samples.addLast(new Sample(value,now,source));
            while (!samples.isEmpty() && (now-samples.getFirst().time>1800 || samples.size()>14)) samples.removeFirst();
            List<Double> vals = new ArrayList<>();
            for (Sample s : samples) vals.add(s.value);
            Collections.sort(vals);
            double median = vals.get(vals.size()/2);
            double tolerance = Math.max(.55, stepKg*.55);
            int votes = 0;
            for (Sample s : samples) if (Math.abs(s.value-median)<=tolerance) votes++;
            double stable = snap(median);

            runOnUiThread(() -> {
                liveText.setText(formatKg(value)+" kg");
                stateText.setText(votes>=3 ? "OKUNDU · STABİL" : "OKUNUYOR · "+votes+"/3");
                detailText.setText(source+" · Çoklu kare oylaması "+votes+" · d="+stepKg+" kg");
            });

            if (votes>=3 && (!Double.isFinite(lastPublished) || Math.abs(stable-lastPublished)>.0001 || now-lastPublishMs>450)) {
                lastPublished = stable; lastPublishMs = now;
                sendWeight(stable, source, votes);
            }
        }
    }

    private double snap(double v) { return stepKg>0 ? Math.rint(v/stepKg)*stepKg : v; }
    private String formatKg(double v) { return Math.abs(v-Math.rint(v))<.001 ? String.format(Locale.US,"%.0f",v) : String.format(Locale.US,"%.1f",v); }

    private void sendWeight(double kg, String source, int votes) {
        if (receiver == null) return;
        Bundle b = new Bundle(); b.putDouble("kg",kg); b.putString("source",source); b.putInt("votes",votes); b.putString("state","KAMERA CANLI");
        try { receiver.send(1,b); } catch (Exception ignored) {}
    }

    private void sendState(String state, String detail) {
        if (receiver != null) { Bundle b=new Bundle(); b.putString("state",state); b.putString("detail",detail); try{receiver.send(2,b);}catch(Exception ignored){} }
        runOnUiThread(() -> { if(stateText!=null)stateText.setText(state); if(detailText!=null && detail!=null && !detail.isEmpty())detailText.setText(detail); });
    }

    private void toggleTorch() {
        if (camera==null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchOn=!torchOn; camera.getCameraControl().enableTorch(torchOn); torchButton.setText(torchOn?"FLAŞ: AÇIK":"FLAŞ: KAPALI");
    }

    private void changeZoom(boolean plus) {
        if (camera==null) return;
        ZoomState z = camera.getCameraInfo().getZoomState().getValue(); if(z==null)return;
        float next = z.getZoomRatio() * (plus ? 1.25f : .8f);
        next = Math.max(z.getMinZoomRatio(), Math.min(z.getMaxZoomRatio(),next));
        camera.getCameraControl().setZoomRatio(next);
        detailText.setText(String.format(Locale.US,"Zoom %.1fx · Ekranı turuncu çerçeveye doldurun",next));
    }

    private void changeExposure(int delta) {
        if (camera==null) return;
        ExposureState es = camera.getCameraInfo().getExposureState();
        int next = es.getExposureCompensationIndex()+delta;
        next = Math.max(es.getExposureCompensationRange().getLower(), Math.min(es.getExposureCompensationRange().getUpper(),next));
        camera.getCameraControl().setExposureCompensationIndex(next);
        detailText.setText("Kamera parlaklığı / EV: "+next+" · LED rakamlar patlıyorsa PARLAKLIK − kullanın");
    }

    private void cycleStep() {
        int[] a={1,2,5,10}; int ix=0; for(int i=0;i<a.length;i++)if(a[i]==stepKg)ix=i;
        stepKg=a[(ix+1)%a.length]; samples.clear(); stepButton.setText("ADIM: "+stepKg+" kg");
        detailText.setText("Kantar çözünürlüğü d="+stepKg+" kg olarak ayarlandı. OCS-A 5T için 2 kg kullanın.");
    }

    private void focusAt(float x,float y) {
        if(camera==null)return;
        try {
            MeteringPoint p=previewView.getMeteringPointFactory().createPoint(x,y);
            FocusMeteringAction a=new FocusMeteringAction.Builder(p,FocusMeteringAction.FLAG_AF|FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build();
            camera.getCameraControl().startFocusAndMetering(a);
            detailText.setText("Odaklanıyor… Kantar ekranını sabit tutun.");
        } catch(Exception ignored) {}
    }

    private void finishCamera() {
        sendState("KAMERA KAPALI", Double.isFinite(lastPublished)?"Son aktarılan: "+formatKg(lastPublished)+" kg":"Henüz stabil kilo aktarılmadı");
        finish();
    }

    @Override public void onBackPressed() { finishCamera(); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQ_CAMERA) {
            if(grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED) startCamera();
            else sendState("KAMERA İZNİ GEREKİYOR", "Kamera canlı kilo okumak için kamera izni verilmelidir.");
        }
    }

    @Override protected void onDestroy() {
        try { if(cameraProvider!=null)cameraProvider.unbindAll(); } catch(Exception ignored) {}
        try { if(recognizer!=null)recognizer.close(); } catch(Exception ignored) {}
        analyzerExecutor.shutdownNow();
        super.onDestroy();
    }

    private static String safe(Throwable e) {
        if(e==null)return "Bilinmeyen hata"; String m=e.getMessage(); return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m;
    }

    private static class GuideView extends View {
        private final Paint border=new Paint(Paint.ANTI_ALIAS_FLAG), shade=new Paint(), label=new Paint(Paint.ANTI_ALIAS_FLAG);
        GuideView(android.content.Context c){ super(c); border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(6f); border.setColor(0xFFFF9800); shade.setColor(0x55101419); label.setColor(Color.WHITE); label.setTextSize(34f); label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); setClickable(false); }
        @Override protected void onDraw(Canvas c){ super.onDraw(c); float w=getWidth(),h=getHeight(); RectF r=new RectF(w*.07f,h*.29f,w*.93f,h*.64f); c.drawRect(0,0,w,r.top,shade); c.drawRect(0,r.bottom,w,h,shade); c.drawRect(0,r.top,r.left,r.bottom,shade); c.drawRect(r.right,r.top,w,r.bottom,shade); c.drawRoundRect(r,20,20,border); String s="KANTAR RAKAMLARI"; float tw=label.measureText(s); c.drawText(s,(w-tw)/2f,r.top-18,label); }
    }
}

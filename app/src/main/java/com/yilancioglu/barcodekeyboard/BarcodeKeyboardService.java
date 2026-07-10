package com.yilancioglu.barcodekeyboard;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.inputmethodservice.InputMethodService;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BarcodeKeyboardService extends InputMethodService implements LifecycleOwner {
    public static final String PREFS_NAME = "scanner_settings";
    public static final String PREF_APPEND_ENTER = "append_enter";
    public static final String PREF_PAUSE_AFTER_SCAN = "pause_after_scan";

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private PreviewView previewView;
    private Camera camera;
    private BarcodeScanner scanner;
    private TextView statusView;
    private Button torchButton;
    private boolean scanningEnabled = true;
    private boolean torchEnabled = false;
    private float zoomRatio = 1f;
    private String lastValue = "";
    private long lastValueAt = 0L;
    private SharedPreferences preferences;
    private ToneGenerator toneGenerator;

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        cameraExecutor = Executors.newSingleThreadExecutor();
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85);
        } catch (RuntimeException ignored) {
            toneGenerator = null;
        }

        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .enableAllPotentialBarcodes()
                .build();
        scanner = BarcodeScanning.getClient(options);
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(9, 21, 38));
        root.setPadding(dp(8), dp(7), dp(8), dp(7));

        FrameLayout cameraFrame = new FrameLayout(this);
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        cameraFrame.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        cameraFrame.addView(new ScanOverlayView(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int cameraHeight = Math.max(dp(180), Math.min(dp(310), Math.round(screenHeight * 0.29f)));
        root.addView(cameraFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, cameraHeight));

        statusView = new TextView(this);
        statusView.setText("Barkodu turuncu çerçevenin ortasına getir");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(14);
        statusView.setSingleLine(true);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(6), dp(7), dp(6), dp(7));
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        Button scanButton = controlButton("DURDUR");
        scanButton.setOnClickListener(v -> {
            scanningEnabled = !scanningEnabled;
            scanButton.setText(scanningEnabled ? "DURDUR" : "TARA");
            setStatus(scanningEnabled ? "Tarama açık" : "Tarama durduruldu", false);
        });
        controls.addView(scanButton, weighted());

        torchButton = controlButton("FENER");
        torchButton.setOnClickListener(v -> toggleTorch());
        controls.addView(torchButton, weighted());

        Button minus = controlButton("−");
        minus.setTextSize(24);
        minus.setOnClickListener(v -> setZoom(zoomRatio - 0.35f));
        controls.addView(minus, weighted());

        Button plus = controlButton("+");
        plus.setTextSize(24);
        plus.setOnClickListener(v -> setZoom(zoomRatio + 0.35f));
        controls.addView(plus, weighted());

        Button backspace = controlButton("SİL");
        backspace.setOnClickListener(v -> deleteOne());
        controls.addView(backspace, weighted());

        Button enter = controlButton("ENTER");
        enter.setOnClickListener(v -> sendEnter());
        controls.addView(enter, weighted());

        Button next = controlButton("KLAVYE");
        next.setOnClickListener(v -> switchToNextInputMethod(false));
        controls.addView(next, weighted());

        root.addView(controls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        setZoom(zoomRatio * detector.getScaleFactor());
                        return true;
                    }
                });
        previewView.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            if (event.getAction() == android.view.MotionEvent.ACTION_UP && !scaleDetector.isInProgress()) {
                focusAt(event.getX(), event.getY());
                v.performClick();
            }
            return true;
        });

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Kamera izni gerekli — uygulamayı aç", true);
            cameraFrame.setOnClickListener(v -> openSetup());
        }
        return root;
    }

    @Override
    public void onStartInputView(android.view.inputmethod.EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
        scanningEnabled = true;
        if (previewView != null) previewView.post(this::startCamera);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        stopCamera();
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        super.onFinishInputView(finishingInput);
    }

    private void startCamera() {
        if (previewView == null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Kamera izni gerekli — uygulamayı aç", true);
            return;
        }
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                cameraProvider.unbindAll();

                Preview preview = new Preview.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                camera = cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis);
                zoomRatio = camera.getCameraInfo().getZoomState().getValue() != null
                        ? camera.getCameraInfo().getZoomState().getValue().getZoomRatio() : 1f;
                torchButton.setEnabled(camera.getCameraInfo().hasFlashUnit());
                setStatus("Hazır — barkodu ortaya getir", false);
            } catch (Exception e) {
                setStatus("Kamera açılamadı: " + safeMessage(e), true);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImage(ImageProxy imageProxy) {
        if (!scanningEnabled || !processing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }
        if (imageProxy.getImage() == null) {
            processing.set(false);
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();

        scanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    Barcode best = chooseBest(barcodes, width, height);
                    if (best != null && best.getRawValue() != null && !best.getRawValue().isEmpty()) {
                        acceptBarcode(best);
                    }
                })
                .addOnFailureListener(e -> setStatus("Okuma hatası: " + safeMessage(e), true))
                .addOnCompleteListener(task -> {
                    processing.set(false);
                    imageProxy.close();
                });
    }

    private Barcode chooseBest(List<Barcode> barcodes, int width, int height) {
        Barcode best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double centerX = width / 2.0;
        double centerY = height / 2.0;
        for (Barcode barcode : barcodes) {
            if (barcode.getRawValue() == null || barcode.getRawValue().isEmpty()) continue;
            Rect r = barcode.getBoundingBox();
            if (r == null) return barcode;
            double area = Math.max(1, r.width() * (double) r.height());
            double dx = r.centerX() - centerX;
            double dy = r.centerY() - centerY;
            double distance = Math.sqrt(dx * dx + dy * dy);
            boolean containsCenter = r.contains((int) centerX, (int) centerY);
            double score = Math.log(area) * 70.0 - distance + (containsCenter ? 10000.0 : 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = barcode;
            }
        }
        return best;
    }

    private void acceptBarcode(Barcode barcode) {
        String value = barcode.getRawValue();
        long now = SystemClock.elapsedRealtime();
        if (value.equals(lastValue) && now - lastValueAt < 1800) return;
        lastValue = value;
        lastValueAt = now;

        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            setStatus("Yazılacak alan bulunamadı", true);
            return;
        }
        connection.commitText(value, 1);
        if (preferences.getBoolean(PREF_APPEND_ENTER, true)) sendEnter();

        if (toneGenerator != null) toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        vibrate();
        setStatus(formatName(barcode.getFormat()) + " • " + shorten(value), false);

        if (preferences.getBoolean(PREF_PAUSE_AFTER_SCAN, true)) {
            scanningEnabled = false;
            if (statusView != null) statusView.postDelayed(() -> scanningEnabled = true, 650);
        }
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchEnabled = !torchEnabled;
        camera.getCameraControl().enableTorch(torchEnabled);
        torchButton.setText(torchEnabled ? "FENER ✓" : "FENER");
    }

    private void setZoom(float desired) {
        if (camera == null || camera.getCameraInfo().getZoomState().getValue() == null) return;
        float min = camera.getCameraInfo().getZoomState().getValue().getMinZoomRatio();
        float max = camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
        zoomRatio = Math.max(min, Math.min(max, desired));
        camera.getCameraControl().setZoomRatio(zoomRatio);
        setStatus(String.format(java.util.Locale.US, "Yakınlaştırma %.1fx", zoomRatio), false);
    }

    private void focusAt(float x, float y) {
        if (camera == null || previewView == null) return;
        MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build();
        camera.getCameraControl().startFocusAndMetering(action);
        setStatus("Odaklanıyor…", false);
    }

    private void stopCamera() {
        if (cameraProvider != null) cameraProvider.unbindAll();
        camera = null;
        torchEnabled = false;
    }

    private void deleteOne() {
        InputConnection c = getCurrentInputConnection();
        if (c == null) return;
        if (!c.deleteSurroundingText(1, 0)) {
            c.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
            c.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
        }
    }

    private void sendEnter() {
        InputConnection c = getCurrentInputConnection();
        if (c == null) return;
        c.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        c.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
    }

    private void vibrate() {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(70);
        }
    }

    private void openSetup() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void setStatus(String message, boolean error) {
        if (statusView == null) return;
        statusView.post(() -> {
            statusView.setText(message);
            statusView.setTextColor(error ? Color.rgb(255, 120, 105) : Color.WHITE);
        });
    }

    private Button controlButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(11);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setBackgroundColor(Color.rgb(24, 53, 91));
        return button;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private String shorten(String value) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 32 ? oneLine : oneLine.substring(0, 29) + "…";
    }

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private String formatName(int format) {
        return switch (format) {
            case Barcode.FORMAT_EAN_13 -> "EAN-13";
            case Barcode.FORMAT_EAN_8 -> "EAN-8";
            case Barcode.FORMAT_UPC_A -> "UPC-A";
            case Barcode.FORMAT_UPC_E -> "UPC-E";
            case Barcode.FORMAT_CODE_128 -> "CODE-128";
            case Barcode.FORMAT_CODE_39 -> "CODE-39";
            case Barcode.FORMAT_CODE_93 -> "CODE-93";
            case Barcode.FORMAT_CODABAR -> "CODABAR";
            case Barcode.FORMAT_ITF -> "ITF";
            case Barcode.FORMAT_QR_CODE -> "QR";
            case Barcode.FORMAT_DATA_MATRIX -> "DATA MATRIX";
            case Barcode.FORMAT_PDF417 -> "PDF417";
            case Barcode.FORMAT_AZTEC -> "AZTEC";
            default -> "BARKOD";
        };
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        stopCamera();
        if (scanner != null) scanner.close();
        if (toneGenerator != null) toneGenerator.release();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        super.onDestroy();
    }
}

package com.yilancioglu.barcodekeyboard;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BarcodeKeyboardService extends InputMethodService implements LifecycleOwner {
    public static final String PREFS_NAME = "scanner_settings_v2";
    public static final String PREF_SUFFIX_MODE = "suffix_mode";
    public static final String PREF_SOUND = "sound_enabled";
    public static final String PREF_VIBRATION = "vibration_enabled";
    public static final String PREF_DUPLICATE_GUARD = "duplicate_guard";
    public static final String PREF_CONTINUOUS_MODE = "continuous_mode";
    public static final String PREF_HISTORY = "scan_history";

    public static final String SUFFIX_ENTER = "ENTER";
    public static final String SUFFIX_TAB = "TAB";
    public static final String SUFFIX_SPACE = "SPACE";
    public static final String SUFFIX_NONE = "NONE";

    private static final int NAVY = Color.rgb(7, 18, 31);
    private static final int PANEL = Color.rgb(18, 42, 70);
    private static final int GOLD = Color.rgb(218, 169, 92);

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private PreviewView previewView;
    private Camera camera;
    private BarcodeScanner scanner;
    private TextView statusView;
    private Button torchButton;
    private Button scanButton;
    private boolean scanningEnabled = true;
    private boolean torchEnabled = false;
    private float zoomRatio = 1f;
    private String lastValue = "";
    private long lastValueAt = 0L;
    private int sessionCount = 0;
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
        root.setBackgroundColor(NAVY);
        root.setPadding(dp(7), dp(7), dp(7), dp(7));

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
        int cameraHeight = Math.max(dp(175), Math.min(dp(300), Math.round(screenHeight * 0.27f)));
        root.addView(cameraFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, cameraHeight));

        statusView = new TextView(this);
        statusView.setText("V2 hazır — barkodu turuncu çerçeveye getir");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(13);
        statusView.setSingleLine(true);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(6), dp(7), dp(6), dp(7));
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout firstRow = controlRow();
        scanButton = controlButton("DURDUR");
        scanButton.setOnClickListener(v -> {
            scanningEnabled = !scanningEnabled;
            scanButton.setText(scanningEnabled ? "DURDUR" : "TARA");
            setStatus(scanningEnabled ? "Tarama açık" : "Tarama durduruldu", false);
        });
        firstRow.addView(scanButton, weighted());

        torchButton = controlButton("FENER");
        torchButton.setOnClickListener(v -> toggleTorch());
        firstRow.addView(torchButton, weighted());

        Button zoom1 = controlButton("1×");
        zoom1.setOnClickListener(v -> setZoom(1f));
        firstRow.addView(zoom1, weighted());

        Button zoom2 = controlButton("2×");
        zoom2.setOnClickListener(v -> setZoom(2f));
        firstRow.addView(zoom2, weighted());

        Button zoom3 = controlButton("3×");
        zoom3.setOnClickListener(v -> setZoom(3f));
        firstRow.addView(zoom3, weighted());
        root.addView(firstRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout secondRow = controlRow();
        Button backspace = controlButton("SİL");
        backspace.setOnClickListener(v -> deleteOne());
        secondRow.addView(backspace, weighted());

        Button tab = controlButton("TAB");
        tab.setOnClickListener(v -> sendKey(KeyEvent.KEYCODE_TAB));
        secondRow.addView(tab, weighted());

        Button enter = controlButton("ENTER");
        enter.setOnClickListener(v -> sendKey(KeyEvent.KEYCODE_ENTER));
        secondRow.addView(enter, weighted());

        Button settings = controlButton("AYAR");
        settings.setOnClickListener(v -> openSetup());
        secondRow.addView(settings, weighted());

        Button next = controlButton("KLAVYE");
        next.setOnClickListener(v -> switchToNextInputMethod(false));
        secondRow.addView(next, weighted());
        LinearLayout.LayoutParams secondRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        secondRowParams.topMargin = dp(4);
        root.addView(secondRow, secondRowParams);

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
            setStatus("Kamera izni gerekli — AYAR düğmesine bas", true);
            cameraFrame.setOnClickListener(v -> openSetup());
        }
        return root;
    }

    @Override
    public void onStartInputView(android.view.inputmethod.EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
        scanningEnabled = true;
        sessionCount = 0;
        if (scanButton != null) scanButton.setText("DURDUR");
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
            setStatus("Kamera izni gerekli — AYAR düğmesine bas", true);
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
                if (camera.getCameraInfo().getZoomState().getValue() != null) {
                    zoomRatio = camera.getCameraInfo().getZoomState().getValue().getZoomRatio();
                } else {
                    zoomRatio = 1f;
                }
                torchButton.setEnabled(camera.getCameraInfo().hasFlashUnit());
                setStatus("V2 hazır — barkodu ortaya getir", false);
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
        boolean duplicateGuard = preferences.getBoolean(PREF_DUPLICATE_GUARD, true);
        if (duplicateGuard && value.equals(lastValue) && now - lastValueAt < 1800) return;
        lastValue = value;
        lastValueAt = now;

        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            setStatus("Yazılacak alan bulunamadı", true);
            return;
        }
        connection.commitText(value, 1);
        applySuffix(connection);

        if (preferences.getBoolean(PREF_SOUND, true) && toneGenerator != null) {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        }
        if (preferences.getBoolean(PREF_VIBRATION, true)) vibrate();

        sessionCount++;
        String format = formatName(barcode.getFormat());
        addHistory(format, value);
        setStatus("#" + sessionCount + "  " + format + " • " + shorten(value), false);

        boolean continuous = preferences.getBoolean(PREF_CONTINUOUS_MODE, false);
        if (!continuous) {
            scanningEnabled = false;
            if (statusView != null) {
                statusView.postDelayed(() -> scanningEnabled = true, 650);
            }
        }
    }

    private void applySuffix(InputConnection connection) {
        String suffix = preferences.getString(PREF_SUFFIX_MODE, SUFFIX_ENTER);
        if (SUFFIX_TAB.equals(suffix)) {
            sendKey(connection, KeyEvent.KEYCODE_TAB);
        } else if (SUFFIX_SPACE.equals(suffix)) {
            connection.commitText(" ", 1);
        } else if (SUFFIX_ENTER.equals(suffix)) {
            sendKey(connection, KeyEvent.KEYCODE_ENTER);
        }
    }

    private void addHistory(String format, String value) {
        String cleanValue = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (cleanValue.length() > 70) cleanValue = cleanValue.substring(0, 67) + "…";
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String entry = time + "  •  " + format + "  •  " + cleanValue;
        String oldHistory = preferences.getString(PREF_HISTORY, "");
        StringBuilder updated = new StringBuilder(entry);
        if (oldHistory != null && !oldHistory.trim().isEmpty()) {
            String[] lines = oldHistory.split("\\n");
            int limit = Math.min(lines.length, 19);
            for (int i = 0; i < limit; i++) {
                updated.append('\n').append(lines[i]);
            }
        }
        preferences.edit().putString(PREF_HISTORY, updated.toString()).apply();
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
        setStatus(String.format(Locale.US, "Yakınlaştırma %.1f×", zoomRatio), false);
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
        if (torchButton != null) torchButton.setText("FENER");
    }

    private void deleteOne() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        if (!connection.deleteSurroundingText(1, 0)) {
            sendKey(connection, KeyEvent.KEYCODE_DEL);
        }
    }

    private void sendKey(int keyCode) {
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) sendKey(connection, keyCode);
    }

    private void sendKey(InputConnection connection, int keyCode) {
        connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
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

    private LinearLayout controlRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private Button controlButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(11);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setPadding(dp(2), 0, dp(2), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(PANEL);
        background.setCornerRadius(dp(9));
        background.setStroke(dp(1), GOLD);
        button.setBackground(background);
        return button;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private String shorten(String value) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 32 ? oneLine : oneLine.substring(0, 29) + "…";
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message;
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

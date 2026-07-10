package com.yilancioglu.barcodekeyboard;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends Activity {
    private static final int CAMERA_REQUEST = 401;
    private TextView statusView;
    private SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(BarcodeKeyboardService.PREFS_NAME, MODE_PRIVATE);
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(246, 248, 252));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = text("YILANCIOĞLU\nBarkod Klavye", 28, Color.rgb(16, 35, 63));
        title.setGravity(Gravity.CENTER);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title, matchWrap(dp(12)));

        TextView description = text(
                "Kamerayı klavye gibi kullanır. Okunan barkodu açık olan metin kutusuna doğrudan yazar. " +
                        "İnternet izni yoktur; tarama cihazın içinde yapılır.",
                16, Color.DKGRAY);
        description.setGravity(Gravity.CENTER);
        root.addView(description, matchWrap(dp(20)));

        statusView = text("Durum kontrol ediliyor…", 16, Color.rgb(16, 35, 63));
        statusView.setPadding(dp(14), dp(14), dp(14), dp(14));
        statusView.setBackgroundColor(Color.WHITE);
        root.addView(statusView, matchWrap(dp(18)));

        Button permission = button("1 — Kamera iznini ver");
        permission.setOnClickListener(v -> requestCamera());
        root.addView(permission, matchWrap(dp(10)));

        Button enable = button("2 — Klavyeyi etkinleştir");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(enable, matchWrap(dp(10)));

        Button select = button("3 — Barkod klavyeyi seç");
        select.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        });
        root.addView(select, matchWrap(dp(18)));

        TextView optionsTitle = text("Tarama ayarları", 19, Color.rgb(16, 35, 63));
        optionsTitle.setTypeface(optionsTitle.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(optionsTitle, matchWrap(dp(8)));

        CheckBox appendEnter = new CheckBox(this);
        appendEnter.setText("Barkoddan sonra Enter gönder");
        appendEnter.setTextSize(16);
        appendEnter.setChecked(preferences.getBoolean(BarcodeKeyboardService.PREF_APPEND_ENTER, true));
        appendEnter.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(BarcodeKeyboardService.PREF_APPEND_ENTER, isChecked).apply());
        root.addView(appendEnter, matchWrap(dp(4)));

        CheckBox pauseAfterScan = new CheckBox(this);
        pauseAfterScan.setText("Her okumadan sonra kısa süre bekle");
        pauseAfterScan.setTextSize(16);
        pauseAfterScan.setChecked(preferences.getBoolean(BarcodeKeyboardService.PREF_PAUSE_AFTER_SCAN, true));
        pauseAfterScan.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(BarcodeKeyboardService.PREF_PAUSE_AFTER_SCAN, isChecked).apply());
        root.addView(pauseAfterScan, matchWrap(dp(18)));

        TextView testTitle = text("Deneme alanı", 19, Color.rgb(16, 35, 63));
        testTitle.setTypeface(testTitle.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(testTitle, matchWrap(dp(8)));

        EditText test = new EditText(this);
        test.setHint("Buraya dokun, barkod klavyeyi seç ve okut…");
        test.setTextSize(17);
        test.setMinLines(4);
        test.setGravity(Gravity.TOP);
        test.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        test.setPadding(dp(14), dp(14), dp(14), dp(14));
        test.setBackgroundColor(Color.WHITE);
        root.addView(test, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150)));

        TextView supported = text(
                "Desteklenenler: EAN-13, EAN-8, UPC-A, UPC-E, Code 128, Code 39, Code 93, " +
                        "Codabar, ITF, QR, Data Matrix, PDF417 ve Aztec.",
                14, Color.GRAY);
        supported.setPadding(0, dp(16), 0, 0);
        root.addView(supported, matchWrap(0));
        return scroll;
    }

    private void requestCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            updateStatus();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateStatus();
    }

    private void updateStatus() {
        if (statusView == null) return;
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean enabled = isKeyboardEnabled();
        boolean selected = isKeyboardSelected();

        StringBuilder sb = new StringBuilder();
        sb.append(camera ? "✓ Kamera izni hazır" : "○ Kamera izni gerekli");
        sb.append('\n').append(enabled ? "✓ Klavye etkin" : "○ Klavye henüz etkin değil");
        sb.append('\n').append(selected ? "✓ Şu anda seçili" : "○ Barkod klavyeyi seçmen gerekiyor");
        statusView.setText(sb.toString());
        statusView.setTextColor(camera && enabled ? Color.rgb(20, 110, 55) : Color.rgb(145, 75, 0));
    }

    private boolean isKeyboardEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> enabled = imm.getEnabledInputMethodList();
        for (InputMethodInfo info : enabled) {
            if (getPackageName().equals(info.getPackageName())) return true;
        }
        return false;
    }

    private boolean isKeyboardSelected() {
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return current != null && current.startsWith(getPackageName() + "/");
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(16, 35, 63));
        b.setMinHeight(dp(52));
        return b;
    }

    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = bottomMargin;
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

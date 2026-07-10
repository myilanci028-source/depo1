package com.yilancioglu.barcodekeyboard;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends Activity {
    private static final int CAMERA_REQUEST = 401;
    private static final int NAVY = Color.rgb(8, 20, 34);
    private static final int CARD = Color.rgb(18, 39, 64);
    private static final int GOLD = Color.rgb(218, 169, 92);
    private static final int MUTED = Color.rgb(190, 201, 214);

    private TextView statusView;
    private TextView historyView;
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
        updateHistory();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(NAVY);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView badge = text("YILANCIOĞLU", 15, GOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(badge, matchWrap(dp(4)));

        TextView title = text("BARKOD KLAVYE V2", 29, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title, matchWrap(dp(8)));

        TextView description = text(
                "Hızlı seri tarama, seçilebilir Enter/Tab davranışı ve yerel okuma geçmişi. " +
                        "İnternet izni yoktur; veriler cihazda kalır.",
                15, MUTED);
        description.setGravity(Gravity.CENTER);
        root.addView(description, matchWrap(dp(18)));

        statusView = text("Durum kontrol ediliyor…", 16, Color.WHITE);
        statusView.setPadding(dp(15), dp(14), dp(15), dp(14));
        statusView.setBackground(rounded(CARD, GOLD, 14));
        root.addView(statusView, matchWrap(dp(14)));

        Button permission = primaryButton("1 — Kamera iznini ver");
        permission.setOnClickListener(v -> requestCamera());
        root.addView(permission, matchWrap(dp(8)));

        Button enable = primaryButton("2 — V2 klavyeyi etkinleştir");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(enable, matchWrap(dp(8)));

        Button select = primaryButton("3 — V2 barkod klavyeyi seç");
        select.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        });
        root.addView(select, matchWrap(dp(20)));

        root.addView(sectionTitle("OKUMA SONRASI"), matchWrap(dp(8)));

        TextView suffixLabel = text("Barkoddan sonra gönderilecek tuş", 14, MUTED);
        root.addView(suffixLabel, matchWrap(dp(6)));

        String[] suffixLabels = {"Enter", "Tab", "Boşluk", "Hiçbiri"};
        String[] suffixValues = {
                BarcodeKeyboardService.SUFFIX_ENTER,
                BarcodeKeyboardService.SUFFIX_TAB,
                BarcodeKeyboardService.SUFFIX_SPACE,
                BarcodeKeyboardService.SUFFIX_NONE
        };
        Spinner suffixSpinner = new Spinner(this);
        ArrayAdapter<String> suffixAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, suffixLabels);
        suffixAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        suffixSpinner.setAdapter(suffixAdapter);
        suffixSpinner.setPadding(dp(12), dp(5), dp(12), dp(5));
        suffixSpinner.setBackground(rounded(Color.WHITE, GOLD, 12));
        String currentSuffix = preferences.getString(
                BarcodeKeyboardService.PREF_SUFFIX_MODE,
                BarcodeKeyboardService.SUFFIX_ENTER);
        int selectedSuffix = 0;
        for (int i = 0; i < suffixValues.length; i++) {
            if (suffixValues[i].equals(currentSuffix)) selectedSuffix = i;
        }
        suffixSpinner.setSelection(selectedSuffix);
        suffixSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position ->
                preferences.edit().putString(
                        BarcodeKeyboardService.PREF_SUFFIX_MODE,
                        suffixValues[position]).apply()));
        root.addView(suffixSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        addBottomSpace(root, 12);

        root.addView(sectionTitle("GERİ BİLDİRİM VE HIZ"), matchWrap(dp(6)));
        root.addView(optionCheckBox(
                "Başarılı okumada ses çal",
                BarcodeKeyboardService.PREF_SOUND,
                true), matchWrap(0));
        root.addView(optionCheckBox(
                "Başarılı okumada titreşim yap",
                BarcodeKeyboardService.PREF_VIBRATION,
                true), matchWrap(0));
        root.addView(optionCheckBox(
                "Aynı barkodun art arda yazılmasını engelle",
                BarcodeKeyboardService.PREF_DUPLICATE_GUARD,
                true), matchWrap(0));
        root.addView(optionCheckBox(
                "Seri okuma modu — okumadan sonra bekleme",
                BarcodeKeyboardService.PREF_CONTINUOUS_MODE,
                false), matchWrap(dp(16)));

        root.addView(sectionTitle("DENEME ALANI"), matchWrap(dp(8)));
        EditText test = new EditText(this);
        test.setHint("Buraya dokun, V2 klavyeyi seç ve barkodu okut…");
        test.setHintTextColor(Color.rgb(120, 130, 140));
        test.setTextColor(Color.BLACK);
        test.setTextSize(17);
        test.setMinLines(4);
        test.setGravity(Gravity.TOP);
        test.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        test.setPadding(dp(14), dp(14), dp(14), dp(14));
        test.setBackground(rounded(Color.WHITE, GOLD, 14));
        root.addView(test, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(145)));
        addBottomSpace(root, 18);

        LinearLayout historyHeader = new LinearLayout(this);
        historyHeader.setOrientation(LinearLayout.HORIZONTAL);
        historyHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView historyTitle = sectionTitle("SON OKUMALAR");
        historyHeader.addView(historyTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button clearHistory = smallButton("TEMİZLE");
        clearHistory.setOnClickListener(v -> {
            preferences.edit().remove(BarcodeKeyboardService.PREF_HISTORY).apply();
            updateHistory();
        });
        historyHeader.addView(clearHistory, new LinearLayout.LayoutParams(dp(96), dp(42)));
        root.addView(historyHeader, matchWrap(dp(8)));

        historyView = text("Henüz okuma yok.", 14, Color.WHITE);
        historyView.setPadding(dp(14), dp(12), dp(14), dp(12));
        historyView.setBackground(rounded(CARD, Color.rgb(45, 72, 102), 14));
        root.addView(historyView, matchWrap(dp(16)));

        TextView supported = text(
                "EAN-13 • EAN-8 • UPC-A • UPC-E • Code 128 • Code 39 • Code 93 • " +
                        "Codabar • ITF • QR • Data Matrix • PDF417 • Aztec",
                13, MUTED);
        supported.setGravity(Gravity.CENTER);
        root.addView(supported, matchWrap(dp(8)));

        TextView version = text("V2.0 TEST • ÇEVRİMDIŞI", 12, GOLD);
        version.setGravity(Gravity.CENTER);
        root.addView(version, matchWrap(0));
        return scroll;
    }

    private CheckBox optionCheckBox(String label, String key, boolean defaultValue) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setTextColor(Color.WHITE);
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(GOLD));
        checkBox.setTextSize(15);
        checkBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        checkBox.setChecked(preferences.getBoolean(key, defaultValue));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(key, isChecked).apply());
        return checkBox;
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
        sb.append('\n').append(enabled ? "✓ V2 klavye etkin" : "○ V2 klavye henüz etkin değil");
        sb.append('\n').append(selected ? "✓ V2 şu anda seçili" : "○ V2 barkod klavyeyi seçmen gerekiyor");
        statusView.setText(sb.toString());
        statusView.setTextColor(camera && enabled ? Color.rgb(115, 232, 148) : Color.rgb(255, 205, 110));
    }

    private void updateHistory() {
        if (historyView == null) return;
        String history = preferences.getString(BarcodeKeyboardService.PREF_HISTORY, "");
        historyView.setText(history == null || history.trim().isEmpty()
                ? "Henüz okuma yok."
                : history);
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

    private TextView sectionTitle(String value) {
        TextView t = text(value, 17, GOLD);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(CARD, GOLD, 13));
        b.setMinHeight(dp(54));
        return b;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(NAVY);
        b.setBackground(rounded(GOLD, GOLD, 10));
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

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = bottomMargin;
        return p;
    }

    private void addBottomSpace(LinearLayout root, int valueDp) {
        View space = new View(this);
        root.addView(space, new LinearLayout.LayoutParams(1, dp(valueDp)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class SimpleItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        interface SelectionHandler { void onSelected(int position); }
        private final SelectionHandler handler;

        SimpleItemSelectedListener(SelectionHandler handler) {
            this.handler = handler;
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            handler.onSelected(position);
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
            // Keep the previous preference.
        }
    }
}

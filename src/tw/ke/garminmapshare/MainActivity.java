package tw.ke.garminmapshare;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH_CONNECT = 1001;
    private static final String PREFS = "garmin_map_share";
    private static final String PREF_DEVICE_ADDRESS = "device_address";
    private static final String PREF_LOG = "log";
    private static final String PREF_LOG_ENABLED = "log_enabled";
    private static final String SMARTPHONE_LINK_PACKAGE = "com.garmin.android.apps.phonelink";
    private static final int MAX_LOG_CHARS = 30000;
    private static final int COLOR_BACKGROUND = 0xFFF4F6F8;
    private static final int COLOR_SURFACE = 0xFFFFFFFF;
    private static final int COLOR_PRIMARY = 0xFF00704A;
    private static final int COLOR_PRIMARY_DARK = 0xFF005C3D;
    private static final int COLOR_TEXT = 0xFF17212B;
    private static final int COLOR_MUTED = 0xFF64707D;
    private static final int COLOR_BORDER = 0xFFD8DEE5;
    private static final int COLOR_WARNING = 0xFF7A4E00;
    private static final int COLOR_DIAGNOSTIC = 0xFF243447;

    private final List<BluetoothDevice> pairedDevices = new ArrayList<BluetoothDevice>();
    private final List<BluetoothDevice> garminDevices = new ArrayList<BluetoothDevice>();
    private final List<BluetoothDevice> deviceMenuDevices = new ArrayList<BluetoothDevice>();
    private ArrayAdapter<String> deviceAdapter;
    private Spinner deviceSpinner;
    private EditText nameInput;
    private EditText latInput;
    private EditText lonInput;
    private TextView statusText;
    private TextView requestPreviewText;
    private TextView rawShareText;
    private TextView logText;
    private WebView mapPreview;
    private Button openGoogleMapsButton;
    private Button sendButton;
    private Button smartphoneLinkButton;
    private SharedPreferences preferences;
    private boolean loadingDevices;
    private boolean diagnosticsVisible;
    private boolean logEnabled;
    private boolean logVisible;
    private String selectedDeviceAddress = "";
    private int selectedDeviceMenuIndex = -1;
    private final StringBuilder logBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        logEnabled = preferences.getBoolean(PREF_LOG_ENABLED, false);
        if (logEnabled) {
            logBuffer.append(preferences.getString(PREF_LOG, ""));
            trimLogBuffer();
        } else {
            preferences.edit().remove(PREF_LOG).apply();
        }
        buildUi();
        appendLog("APP", "onCreate action=" + (getIntent() == null ? "null" : getIntent().getAction()));
        ensureBluetoothPermission();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        appendLog("APP", "onNewIntent action=" + (intent == null ? "null" : intent.getAction()));
        handleIntent(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_CONNECT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadPairedDevices();
            } else {
                setStatus("缺少 Bluetooth 權限，S23 無法讀取已配對裝置或連線。");
                appendLog("PERMISSION", "BLUETOOTH_CONNECT denied");
            }
        }
    }

    private void buildUi() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(COLOR_BACKGROUND);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);
        scrollView.addView(root);
        screen.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1));

        TextView title = new TextView(this);
        title.setText("Garmin Map Share");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("從 Google Maps 分享位置，確認座標後傳送到 Garmin 車機。");
        subtitle.setTextSize(14);
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle);

        LinearLayout mapSection = section(root, "Map 位置預覽");

        mapPreview = new WebView(this);
        WebSettings webSettings = mapPreview.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        mapPreview.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if ("garminmapshare://refresh-map".equals(url)) {
                    updatePreview();
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(320));
        mapParams.setMargins(0, dp(8), 0, dp(10));
        mapPreview.setLayoutParams(mapParams);
        mapPreview.setBackgroundColor(Color.WHITE);
        mapPreview.loadData(
                "<html><body style='font-family:sans-serif;color:#64707D;padding:16px'>等待座標...</body></html>",
                "text/html",
                "UTF-8");
        mapSection.addView(mapPreview);

        LinearLayout destinationSection = compactSection(root, "目的地");
        nameInput = compactEdit(destinationSection, InputType.TYPE_CLASS_TEXT, 18);

        LinearLayout coordinateRow = new LinearLayout(this);
        coordinateRow.setOrientation(LinearLayout.HORIZONTAL);
        destinationSection.addView(coordinateRow, buttonParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0,
                dp(2),
                0,
                0));
        latInput = compactEditColumn(coordinateRow, "lat", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED,
                0,
                dp(6));
        lonInput = compactEditColumn(coordinateRow, "lon", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED,
                dp(6),
                0);

        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusText.setTextColor(COLOR_WARNING);
        statusText.setPadding(dp(10), dp(7), dp(10), dp(7));
        statusText.setBackground(cardBackground(0xFFFFF8E8, 0xFFF0D38A));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(8), 0, 0);
        destinationSection.addView(statusText, statusParams);

        LinearLayout deviceSection = section(root, "Garmin 車機");

        deviceAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, new ArrayList<String>());
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner = new Spinner(this);
        deviceSpinner.setAdapter(deviceAdapter);
        deviceSection.addView(deviceSpinner, buttonParams(dp(52), 0, dp(8), 0, dp(8)));
        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (loadingDevices) {
                    return;
                }
                if (position < 0 || position >= deviceMenuDevices.size()) {
                    return;
                }
                BluetoothDevice device = deviceMenuDevices.get(position);
                if (device == null) {
                    if (selectedDeviceMenuIndex >= 0 && selectedDeviceMenuIndex < deviceMenuDevices.size()) {
                        deviceSpinner.setSelection(selectedDeviceMenuIndex);
                    }
                    return;
                }
                selectedDeviceMenuIndex = position;
                selectDevice(device);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        Button reloadButton = new Button(this);
        reloadButton.setText("重新載入已配對裝置");
        reloadButton.setAllCaps(false);
        styleButton(reloadButton, Color.WHITE, COLOR_TEXT, COLOR_BORDER);
        deviceSection.addView(reloadButton, buttonParams(dp(50), 0, 0, 0, 0));
        reloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ensureBluetoothPermission();
            }
        });

        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.VERTICAL);
        actionBar.setPadding(dp(12), dp(10), dp(12), dp(12));
        actionBar.setBackground(cardBackground(COLOR_SURFACE, COLOR_BORDER));
        if (Build.VERSION.SDK_INT >= 21) {
            actionBar.setElevation(dp(8));
        }

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(76)));

        sendButton = new Button(this);
        sendButton.setText("傳送到 Garmin");
        sendButton.setTextSize(24);
        sendButton.setTypeface(Typeface.DEFAULT_BOLD);
        sendButton.setAllCaps(false);
        styleButton(sendButton, COLOR_PRIMARY, Color.WHITE, COLOR_PRIMARY_DARK);
        LinearLayout.LayoutParams sendButtonParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                2);
        sendButtonParams.setMargins(0, 0, dp(8), 0);
        actionRow.addView(sendButton, sendButtonParams);
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendToGarmin();
            }
        });

        smartphoneLinkButton = new Button(this);
        smartphoneLinkButton.setText("Smartphone\nLink");
        smartphoneLinkButton.setTextSize(16);
        smartphoneLinkButton.setTypeface(Typeface.DEFAULT_BOLD);
        smartphoneLinkButton.setAllCaps(false);
        styleButton(smartphoneLinkButton, COLOR_DIAGNOSTIC, Color.WHITE, COLOR_DIAGNOSTIC);
        LinearLayout.LayoutParams smartphoneLinkButtonParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1);
        actionRow.addView(smartphoneLinkButton, smartphoneLinkButtonParams);
        smartphoneLinkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareToSmartphoneLink();
            }
        });

        LinearLayout diagnosticsSection = section(root, "診斷");

        Button toggleDiagnosticsButton = new Button(this);
        toggleDiagnosticsButton.setText("顯示診斷");
        toggleDiagnosticsButton.setAllCaps(false);
        styleButton(toggleDiagnosticsButton, Color.WHITE, COLOR_TEXT, COLOR_BORDER);
        diagnosticsSection.addView(toggleDiagnosticsButton, buttonParams(dp(50), 0, dp(8), 0, 0));

        final LinearLayout diagnosticsContent = new LinearLayout(this);
        diagnosticsContent.setOrientation(LinearLayout.VERTICAL);
        diagnosticsContent.setVisibility(View.GONE);
        diagnosticsSection.addView(diagnosticsContent);

        toggleDiagnosticsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                diagnosticsVisible = !diagnosticsVisible;
                diagnosticsContent.setVisibility(diagnosticsVisible ? View.VISIBLE : View.GONE);
                ((Button) v).setText(diagnosticsVisible ? "隱藏診斷" : "顯示診斷");
                if (diagnosticsVisible) {
                    refreshLogText();
                }
            }
        });

        TextView rawShareLabel = label("收到的分享內容");
        diagnosticsContent.addView(rawShareLabel);
        rawShareText = new TextView(this);
        rawShareText.setTextSize(12);
        rawShareText.setTextColor(COLOR_TEXT);
        rawShareText.setTextIsSelectable(true);
        rawShareText.setPadding(dp(10), dp(8), dp(10), dp(10));
        rawShareText.setBackground(cardBackground(0xFFF8FAFC, COLOR_BORDER));
        diagnosticsContent.addView(rawShareText, buttonParams(LinearLayout.LayoutParams.WRAP_CONTENT, 0, dp(4), 0, dp(10)));

        TextView previewLabel = label("BT Request 預覽");
        diagnosticsContent.addView(previewLabel);
        requestPreviewText = new TextView(this);
        requestPreviewText.setTextSize(12);
        requestPreviewText.setTypeface(Typeface.MONOSPACE);
        requestPreviewText.setTextColor(COLOR_TEXT);
        requestPreviewText.setTextIsSelectable(true);
        requestPreviewText.setPadding(dp(10), dp(8), dp(10), dp(10));
        requestPreviewText.setBackground(cardBackground(0xFFF8FAFC, COLOR_BORDER));
        diagnosticsContent.addView(requestPreviewText, buttonParams(LinearLayout.LayoutParams.WRAP_CONTENT, 0, dp(4), 0, dp(10)));

        final Button toggleLogEnabledButton = new Button(this);
        toggleLogEnabledButton.setText(logEnabled ? "關閉 Log 記錄" : "開啟 Log 記錄");
        toggleLogEnabledButton.setAllCaps(false);
        styleButton(toggleLogEnabledButton, logEnabled ? COLOR_WARNING : Color.WHITE, logEnabled ? Color.WHITE : COLOR_TEXT, logEnabled ? COLOR_WARNING : COLOR_BORDER);
        diagnosticsContent.addView(toggleLogEnabledButton, buttonParams(dp(50), 0, 0, 0, dp(8)));
        toggleLogEnabledButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLogEnabled(!logEnabled);
                toggleLogEnabledButton.setText(logEnabled ? "關閉 Log 記錄" : "開啟 Log 記錄");
                styleButton(toggleLogEnabledButton, logEnabled ? COLOR_WARNING : Color.WHITE, logEnabled ? Color.WHITE : COLOR_TEXT, logEnabled ? COLOR_WARNING : COLOR_BORDER);
                refreshLogText();
            }
        });

        Button toggleLogButton = new Button(this);
        toggleLogButton.setText("顯示測試 Log");
        toggleLogButton.setAllCaps(false);
        styleButton(toggleLogButton, Color.WHITE, COLOR_TEXT, COLOR_BORDER);
        diagnosticsContent.addView(toggleLogButton, buttonParams(dp(50), 0, 0, 0, dp(8)));
        toggleLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logVisible = !logVisible;
                logText.setVisibility(logVisible ? View.VISIBLE : View.GONE);
                ((Button) v).setText(logVisible ? "隱藏測試 Log" : "顯示測試 Log");
                refreshLogText();
            }
        });

        LinearLayout logActions = new LinearLayout(this);
        logActions.setOrientation(LinearLayout.HORIZONTAL);
        diagnosticsContent.addView(logActions);

        Button copyLogButton = new Button(this);
        copyLogButton.setText("複製");
        copyLogButton.setAllCaps(false);
        styleButton(copyLogButton, Color.WHITE, COLOR_TEXT, COLOR_BORDER);
        LinearLayout.LayoutParams copyLogParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        copyLogParams.setMargins(0, 0, dp(6), 0);
        logActions.addView(copyLogButton, copyLogParams);
        copyLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLogToClipboard();
            }
        });

        Button shareLogButton = new Button(this);
        shareLogButton.setText("分享");
        shareLogButton.setAllCaps(false);
        styleButton(shareLogButton, Color.WHITE, COLOR_TEXT, COLOR_BORDER);
        LinearLayout.LayoutParams shareLogParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        shareLogParams.setMargins(dp(3), 0, dp(3), 0);
        logActions.addView(shareLogButton, shareLogParams);
        shareLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareLog();
            }
        });

        Button clearLogButton = new Button(this);
        clearLogButton.setText("清除");
        clearLogButton.setAllCaps(false);
        styleButton(clearLogButton, Color.WHITE, COLOR_TEXT, COLOR_BORDER);
        LinearLayout.LayoutParams clearLogParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        clearLogParams.setMargins(dp(6), 0, 0, 0);
        logActions.addView(clearLogButton, clearLogParams);
        clearLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearLog();
            }
        });

        logText = new TextView(this);
        logText.setTextSize(11);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextColor(COLOR_TEXT);
        logText.setTextIsSelectable(true);
        logText.setVisibility(View.GONE);
        logText.setPadding(dp(10), dp(8), dp(10), dp(10));
        logText.setBackground(cardBackground(0xFFF8FAFC, COLOR_BORDER));
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        logParams.setMargins(0, dp(8), 0, 0);
        diagnosticsContent.addView(logText, logParams);

        screen.addView(actionBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(screen);
        setStatus("請從 Google Maps 分享，或手動輸入座標。");
    }

    private EditText labeledEdit(LinearLayout root, String label, int inputType) {
        root.addView(label(label));
        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        editText.setTextColor(COLOR_TEXT);
        editText.setTextSize(18);
        editText.setPadding(dp(10), 0, dp(10), 0);
        root.addView(editText, buttonParams(dp(52), 0, dp(4), 0, dp(8)));
        return editText;
    }

    private EditText labeledEditColumn(LinearLayout row, String label, int inputType, int leftMargin, int rightMargin) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1);
        columnParams.setMargins(leftMargin, 0, rightMargin, 0);
        row.addView(column, columnParams);

        column.addView(label(label));

        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        editText.setTextColor(COLOR_TEXT);
        editText.setTextSize(16);
        editText.setPadding(dp(8), 0, dp(8), 0);
        column.addView(editText, buttonParams(dp(52), 0, dp(4), 0, dp(8)));
        return editText;
    }

    private EditText compactEdit(LinearLayout root, int inputType, int textSize) {
        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        editText.setTextColor(COLOR_TEXT);
        editText.setTextSize(textSize);
        editText.setPadding(dp(8), 0, dp(8), 0);
        root.addView(editText, buttonParams(dp(44), 0, dp(4), 0, dp(4)));
        return editText;
    }

    private EditText compactEditColumn(LinearLayout row, String label, int inputType, int leftMargin, int rightMargin) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                0,
                dp(42),
                1);
        columnParams.setMargins(leftMargin, 0, rightMargin, 0);
        row.addView(column, columnParams);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(COLOR_MUTED);
        labelView.setTextSize(12);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        labelView.setGravity(android.view.Gravity.CENTER_VERTICAL);
        column.addView(labelView, new LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.MATCH_PARENT));

        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        editText.setTextColor(COLOR_TEXT);
        editText.setTextSize(14);
        editText.setPadding(dp(4), 0, dp(4), 0);
        column.addView(editText, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1));
        return editText;
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(13);
        label.setPadding(0, dp(8), 0, 0);
        return label;
    }

    private LinearLayout section(LinearLayout root, String titleText) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(14), dp(12), dp(14), dp(14));
        section.setBackground(cardBackground(COLOR_SURFACE, COLOR_BORDER));
        if (Build.VERSION.SDK_INT >= 21) {
            section.setElevation(dp(1));
        }

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        section.addView(title);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        root.addView(section, params);
        return section;
    }

    private LinearLayout compactSection(LinearLayout root, String titleText) {
        LinearLayout section = section(root, titleText);
        section.setPadding(dp(12), dp(8), dp(12), dp(10));
        return section;
    }

    private void styleButton(Button button, int fillColor, int textColor, int strokeColor) {
        button.setTextColor(textColor);
        button.setBackground(cardBackground(fillColor, strokeColor));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), 0, dp(10), 0);
    }

    private LinearLayout.LayoutParams buttonParams(int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private GradientDrawable cardBackground(int fillColor, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private void ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    REQUEST_BLUETOOTH_CONNECT);
            return;
        }
        loadPairedDevices();
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void loadPairedDevices() {
        if (!hasBluetoothConnectPermission()) {
            setStatus("請先允許 Bluetooth 權限。");
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        loadingDevices = true;
        pairedDevices.clear();
        garminDevices.clear();
        deviceMenuDevices.clear();
        deviceAdapter.clear();
        selectedDeviceMenuIndex = -1;

        if (adapter == null) {
            loadingDevices = false;
            setStatus("這支手機不支援 Bluetooth。");
            return;
        }

        if (!adapter.isEnabled()) {
            loadingDevices = false;
            setStatus("請先開啟 Bluetooth。");
            return;
        }

        Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
        String selectedAddress = preferences.getString(PREF_DEVICE_ADDRESS, "");
        int selectedAllIndex = -1;

        for (BluetoothDevice device : bondedDevices) {
            pairedDevices.add(device);
            if (device.getAddress().equals(selectedAddress)) {
                selectedAllIndex = pairedDevices.size() - 1;
            }
            if (isGarminDevice(device)) {
                garminDevices.add(device);
            }
        }

        int selectedMenuIndex = -1;
        addDeviceMenuHeader("Garmin 裝置");
        if (garminDevices.isEmpty()) {
            addDeviceMenuHeader("未找到名稱含 Garmin 的裝置");
        } else {
            for (BluetoothDevice device : garminDevices) {
                int menuIndex = addDeviceMenuItem(device);
                if (device.getAddress().equals(selectedAddress)) {
                    selectedMenuIndex = menuIndex;
                }
            }
        }

        addDeviceMenuHeader("全部已配對裝置");
        for (BluetoothDevice device : pairedDevices) {
            int menuIndex = addDeviceMenuItem(device);
            if (selectedMenuIndex < 0 && device.getAddress().equals(selectedAddress)) {
                selectedMenuIndex = menuIndex;
            }
        }

        if (selectedMenuIndex < 0 && !garminDevices.isEmpty()) {
            selectedAddress = garminDevices.get(0).getAddress();
            selectedMenuIndex = findDeviceMenuIndex(selectedAddress);
        }

        if (selectedMenuIndex < 0 && !pairedDevices.isEmpty()) {
            selectedAddress = pairedDevices.get(0).getAddress();
            selectedMenuIndex = findDeviceMenuIndex(selectedAddress);
        }

        deviceAdapter.notifyDataSetChanged();
        if (selectedMenuIndex >= 0) {
            selectedDeviceMenuIndex = selectedMenuIndex;
            deviceSpinner.setSelection(selectedMenuIndex);
        }

        selectedDeviceAddress = selectedAddress;
        if (selectedDeviceAddress.length() > 0) {
            preferences.edit()
                    .putString(PREF_DEVICE_ADDRESS, selectedDeviceAddress)
                    .apply();
        }

        loadingDevices = false;

        if (pairedDevices.isEmpty()) {
            setStatus("沒有已配對裝置。請先在 S23 系統 Bluetooth 設定與 Garmin 車機配對。");
        } else if (selectedAllIndex >= 0) {
            setStatus("已自動選擇上次使用的車機：" + safeDeviceName(findDeviceByAddress(selectedDeviceAddress)));
        } else if (!garminDevices.isEmpty()) {
            setStatus("已優先選擇 Garmin 裝置：" + safeDeviceName(garminDevices.get(0)));
        } else {
            setStatus("已載入 " + pairedDevices.size() + " 個已配對裝置。");
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String text = collectSharedText(intent);

        if (text == null || text.trim().length() == 0) {
            rawShareText.setText("action=" + intent.getAction() + "\ntype=" + intent.getType() + "\n(no text/data)");
            setStatus("App 有被開啟，但沒有收到 Google Maps 分享文字。請用 Google Maps 的分享按鈕再試一次。");
            return;
        }

        rawShareText.setText(text);
        appendLog("SHARE", text);
        parseSharedText(text);
    }

    private String collectSharedText(Intent intent) {
        StringBuilder builder = new StringBuilder();

        append(builder, intent.getStringExtra(Intent.EXTRA_TEXT));

        CharSequence textExtra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (textExtra != null) {
            append(builder, textExtra.toString());
        }

        append(builder, intent.getStringExtra(Intent.EXTRA_SUBJECT));
        CharSequence title = intent.getCharSequenceExtra(Intent.EXTRA_TITLE);
        if (title != null) {
            append(builder, title.toString());
        }

        Uri data = intent.getData();
        if (data != null) {
            append(builder, data.toString());
        }

        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                ClipData.Item item = clipData.getItemAt(i);
                if (item.getText() != null) {
                    append(builder, item.getText().toString());
                }
                if (item.getUri() != null) {
                    append(builder, item.getUri().toString());
                }
                if (item.getHtmlText() != null) {
                    append(builder, item.getHtmlText());
                }
            }
        }

        return builder.toString();
    }

    private void append(StringBuilder builder, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() == 0) {
            return;
        }
        if (builder.indexOf(trimmed) >= 0) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n");
        }
        builder.append(trimmed);
    }

    private void parseSharedText(final String text) {
        SharedPlace place = GoogleMapsShareParser.parse(text);
        if (place != null) {
            appendLog("PARSE", "direct lat=" + place.lat + " lon=" + place.lon + " name=" + place.name);
            applyPlace(place);
            return;
        }

        if (!GoogleMapsShareParser.mayNeedRedirect(text)
                && !GoogleMapsShareParser.extractGeocodeQueries(text).isEmpty()) {
            appendLog("GEOCODE", "queries=" + GoogleMapsShareParser.extractGeocodeQueries(text).toString());
            geocodeSharedText(text, "地址轉經緯度失敗。");
            return;
        }

        if (!GoogleMapsShareParser.mayNeedRedirect(text)) {
            setStatus("已收到分享內容，但找不到座標。請確認 Google Maps 分享內容含座標，或手動輸入。");
            return;
        }

        setStatus("正在展開 Google Maps 短網址...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String expanded = UrlExpander.expandFirstUrl(text);
                    final SharedPlace expandedPlace = GoogleMapsShareParser.parse(expanded);
                    final String expandedText = expanded;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            appendLog("REDIRECT", expandedText);
                            rawShareText.setText(expandedText);
                            if (expandedPlace != null) {
                                applyPlace(expandedPlace);
                            } else if (!GoogleMapsShareParser.extractGeocodeQueries(expandedText).isEmpty()) {
                                geocodeSharedText(expandedText, "短網址已展開，但地址轉經緯度失敗。");
                            } else {
                                setStatus("短網址已展開，但仍找不到座標。可能需要 Geocoding API 才能處理這個分享格式。");
                            }
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("展開 Google Maps 短網址失敗：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void geocodeSharedText(final String text, final String failureMessage) {
        final List<String> queries = GoogleMapsShareParser.extractGeocodeQueries(text);
        if (queries.isEmpty()) {
            setStatus(failureMessage);
            return;
        }

        if (!Geocoder.isPresent()) {
            setStatus("這支手機沒有可用的 Geocoder，無法把地址轉成經緯度。");
            appendLog("GEOCODE", "Geocoder.isPresent=false");
            return;
        }

        setStatus("Google Maps 沒有直接座標，正在用地址轉經緯度：" + queries.get(0));
        appendLog("GEOCODE", "start " + queries.toString());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final SharedPlace result = geocodeFirstMatch(text, queries);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (result != null) {
                            appendLog("GEOCODE", "success lat=" + result.lat + " lon=" + result.lon);
                            applyPlace(result);
                        } else {
                            appendLog("GEOCODE", "failed " + queries.toString());
                            setStatus(failureMessage + " 已嘗試：" + queries.toString());
                        }
                    }
                });
            }
        }).start();
    }

    private SharedPlace geocodeFirstMatch(String source, List<String> queries) {
        Geocoder geocoder = new Geocoder(this, Locale.TAIWAN);
        for (int i = 0; i < queries.size(); i++) {
            String query = queries.get(i);
            try {
                List<Address> addresses = geocoder.getFromLocationName(query, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    if (address.hasLatitude() && address.hasLongitude()) {
                        String name = GoogleMapsShareParser.extractDisplayName(source);
                        if (name.length() == 0) {
                            name = query;
                        }
                        return new SharedPlace(address.getLatitude(), address.getLongitude(), name, source);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void applyPlace(SharedPlace place) {
        latInput.setText(String.format(Locale.US, "%.8f", place.lat));
        lonInput.setText(String.format(Locale.US, "%.8f", place.lon));
        if (place.name.length() > 0) {
            nameInput.setText(place.name);
        }
        setStatus("已解析座標：lat=" + place.lat + ", lon=" + place.lon);
        appendLog("PLACE", "applied lat=" + place.lat + " lon=" + place.lon + " name=" + place.name);
        updatePreview();
    }

    private void updatePreview() {
        try {
            double lat = parseDouble(latInput, "緯度");
            double lon = parseDouble(lonInput, "經度");
            String name = nameInput.getText().toString();
            requestPreviewText.setText(GarminRouteRequestBuilder.buildPreview(lat, lon, name));
            updateMapPreview(lat, lon);
            long latSemi = GarminCoordinateConverter.toSemiCircle(lat);
            long lonSemi = GarminCoordinateConverter.toSemiCircle(lon);
            setStatus("semi-circle: lat=" + latSemi + ", lon=" + lonSemi);
            appendLog("REQUEST", "lat=" + lat + " lon=" + lon
                    + " latSemi=" + latSemi + " lonSemi=" + lonSemi);
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage());
            appendLog("REQUEST", "failed " + e.getMessage());
        }
    }

    private void updateMapPreview(double lat, double lon) {
        double delta = 0.0025d;
        String marker = String.format(Locale.US, "%.8f,%.8f", lat, lon);
        String bbox = String.format(
                Locale.US,
                "%.8f,%.8f,%.8f,%.8f",
                lon - delta,
                lat - delta,
                lon + delta,
                lat + delta);
        String iframeUrl = "https://www.openstreetmap.org/export/embed.html"
                + "?bbox=" + bbox
                + "&layer=mapnik"
                + "&marker=" + marker;
        String openUrl = "https://www.google.com/maps/search/?api=1&query="
                + String.format(Locale.US, "%.8f,%.8f", lat, lon);
        String html = "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<style>"
                + "html,body,iframe{margin:0;width:100%;height:100%;border:0;overflow:hidden;}"
                + ".mapActions{position:absolute;right:8px;bottom:8px;display:flex;gap:6px;}"
                + ".mapButton{background:#fff;color:#17212b;font-family:sans-serif;"
                + "font-size:12px;padding:5px 7px;border-radius:4px;text-decoration:none;"
                + "box-shadow:0 1px 4px rgba(0,0,0,.25);}"
                + "</style>"
                + "</head><body>"
                + "<iframe src='" + iframeUrl + "'></iframe>"
                + "<div class='mapActions'>"
                + "<a class='mapButton' href='garminmapshare://refresh-map'>更新預覽</a>"
                + "<a class='mapButton' href='" + openUrl + "'>Google Maps</a>"
                + "</div>"
                + "</body></html>";
        mapPreview.loadDataWithBaseURL("https://www.openstreetmap.org/", html, "text/html", "UTF-8", null);
    }

    private void openCurrentLocationInGoogleMaps() {
        try {
            double lat = parseDouble(latInput, "緯度");
            double lon = parseDouble(lonInput, "經度");
            String url = "https://www.google.com/maps/search/?api=1&query="
                    + String.format(Locale.US, "%.8f,%.8f", lat, lon);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage());
        }
    }

    private void sendToGarmin() {
        if (!hasBluetoothConnectPermission()) {
            ensureBluetoothPermission();
            return;
        }
        if (pairedDevices.isEmpty()) {
            setStatus("沒有可用裝置。請先配對 Garmin 車機。");
            return;
        }

        final BluetoothDevice device = findDeviceByAddress(selectedDeviceAddress);
        if (device == null) {
            setStatus("請選擇 Garmin 車機。");
            return;
        }

        final double lat;
        final double lon;
        try {
            lat = parseDouble(latInput, "緯度");
            lon = parseDouble(lonInput, "經度");
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage());
            return;
        }

        if (lat < -90.0d || lat > 90.0d || lon < -180.0d || lon > 180.0d) {
            setStatus("座標範圍不正確。");
            return;
        }

        final String name = nameInput.getText().toString();
        preferences.edit()
                .putString(PREF_DEVICE_ADDRESS, device.getAddress())
                .apply();
        sendButton.setEnabled(false);
        setStatus("正在連線並傳送到 " + safeDeviceName(device) + "...");
        appendLog("SEND", "start device=" + safeDeviceName(device)
                + " address=" + device.getAddress()
                + " lat=" + lat
                + " lon=" + lon
                + " bodyBytes=" + GarminRouteRequestBuilder.buildBodyByteLength(lat, lon, name)
                + " requestBytes=" + GarminRouteRequestBuilder.build(lat, lon, name).length);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final GarminBluetoothClient.SendResult result =
                            new GarminBluetoothClient().sendRoute(device, lat, lon, name);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            sendButton.setEnabled(true);
                            setStatus("傳送完成。請確認 Garmin 車機是否收到目的地。");
                            appendLog("SEND", "success mode=" + result.socketMode
                                    + " connectMs=" + result.connectMillis
                                    + " writeMs=" + result.writeMillis
                                    + " bodyBytes=" + result.bodyBytes
                                    + " requestBytes=" + result.requestBytes
                                    + " responseBytes=" + result.responseBytes
                                    + (result.responsePreview.length() > 0
                                    ? " response=" + result.responsePreview
                                    : ""));
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            sendButton.setEnabled(true);
                            appendLog("SEND", "failed " + e.getClass().getSimpleName() + ": " + e.getMessage());
                            setStatus("傳送失敗：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void shareToSmartphoneLink() {
        String shareText = buildSmartphoneLinkShareText();
        if (shareText.length() == 0) {
            setStatus("沒有可分享給 Smartphone Link 的座標或文字。");
            appendLog("SMARTPHONE_LINK", "nothing to share");
            return;
        }
        appendLog("SMARTPHONE_LINK", "shareText=" + shareText);

        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setPackage(SMARTPHONE_LINK_PACKAGE);
        viewIntent.setData(Uri.parse(buildGeoUri()));
        viewIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        try {
            startActivity(viewIntent);
            setStatus("已用 geo 座標開啟 Smartphone Link。");
            appendLog("SMARTPHONE_LINK", "geo ACTION_VIEW started");
            return;
        } catch (ActivityNotFoundException ignored) {
            appendLog("SMARTPHONE_LINK", "geo ACTION_VIEW not found");
        }

        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("text/plain");
        sendIntent.setPackage(SMARTPHONE_LINK_PACKAGE);
        sendIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, nameInput.getText().toString());
        sendIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        try {
            startActivity(sendIntent);
            setStatus("已開啟 Smartphone Link 分享入口。");
            appendLog("SMARTPHONE_LINK", "direct ACTION_SEND started");
            return;
        } catch (ActivityNotFoundException ignored) {
            appendLog("SMARTPHONE_LINK", "direct ACTION_SEND not found");
        }

        Intent chooserIntent = new Intent(Intent.ACTION_SEND);
        chooserIntent.setType("text/plain");
        chooserIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        chooserIntent.putExtra(Intent.EXTRA_SUBJECT, nameInput.getText().toString());

        try {
            startActivity(Intent.createChooser(chooserIntent, "分享到 Smartphone Link"));
            setStatus("無法直接指定 Smartphone Link，已開啟系統分享選單。請選 Garmin Smartphone Link。");
            appendLog("SMARTPHONE_LINK", "chooser started");
            return;
        } catch (ActivityNotFoundException ignored) {
            appendLog("SMARTPHONE_LINK", "chooser failed");
        }

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(SMARTPHONE_LINK_PACKAGE);
        if (launchIntent != null) {
            startActivity(launchIntent);
            setStatus("找不到可直接分享的 Smartphone Link 入口，已改為開啟官方 App。");
            appendLog("SMARTPHONE_LINK", "package launched");
        } else {
            setStatus("找不到 Smartphone Link 可用入口。若已安裝，請確認官方 App 可正常開啟，或從系統分享選單手動選它。");
            appendLog("SMARTPHONE_LINK", "package launch intent null");
        }
    }

    private String buildSmartphoneLinkShareText() {
        try {
            double lat = parseDouble(latInput, "緯度");
            double lon = parseDouble(lonInput, "經度");
            String name = nameInput.getText().toString().trim();
            String url = "https://www.google.com/maps/search/?api=1&query="
                    + String.format(Locale.US, "%.8f,%.8f", lat, lon);
            if (name.length() > 0) {
                return name + "\n" + url;
            }
            return url;
        } catch (IllegalArgumentException ignored) {
            String raw = rawShareText.getText() == null ? "" : rawShareText.getText().toString().trim();
            return raw;
        }
    }

    private String buildGeoUri() {
        try {
            double lat = parseDouble(latInput, "緯度");
            double lon = parseDouble(lonInput, "經度");
            String name = nameInput.getText().toString().trim();
            if (name.length() > 0) {
                return "geo:" + lat + "," + lon + "?q=" + Uri.encode(lat + "," + lon + "(" + name + ")");
            }
            return "geo:" + lat + "," + lon + "?q=" + Uri.encode(lat + "," + lon);
        } catch (IllegalArgumentException ignored) {
            return "geo:0,0?q=";
        }
    }

    private double parseDouble(EditText input, String label) {
        String text = input.getText().toString().trim();
        if (text.length() == 0) {
            throw new IllegalArgumentException(label + "不能空白。");
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "格式不正確。");
        }
    }

    private void addDeviceMenuHeader(String text) {
        deviceAdapter.add(text);
        deviceMenuDevices.add(null);
    }

    private int addDeviceMenuItem(BluetoothDevice device) {
        deviceAdapter.add("  " + safeDeviceName(device) + "  " + device.getAddress());
        deviceMenuDevices.add(device);
        return deviceMenuDevices.size() - 1;
    }

    private int findDeviceMenuIndex(String address) {
        if (address == null || address.length() == 0) {
            return -1;
        }
        for (int i = 0; i < deviceMenuDevices.size(); i++) {
            BluetoothDevice device = deviceMenuDevices.get(i);
            if (device != null && device.getAddress().equals(address)) {
                return i;
            }
        }
        return -1;
    }

    private void selectDevice(BluetoothDevice device) {
        if (device == null) {
            return;
        }
        selectedDeviceAddress = device.getAddress();
        preferences.edit()
                .putString(PREF_DEVICE_ADDRESS, selectedDeviceAddress)
                .apply();
        appendLog("DEVICE", "selected " + safeDeviceName(device) + " " + selectedDeviceAddress);
    }

    private BluetoothDevice findDeviceByAddress(String address) {
        if (address != null && address.length() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                if (device.getAddress().equals(address)) {
                    return device;
                }
            }
        }
        if (!garminDevices.isEmpty()) {
            return garminDevices.get(0);
        }
        return pairedDevices.isEmpty() ? null : pairedDevices.get(0);
    }

    private boolean isGarminDevice(BluetoothDevice device) {
        String name = safeDeviceName(device).toLowerCase(Locale.US);
        return name.indexOf("garmin") >= 0;
    }

    private String safeDeviceName(BluetoothDevice device) {
        if (device == null) {
            return "Unknown Garmin";
        }
        if (!hasBluetoothConnectPermission()) {
            return "Bluetooth device";
        }
        String name = device.getName();
        return name == null || name.length() == 0 ? "Unknown Garmin" : name;
    }

    private void setStatus(String message) {
        statusText.setText(message);
        appendLog("STATUS", message);
    }

    private void appendLog(String tag, String message) {
        if (!logEnabled) {
            return;
        }
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String safeMessage = message == null ? "" : message.replace('\r', ' ').trim();
        logBuffer.append(time)
                .append(" ")
                .append(tag)
                .append(" ")
                .append(safeMessage)
                .append("\n");

        trimLogBuffer();

        if (preferences != null) {
            preferences.edit().putString(PREF_LOG, logBuffer.toString()).apply();
        }
        refreshLogText();
    }

    private void trimLogBuffer() {
        if (logBuffer.length() > MAX_LOG_CHARS) {
            logBuffer.delete(0, logBuffer.length() - MAX_LOG_CHARS);
        }
    }

    private void refreshLogText() {
        if (logText != null) {
            if (logEnabled) {
                logText.setText(logBuffer.toString());
            } else {
                logText.setText("Log 記錄目前關閉。需要外出測試時，請先按「開啟 Log 記錄」。");
            }
        }
    }

    private void setLogEnabled(boolean enabled) {
        logEnabled = enabled;
        if (preferences != null) {
            preferences.edit().putBoolean(PREF_LOG_ENABLED, logEnabled).apply();
        }
        if (logEnabled) {
            appendLog("LOG", "enabled");
        } else {
            logBuffer.setLength(0);
            if (preferences != null) {
                preferences.edit().remove(PREF_LOG).apply();
            }
        }
    }

    private void copyLogToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("GarminMapShare log", logEnabled ? logBuffer.toString() : "Log 記錄目前關閉。"));
            setStatus("Log 已複製到剪貼簿。");
        } else {
            setStatus("無法取得剪貼簿服務。");
        }
    }

    private void shareLog() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "GarminMapShare log");
        intent.putExtra(Intent.EXTRA_TEXT, logEnabled ? logBuffer.toString() : "Log 記錄目前關閉。");
        try {
            startActivity(Intent.createChooser(intent, "分享 Log"));
        } catch (ActivityNotFoundException e) {
            setStatus("沒有可分享 Log 的 App。");
        }
    }

    private void clearLog() {
        logBuffer.setLength(0);
        if (preferences != null) {
            preferences.edit().remove(PREF_LOG).apply();
        }
        refreshLogText();
        setStatus("Log 已清除。");
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

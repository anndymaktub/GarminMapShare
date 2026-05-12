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
    private static final String SMARTPHONE_LINK_PACKAGE = "com.garmin.android.apps.phonelink";
    private static final int MAX_LOG_CHARS = 30000;

    private final List<BluetoothDevice> pairedDevices = new ArrayList<BluetoothDevice>();
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
    private boolean logVisible;
    private final StringBuilder logBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        logBuffer.append(preferences.getString(PREF_LOG, ""));
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
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(18);
        root.setPadding(padding, padding, padding, padding);
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("Garmin Map Share");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("從 Google Maps 分享座標，透過 BT 傳送到 Garmin GPS 車機。");
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(6), 0, dp(14));
        root.addView(subtitle);

        nameInput = labeledEdit(root, "名稱", InputType.TYPE_CLASS_TEXT);
        latInput = labeledEdit(root, "緯度 lat", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        lonInput = labeledEdit(root, "經度 lon", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);

        TextView mapLabel = label("Map 位置預覽");
        root.addView(mapLabel);

        mapPreview = new WebView(this);
        WebSettings webSettings = mapPreview.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(220));
        mapParams.setMargins(0, dp(6), 0, dp(8));
        mapPreview.setLayoutParams(mapParams);
        mapPreview.loadData(
                "<html><body style='font-family:sans-serif;color:#666;padding:12px'>等待座標...</body></html>",
                "text/html",
                "UTF-8");
        root.addView(mapPreview);

        openGoogleMapsButton = new Button(this);
        openGoogleMapsButton.setText("用 Google Maps 開啟此座標");
        openGoogleMapsButton.setAllCaps(false);
        root.addView(openGoogleMapsButton);
        openGoogleMapsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openCurrentLocationInGoogleMaps();
            }
        });

        Button previewButton = new Button(this);
        previewButton.setText("更新傳送內容預覽");
        root.addView(previewButton);
        previewButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updatePreview();
            }
        });

        TextView deviceLabel = label("已配對 Garmin 車機");
        root.addView(deviceLabel);

        deviceAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, new ArrayList<String>());
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner = new Spinner(this);
        deviceSpinner.setAdapter(deviceAdapter);
        root.addView(deviceSpinner);
        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (loadingDevices) {
                    return;
                }
                if (position >= 0 && position < pairedDevices.size()) {
                    preferences.edit()
                            .putString(PREF_DEVICE_ADDRESS, pairedDevices.get(position).getAddress())
                            .apply();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        Button reloadButton = new Button(this);
        reloadButton.setText("重新載入已配對裝置");
        root.addView(reloadButton);
        reloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ensureBluetoothPermission();
            }
        });

        sendButton = new Button(this);
        sendButton.setText("傳送到 Garmin");
        sendButton.setTextSize(24);
        sendButton.setTypeface(Typeface.DEFAULT_BOLD);
        sendButton.setTextColor(Color.WHITE);
        sendButton.setBackgroundColor(Color.rgb(0, 112, 74));
        sendButton.setAllCaps(false);
        LinearLayout.LayoutParams sendButtonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(88));
        sendButtonParams.setMargins(0, dp(18), 0, dp(10));
        sendButton.setLayoutParams(sendButtonParams);
        root.addView(sendButton);
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendToGarmin();
            }
        });

        smartphoneLinkButton = new Button(this);
        smartphoneLinkButton.setText("分享到 Smartphone Link");
        smartphoneLinkButton.setTextSize(20);
        smartphoneLinkButton.setTypeface(Typeface.DEFAULT_BOLD);
        smartphoneLinkButton.setTextColor(Color.WHITE);
        smartphoneLinkButton.setBackgroundColor(Color.rgb(70, 70, 70));
        smartphoneLinkButton.setAllCaps(false);
        LinearLayout.LayoutParams smartphoneLinkButtonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(68));
        smartphoneLinkButtonParams.setMargins(0, dp(4), 0, dp(10));
        smartphoneLinkButton.setLayoutParams(smartphoneLinkButtonParams);
        root.addView(smartphoneLinkButton);
        smartphoneLinkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareToSmartphoneLink();
            }
        });

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setPadding(0, dp(12), 0, dp(12));
        root.addView(statusText);

        TextView rawShareLabel = label("收到的分享內容");
        root.addView(rawShareLabel);

        rawShareText = new TextView(this);
        rawShareText.setTextSize(12);
        rawShareText.setTextIsSelectable(true);
        rawShareText.setPadding(0, dp(4), 0, dp(10));
        root.addView(rawShareText);

        TextView previewLabel = label("BT Request 預覽");
        root.addView(previewLabel);

        requestPreviewText = new TextView(this);
        requestPreviewText.setTextSize(12);
        requestPreviewText.setTypeface(Typeface.MONOSPACE);
        requestPreviewText.setTextIsSelectable(true);
        root.addView(requestPreviewText);

        TextView logLabel = label("測試 Log");
        root.addView(logLabel);

        Button toggleLogButton = new Button(this);
        toggleLogButton.setText("顯示/隱藏 Log");
        toggleLogButton.setAllCaps(false);
        root.addView(toggleLogButton);
        toggleLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logVisible = !logVisible;
                logText.setVisibility(logVisible ? View.VISIBLE : View.GONE);
                refreshLogText();
            }
        });

        LinearLayout logActions = new LinearLayout(this);
        logActions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(logActions);

        Button copyLogButton = new Button(this);
        copyLogButton.setText("複製");
        copyLogButton.setAllCaps(false);
        logActions.addView(copyLogButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        copyLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLogToClipboard();
            }
        });

        Button shareLogButton = new Button(this);
        shareLogButton.setText("分享");
        shareLogButton.setAllCaps(false);
        logActions.addView(shareLogButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        shareLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareLog();
            }
        });

        Button clearLogButton = new Button(this);
        clearLogButton.setText("清除");
        clearLogButton.setAllCaps(false);
        logActions.addView(clearLogButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        clearLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearLog();
            }
        });

        logText = new TextView(this);
        logText.setTextSize(11);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setVisibility(View.GONE);
        logText.setPadding(0, dp(6), 0, dp(16));
        root.addView(logText);

        setContentView(scrollView);
        setStatus("請從 Google Maps 分享，或手動輸入座標。");
    }

    private EditText labeledEdit(LinearLayout root, String label, int inputType) {
        root.addView(label(label));
        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        root.addView(editText);
        return editText;
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(0, dp(10), 0, 0);
        return label;
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
        deviceAdapter.clear();

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
        int selectedIndex = -1;

        for (BluetoothDevice device : bondedDevices) {
            pairedDevices.add(device);
            String name = safeDeviceName(device);
            deviceAdapter.add(name + "  " + device.getAddress());
            if (device.getAddress().equals(selectedAddress)) {
                selectedIndex = pairedDevices.size() - 1;
            }
        }

        deviceAdapter.notifyDataSetChanged();
        if (selectedIndex >= 0) {
            deviceSpinner.setSelection(selectedIndex);
        }
        loadingDevices = false;

        if (pairedDevices.isEmpty()) {
            setStatus("沒有已配對裝置。請先在 S23 系統 Bluetooth 設定與 Garmin 車機配對。");
        } else if (selectedIndex >= 0) {
            setStatus("已自動選擇上次使用的車機：" + safeDeviceName(pairedDevices.get(selectedIndex)));
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

        if (!GoogleMapsShareParser.extractGeocodeQueries(text).isEmpty()) {
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
                + "<style>html,body,iframe{margin:0;width:100%;height:100%;border:0;}</style>"
                + "</head><body>"
                + "<iframe src='" + iframeUrl + "'></iframe>"
                + "<a href='" + openUrl + "'>Open in Google Maps</a>"
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

        final int selected = deviceSpinner.getSelectedItemPosition();
        if (selected < 0 || selected >= pairedDevices.size()) {
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
        final BluetoothDevice device = pairedDevices.get(selected);
        preferences.edit()
                .putString(PREF_DEVICE_ADDRESS, device.getAddress())
                .apply();
        sendButton.setEnabled(false);
        setStatus("正在連線並傳送到 " + safeDeviceName(device) + "...");
        appendLog("SEND", "start device=" + safeDeviceName(device)
                + " address=" + device.getAddress()
                + " lat=" + lat
                + " lon=" + lon);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    new GarminBluetoothClient().sendRoute(device, lat, lon, name);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            sendButton.setEnabled(true);
                            setStatus("傳送完成。請確認 Garmin 車機是否收到目的地。");
                            appendLog("SEND", "success");
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

        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("text/plain");
        sendIntent.setPackage(SMARTPHONE_LINK_PACKAGE);
        sendIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, nameInput.getText().toString());

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

        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setPackage(SMARTPHONE_LINK_PACKAGE);
        viewIntent.setData(Uri.parse(buildGeoUri()));

        try {
            startActivity(viewIntent);
            setStatus("已用 geo 連結開啟 Smartphone Link。");
            appendLog("SMARTPHONE_LINK", "geo ACTION_VIEW started");
            return;
        } catch (ActivityNotFoundException ignored) {
            appendLog("SMARTPHONE_LINK", "geo ACTION_VIEW not found");
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

    private String safeDeviceName(BluetoothDevice device) {
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
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String safeMessage = message == null ? "" : message.replace('\r', ' ').trim();
        logBuffer.append(time)
                .append(" ")
                .append(tag)
                .append(" ")
                .append(safeMessage)
                .append("\n");

        if (logBuffer.length() > MAX_LOG_CHARS) {
            logBuffer.delete(0, logBuffer.length() - MAX_LOG_CHARS);
        }

        if (preferences != null) {
            preferences.edit().putString(PREF_LOG, logBuffer.toString()).apply();
        }
        refreshLogText();
    }

    private void refreshLogText() {
        if (logText != null) {
            logText.setText(logBuffer.toString());
        }
    }

    private void copyLogToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("GarminMapShare log", logBuffer.toString()));
            setStatus("Log 已複製到剪貼簿。");
        } else {
            setStatus("無法取得剪貼簿服務。");
        }
    }

    private void shareLog() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "GarminMapShare log");
        intent.putExtra(Intent.EXTRA_TEXT, logBuffer.toString());
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

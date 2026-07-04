package com.codexbridge.approval;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 301;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private EditText urlInput;
    private EditText tokenInput;
    private TextView statusText;
    private TextView dialogStatusText;
    private TextView watchRelayText;
    private TextView loadErrorTitle;
    private TextView loadErrorMessage;
    private FrameLayout loadErrorOverlay;
    private FrameLayout settingsOverlay;
    private LinearLayout settingsCard;
    private FrameLayout splashOverlay;
    private WebView webView;
    private boolean bridgeOnline;
    private boolean dashboardLoadFailed;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.rgb(7, 16, 14));
            getWindow().setNavigationBarColor(Color.rgb(7, 16, 14));
        }
        buildLayout();
        requestNotificationPermission();
        if (Prefs.monitorEnabled(this)) {
            ApprovalMonitorService.start(this);
        }
        loadDashboard();
        showSplash();
        handler.postDelayed(() -> checkBridgeAndMaybeShowSettings(true), 900);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        checkBridgeAndMaybeShowSettings(false);
    }

    private void buildLayout() {
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Color.rgb(7, 16, 14));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(7, 16, 14));
        rootFrame.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        page.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                dashboardLoadFailed = false;
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!dashboardLoadFailed) hideLoadError();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    showDashboardLoadError();
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                String dashboardUrl = Prefs.dashboardUrl(MainActivity.this);
                if (failingUrl == null || dashboardUrl.startsWith(failingUrl) || failingUrl.startsWith(Prefs.baseUrl(MainActivity.this))) {
                    showDashboardLoadError();
                }
            }
        });
        page.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        loadErrorOverlay = buildLoadErrorOverlay();
        rootFrame.addView(loadErrorOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        settingsOverlay = buildSettingsOverlay();
        rootFrame.addView(settingsOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        splashOverlay = buildSplashOverlay();
        rootFrame.addView(splashOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        setContentView(rootFrame);
        updateStatus();
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(12), dp(10));
        bar.setBackgroundColor(Color.rgb(7, 16, 14));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("审批同步助手");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        copy.addView(title);

        statusText = new TextView(this);
        statusText.setTextSize(12);
        statusText.setTextColor(Color.rgb(148, 166, 158));
        copy.addView(statusText);
        bar.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button refresh = compactButton("刷新");
        refresh.setOnClickListener(view -> {
            loadDashboard();
            checkBridgeAndMaybeShowSettings(false);
            Toast.makeText(this, "正在刷新审批和额度", Toast.LENGTH_SHORT).show();
        });
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(64), dp(38)));

        Button setup = compactButton("设置");
        setup.setOnClickListener(view -> showSettings());
        LinearLayout.LayoutParams setupParams = new LinearLayout.LayoutParams(dp(64), dp(38));
        setupParams.leftMargin = dp(8);
        bar.addView(setup, setupParams);

        return bar;
    }

    private FrameLayout buildSettingsOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setVisibility(View.GONE);
        overlay.setAlpha(0f);
        overlay.setBackgroundColor(0x99000000);
        overlay.setOnClickListener(view -> {
            if (bridgeOnline && Prefs.monitorEnabled(this)) hideSettings();
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setOnClickListener(view -> {
        });

        settingsCard = new LinearLayout(this);
        settingsCard.setOrientation(LinearLayout.VERTICAL);
        settingsCard.setPadding(dp(20), dp(18), dp(20), dp(18));
        settingsCard.setBackground(rounded(Color.rgb(248, 252, 250), dp(24), 0x22000000, 1));
        settingsCard.setOnClickListener(view -> {
        });

        TextView title = new TextView(this);
        title.setText("连接与提醒");
        title.setTextColor(Color.rgb(7, 32, 25));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        settingsCard.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("电脑端配置好后，这个窗口会自动隐藏。需要调整时点右上角“设置”。");
        subtitle.setTextColor(Color.rgb(84, 105, 96));
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(6), 0, dp(12));
        settingsCard.addView(subtitle);

        dialogStatusText = new TextView(this);
        dialogStatusText.setTextSize(13);
        dialogStatusText.setTextColor(Color.rgb(22, 120, 77));
        dialogStatusText.setBackground(rounded(Color.rgb(229, 249, 239), dp(12), 0x00000000, 0));
        dialogStatusText.setPadding(dp(12), dp(9), dp(12), dp(9));
        settingsCard.addView(dialogStatusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        watchRelayText = new TextView(this);
        watchRelayText.setTextSize(13);
        watchRelayText.setTextColor(Color.rgb(73, 92, 84));
        watchRelayText.setBackground(rounded(Color.rgb(238, 246, 242), dp(12), 0x00000000, 0));
        watchRelayText.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams relayParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        relayParams.topMargin = dp(10);
        settingsCard.addView(watchRelayText, relayParams);

        Button pairWatch = secondaryButton("扫码配对手表");
        pairWatch.setOnClickListener(view -> startActivity(new Intent(this, PairScanActivity.class)));
        addButton(settingsCard, pairWatch, dp(10));

        urlInput = input("电脑桥接地址", Prefs.baseUrl(this));
        tokenInput = input("Token", Prefs.token(this));
        settingsCard.addView(label("电脑地址"));
        settingsCard.addView(urlInput);
        settingsCard.addView(label("访问 Token"));
        settingsCard.addView(tokenInput);

        Button start = primaryButton("保存并开启后台提醒");
        start.setOnClickListener(view -> saveAndStartMonitor());
        addButton(settingsCard, start, 0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button test = secondaryButton("测试连接");
        test.setOnClickListener(view -> {
            saveBridge();
            checkBridgeAndMaybeShowSettings(false);
            Toast.makeText(this, "正在测试电脑端连接", Toast.LENGTH_SHORT).show();
        });
        row.addView(test, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        addViewWithTopMargin(settingsCard, row, dp(10));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button battery = secondaryButton("后台权限");
        battery.setOnClickListener(view -> openBatterySettings());
        row2.addView(battery, new LinearLayout.LayoutParams(0, dp(44), 1));

        Button stop = secondaryButton("停止提醒");
        stop.setOnClickListener(view -> {
            ApprovalMonitorService.stop(this);
            Toast.makeText(this, "已停止后台提醒", Toast.LENGTH_SHORT).show();
            updateStatus();
            checkBridgeAndMaybeShowSettings(false);
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        stopParams.leftMargin = dp(10);
        row2.addView(stop, stopParams);
        addViewWithTopMargin(settingsCard, row2, dp(10));

        Button close = textButton("关闭");
        close.setOnClickListener(view -> hideSettings());
        addButton(settingsCard, close, dp(6));

        scroll.addView(settingsCard, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        params.leftMargin = dp(18);
        params.rightMargin = dp(18);
        overlay.addView(scroll, params);
        return overlay;
    }

    private FrameLayout buildLoadErrorOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(1f);
        overlay.setBackgroundColor(Color.rgb(244, 246, 245));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(28), dp(24), dp(28));

        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.connection_failed);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(image, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(250)
        ));

        loadErrorTitle = new TextView(this);
        loadErrorTitle.setText("正在连接电脑端");
        loadErrorTitle.setTextColor(Color.rgb(22, 31, 29));
        loadErrorTitle.setTextSize(24);
        loadErrorTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        loadErrorTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dp(8);
        content.addView(loadErrorTitle, titleParams);

        loadErrorMessage = new TextView(this);
        loadErrorMessage.setText("正在打开审批面板。如果长时间没有响应，请确认电脑端已启动，并且手机和电脑在同一网络。");
        loadErrorMessage.setTextColor(Color.rgb(91, 105, 101));
        loadErrorMessage.setTextSize(14);
        loadErrorMessage.setGravity(Gravity.CENTER);
        loadErrorMessage.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        messageParams.topMargin = dp(10);
        messageParams.leftMargin = dp(6);
        messageParams.rightMargin = dp(6);
        content.addView(loadErrorMessage, messageParams);

        Button retry = primaryButton("重试连接");
        retry.setOnClickListener(view -> {
            loadDashboard();
            checkBridgeAndMaybeShowSettings(false);
        });
        addButton(content, retry, dp(24));

        Button settings = secondaryButton("打开设置");
        settings.setOnClickListener(view -> showSettings());
        addButton(content, settings, dp(10));

        TextView hint = new TextView(this);
        hint.setText("电脑端启动后，点“重试连接”即可恢复审批界面。");
        hint.setTextColor(Color.rgb(125, 137, 133));
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hintParams.topMargin = dp(12);
        content.addView(hint, hintParams);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        params.leftMargin = dp(20);
        params.rightMargin = dp(20);
        overlay.addView(content, params);
        return overlay;
    }

    private FrameLayout buildSplashOverlay() {
        FrameLayout splash = new FrameLayout(this);
        splash.setBackgroundColor(Color.rgb(4, 12, 10));

        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.splash_approval);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        splash.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_HORIZONTAL);
        copy.setPadding(dp(24), 0, dp(24), dp(82));

        TextView name = new TextView(this);
        name.setText("审批同步助手");
        name.setTextColor(Color.WHITE);
        name.setTextSize(30);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(name);

        TextView zh = new TextView(this);
        zh.setText("腕上审批，额度随行");
        zh.setTextColor(Color.rgb(205, 242, 224));
        zh.setTextSize(15);
        zh.setPadding(0, dp(9), 0, dp(2));
        copy.addView(zh);

        TextView en = new TextView(this);
        en.setText("Approve fast. Stay in flow.");
        en.setTextColor(Color.rgb(137, 172, 158));
        en.setTextSize(13);
        copy.addView(en);

        FrameLayout.LayoutParams copyParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        splash.addView(copy, copyParams);
        return splash;
    }

    private void showSplash() {
        splashOverlay.setAlpha(1f);
        splashOverlay.setVisibility(View.VISIBLE);
        handler.postDelayed(() -> splashOverlay.animate()
                .alpha(0f)
                .setDuration(380)
                .withEndAction(() -> splashOverlay.setVisibility(View.GONE))
                .start(), 1250);
    }

    private void showSettings() {
        settingsOverlay.setVisibility(View.VISIBLE);
        settingsOverlay.animate().cancel();
        settingsCard.animate().cancel();
        settingsOverlay.animate().alpha(1f).setDuration(160).start();
        settingsCard.setScaleX(0.92f);
        settingsCard.setScaleY(0.92f);
        settingsCard.setAlpha(0f);
        settingsCard.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280)
                .setInterpolator(new OvershootInterpolator(1.05f))
                .start();
    }

    private void hideSettings() {
        settingsOverlay.animate()
                .alpha(0f)
                .setDuration(160)
                .withEndAction(() -> settingsOverlay.setVisibility(View.GONE))
                .start();
    }

    private void showLoadError(String title, String message) {
        dashboardLoadFailed = true;
        if (loadErrorTitle != null) loadErrorTitle.setText(title);
        if (loadErrorMessage != null) loadErrorMessage.setText(message);
        if (loadErrorOverlay == null || loadErrorOverlay.getVisibility() == View.VISIBLE) return;
        loadErrorOverlay.setAlpha(0f);
        loadErrorOverlay.setVisibility(View.VISIBLE);
        loadErrorOverlay.animate().alpha(1f).setDuration(180).start();
    }

    private void hideLoadError() {
        if (loadErrorOverlay == null || loadErrorOverlay.getVisibility() != View.VISIBLE) return;
        loadErrorOverlay.animate()
                .alpha(0f)
                .setDuration(140)
                .withEndAction(() -> loadErrorOverlay.setVisibility(View.GONE))
                .start();
    }

    private void showDashboardLoadError() {
        showLoadError("电脑端未连接", "请先在电脑上启动“审批同步助手”，并确认手机和电脑在同一网络。");
    }

    private void checkBridgeAndMaybeShowSettings(boolean automatic) {
        if (dialogStatusText != null) {
            dialogStatusText.setText("正在检查电脑端连接...");
        }
        new Thread(() -> {
            StatusSnapshot snapshot = null;
            Exception failure = null;
            try {
                snapshot = BridgeClient.fetchStatus(this);
            } catch (Exception error) {
                failure = error;
            }
            StatusSnapshot finalSnapshot = snapshot;
            Exception finalFailure = failure;
            runOnUiThread(() -> {
                bridgeOnline = finalSnapshot != null;
                updateStatus();
                if (dialogStatusText != null) {
                    if (bridgeOnline) {
                        dialogStatusText.setText("电脑端已连接 · " + finalSnapshot.quotaTitle);
                        dialogStatusText.setTextColor(Color.rgb(22, 120, 77));
                    } else {
                        String message = finalFailure == null ? "未知错误" : finalFailure.getMessage();
                        dialogStatusText.setText("电脑端未连接 · " + message);
                        dialogStatusText.setTextColor(Color.rgb(176, 73, 50));
                    }
                }
                if (!bridgeOnline) {
                    showDashboardLoadError();
                }
                if (automatic && (!bridgeOnline || !Prefs.monitorEnabled(this))) {
                    showSettings();
                } else if (automatic && settingsOverlay.getVisibility() == View.VISIBLE) {
                    if (bridgeOnline) hideLoadError();
                    hideSettings();
                } else if (bridgeOnline) {
                    hideLoadError();
                }
            });
        }, "bridge-check").start();
    }

    private void saveAndStartMonitor() {
        saveBridge();
        requestNotificationPermission();
        ApprovalMonitorService.start(this);
        Toast.makeText(this, "已开启后台提醒", Toast.LENGTH_SHORT).show();
        loadDashboard();
        updateStatus();
        checkBridgeAndMaybeShowSettings(true);
    }

    private void saveBridge() {
        Prefs.saveBridge(this, urlInput.getText().toString(), tokenInput.getText().toString());
    }

    private void loadDashboard() {
        if (urlInput != null && tokenInput != null) saveBridge();
        dashboardLoadFailed = false;
        webView.loadUrl(Prefs.dashboardUrl(this));
    }

    private void updateStatus() {
        if (statusText == null) return;
        String monitor = Prefs.monitorEnabled(this) ? "后台已开启" : "后台未开启";
        String bridge = bridgeOnline ? "电脑已连接" : "电脑待检查";
        String relay = WatchRelayServer.isRunning() ? "手表中继已开" : "手表中继待开";
        statusText.setText(monitor + " · " + bridge + " · " + relay);
        updateWatchRelayText();
    }

    private void updateWatchRelayText() {
        if (watchRelayText == null) return;
        String state = WatchRelayServer.isRunning()
                ? "手表中继正在运行"
                : "保存并开启后台提醒后，手机会启动手表中继";
        String pair = Prefs.watchPaired(this)
                ? "已扫码配对" + (Prefs.watchPairLabel(this).isEmpty() ? "" : "：" + Prefs.watchPairLabel(this))
                : "未扫码配对，可点下方按钮扫描手表二维码";
        watchRelayText.setText(state + "\n" + pair + "\n手表 App 连接地址：" + WatchRelayServer.localUrl());
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void openBatterySettings() {
        try {
            PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
            String packageName = getPackageName();
            if (manager != null && !manager.isIgnoringBatteryOptimizations(packageName)) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:" + packageName));
                startActivity(intent);
                return;
            }
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception error) {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(82, 100, 93));
        label.setTextSize(12);
        label.setPadding(0, dp(12), 0, dp(4));
        return label;
    }

    private EditText input(String hint, String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setHint(hint);
        input.setTextSize(15);
        input.setTextColor(Color.rgb(10, 32, 24));
        input.setHintTextColor(Color.rgb(133, 148, 141));
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(rounded(Color.rgb(239, 246, 243), dp(12), 0x22000000, 1));
        return input;
    }

    private Button compactButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(13);
        button.setTextColor(Color.rgb(204, 250, 226));
        button.setBackground(rounded(0x2218B875, dp(18), 0x4418B875, 1));
        wireButtonFeedback(button);
        return button;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.rgb(2, 31, 18));
        button.setBackground(rounded(Color.rgb(98, 242, 167), dp(16), 0x00000000, 0));
        wireButtonFeedback(button);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(Color.rgb(12, 68, 46));
        button.setBackground(rounded(Color.rgb(230, 241, 236), dp(14), 0x22000000, 1));
        wireButtonFeedback(button);
        return button;
    }

    private Button textButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(Color.rgb(72, 88, 81));
        button.setBackgroundColor(Color.TRANSPARENT);
        wireButtonFeedback(button);
        return button;
    }

    private void wireButtonFeedback(Button button) {
        button.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                view.animate().cancel();
                view.animate()
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .alpha(0.9f)
                        .setDuration(72)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                view.animate().cancel();
                view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(230)
                        .setInterpolator(new OvershootInterpolator(1.55f))
                        .start();
            }
            return false;
        });
    }

    private void addButton(LinearLayout parent, Button button, int topMargin) {
        addViewWithTopMargin(parent, button, topMargin == 0 ? dp(14) : topMargin);
        ViewGroup.LayoutParams params = button.getLayoutParams();
        params.height = dp(48);
        button.setLayoutParams(params);
    }

    private void addViewWithTopMargin(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = topMargin;
        parent.addView(child, params);
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor, int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidthDp > 0) drawable.setStroke(dp(strokeWidthDp), strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

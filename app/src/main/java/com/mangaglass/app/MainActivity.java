package com.mangaglass.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.*;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int OVERLAY = 11, CAPTURE = 12, NOTIFICATIONS = 13;
    private static final String[] SHORT_LANG_LABELS = {"自动", "日语", "英语", "韩语"};
    private Settings settings;
    private FrameLayout orbHalo;
    private LinearLayout orbButton, runningBar, langRow;
    private TextView orbGlyph, orbSub, statusCaption;
    private TextView segSingle, segContinuous;
    private TextView modeValue, configuration, usageLink, timingLink;
    private TextView[] langChips;
    private boolean quickStart;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        settings = new Settings(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        Ui.unclip(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout page = Ui.column(this);
        Ui.unclip(page);
        page.setPadding(Ui.dp(this, 22), Ui.dp(this, 6), Ui.dp(this, 22), Ui.dp(this, 16));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -1));

        // 顶部精简导航栏：移除左侧标题，仅保留右上角「说明」入口。
        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView helpLink = Ui.text(this, "说明", 15.5f, Ui.APPLE_BLUE, 500);
        helpLink.setGravity(Gravity.CENTER);
        helpLink.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        helpLink.setMinimumHeight(Ui.dp(this, 44));
        helpLink.setBackground(Ui.ripple(0x18007aff, Ui.shape(Color.TRANSPARENT, 8, this)));
        helpLink.setOnClickListener(v -> help());
        topBar.addView(helpLink);
        page.addView(topBar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));

        // 中央焦点舞台：实体悬浮球主开关与运行态操作胶囊。
        LinearLayout hero = Ui.column(this);
        Ui.unclip(hero);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 22));

        int haloSize = Ui.dp(this, 160);
        int orbSize = Ui.dp(this, 136);
        orbHalo = new FrameLayout(this);
        Ui.unclip(orbHalo);

        orbButton = Ui.column(this);
        orbButton.setGravity(Gravity.CENTER);
        orbButton.setClickable(true);
        orbButton.setFocusable(true);
        orbButton.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        Ui.softShadow(orbButton, 6f, 0x55007aff);
        orbButton.setOnClickListener(v -> begin());

        orbGlyph = Ui.text(this, "译", 36f, Color.WHITE, 600);
        orbGlyph.setIncludeFontPadding(false);
        orbGlyph.setLineSpacing(0, 1f);
        orbButton.addView(orbGlyph, new LinearLayout.LayoutParams(-2, -2));

        orbSub = Ui.text(this, "开启悬浮球", 12.5f, 0xe6ffffff, 500);
        orbSub.setLineSpacing(0, 1f);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-2, -2);
        subParams.topMargin = Ui.dp(this, 4);
        orbButton.addView(orbSub, subParams);

        FrameLayout.LayoutParams orbParams = new FrameLayout.LayoutParams(orbSize, orbSize, Gravity.CENTER);
        orbHalo.addView(orbButton, orbParams);
        hero.addView(orbHalo, new LinearLayout.LayoutParams(haloSize, haloSize));

        statusCaption = Ui.text(this, "", 14f, Ui.APPLE_LABEL_2, 400);
        statusCaption.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(-1, -2);
        captionParams.topMargin = Ui.dp(this, 14);
        hero.addView(statusCaption, captionParams);

        runningBar = new LinearLayout(this);
        runningBar.setGravity(Gravity.CENTER);
        TextView returnPill = buildActionPill("返回漫画", Ui.APPLE_BLUE, Ui.APPLE_BLUE_TINT, 0x22007aff);
        returnPill.setOnClickListener(v -> begin());
        TextView stopPill = buildActionPill("停止服务", Ui.APPLE_RED, Ui.APPLE_RED_TINT, 0x22ff3b30);
        stopPill.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            orbButton.postDelayed(this::refresh, 250);
        });
        runningBar.addView(returnPill, new LinearLayout.LayoutParams(-2, Ui.dp(this, 36)));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-2, Ui.dp(this, 36));
        stopParams.leftMargin = Ui.dp(this, 10);
        runningBar.addView(stopPill, stopParams);

        LinearLayout.LayoutParams runBarParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 38));
        runBarParams.topMargin = Ui.dp(this, 14);
        hero.addView(runningBar, runBarParams);

        page.addView(hero, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 下半区极简配置区：模式分段控件 + 两项核心配置组 + 角落信息页脚。
        LinearLayout controls = Ui.column(this);
        Ui.unclip(controls);

        // 标准单行分段控件（单次翻译 / 连续翻页）
        LinearLayout segmented = new LinearLayout(this);
        Ui.unclip(segmented);
        segmented.setBackground(Ui.shape(Ui.APPLE_SEG_BG, 10, this));
        int segPad = Ui.dp(this, 3);
        segmented.setPadding(segPad, segPad, segPad, segPad);
        segSingle = buildSegment("单次翻译", () -> setContinuousMode(false));
        segContinuous = buildSegment("连续翻页", () -> setContinuousMode(true));
        segmented.addView(segSingle, new LinearLayout.LayoutParams(0, -1, 1f));
        segmented.addView(segContinuous, new LinearLayout.LayoutParams(0, -1, 1f));
        controls.addView(segmented, new LinearLayout.LayoutParams(-1, Ui.dp(this, 38)));

        Ui.gap(controls, 14);

        // 轻灰分组卡片（翻译方式 + 条件语言药丸 + API 模型）
        LinearLayout groupCard = Ui.column(this);
        Ui.unclip(groupCard);
        groupCard.setBackground(Ui.shape(Ui.APPLE_GROUP, 14, this));

        LinearLayout modeRow = buildCompactRow("翻译方式", this::chooseMode);
        modeValue = modeRow.findViewWithTag("value");
        groupCard.addView(modeRow);

        langRow = new LinearLayout(this);
        Ui.unclip(langRow);
        langRow.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 14));
        langChips = new TextView[Settings.OCR_LANGUAGES.length];
        for (int i = 0; i < Settings.OCR_LANGUAGES.length; i++) {
            final String code = Settings.OCR_LANGUAGES[i];
            TextView chip = Ui.text(this, SHORT_LANG_LABELS[i], 13f, Ui.APPLE_LABEL_2, 500);
            chip.setGravity(Gravity.CENTER);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(v -> {
                settings.saveOcrLanguage(code);
                refresh();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 32), 1f);
            if (i > 0) lp.leftMargin = Ui.dp(this, 6);
            langRow.addView(chip, lp);
            langChips[i] = chip;
        }
        groupCard.addView(langRow);

        View sep = new View(this);
        sep.setBackgroundColor(Ui.APPLE_SEP);
        LinearLayout.LayoutParams sepParams = new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(this, 0.5f)));
        sepParams.leftMargin = Ui.dp(this, 16);
        groupCard.addView(sep, sepParams);

        LinearLayout apiRow = buildCompactRow("API 模型", this::editApi);
        configuration = apiRow.findViewWithTag("value");
        groupCard.addView(apiRow);

        controls.addView(groupCard);
        Ui.gap(controls, 14);

        // 页脚角落收纳条：用量、耗时与控制中心快捷开关。
        View footerLine = new View(this);
        footerLine.setBackgroundColor(Ui.APPLE_SEP);
        controls.addView(footerLine, new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(this, 0.5f))));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2), 0);

        LinearLayout footerLeft = new LinearLayout(this);
        footerLeft.setGravity(Gravity.CENTER_VERTICAL);
        usageLink = buildCornerLink("今日 0 Token", () -> TokenUsageDialog.show(this));
        TextView dot = Ui.text(this, " · ", 12f, Ui.APPLE_LABEL_3, 400);
        timingLink = buildCornerLink("耗时", () -> TranslationTimingDialog.show(this));
        footerLeft.addView(usageLink);
        footerLeft.addView(dot);
        footerLeft.addView(timingLink);

        TextView tileLink = buildCornerLink("快捷开关", this::addQuickTile);
        tileLink.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6));

        footer.addView(footerLeft, new LinearLayout.LayoutParams(0, -2, 1f));
        footer.addView(tileLink);
        controls.addView(footer);

        page.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        setContentView(scroll);
        refresh();
        handleQuickStart(getIntent());
    }

    private TextView buildActionPill(String label, int textColor, int bgColor, int rippleColor) {
        TextView pill = Ui.text(this, label, 13.5f, textColor, 600);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        pill.setBackground(Ui.ripple(rippleColor, Ui.shape(bgColor, 18, this)));
        pill.setClickable(true);
        pill.setFocusable(true);
        return pill;
    }

    private TextView buildSegment(String label, Runnable action) {
        TextView item = Ui.text(this, label, 13.5f, Color.BLACK, 500);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(v -> action.run());
        return item;
    }

    private LinearLayout buildCompactRow(String title, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 50));
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        row.setBackground(Ui.ripple(0x10000000, Ui.shape(Color.TRANSPARENT, 0, this)));
        row.setOnClickListener(v -> action.run());
        row.setFocusable(true);

        TextView label = Ui.text(this, title, 15.5f, Color.BLACK, 400);
        row.addView(label, new LinearLayout.LayoutParams(-2, -2));

        TextView value = Ui.text(this, "", 15f, Ui.APPLE_LABEL_2, 400);
        value.setTag("value");
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setGravity(Gravity.END);
        LinearLayout.LayoutParams valParams = new LinearLayout.LayoutParams(0, -2, 1f);
        valParams.leftMargin = Ui.dp(this, 12);
        row.addView(value, valParams);

        TextView chevron = Ui.text(this, "›", 20f, Ui.APPLE_LABEL_3, 500);
        chevron.setIncludeFontPadding(false);
        LinearLayout.LayoutParams chevParams = new LinearLayout.LayoutParams(-2, -2);
        chevParams.leftMargin = Ui.dp(this, 6);
        row.addView(chevron, chevParams);
        return row;
    }

    private TextView buildCornerLink(String text, Runnable action) {
        TextView link = Ui.text(this, text, 12f, Ui.APPLE_LABEL_2, 400);
        link.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6));
        link.setMinimumHeight(Ui.dp(this, 32));
        link.setGravity(Gravity.CENTER_VERTICAL);
        link.setBackground(Ui.ripple(0x12000000, Ui.shape(Color.TRANSPARENT, 6, this)));
        link.setClickable(true);
        link.setFocusable(true);
        link.setOnClickListener(v -> action.run());
        return link;
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleQuickStart(intent); }

    private void handleQuickStart(Intent intent) {
        if (intent != null && TranslationTileService.START.equals(intent.getAction())) {
            intent.setAction(null); quickStart = true; getWindow().getDecorView().post(this::begin);
        }
    }

    private void addQuickTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                getSystemService(android.app.StatusBarManager.class).requestAddTileService(
                        new android.content.ComponentName(this, TranslationTileService.class), "漫译",
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile), getMainExecutor(),
                        result -> toast(result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                                || result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                                ? "快捷开关已添加，可在控制中心使用" : "未添加快捷开关，可在控制中心编辑开关列表"));
                return;
            } catch (RuntimeException ignored) { }
        }
        new AlertDialog.Builder(this).setTitle("添加快捷开关")
                .setMessage("下拉打开控制中心，进入快捷开关编辑页面，将「漫译」添加到常用开关。点按开启悬浮球，再次点按停止服务。")
                .setPositiveButton("知道了", null).show();
    }

    private void setContinuousMode(boolean continuous) {
        if (settings.continuousMode() == continuous) return;
        settings.saveContinuousMode(continuous);
        if (CaptureService.running) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.SYNC_MODE));
        }
        refresh();
    }

    @Override protected void onResume() { super.onResume(); if (orbButton != null) refresh(); }

    private void refresh() {
        boolean apiReady = false;
        try {
            Settings.Profile p = settings.profile();
            apiReady = p.ready();
            configuration.setText(apiReady ? p.model : "未配置");
            configuration.setTextColor(apiReady ? Ui.APPLE_LABEL_2 : Ui.APPLE_ORANGE);
            configuration.setTypeface(Ui.typeface(apiReady ? 400 : 500));
        } catch (Exception e) {
            configuration.setText("密钥失效，请重设");
            configuration.setTextColor(Ui.APPLE_ORANGE);
            configuration.setTypeface(Ui.typeface(500));
        }

        boolean running = CaptureService.running;
        boolean continuous = settings.continuousMode();

        // 中央实体悬浮球与状态文案
        orbHalo.setBackground(running ? Ui.oval(Ui.APPLE_GREEN_RING) : null);
        orbButton.setBackground(Ui.ripple(0x28ffffff, Ui.oval(running ? Ui.APPLE_GREEN : Ui.APPLE_BLUE)));
        Ui.softShadow(orbButton, 6f, running ? 0x5534c759 : 0x55007aff);
        orbGlyph.setText(continuous ? "连" : "译");
        orbSub.setText(running ? "运行中" : (continuous ? "开启连续翻译" : "开启悬浮球"));
        orbButton.setContentDescription(running ? "漫译运行中，点击返回漫画" : (continuous ? "开启连续翻译" : "开启悬浮球"));

        if (running) {
            statusCaption.setText(continuous
                    ? "已就绪 · 切至漫画点击「连」球自动翻页翻译"
                    : "已就绪 · 切至漫画点击「译」球翻译当前画面");
        } else if (!apiReady) {
            statusCaption.setText("启动前请先配置 API 模型与密钥");
        } else {
            statusCaption.setText(continuous
                    ? "翻页自动隐去并重译 · 长按球可切回单次"
                    : "轻点启动悬浮球，切至漫画即可翻译");
        }
        runningBar.setVisibility(running ? View.VISIBLE : View.INVISIBLE);

        // 分段控件状态
        applySegmentStyle(segSingle, !continuous);
        applySegmentStyle(segContinuous, continuous);

        // 翻译方式与条件内联语言药丸
        boolean local = Settings.MODE_OCR.equals(settings.mode());
        modeValue.setText(local ? "本地 OCR" : "AI 识图");
        langRow.setVisibility(local ? View.VISIBLE : View.GONE);
        String activeLang = settings.ocrLanguage();
        for (int i = 0; i < Settings.OCR_LANGUAGES.length; i++) {
            boolean selected = Settings.OCR_LANGUAGES[i].equals(activeLang);
            TextView chip = langChips[i];
            chip.setBackground(selected
                    ? Ui.borderedShape(Color.WHITE, 0x12000000, 0.5f, 8, this)
                    : Ui.shape(0xb3ffffff, 8, this));
            chip.setTextColor(selected ? Ui.APPLE_BLUE : Ui.APPLE_LABEL_2);
            chip.setTypeface(Ui.typeface(selected ? 600 : 500));
            Ui.softShadow(chip, selected ? 1.2f : 0f, 0x24000000);
        }

        // 角落统计信息（Token 与最近一次耗时）
        TokenUsageStore.Day usage = TokenUsageStore.get(this).query(java.time.LocalDate.now());
        usageLink.setText(formatUsageCorner(usage));
        timingLink.setText(formatLatestTiming());
    }

    private void applySegmentStyle(TextView tab, boolean active) {
        tab.setBackground(active ? Ui.borderedShape(Color.WHITE, 0x12000000, 0.5f, 7.5f, this) : null);
        Ui.softShadow(tab, active ? 1.5f : 0f, 0x26000000);
        tab.setTextColor(active ? Color.BLACK : Ui.APPLE_LABEL_2);
        tab.setTypeface(Ui.typeface(active ? 600 : 500));
    }

    private static String formatUsageCorner(TokenUsageStore.Day usage) {
        if (usage.requests == 0) return "今日 0 Token";
        if (usage.totalKnown == 0) return "今日用量";
        if (usage.total >= 10_000) return String.format(Locale.ROOT, "今日 %.1fk Token", usage.total / 1000.0);
        return String.format(Locale.CHINA, "今日 %,d Token", usage.total);
    }

    private String formatLatestTiming() {
        try {
            List<TranslationTiming.Record> recent = TranslationTimingStore.get(this).recent();
            for (TranslationTiming.Record record : recent) {
                if (record.completed()) {
                    return record.totalMillis < 1000
                            ? "耗时 " + record.totalMillis + "ms"
                            : String.format(Locale.ROOT, "耗时 %.1fs", record.totalMillis / 1000.0);
                }
            }
        } catch (Exception ignored) { }
        return "耗时";
    }

    private void chooseMode() {
        String[] labels = {"AI 识图 · 发送完整截图（需视觉模型）", "本地 OCR · 离线识字后仅上传文本"};
        int checked = Settings.MODE_OCR.equals(settings.mode()) ? 1 : 0;
        new AlertDialog.Builder(this).setTitle("翻译方式").setSingleChoiceItems(labels, checked, (dialog, which) -> {
            settings.saveMode(which == 1 ? Settings.MODE_OCR : Settings.MODE_VISION);
            dialog.dismiss();
            refresh();
        }).setNegativeButton("取消", null).show();
    }

    private void begin() {
        if (CaptureService.running) { moveTaskToBack(true); return; }
        try { if (!settings.profile().ready()) { editApi(); return; } } catch (Exception e) { editApi(); return; }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            try { startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), OVERLAY); }
            catch (Exception e) { toast("请在系统设置中允许漫译显示悬浮窗"); }
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
            return;
        }
        requestCapture();
    }

    private void requestCapture() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        // Full display coordinates are essential to place the translations on the original bubbles.
        Intent capture = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(capture, CAPTURE);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NOTIFICATIONS) requestCapture();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OVERLAY) {
            refresh();
            boolean allowed = android.provider.Settings.canDrawOverlays(this);
            if (quickStart && allowed) { quickStart = false; begin(); }
            else { quickStart = false; toast(allowed ? "已授权，再点开启悬浮球即可" : "未授权，可再次开启重试"); }
        }
        if (request == CAPTURE) quickStart = false;
        if (request == CAPTURE && result == RESULT_OK && data != null) {
            try {
                startForegroundService(new Intent(this, CaptureService.class).putExtra("resultCode", result).putExtra("resultData", data));
                moveTaskToBack(true);
            } catch (Exception e) { toast("启动失败，请回到前台后重新授权"); }
        } else if (request == CAPTURE) toast("已取消屏幕捕获，未开始翻译");
    }

    private ApiSettingsDialog apiDialog;

    private void editApi() {
        if (apiDialog != null) apiDialog.close();
        apiDialog = new ApiSettingsDialog(this, settings, this::refresh);
        apiDialog.show();
    }

    private void help() {
        String instructions = "1. 在「API 模型」中保存服务地址、模型与密钥。译文统一输出为简体中文。\n\n"
                + "2. 点击中央悬浮球图标启动服务，允许悬浮窗与整个屏幕捕获权限。\n\n"
                + "3. 单次翻译（灰球「译」）：在漫画页面点击悬浮球翻译当前画面；再次点击退出后可滑动或翻页。点按画面可对照原图，长按译文放大阅读，双击译文聚焦查看。\n\n"
                + "4. 连续翻页（绿球「连」/「停」）：点击「连」球后直接滑动翻页，触屏瞬间译文消失，画面停稳后自动识别下一屏。\n\n"
                + "5. 长按悬浮球 1.5 秒切换单次与连续模式；长按 3 秒停止服务。\n\n"
                + "6. 返回桌面、切换最近任务或锁屏时，自动退出当前翻译或暂停连续模式。\n\n"
                + "7. 底部「快捷开关」可将漫译加入控制中心；「今日 Token」与「耗时」仅保存在本机，不留存截图、原文或译文。\n\n"
                + "8. 澎湃 OS 若切换应用后悬浮球消失，请检查后台弹出界面权限与省电限制。";
        new AlertDialog.Builder(this).setTitle("使用说明").setMessage(instructions).setPositiveButton("知道了", null).show();
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy() { if (apiDialog != null) apiDialog.close(); super.onDestroy(); }
}

package com.mangaglass.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Model discovery does not require a model and never overwrites a working saved profile. */
final class ApiSettingsDialog {
    private final Activity activity;
    private final Settings settings;
    private final Runnable onSaved;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<View> editable = new ArrayList<>();
    private AlertDialog dialog;
    private EditText url, key;
    private AutoCompleteTextView model;
    private TextView providerValue, thinkingValue, revealToggle, choose, fetch, test, save, clear;
    private TextView result, policyHint;
    private FrameLayout losslessTrack;
    private View losslessThumb;
    private int providerIndex, thinkingIndex;
    private boolean keyRevealed, losslessChecked;
    private List<ModelCatalog.Model> models = Collections.emptyList();
    private TranslationEngine active;
    private boolean closed, busy;
    private long started;
    private String busyLabel;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (closed || !busy) return;
            showResult(busyLabel + " · " + TranslationEngine.seconds(SystemClock.elapsedRealtime() - started) + " 秒");
            main.postDelayed(this, 500);
        }
    };

    ApiSettingsDialog(Activity activity, Settings settings, Runnable onSaved) {
        this.activity = activity;
        this.settings = settings;
        this.onSaved = onSaved;
    }

    void show() {
        Settings.Profile initial;
        try { initial = settings.profile(); } catch (Exception e) { initial = new Settings.Profile("", "", ""); }
        providerIndex = ApiProvider.index(initial.provider);
        thinkingIndex = RequestPolicy.index(initial.thinking);
        losslessChecked = initial.lossless;

        LinearLayout root = Ui.column(activity);
        root.setBackgroundColor(Ui.APPLE_GROUP);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        // 顶部固定导航栏（取消 / API 设置 / 保存）
        FrameLayout navBar = new FrameLayout(activity);
        navBar.setBackgroundColor(Ui.APPLE_GROUP);
        navBar.setPadding(Ui.dp(activity, 10), 0, Ui.dp(activity, 10), 0);

        TextView cancel = Ui.text(activity, "取消", 16f, Ui.APPLE_BLUE, 400);
        cancel.setGravity(Gravity.CENTER_VERTICAL);
        cancel.setPadding(Ui.dp(activity, 10), 0, Ui.dp(activity, 10), 0);
        cancel.setBackground(Ui.ripple(0x18007aff, Ui.shape(Color.TRANSPARENT, 8, activity)));
        cancel.setOnClickListener(v -> dialog.dismiss());
        navBar.addView(cancel, new FrameLayout.LayoutParams(-2, Ui.dp(activity, 48), Gravity.START | Gravity.CENTER_VERTICAL));

        TextView title = Ui.text(activity, "API 设置", 17f, Color.BLACK, 650);
        title.setGravity(Gravity.CENTER);
        navBar.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        save = Ui.text(activity, "保存", 16f, Ui.APPLE_BLUE, 600);
        save.setGravity(Gravity.CENTER_VERTICAL);
        save.setPadding(Ui.dp(activity, 10), 0, Ui.dp(activity, 10), 0);
        save.setBackground(Ui.ripple(0x18007aff, Ui.shape(Color.TRANSPARENT, 8, activity)));
        navBar.addView(save, new FrameLayout.LayoutParams(-2, Ui.dp(activity, 48), Gravity.END | Gravity.CENTER_VERTICAL));

        root.addView(navBar, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 50)));
        View navSep = new View(activity);
        navSep.setBackgroundColor(Ui.APPLE_SEP);
        root.addView(navSep, new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(activity, 0.5f))));

        // 可滚动的 iOS 分组表单主体
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.APPLE_GROUP);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout content = Ui.column(activity);
        Ui.unclip(content);
        content.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 18), Ui.dp(activity, 20), Ui.dp(activity, 32));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // === 第一组：接口连接 ===
        addSectionHeader(content, "接口连接");
        LinearLayout connGroup = createGroupCard();

        LinearLayout providerRow = createPickerRow("服务商", ApiProvider.LABELS[providerIndex], this::pickProvider);
        providerValue = providerRow.findViewWithTag("value");
        editable.add(providerRow);
        connGroup.addView(providerRow);

        addInsetSeparator(connGroup);
        url = addInputRow(connGroup, "基础地址", initial.baseUrl, false, null);
        updateAddressHint();

        addInsetSeparator(connGroup);
        revealToggle = Ui.text(activity, "显示", 14.5f, Ui.APPLE_BLUE, 500);
        revealToggle.setGravity(Gravity.CENTER);
        revealToggle.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 6), Ui.dp(activity, 2), Ui.dp(activity, 6));
        revealToggle.setBackground(Ui.ripple(0x18007aff, Ui.shape(Color.TRANSPARENT, 6, activity)));
        revealToggle.setOnClickListener(v -> toggleKeyReveal());
        key = addInputRow(connGroup, "API Key", initial.key, true, revealToggle);
        editable.add(revealToggle);

        content.addView(connGroup);
        addFootnote(content, "留空使用所选服务商默认地址；填写的地址优先使用。自定义服务需填写 HTTPS 地址。");

        // === 第二组：模型 ===
        addSectionHeader(content, "模型");
        LinearLayout modelGroup = createGroupCard();

        LinearLayout modelRow = new LinearLayout(activity);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);
        modelRow.setMinimumHeight(Ui.dp(activity, 50));
        modelRow.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 6), Ui.dp(activity, 14), Ui.dp(activity, 6));
        modelRow.addView(Ui.text(activity, "模型", 15.5f, Color.BLACK, 400), new LinearLayout.LayoutParams(Ui.dp(activity, 74), -2));

        model = new AutoCompleteTextView(activity);
        model.setSingleLine(true);
        model.setTextSize(15f);
        model.setThreshold(1);
        model.setBackground(null);
        model.setPadding(0, Ui.dp(activity, 8), Ui.dp(activity, 6), Ui.dp(activity, 8));
        model.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        model.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        model.setText(initial.model);
        model.setHint("填写或获取后选择");
        model.setHintTextColor(Ui.APPLE_LABEL_3);
        model.setContentDescription("模型名称");
        model.setTextColor(Color.BLACK);
        model.setTypeface(Ui.typeface(400));
        modelRow.addView(model, new LinearLayout.LayoutParams(0, -2, 1f));
        editable.add(model);

        choose = Ui.text(activity, "选择", 14.5f, Ui.APPLE_BLUE, 500);
        choose.setContentDescription("选择已获取的模型");
        choose.setGravity(Gravity.CENTER);
        choose.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 6), Ui.dp(activity, 2), Ui.dp(activity, 6));
        choose.setBackground(Ui.ripple(0x18007aff, Ui.shape(Color.TRANSPARENT, 6, activity)));
        modelRow.addView(choose, new LinearLayout.LayoutParams(-2, -2));
        modelGroup.addView(modelRow);

        addInsetSeparator(modelGroup);
        fetch = createActionRow("连接并获取模型", Ui.APPLE_BLUE, 500, Gravity.START);
        modelGroup.addView(fetch);

        content.addView(modelGroup);
        Ui.gap(content, 20);

        models = settings.models(initial.url, initial.key);
        updateChoices();
        choose.setOnClickListener(v -> {
            if (models.isEmpty()) return;
            String[] labels = new String[models.size()];
            for (int i = 0; i < models.size(); i++) labels[i] = models.get(i).label();
            AlertDialog selection = new AlertDialog.Builder(activity).setTitle("选择模型")
                    .setItems(labels, (d, which) -> model.setText(models.get(which).id, false))
                    .setNegativeButton("取消", null).create();
            selection.setOnShowListener(d -> selection.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
            selection.show();
        });

        // === 第三组：高级选项 ===
        addSectionHeader(content, "高级选项");
        LinearLayout advGroup = createGroupCard();

        LinearLayout thinkingRow = createPickerRow("思考设置", RequestPolicy.LABELS[thinkingIndex], this::pickThinking);
        thinkingValue = thinkingRow.findViewWithTag("value");
        editable.add(thinkingRow);
        advGroup.addView(thinkingRow);

        addInsetSeparator(advGroup);
        LinearLayout losslessRow = buildLosslessRow();
        editable.add(losslessRow);
        advGroup.addView(losslessRow);

        content.addView(advGroup);
        policyHint = Ui.text(activity, "", 12.5f, Ui.APPLE_LABEL_2, 400);
        policyHint.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 7), Ui.dp(activity, 16), Ui.dp(activity, 20));
        content.addView(policyHint);

        // === 第四组：连接测试 ===
        LinearLayout testGroup = createGroupCard();
        test = createActionRow("测试识图与翻译", Ui.APPLE_BLUE, 500, Gravity.START);
        testGroup.addView(test);
        content.addView(testGroup);

        result = Ui.text(activity, "", 13f, Color.BLACK, 400);
        result.setBackground(Ui.shape(Color.WHITE, 12, activity));
        result.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 16), Ui.dp(activity, 12));
        result.setVisibility(View.GONE);
        LinearLayout.LayoutParams resParams = new LinearLayout.LayoutParams(-1, -2);
        resParams.topMargin = Ui.dp(activity, 10);
        content.addView(result, resParams);

        addFootnote(content, "模型可用性以测试为准，翻译测试会产生 API 用量。");

        // === 第五组：清除密钥（Apple 破坏性红色独立组） ===
        LinearLayout clearGroup = createGroupCard();
        clear = createActionRow("清除密钥", Ui.APPLE_RED, 400, Gravity.CENTER);
        clearGroup.addView(clear);
        content.addView(clearGroup);

        // 事件绑定
        TextWatcher endpointChange = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { endpointChanged(); }
            public void afterTextChanged(Editable e) { }
        };
        url.addTextChangedListener(endpointChange);
        key.addTextChangedListener(endpointChange);
        model.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { updatePolicyHint(); }
            public void afterTextChanged(Editable e) { }
        });

        clear.setOnClickListener(v -> { settings.clearKey(); onSaved.run(); dialog.dismiss(); });
        save.setOnClickListener(v -> {
            try {
                Settings.Profile profile = snapshot();
                settings.saveProfile(profile.provider, profile.baseUrl, profile.model, profile.key,
                        profile.thinking, profile.lossless, models);
                onSaved.run();
                dialog.dismiss();
            } catch (Exception e) {
                showResult(e instanceof IllegalArgumentException ? e.getMessage() : "配置保存失败，请重试");
            }
        });
        fetch.setOnClickListener(v -> run(false));
        test.setOnClickListener(v -> run(true));

        dialog = new AlertDialog.Builder(activity).create();
        dialog.setView(root, 0, 0, 0, 0);
        dialog.setOnDismissListener(d -> close());
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        dialog.getWindow().setStatusBarColor(Ui.APPLE_GROUP);
        dialog.getWindow().setNavigationBarColor(Ui.APPLE_GROUP);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Ui.APPLE_GROUP));
        dialog.getWindow().setLayout(-1, -1);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        updatePolicyHint();
    }

    private void addSectionHeader(LinearLayout parent, String text) {
        TextView h = Ui.text(activity, text, 13f, Ui.APPLE_LABEL_2, 400);
        h.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), Ui.dp(activity, 7));
        parent.addView(h);
    }

    private void addFootnote(LinearLayout parent, String text) {
        TextView f = Ui.text(activity, text, 12.5f, Ui.APPLE_LABEL_2, 400);
        f.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 7), Ui.dp(activity, 16), Ui.dp(activity, 20));
        parent.addView(f);
    }

    private LinearLayout createGroupCard() {
        LinearLayout card = Ui.column(activity);
        Ui.unclip(card);
        card.setBackground(Ui.shape(Color.WHITE, 12, activity));
        return card;
    }

    private void addInsetSeparator(LinearLayout parent) {
        View line = new View(activity);
        line.setBackgroundColor(Ui.APPLE_SEP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(activity, 0.5f)));
        lp.leftMargin = Ui.dp(activity, 16);
        parent.addView(line, lp);
    }

    private LinearLayout createPickerRow(String label, String initialValue, Runnable onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(activity, 50));
        row.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 10), Ui.dp(activity, 14), Ui.dp(activity, 10));
        row.setBackground(Ui.ripple(0x10000000, Ui.shape(Color.TRANSPARENT, 0, activity)));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> onClick.run());

        row.addView(Ui.text(activity, label, 15.5f, Color.BLACK, 400), new LinearLayout.LayoutParams(-2, -2));

        TextView value = Ui.text(activity, initialValue, 15f, Ui.APPLE_LABEL_2, 400);
        value.setTag("value");
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setGravity(Gravity.END);
        LinearLayout.LayoutParams valParams = new LinearLayout.LayoutParams(0, -2, 1f);
        valParams.leftMargin = Ui.dp(activity, 12);
        row.addView(value, valParams);

        TextView chevron = Ui.text(activity, "›", 20f, Ui.APPLE_LABEL_3, 500);
        chevron.setIncludeFontPadding(false);
        LinearLayout.LayoutParams chevParams = new LinearLayout.LayoutParams(-2, -2);
        chevParams.leftMargin = Ui.dp(activity, 6);
        row.addView(chevron, chevParams);
        return row;
    }

    private EditText addInputRow(LinearLayout parent, String label, String value, boolean secret, View trailing) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(activity, 50));
        row.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 6), Ui.dp(activity, 14), Ui.dp(activity, 6));

        row.addView(Ui.text(activity, label, 15.5f, Color.BLACK, 400), new LinearLayout.LayoutParams(Ui.dp(activity, 74), -2));

        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setTextSize(15f);
        field.setBackground(null);
        field.setPadding(0, Ui.dp(activity, 8), Ui.dp(activity, 6), Ui.dp(activity, 8));
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setText(value);
        field.setHint(secret ? "必填" : "");
        field.setHintTextColor(Ui.APPLE_LABEL_3);
        field.setContentDescription(label);
        field.setTextColor(Color.BLACK);
        field.setTypeface(Ui.typeface(400));
        row.addView(field, new LinearLayout.LayoutParams(0, -2, 1f));
        editable.add(field);

        if (trailing != null) row.addView(trailing, new LinearLayout.LayoutParams(-2, -2));
        parent.addView(row);
        return field;
    }

    private LinearLayout buildLosslessRow() {
        LinearLayout row = new LinearLayout(activity);
        Ui.unclip(row);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(activity, 50));
        row.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 9), Ui.dp(activity, 16), Ui.dp(activity, 9));
        row.setClickable(true);
        row.setFocusable(true);

        row.addView(Ui.text(activity, "发送无损截图", 15.5f, Color.BLACK, 400), new LinearLayout.LayoutParams(0, -2, 1f));

        losslessTrack = new FrameLayout(activity);
        Ui.unclip(losslessTrack);
        int trackW = Ui.dp(activity, 51), trackH = Ui.dp(activity, 31), thumbSize = Ui.dp(activity, 27), pad = Ui.dp(activity, 2);
        losslessThumb = new View(activity);
        losslessThumb.setBackground(Ui.borderedShape(Color.WHITE, 0x14000000, 0.5f, 13.5f, activity));
        Ui.softShadow(losslessThumb, 1.5f, 0x26000000);
        FrameLayout.LayoutParams thumbParams = new FrameLayout.LayoutParams(thumbSize, thumbSize, Gravity.START | Gravity.CENTER_VERTICAL);
        thumbParams.leftMargin = pad;
        losslessTrack.addView(losslessThumb, thumbParams);
        row.addView(losslessTrack, new LinearLayout.LayoutParams(trackW, trackH));

        syncLosslessSwitch(false);
        row.setOnClickListener(v -> {
            if (busy) return;
            losslessChecked = !losslessChecked;
            syncLosslessSwitch(true);
        });
        return row;
    }

    private void syncLosslessSwitch(boolean animate) {
        losslessTrack.setBackground(Ui.shape(losslessChecked ? Ui.APPLE_GREEN : 0x29787880, 15.5f, activity));
        float tx = losslessChecked ? Ui.dp(activity, 20) : 0f;
        if (animate) losslessThumb.animate().translationX(tx).setDuration(180).start();
        else losslessThumb.setTranslationX(tx);
    }

    private TextView createActionRow(String label, int color, int weight, int gravity) {
        TextView row = Ui.text(activity, label, 16f, color, weight);
        row.setGravity(gravity | Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(activity, 50));
        row.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), 0);
        row.setBackground(Ui.ripple(0x10000000, Ui.shape(Color.TRANSPARENT, 0, activity)));
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    private void pickProvider() {
        if (busy) return;
        new AlertDialog.Builder(activity).setTitle("选择服务商")
                .setSingleChoiceItems(ApiProvider.LABELS, providerIndex, (d, which) -> {
                    if (which != providerIndex) {
                        String oldProvider = ApiProvider.IDS[providerIndex];
                        providerIndex = which;
                        providerValue.setText(ApiProvider.LABELS[providerIndex]);
                        updateAddressHint();
                        if (!"custom".equals(oldProvider) && oldProvider.equals(ApiProvider.infer(url.getText().toString()))) {
                            url.setText("");
                        }
                        endpointChanged();
                    }
                    d.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void pickThinking() {
        if (busy) return;
        new AlertDialog.Builder(activity).setTitle("思考设置")
                .setSingleChoiceItems(RequestPolicy.LABELS, thinkingIndex, (d, which) -> {
                    thinkingIndex = which;
                    thinkingValue.setText(RequestPolicy.LABELS[thinkingIndex]);
                    updatePolicyHint();
                    d.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void toggleKeyReveal() {
        keyRevealed = !keyRevealed;
        key.setTransformationMethod(keyRevealed ? null : PasswordTransformationMethod.getInstance());
        key.setSelection(key.length());
        revealToggle.setText(keyRevealed ? "隐藏" : "显示");
    }

    private void showResult(String text) { result.setVisibility(View.VISIBLE); result.setText(text); }
    private String providerId() { return ApiProvider.IDS[providerIndex]; }
    private String effectiveUrl() { return ApiProvider.resolve(providerId(), url.getText().toString()); }

    private void updateAddressHint() {
        String address = ApiProvider.defaultUrl(providerId());
        url.setHint(address.isEmpty() ? "请填写自定义 HTTPS 地址" : address);
    }

    private void endpointChanged() {
        try { models = settings.models(effectiveUrl(), key.getText().toString()); }
        catch (IllegalArgumentException e) { models = Collections.emptyList(); }
        updateChoices();
        updatePolicyHint();
        showResult("配置已修改，请重新获取模型或测试翻译后保存。");
    }

    private Settings.Profile snapshot() {
        String address = url.getText().toString().trim(), secret = key.getText().toString().trim();
        ApiProvider.resolve(providerId(), address);
        if (secret.isEmpty()) throw new IllegalArgumentException("请填写 API 密钥");
        return new Settings.Profile(providerId(), address, model.getText().toString(), secret,
                RequestPolicy.VALUES[thinkingIndex], losslessChecked, models);
    }

    private void updateChoices() {
        List<String> ids = new ArrayList<>();
        for (ModelCatalog.Model item : models) ids.add(item.id);
        model.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_dropdown_item_1line, ids));
        boolean available = !busy && !models.isEmpty();
        choose.setEnabled(available);
        choose.setTextColor(available ? Ui.APPLE_BLUE : Ui.APPLE_LABEL_3);
    }

    private void updatePolicyHint() {
        if (policyHint == null) return;
        String tail = "\n默认发送高质量 JPEG；细字失真时可开启无损截图。";
        try {
            policyHint.setText(RequestPolicy.description(effectiveUrl(), model.getText().toString(),
                    RequestPolicy.VALUES[thinkingIndex]) + tail);
        } catch (IllegalArgumentException e) {
            policyHint.setText(e.getMessage() + tail);
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        for (View field : editable) field.setEnabled(!value);
        fetch.setEnabled(!value);
        fetch.setTextColor(value ? Ui.APPLE_LABEL_3 : Ui.APPLE_BLUE);
        test.setEnabled(!value);
        test.setTextColor(value ? Ui.APPLE_LABEL_3 : Ui.APPLE_BLUE);
        save.setEnabled(!value);
        save.setTextColor(value ? Ui.APPLE_LABEL_3 : Ui.APPLE_BLUE);
        clear.setEnabled(!value);
        clear.setTextColor(value ? Ui.APPLE_LABEL_3 : Ui.APPLE_RED);
        updateChoices();
        main.removeCallbacks(ticker);
        if (value) { started = SystemClock.elapsedRealtime(); main.post(ticker); }
    }

    private void run(boolean generation) {
        final Settings.Profile profile;
        try {
            profile = snapshot();
            if (generation && !profile.ready()) throw new IllegalArgumentException("请先选择或填写模型名称");
            if (generation && profile.modelInfo != null && profile.modelInfo.knownTextOnly())
                throw new IllegalArgumentException("此模型仅支持文字输入，请选择支持图片的模型");
        } catch (Exception e) { showResult(TranslationEngine.error(e)); return; }
        TranslationEngine engine = new TranslationEngine().recordUsage(TokenUsageStore.get(activity));
        active = engine;
        busyLabel = generation ? "测试识图与翻译" : "正在获取模型列表";
        setBusy(true);
        worker.execute(() -> {
            String message;
            List<ModelCatalog.Model> fetched = null;
            try {
                if (!generation) {
                    fetched = engine.models(profile);
                    message = fetched.isEmpty() ? "接口已响应，但未返回模型。可手动填写模型后测试翻译。" : "已获取 " + fetched.size() + " 个模型，请选择后测试。";
                } else {
                    Bitmap image = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888);
                    try {
                        Canvas canvas = new Canvas(image);
                        canvas.drawColor(Color.WHITE);
                        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        paint.setColor(Color.BLACK);
                        paint.setTextSize(38);
                        canvas.drawText("Hello, my friend!", 50, 125, paint);
                        List<TextRegion> regions = engine.translate(image, profile, ignored -> { });
                        message = regions.isEmpty() ? "接口已响应，但未识别出样例文字。请更换视觉模型。" : "识图测试成功：" + regions.get(0).translated;
                    } finally { image.recycle(); }
                }
            } catch (Exception e) { message = TranslationEngine.error(e); }
            String text = message + "\n耗时 " + TranslationEngine.seconds(SystemClock.elapsedRealtime() - started) + " 秒";
            List<ModelCatalog.Model> received = fetched;
            main.post(() -> {
                if (closed || activity.isDestroyed() || active != engine) return;
                active = null;
                setBusy(false);
                showResult(text);
                if (received != null) {
                    models = received;
                    updateChoices();
                    // Never silently select a potentially unsuitable, expensive, or text-only model.
                    if (model.getText().toString().isEmpty() && !models.isEmpty()) choose.performClick();
                }
            });
        });
    }

    void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (active != null) active.cancel();
        worker.shutdownNow();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }
}

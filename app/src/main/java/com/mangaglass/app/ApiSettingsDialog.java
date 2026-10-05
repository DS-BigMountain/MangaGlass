package com.mangaglass.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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
    private Spinner thinking;
    private CheckBox lossless;
    private TextView result, policyHint;
    private Button fetch, choose, test, save, clear;
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
        this.activity = activity; this.settings = settings; this.onSaved = onSaved;
    }
    void show() {
        Settings.Profile initial;
        try { initial = settings.profile(); } catch (Exception e) { initial = new Settings.Profile("", "", ""); }
        LinearLayout content = Ui.column(activity);
        content.setPadding(Ui.dp(activity,22), Ui.dp(activity,12), Ui.dp(activity,22), Ui.dp(activity,20));
        content.setBackgroundColor(Ui.BG);
        LinearLayout header = new LinearLayout(activity); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(activity); back.setImageDrawable(new UiIcon("back"));
        back.setBackgroundColor(Color.TRANSPARENT); back.setContentDescription("返回首页");
        back.setPadding(Ui.dp(activity,12),Ui.dp(activity,12),Ui.dp(activity,12),Ui.dp(activity,12));
        back.setOnClickListener(v -> dialog.dismiss()); header.addView(back,new LinearLayout.LayoutParams(Ui.dp(activity,44),Ui.dp(activity,48)));
        header.addView(Ui.text(activity,"AI 截图翻译 API",21,Ui.INK,true)); content.addView(header);
        Ui.gap(content,12);
        content.addView(Ui.text(activity, "发送完整截图，AI 自动识别语言并翻译为简体中文。", 12, Ui.MUTED, false));
        Ui.gap(content,12);
        url = input(content, "API 基础地址（HTTPS）", initial.url, false);
        key = input(content, "API Key", initial.key, true);
        CheckBox reveal = new CheckBox(activity); reveal.setText("显示 Key"); reveal.setTextSize(13); reveal.setTextColor(Ui.MUTED);
        reveal.setOnCheckedChangeListener((b, checked) -> { key.setTransformationMethod(checked ? null : android.text.method.PasswordTransformationMethod.getInstance()); key.setSelection(key.length()); });
        content.addView(reveal); editable.add(reveal);
        Ui.gap(content,10); content.addView(Ui.text(activity, "模型（可选择或手动填写）", 12, Ui.MUTED, false));
        model = new AutoCompleteTextView(activity); model.setSingleLine(true); model.setTextSize(14); model.setThreshold(1);
        model.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        model.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); model.setText(initial.model);
        model.setContentDescription("模型名称"); model.setTextColor(Ui.INK);
        LinearLayout modelRow=new LinearLayout(activity);
        modelRow.addView(model, new LinearLayout.LayoutParams(0, Ui.dp(activity,54),1)); editable.add(model);
        choose = Ui.button(activity, "⌄", false); choose.setContentDescription("选择已获取的模型"); choose.setBackgroundColor(Color.TRANSPARENT);
        modelRow.addView(choose,new LinearLayout.LayoutParams(Ui.dp(activity,48),Ui.dp(activity,54))); content.addView(modelRow);
        Ui.gap(content,8); fetch = Ui.button(activity, "连接并获取模型", false); content.addView(fetch);
        models = settings.models(initial.url, initial.key); updateChoices();
        choose.setOnClickListener(v -> {
            String[] labels = new String[models.size()];
            for (int i = 0; i < models.size(); i++) labels[i] = models.get(i).label();
            AlertDialog selection = new AlertDialog.Builder(activity).setTitle("选择模型")
                    .setItems(labels, (d, which) -> model.setText(models.get(which).id, false)).setNegativeButton("返回", null).create();
            selection.setOnShowListener(d -> selection.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
            selection.show();
        });
        Ui.gap(content,12); content.addView(Ui.text(activity,"思考设置",12,Ui.MUTED,false));
        thinking = new Spinner(activity);
        ArrayAdapter<String> policies = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, RequestPolicy.LABELS);
        policies.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); thinking.setAdapter(policies);
        thinking.setSelection(RequestPolicy.index(initial.thinking)); content.addView(thinking); editable.add(thinking);
        policyHint = Ui.text(activity,"",12,Ui.MUTED,false); content.addView(policyHint);
        thinking.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) { updatePolicyHint(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        lossless = new CheckBox(activity); lossless.setText("发送无损截图（图片较大）"); lossless.setChecked(initial.lossless);
        content.addView(lossless); editable.add(lossless); content.addView(Ui.text(activity,"默认发送高质量 JPEG，保留原始像素尺寸。细字失真时可切换无损截图。",12,Ui.MUTED,false));
        Ui.gap(content,12);
        content.addView(Ui.text(activity,"模型可用性以测试为准，翻译测试会产生 API 用量。",12,Ui.MUTED,false));
        Ui.gap(content,8); result = Ui.text(activity,"",12,Ui.INK,false); result.setVisibility(View.GONE); content.addView(result);
        test = Ui.button(activity,"测试识图与翻译",false); content.addView(test);
        TextWatcher endpointChange = new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) {
                models = settings.models(url.getText().toString(),key.getText().toString()); updateChoices(); updatePolicyHint();
                showResult("配置已修改，请重新获取模型或测试翻译后保存。");
            }
            public void afterTextChanged(Editable e) { }
        };
        url.addTextChangedListener(endpointChange); key.addTextChangedListener(endpointChange);
        model.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) { updatePolicyHint(); }
            public void afterTextChanged(Editable e) { }
        });
        Ui.gap(content,10); save=Ui.button(activity,"保存",true); content.addView(save);
        clear=Ui.button(activity,"清除密钥",false); clear.setBackgroundColor(Color.TRANSPARENT); content.addView(clear);
        clear.setOnClickListener(v -> { settings.clearKey(); onSaved.run(); dialog.dismiss(); });
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG); scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((v,insets) -> { v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
        dialog = new AlertDialog.Builder(activity).create(); dialog.setView(scroll,0,0,0,0);
        save.setOnClickListener(v -> {
                try {
                    Settings.Profile profile = snapshot();
                    settings.saveProfile(profile.url, profile.model, profile.key, profile.thinking, profile.lossless, models);
                    onSaved.run(); dialog.dismiss();
                } catch (Exception e) { showResult(e instanceof IllegalArgumentException ? e.getMessage() : "配置保存失败，请重试"); }
        });
        fetch.setOnClickListener(v -> run(false)); test.setOnClickListener(v -> run(true));
        dialog.setOnDismissListener(d -> close());
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        dialog.getWindow().setStatusBarColor(Ui.BG);
        dialog.getWindow().setNavigationBarColor(Ui.BG);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
        dialog.getWindow().setLayout(-1,-1);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        updatePolicyHint();
    }
    private EditText input(LinearLayout parent, String label, String value, boolean secret) {
        parent.addView(Ui.text(activity,label,12,Ui.MUTED,false));
        EditText field = new EditText(activity); field.setSingleLine(true); field.setTextSize(14);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); field.setText(value);
        field.setContentDescription(label); field.setTextColor(Ui.INK);
        parent.addView(field,new LinearLayout.LayoutParams(-1,Ui.dp(activity,54))); editable.add(field); return field;
    }
    private void showResult(String text) { result.setVisibility(View.VISIBLE); result.setText(text); }
    private Settings.Profile snapshot() {
        String address = url.getText().toString().trim(), secret = key.getText().toString().trim();
        TranslationProtocol.endpoint(address);
        if (secret.isEmpty()) throw new IllegalArgumentException("请填写 API 密钥");
        return new Settings.Profile(address,model.getText().toString(),secret,RequestPolicy.VALUES[thinking.getSelectedItemPosition()],lossless.isChecked(),models);
    }
    private void updateChoices() {
        List<String> ids = new ArrayList<>(); for (ModelCatalog.Model item : models) ids.add(item.id);
        model.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_dropdown_item_1line,ids));
        choose.setEnabled(!busy && !models.isEmpty());
    }
    private void updatePolicyHint() {
        if (policyHint != null) policyHint.setText(RequestPolicy.description(url.getText().toString(),model.getText().toString(),RequestPolicy.VALUES[thinking.getSelectedItemPosition()]));
    }
    private void setBusy(boolean value) {
        busy = value;
        for (View field : editable) field.setEnabled(!value);
        fetch.setEnabled(!value); test.setEnabled(!value); choose.setEnabled(!value && !models.isEmpty());
        save.setEnabled(!value); clear.setEnabled(!value);
        main.removeCallbacks(ticker);
        if (value) { started = SystemClock.elapsedRealtime(); main.post(ticker); }
    }
    private void run(boolean generation) {
        final Settings.Profile profile;
        try {
            profile = snapshot();
            if (generation && !profile.ready()) throw new IllegalArgumentException("请先选择或填写模型名称");
            if (generation && profile.modelInfo != null && profile.modelInfo.knownTextOnly()) throw new IllegalArgumentException("此模型仅支持文字输入，请选择支持图片的模型");
        } catch (Exception e) { showResult(TranslationEngine.error(e)); return; }
        TranslationEngine engine = new TranslationEngine().recordUsage(TokenUsageStore.get(activity)); active = engine;
        busyLabel = generation ? "测试识图与翻译" : "正在获取模型列表";
        setBusy(true);
        worker.execute(() -> {
            String message; List<ModelCatalog.Model> fetched = null;
            try {
                if (!generation) {
                    fetched = engine.models(profile);
                    message = fetched.isEmpty() ? "接口已响应，但未返回模型。可手动填写模型后测试翻译。" : "已获取 " + fetched.size() + " 个模型，请选择后测试。";
                } else {
                    Bitmap image = Bitmap.createBitmap(480,240,Bitmap.Config.ARGB_8888);
                    try {
                        Canvas canvas = new Canvas(image); canvas.drawColor(Color.WHITE);
                        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTextSize(38); canvas.drawText("Hello, my friend!",50,125,paint);
                        List<TextRegion> regions = engine.translate(image,profile,ignored -> { });
                        message = regions.isEmpty() ? "接口已响应，但未识别出样例文字。请更换视觉模型。" : "识图测试成功：" + regions.get(0).translated;
                    } finally { image.recycle(); }
                }
            } catch (Exception e) { message = TranslationEngine.error(e); }
            String text = message + "\n耗时 " + TranslationEngine.seconds(SystemClock.elapsedRealtime() - started) + " 秒";
            List<ModelCatalog.Model> received = fetched;
            main.post(() -> {
                if (closed || activity.isDestroyed() || active != engine) return;
                active = null; setBusy(false); showResult(text);
                if (received != null) {
                    models = received; updateChoices();
                    // Never silently select a potentially unsuitable, expensive, or text-only model.
                    if (model.getText().toString().isEmpty() && !models.isEmpty()) choose.performClick();
                }
            });
        });
    }
    void close() {
        if (closed) return;
        closed = true; main.removeCallbacksAndMessages(null);
        if (active != null) active.cancel(); worker.shutdownNow();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }
}


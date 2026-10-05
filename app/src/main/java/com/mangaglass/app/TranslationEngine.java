package com.mangaglass.app;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** One image request per job. All methods run on a worker, never on the UI thread. */
public final class TranslationEngine {
    public interface Progress { void update(String message); }
    private final ApiTransport transport;
    private volatile boolean cancelled;
    private volatile String stage = "准备处理";
    public TranslationEngine() { this(null); }
    TranslationEngine(okhttp3.OkHttpClient client) { transport = new ApiTransport(client); }
    public TranslationEngine recordUsage(TokenUsage.Recorder recorder) { transport.usageRecorder=recorder; return this; }
    TranslationEngine recordTiming(TranslationTiming timing) { transport.timing=timing; return this; }
    public static String seconds(long millis) { return String.format(java.util.Locale.ROOT, "%.1f", millis / 1000.0); }
    public void cancel() { cancelled = true; transport.cancel(); }
    public List<TextRegion> translate(Bitmap bitmap, Settings.Profile profile, Progress progress) throws Exception {
        check();
        if (!profile.ready()) throw new IllegalArgumentException("请先配置 AI 截图翻译的 API 密钥与模型");
        if (profile.modelInfo != null && profile.modelInfo.knownTextOnly()) throw new IllegalArgumentException("此模型的列表信息标明仅支持文字，请选择支持图片输入的模型");
        stage = "准备图片"; progress.update(stage);
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        // Keep full-image dimensions; normalized coordinates refer to these exact pixels.
        if (!bitmap.compress(profile.lossless ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 85, image)) {
            throw new IllegalArgumentException("截图编码失败，请重新截图");
        }
        if (image.size() > 20 * 1024 * 1024) throw new IllegalArgumentException("截图过大，请降低屏幕分辨率后重试");
        String request = VisionProtocol.request(profile.model, Base64.encodeToString(image.toByteArray(), Base64.NO_WRAP), profile.lossless ? "image/png" : "image/jpeg",bitmap.getWidth(),bitmap.getHeight());
        check();
        stage = "等待 AI 截图翻译"; progress.update(stage + " · " + image.size() / 1024 + " KB");
        List<VisionProtocol.Region> parsed = VisionProtocol.response(post(request, profile),bitmap.getWidth(),bitmap.getHeight());
        List<TextRegion> regions = new ArrayList<>();
        for (VisionProtocol.Region region : parsed) {
            int[] b = region.box;
            if(b==null) { regions.add(new TextRegion(new Rect(),region.source,region.text)); continue; }
            // Round outward so the mask includes small glyphs at the right and bottom edges.
            Rect rect = new Rect(b[0] * bitmap.getWidth() / 1000, b[1] * bitmap.getHeight() / 1000,
                    (b[2] * bitmap.getWidth() + 999) / 1000, (b[3] * bitmap.getHeight() + 999) / 1000);
            if (rect.width() < 2 || rect.height() < 2) rect.setEmpty();
            regions.add(new TextRegion(rect, region.source, region.text));
        }
        check();
        return regions;
    }
    public String post(String requestBody, Settings.Profile profile) throws Exception {
        if (!profile.ready()) throw new IllegalArgumentException("请先配置 AI 截图翻译的 API 密钥与模型");
        String body = RequestPolicy.apply(requestBody, profile.url, profile.model, profile.thinking, profile.modelInfo);
        return exchange(TranslationProtocol.endpoint(profile.url), body, profile, false);
    }
    public List<ModelCatalog.Model> models(Settings.Profile profile) throws Exception {
        if (profile.key.isEmpty()) throw new IllegalArgumentException("请填写 API 密钥");
        stage = "获取模型列表";
        String result = exchange(ModelCatalog.endpoint(profile.url), null, profile, true);
        try { return ModelCatalog.parse(result); }
        catch (org.json.JSONException e) { throw new IllegalArgumentException("服务返回的模型列表格式不受支持，可手动填写模型后测试翻译"); }
    }
    private String exchange(String endpoint, String requestBody, Settings.Profile profile, boolean models) throws Exception {
        check(); return transport.exchange(endpoint,requestBody,profile.key,models);
    }
    private void check() throws InterruptedException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
    public static String error(Exception e) {
        if (e instanceof IllegalArgumentException && e.getMessage() != null) return e.getMessage();
        if (e instanceof java.net.SocketTimeoutException || e instanceof java.util.concurrent.TimeoutException) return "处理超时，请检查网络后重试";
        if (e instanceof org.json.JSONException) return "模型本次返回的译文数据格式不完整，请重试";
        if (e instanceof java.io.IOException) return "网络连接失败，请检查网络和 API 地址";
        return "AI 截图翻译失败，请重试或更换支持图片输入的模型";
    }
}

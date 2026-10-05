package com.mangaglass.app;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Base64;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** One instance per job. All methods run on a worker, never on the UI thread. */
public final class TranslationEngine {
    public interface Progress { void update(String message); }
    private final ApiTransport transport;
    private volatile boolean cancelled;
    private final OcrSession ocr;
    private final TranslationCache cache;
    private long ocrMillis, apiMillis, encodeMillis;
    private boolean cached;
    private volatile String stage = "准备处理";
    public TranslationEngine() { this(null, null); }
    public TranslationEngine recordUsage(TokenUsage.Recorder recorder) { transport.usageRecorder=recorder; return this; }
    public TranslationEngine(OcrSession ocr, TranslationCache cache) { this(ocr, cache, null); }
    TranslationEngine(OcrSession ocr, TranslationCache cache, okhttp3.OkHttpClient client) { this.ocr = ocr; this.cache = cache; this.transport = new ApiTransport(client); }
    private static long now() { return System.nanoTime() / 1_000_000; }
    public String timings() {
        return "识别 " + seconds(ocrMillis) + " 秒 · 图片准备 " + seconds(encodeMillis) + " 秒 · API " + seconds(apiMillis) + " 秒" + (cached ? "（缓存）" : "");
    }
    public static String seconds(long millis) { return String.format(java.util.Locale.ROOT, "%.1f", millis / 1000.0); }
    public String stage() { return stage; }
    public void cancel() {
        cancelled = true;
        transport.cancel();
    }
    public List<TextRegion> translate(Bitmap bitmap, boolean vision, String language, Settings.Profile profile, Progress progress) throws Exception {
        check();
        if (vision) {
            if (profile.modelInfo != null && profile.modelInfo.knownTextOnly()) throw new IllegalArgumentException("此模型的列表信息标明仅支持文字，请选择支持图片输入的模型");
            stage = "准备图片"; progress.update(stage);
            long encodingStarted = now();
            ByteArrayOutputStream image = new ByteArrayOutputStream();
            // Keep every input pixel for small vertical glyphs; only change the transfer encoding.
            bitmap.compress(profile.lossless ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 95, image);
            if (image.size() > 20 * 1024 * 1024) throw new IllegalArgumentException("截图过大，请降低屏幕分辨率后重试");
            String request = VisionProtocol.request(profile.model, language, Base64.encodeToString(image.toByteArray(), Base64.NO_WRAP), profile.lossless ? "image/png" : "image/jpeg");
            encodeMillis = now() - encodingStarted;
            stage = "等待识图翻译"; progress.update(stage + " · " + image.size() / 1024 + " KB");
            List<VisionProtocol.Region> parsed = VisionProtocol.response(post(request, profile));
            List<TextRegion> regions = new ArrayList<>();
            for (VisionProtocol.Region region : parsed) {
                int[] b = region.box;
                Rect rect = new Rect(b[0] * bitmap.getWidth() / 1000, b[1] * bitmap.getHeight() / 1000,
                        b[2] * bitmap.getWidth() / 1000, b[3] * bitmap.getHeight() / 1000);
                if (rect.width() < 2 || rect.height() < 2) continue;
                regions.add(new TextRegion(rect, region.source, region.text));
            }
            check();
            return regions;
        }
        stage = "本机识别"; progress.update(stage);
        long ocrStarted = now();
        OcrSession session = ocr == null ? new OcrSession() : ocr;
        OcrSession.Lease lease = session.acquire(language);
        // OCR owns a private copy so cancellation cannot race the overlay's bitmap lifecycle.
        Bitmap ocrBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        Task<Text> task;
        try { task = lease.client.process(InputImage.fromBitmap(ocrBitmap, 0)); }
        catch (Exception e) { ocrBitmap.recycle(); session.release(lease); if (ocr == null) session.close(); throw e; }
        task.addOnCompleteListener(Runnable::run, ignored -> { session.release(lease); ocrBitmap.recycle(); });
        if (ocr == null) session.close();
        Text result = Tasks.await(task, 40, TimeUnit.SECONDS);
        ocrMillis = now() - ocrStarted;
        check();
        List<TextRegion> regions = new ArrayList<>();
        int chars = 0;
        for (Text.TextBlock block : result.getTextBlocks()) {
            Rect bounds = block.getBoundingBox();
            String content = readingOrder(block, language);
            if (bounds == null || content.isEmpty() || bounds.width() < 3 || bounds.height() < 3) continue;
            Rect clipped = new Rect(bounds);
            if (!clipped.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) continue;
            chars += content.length();
            if (regions.size() >= 120 || chars > 16000) throw new IllegalArgumentException("本页文字太多，请放大漫画后再翻译");
            regions.add(new TextRegion(clipped, content, ""));
        }
        if (regions.isEmpty()) return regions;
        stage = "整理气泡文字"; progress.update(stage);
        regions = OcrParagraphs.merge(regions, bitmap, language);
        ocrMillis = now() - ocrStarted;
        check();
        regions.sort(Comparator.comparingInt((TextRegion r) -> r.bounds.top)
                .thenComparingInt(r -> "ja".equals(language) ? -r.bounds.right : r.bounds.left));
        List<String> texts = new ArrayList<>();
        for (TextRegion region : regions) texts.add(region.source);
        String cacheKey = TranslationCache.key(TranslationProtocol.endpoint(profile.url), profile.model, profile.key, profile.thinking, language, texts);
        List<String> translations = cache == null ? null : cache.get(cacheKey);
        cached = translations != null;
        if (translations == null) {
            stage = "等待文字翻译"; progress.update(stage + " · " + texts.size() + " 处");
            translations = TranslationProtocol.response(post(TranslationProtocol.request(profile.model, language, texts), profile), texts.size());
            check();
            if (cache != null) cache.put(cacheKey, translations);
        }
        check();
        List<TextRegion> output = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) output.add(new TextRegion(regions.get(i).bounds, regions.get(i).source, translations.get(i)));
        return output;
    }
    private static String readingOrder(Text.TextBlock block, String language) {
        if (!"ja".equals(language)) return block.getText().trim();
        List<Text.Line> lines = new ArrayList<>(block.getLines());
        long vertical = lines.stream().filter(l -> l.getBoundingBox() != null && l.getBoundingBox().height() > l.getBoundingBox().width() * 1.3).count();
        if (vertical == 0 || vertical * 2 < lines.size()) return block.getText().trim();
        lines.sort(Comparator.comparingInt((Text.Line l) -> l.getBoundingBox() == null ? 0 : -l.getBoundingBox().right));
        StringBuilder text = new StringBuilder();
        for (Text.Line line : lines) {
            List<Text.Element> elements = new ArrayList<>(line.getElements());
            elements.sort(Comparator.comparingInt(e -> e.getBoundingBox() == null ? 0 : e.getBoundingBox().top));
            if (elements.isEmpty()) text.append(line.getText());
            else for (Text.Element element : elements) text.append(element.getText());
        }
        return text.toString().trim();
    }
    public String post(String requestBody, Settings.Profile profile) throws Exception {
        if (!profile.ready()) throw new IllegalArgumentException("请先配置此模式的 API 密钥与模型");
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
        long began = now();
        try { check(); return transport.exchange(endpoint,requestBody,profile.key,models); }
        finally { apiMillis += now()-began; }
    }
    private void check() throws InterruptedException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
    public static String error(Exception e) {
        if (e instanceof IllegalArgumentException && e.getMessage() != null) return e.getMessage();
        if (e instanceof java.net.SocketTimeoutException || e instanceof java.util.concurrent.TimeoutException) return "处理超时，请检查网络后重试";
        if (e instanceof org.json.JSONException) return "模型返回格式不符合要求，请重试或更换模型";
        if (e instanceof java.io.IOException) return "网络连接失败，请检查网络和 API 地址";
        return "识别或翻译失败，请重试；也可切换识别模式";
    }
}

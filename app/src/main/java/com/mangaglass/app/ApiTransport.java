package com.mangaglass.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Shared connection pool, per-call deadlines, and non-blocking cancellation. No automatic retries. */
final class ApiTransport {
    private static OkHttpClient sharedClient = new OkHttpClient();
    private final OkHttpClient base;
    private volatile Call active;
    private volatile boolean cancelled;
    TokenUsage.Recorder usageRecorder=(day,usage) -> {};
    ApiTransport(OkHttpClient client) { base = client == null ? sharedClient : client; }
    void cancel() { cancelled = true; Call call = active; if (call != null) call.cancel(); }
    String exchange(String endpoint, String body, String key, boolean models) throws Exception {
        check();
        // OkHttp's invalid-header exceptions can include the rejected value: validate without echoing it.
        if (key.isEmpty() || key.chars().anyMatch(c -> c <= 32 || c >= 127)) throw new IllegalArgumentException("API Key 格式无效，请移除空格或换行后重试");
        OkHttpClient client = base.newBuilder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(8,TimeUnit.SECONDS)
                .readTimeout(models ? 10 : 45,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS)
                .callTimeout(models ? 15 : 60,TimeUnit.SECONDS).build();
        Request.Builder request = new Request.Builder().url(endpoint)
                .header("Authorization","Bearer " + key).header("Accept","application/json");
        if (body == null) request.get();
        else request.post(RequestBody.create(body,MediaType.get("application/json; charset=utf-8")));
        Call call = client.newCall(request.build()); active = call;
        String day=java.time.LocalDate.now().toString();
        boolean attempted=false,recorded=false;
        try {
            check();
            attempted=true;
            try (Response response = call.execute()) {
                boolean success = response.isSuccessful();
                int limit = success ? (models ? 8 : 1) * 1024 * 1024 : 8192;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (response.body() == null) throw new IllegalArgumentException("API 返回空响应");
                try (InputStream input = response.body().byteStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer,0,Math.min(buffer.length,limit-output.size()+1))) != -1) {
                        check();
                        if (output.size()+count > limit) {
                            if (success) throw new IllegalArgumentException("API 响应过大");
                            output.write(buffer,0,limit-output.size()); break;
                        }
                        output.write(buffer,0,count);
                    }
                }
                check();
                String text = output.toString(StandardCharsets.UTF_8.name());
                if(!models) { recorded=true; usageRecorder.record(day,TokenUsage.parse(text)); }
                if (!success) throw new IllegalArgumentException(ApiFailure.message(response.code(),text,models));
                return text;
            }
        } catch (java.io.IOException e) {
            check();
            if (e instanceof InterruptedIOException) throw new IllegalArgumentException(models
                    ? "获取模型列表超时，请检查地址和网络；也可手动填写模型"
                    : "模型响应超时，请检查思考设置或更换模型");
            throw e;
        } finally {
            active = null;
            if(!models && attempted && !recorded) usageRecorder.record(day,TokenUsage.unknown());
        }
    }
    private void check() throws InterruptedException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
}

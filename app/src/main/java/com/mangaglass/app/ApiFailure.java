package com.mangaglass.app;

import java.util.Locale;

/** Classifies bounded error bodies without displaying or logging their untrusted content. */
public final class ApiFailure {
    private ApiFailure() {}
    public static String message(int status, String body, boolean models) {
        if (status == 401 || status == 403) return "API 鉴权失败，请检查密钥和访问权限";
        if (status == 402) return "API 余额不足，请检查账户余额";
        if (status == 429) return "API 额度不足或请求过快，请稍后重试";
        if (models && (status == 404 || status == 405 || status == 501)) return "此服务未提供模型列表接口，可手动填写模型后测试翻译；这不代表 Key 无效";
        String lower = body.toLowerCase(Locale.ROOT);
        if (status == 400 || status == 404 || status == 422) {
            if (lower.contains("image") || lower.contains("vision") || lower.contains("multimodal")) return "当前模型或接口不接受图片，请选择支持图片输入的模型";
            if (lower.contains("thinking") || lower.contains("reasoning") || lower.contains("enable_thinking")) return "服务不接受当前思考参数，请在 API 设置中选择对应协议或最低支持强度";
            if (lower.contains("model")) return "模型不存在或当前 Key 无权使用，请重新获取模型列表并选择";
            return "API 拒绝请求，请检查地址、模型和思考设置（HTTP " + status + "）";
        }
        if (status >= 500) return "API 服务暂时不可用（HTTP " + status + "），请稍后重试";
        return "API 请求失败（HTTP " + status + "），请检查服务地址";
    }
}

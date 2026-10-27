package com.mangaglass.app;

/** Official Chat Completions bases; explicit addresses always take precedence. */
final class ApiProvider {
    static final String[] IDS = {"openai", "deepseek", "qwen-cn", "qwen-intl", "qwen-us", "openrouter", "custom"};
    static final String[] LABELS = {"OpenAI", "DeepSeek", "阿里云百炼（中国内地）", "阿里云百炼（国际）", "阿里云百炼（美国）", "OpenRouter", "自定义兼容服务"};
    private static final String[] BASES = {
            "https://api.openai.com/v1", "https://api.deepseek.com/v1",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            "https://dashscope-us.aliyuncs.com/compatible-mode/v1",
            "https://openrouter.ai/api/v1", ""
    };
    private ApiProvider() { }
    static int index(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return IDS.length - 1;
    }
    static String defaultUrl(String id) { return BASES[index(id)]; }
    static String resolve(String id, String address) {
        String url = address.trim();
        if (url.isEmpty()) url = defaultUrl(id);
        if (url.isEmpty()) throw new IllegalArgumentException("请填写自定义服务的 API 基础地址");
        TranslationProtocol.endpoint(url);
        return url;
    }
    static String infer(String address) {
        if (address.trim().isEmpty()) return "openai";
        try {
            String endpoint = TranslationProtocol.endpoint(address);
            for (int i = 0; i < BASES.length - 1; i++) {
                if (TranslationProtocol.endpoint(BASES[i]).equals(endpoint)) return IDS[i];
            }
            if (TranslationProtocol.endpoint("https://api.deepseek.com").equals(endpoint)) return "deepseek";
        } catch (IllegalArgumentException ignored) { }
        return "custom";
    }
}

package com.mangaglass.app;

import java.net.URI;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Provider extensions are deliberately isolated: a compatible endpoint need not accept all of them. */
public final class RequestPolicy {
    public static final String[] VALUES = {"auto", "deepseek", "qwen", "router", "none", "minimal", "low", "default"};
    public static final String[] LABELS = {"自动：关闭思考或最低强度", "关闭思考（DeepSeek 协议）", "关闭思考（Qwen 协议）", "关闭或最低（OpenRouter 协议）", "关闭（reasoning_effort: none）", "最低（reasoning_effort: minimal）", "低（reasoning_effort: low）", "使用服务商默认值"};
    private RequestPolicy() {}
    public static int index(String policy) {
        for (int i = 0; i < VALUES.length; i++) if (VALUES[i].equals(policy)) return i;
        return 0;
    }
    public static String resolve(String url, String model, String preference) {
        if (!"auto".equals(preference)) return VALUES[index(preference)];
        String host = URI.create(TranslationProtocol.endpoint(url)).getHost().toLowerCase(Locale.ROOT);
        String lowerModel = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (host.equals("api.deepseek.com")
                || host.endsWith(".volces.com")
                || host.equals("open.bigmodel.cn")
                || host.equals("api.moonshot.cn")
                || host.equals("api.moonshot.ai")) {
            return "deepseek";
        }
        if (host.equals("dashscope.aliyuncs.com")
                || host.equals("dashscope-intl.aliyuncs.com")
                || host.equals("dashscope-us.aliyuncs.com")
                || host.equals("api.siliconflow.cn")
                || host.equals("api.siliconflow.com")) {
            return "qwen";
        }
        if (host.equals("openrouter.ai")) return "router";
        if (host.equals("api.openai.com")) {
            if (lowerModel.matches("gpt-5(?:-mini|-nano)?(?:-\\d{4}-\\d{2}-\\d{2})?")) return "minimal";
            if (lowerModel.matches("gpt-5\\.[1245](?:-\\d{4}-\\d{2}-\\d{2})?")
                    || lowerModel.matches("gpt-5\\.6-(?:sol|terra|luna)(?:-\\d{4}-\\d{2}-\\d{2})?")) return "none";
            if (lowerModel.matches("(?:o1|o3|o3-mini|o4-mini)(?:-\\d{4}-\\d{2}-\\d{2})?")) return "low";
        }
        if (host.equals("generativelanguage.googleapis.com")) {
            if (!lowerModel.startsWith("gemini-1") && !lowerModel.startsWith("gemini-2.0")
                    && !lowerModel.startsWith("gemini-2.5-pro") && !lowerModel.startsWith("gemini-3")) {
                return "none";
            }
        }
        return "default";
    }
    public static String apply(String body, String url, String model, String preference, ModelCatalog.Model info) throws Exception {
        return apply(body, url, model, preference, info, true);
    }
    static String apply(String body, String url, String model, String preference, ModelCatalog.Model info,
                        boolean allowJsonMode) throws Exception {
        JSONObject request = new JSONObject(body);
        String host = URI.create(TranslationProtocol.endpoint(url)).getHost().toLowerCase(Locale.ROOT);
        if (allowJsonMode && supportsJsonObject(host) && !request.has("response_format")) {
            request.put("response_format", new JSONObject().put("type", "json_object"));
        }
        String policy = resolve(url, model, preference);
        switch (policy) {
            case "deepseek": request.put("thinking", new JSONObject().put("type", "disabled")); break;
            case "qwen":
                if (model.toLowerCase(Locale.ROOT).contains("thinking") || model.toLowerCase(Locale.ROOT).contains("deepseek-r1"))
                    throw new IllegalArgumentException("此模型属于思考专用模型，无法关闭思考，请选择非思考模型或配置服务商支持的强度");
                request.put("enable_thinking", false); break;
            case "router":
                JSONObject reasoning = info == null ? null : info.data.optJSONObject("reasoning");
                if (reasoning != null && reasoning.optBoolean("mandatory")) {
                    JSONArray efforts = reasoning.optJSONArray("supported_efforts");
                    String minimum = null;
                    if (efforts != null) {
                        for (int i = 0; i < efforts.length(); i++) {
                            String effort = efforts.getString(i);
                            if (!"none".equals(effort)) { minimum = effort; break; }
                        }
                    }
                    if (minimum == null) throw new IllegalArgumentException("此模型未提供可用的最低强度，请选择非思考模型或手动配置强度");
                    request.put("reasoning", new JSONObject().put("effort", minimum));
                } else request.put("reasoning", new JSONObject().put("enabled", false));
                break;
            case "none": case "minimal": case "low": request.put("reasoning_effort", policy); break;
            default: break;
        }
        return request.toString();
    }
    private static boolean supportsJsonObject(String host) {
        return host.equals("api.deepseek.com")
                || host.equals("api.openai.com")
                || host.equals("dashscope.aliyuncs.com")
                || host.equals("dashscope-intl.aliyuncs.com")
                || host.equals("dashscope-us.aliyuncs.com")
                || host.equals("openrouter.ai")
                || host.endsWith(".volces.com")
                || host.equals("open.bigmodel.cn")
                || host.equals("api.moonshot.cn")
                || host.equals("api.moonshot.ai")
                || host.equals("api.siliconflow.cn")
                || host.equals("api.siliconflow.com");
    }
    public static String description(String url, String model, String preference) {
        String policy;
        try { policy = resolve(url, model, preference); } catch (Exception e) { return "填写服务地址后可匹配思考设置。"; }
        if ("default".equals(policy)) return "此接口未自动适配思考参数，可在上方选择服务商支持的协议。";
        if ("router".equals(policy)) return "优先关闭思考；模型要求思考时，使用列表声明的最低强度。";
        return "已设置：" + LABELS[index(policy)];
    }
}

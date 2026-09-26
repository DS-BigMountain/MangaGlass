package com.mangaglass.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Only retains model IDs and capability metadata, never provider messages or credentials. */
public final class ModelCatalog {
    private ModelCatalog() {}
    public static String endpoint(String base) {
        String chat = TranslationProtocol.endpoint(base);
        return chat.substring(0, chat.length() - "/chat/completions".length()) + "/models";
    }
    public static List<Model> parse(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray data = root.getJSONArray("data");
        if (data.length() > 10000) throw new IllegalArgumentException("模型列表过大，请手动填写模型名称");
        TreeMap<String, Model> unique = new TreeMap<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null || !(item.opt("id") instanceof String)) continue;
            String id = item.getString("id").trim();
            if (id.isEmpty() || id.length() > 256 || id.chars().anyMatch(Character::isISOControl)) continue;
            JSONObject safe = new JSONObject().put("id", id);
            JSONObject architecture = item.optJSONObject("architecture");
            JSONArray modalities = architecture == null ? item.optJSONArray("input_modalities") : architecture.optJSONArray("input_modalities");
            if (modalities != null) {
                boolean image = false;
                for (int j = 0; j < modalities.length(); j++) image |= "image".equals(modalities.optString(j));
                safe.put("image", image);
            } else if (item.has("image")) safe.put("image", item.getBoolean("image"));
            JSONObject reasoning = item.optJSONObject("reasoning");
            if (reasoning != null) {
                JSONObject r = new JSONObject().put("mandatory", reasoning.optBoolean("mandatory", false));
                JSONArray efforts = reasoning.optJSONArray("supported_efforts");
                if (efforts != null) {
                    JSONArray allowed = new JSONArray();
                    for (String effort : new String[]{"none", "minimal", "low", "medium", "high", "xhigh", "max"}) {
                        for (int j = 0; j < efforts.length(); j++) if (effort.equals(efforts.optString(j))) { allowed.put(effort); break; }
                    }
                    r.put("supported_efforts", allowed);
                }
                safe.put("reasoning", r);
            }
            unique.putIfAbsent(id, new Model(safe));
        }
        return new ArrayList<>(unique.values());
    }
    public static String serialize(List<Model> models) throws Exception {
        JSONArray data = new JSONArray();
        for (Model model : models) data.put(model.data);
        return new JSONObject().put("data", data).toString();
    }
    public static Model find(List<Model> models, String id) {
        for (Model model : models) if (model.id.equals(id.trim())) return model;
        return null;
    }
    public static final class Model {
        public final String id;
        public final JSONObject data;
        Model(JSONObject data) { this.data = data; this.id = data.optString("id"); }
        public boolean knownTextOnly() { return data.has("image") && !data.optBoolean("image"); }
        public String label() { return id + (data.has("image") ? (data.optBoolean("image") ? " · 支持图片" : " · 仅文字输入") : ""); }
    }
}

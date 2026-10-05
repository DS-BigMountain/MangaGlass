package com.mangaglass.app;

import org.json.JSONObject;

/** Only provider-reported counts. Missing fields remain unknown, never estimated. */
public final class TokenUsage {
    public interface Recorder { void record(String day, TokenUsage usage); }
    public final Long input, output, total;
    public TokenUsage(Long input, Long output, Long total) { this.input=input; this.output=output; this.total=total; }
    public static TokenUsage unknown() { return new TokenUsage(null,null,null); }
    public static TokenUsage parse(String response) {
        try {
            JSONObject usage=new JSONObject(response).optJSONObject("usage");
            if(usage==null) return unknown();
            return new TokenUsage(count(usage,"prompt_tokens","input_tokens"),count(usage,"completion_tokens","output_tokens"),count(usage,"total_tokens","total_tokens"));
        } catch(Exception ignored) { return unknown(); }
    }
    private static Long count(JSONObject value,String key,String fallback) {
        Object number=value.opt(value.has(key)?key:fallback);
        if(!(number instanceof Number)) return null;
        try { long result=new java.math.BigDecimal(number.toString()).longValueExact(); return result>=0?result:null; }
        catch(ArithmeticException | NumberFormatException ignored) { return null; }
    }
}

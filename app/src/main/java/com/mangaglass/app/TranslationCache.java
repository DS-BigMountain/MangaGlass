package com.mangaglass.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;

/** At most eight complete text batches, memory only; scoped to one floating-ball service. */
public final class TranslationCache {
    private final LinkedHashMap<String, List<String>> batches = new LinkedHashMap<>(8, .75f, true);
    private boolean closed;
    public static String key(String endpoint, String model, String credential, String policy, String language, List<String> texts) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> parts = new ArrayList<>();
        parts.add(endpoint); parts.add(model); parts.add(credential); parts.add(policy); parts.add(language); parts.addAll(texts);
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
        }
        return Base64.getEncoder().encodeToString(digest.digest());
    }
    public synchronized List<String> get(String key) {
        List<String> value = batches.get(key);
        return value == null ? null : new ArrayList<>(value);
    }
    public synchronized void put(String key, List<String> values) {
        if (closed) return;
        batches.put(key, new ArrayList<>(values));
        while (batches.size() > 8) batches.remove(batches.keySet().iterator().next());
    }
    public synchronized void close() { closed = true; batches.clear(); }
}

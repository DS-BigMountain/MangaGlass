package com.mangaglass.app;

import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.HashMap;
import java.util.Map;

/** Session-scoped recognizers. Native work retains a lease even if its waiting Java job is cancelled. */
public final class OcrSession implements AutoCloseable {
    private final Map<String, Lease> clients = new HashMap<>();
    private boolean closed;
    public synchronized Lease acquire(String language) {
        if (closed) throw new IllegalStateException("OCR session closed");
        Lease lease = clients.get(language);
        if (lease == null) { lease = new Lease(create(language)); clients.put(language, lease); }
        lease.users++;
        return lease;
    }
    public synchronized void release(Lease lease) {
        if (--lease.users == 0 && closed) lease.client.close();
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Lease lease : clients.values()) if (lease.users == 0) lease.client.close();
        clients.clear();
    }
    public static final class Lease {
        public final TextRecognizer client;
        private int users;
        private Lease(TextRecognizer client) { this.client = client; }
    }
    private static TextRecognizer create(String language) {
        switch (language) {
            case "ja": return TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            case "ko": return TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            case "zh": return TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            default: return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
    }
}

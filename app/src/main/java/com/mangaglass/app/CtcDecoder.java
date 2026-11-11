package com.mangaglass.app;

/**
 * CTC greedy decoding for the Paddle recognition graphs. The alphabet starts with the blank
 * entry, keeps one entry per dictionary line and ends with the space entry, which matches every
 * bundled recognizer class count.
 */
final class CtcDecoder {
    static final class Result {
        final String text;
        final float confidence;
        Result(String text, float confidence) { this.text = text; this.confidence = confidence; }
    }

    private CtcDecoder() {}

    /** Build the alphabet from the dictionary text, either the embedded model metadata or the file. */
    static String[] alphabet(String dictionary) {
        String normalized = dictionary == null ? "" : dictionary.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int count = lines.length;
        // A trailing newline closes the last entry without creating an empty character.
        if (count > 0 && lines[count - 1].isEmpty()) count--;
        String[] alphabet = new String[count + 2];
        alphabet[0] = "";
        System.arraycopy(lines, 0, alphabet, 1, count);
        alphabet[alphabet.length - 1] = " ";
        return alphabet;
    }

    static Result decode(float[] logits, int steps, int classes, String[] alphabet) {
        if (steps < 1 || classes != alphabet.length) throw new IllegalArgumentException("识别字典与模型不匹配");
        StringBuilder text = new StringBuilder();
        int previous = -1;
        double confidence = 0;
        int count = 0;
        for (int t = 0; t < steps; t++) {
            int offset = t * classes;
            int best = 0;
            float bestScore = logits[offset];
            for (int c = 1; c < classes; c++) {
                float score = logits[offset + c];
                if (score > bestScore) { bestScore = score; best = c; }
            }
            if (best != 0 && best != previous) {
                text.append(alphabet[best]);
                confidence += bestScore;
                count++;
            }
            previous = best;
        }
        return new Result(text.toString(), count == 0 ? 0f : (float) (confidence / count));
    }
}

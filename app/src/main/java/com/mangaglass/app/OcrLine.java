package com.mangaglass.app;

import java.util.ArrayList;
import java.util.List;

/** One detected and recognized text line in capture pixels. Every field is Java-only so JVM tests cover it. */
final class OcrLine {
    int[] rect;
    String text;
    float confidence;
    OcrLine(int[] rect, String text, float confidence) {
        this.rect = rect == null ? new int[]{0, 0, 0, 0} : rect.clone();
        this.text = text == null ? "" : text;
        this.confidence = confidence;
    }
    int left() { return rect[0]; }
    int top() { return rect[1]; }
    int right() { return rect[2]; }
    int bottom() { return rect[3]; }
    int width() { return rect[2] - rect[0]; }
    int height() { return rect[3] - rect[1]; }
    static List<OcrLine> copy(List<OcrLine> lines) {
        List<OcrLine> out = new ArrayList<>(lines.size());
        for (OcrLine line : lines) out.add(new OcrLine(line.rect, line.text, line.confidence));
        return out;
    }
}

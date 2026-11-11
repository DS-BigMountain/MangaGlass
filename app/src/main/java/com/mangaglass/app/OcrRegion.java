package com.mangaglass.app;

import java.util.ArrayList;
import java.util.List;

/** One translated passage built from locally recognized lines, expressed in capture pixels. */
final class OcrRegion {
    int id;
    int[] rect;
    String text;
    float confidence;
    float lineHeight;
    final List<int[]> lines = new ArrayList<>();
    OcrRegion(int[] rect, String text, int[] firstLine, float lineHeight, float confidence) {
        this.rect = rect.clone();
        this.text = text == null ? "" : text;
        this.lineHeight = lineHeight;
        this.confidence = confidence;
        this.lines.add(firstLine.clone());
    }
    int left() { return rect[0]; }
    int top() { return rect[1]; }
    int right() { return rect[2]; }
    int bottom() { return rect[3]; }
    int width() { return rect[2] - rect[0]; }
    int height() { return rect[3] - rect[1]; }
    void join(OcrRegion other, String separator) {
        text = text + separator + other.text;
        confidence = Math.min(confidence, other.confidence);
        rect = union(rect, other.rect);
        lines.addAll(other.lines);
    }
    static int[] union(int[] a, int[] b) {
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }
    static boolean inside(int[] a, int[] b) {
        return a[0] <= b[0] && a[1] <= b[1] && a[2] >= b[2] && a[3] >= b[3];
    }
    static int[] intersect(int[] a, int[] b) {
        return new int[]{Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.min(a[2], b[2]), Math.min(a[3], b[3])};
    }
    static boolean valid(int[] rect) { return rect[2] > rect[0] && rect[3] > rect[1]; }
}

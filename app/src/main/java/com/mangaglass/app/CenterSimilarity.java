package com.mangaglass.app;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Samples a sparse grid over the centered 80% display region to detect scroll stabilization
 * and determine whether the page content changed after a touch interaction.
 */
final class CenterSimilarity {
    static final int GRID_COLS = 24;
    static final int GRID_ROWS = 24;
    private static final int PIXEL_TOLERANCE = 12;
    private static final double STEADY_CHANGED_RATIO = 0.015;
    private static final double STEADY_MEAN_DIFF = 2.5;
    private static final double SAME_PAGE_CHANGED_RATIO = 0.025;
    private static final double SAME_PAGE_MEAN_DIFF = 3.5;
    private static final double SHOWING_MOVED_CHANGED_RATIO = 0.055;
    private static final double SHOWING_MOVED_MEAN_DIFF = 4.5;

    private CenterSimilarity() {}

    interface PixelLookup {
        int pixelAt(int x, int y);
    }

    static final class Sample {
        final int width;
        final int height;
        final int[] rgb;

        Sample(int width, int height, int[] rgb) {
            this.width = width;
            this.height = height;
            this.rgb = rgb;
        }
    }

    static final class Diff {
        final int comparableCount;
        final int changedCount;
        final double changedRatio;
        final double meanChannelDiff;

        Diff(int comparableCount, int changedCount, double changedRatio, double meanChannelDiff) {
            this.comparableCount = comparableCount;
            this.changedCount = changedCount;
            this.changedRatio = changedRatio;
            this.meanChannelDiff = meanChannelDiff;
        }
    }

    static Sample sampleRgba(ByteBuffer buffer, int width, int height, int rowStride, int pixelStride,
                             int exLeft, int exTop, int exRight, int exBottom) {
        if (buffer == null || width <= 0 || height <= 0 || pixelStride < 4
                || rowStride < (long) width * pixelStride) {
            return null;
        }
        int limit = buffer.limit();
        if ((long) (height - 1) * rowStride + (long) (width - 1) * pixelStride + 3 >= limit) {
            return null;
        }
        int[] rgb = new int[GRID_COLS * GRID_ROWS];
        Arrays.fill(rgb, -1);
        int[] bounds = centerBounds(width, height);
        int left = bounds[0], top = bounds[1], right = bounds[2], bottom = bounds[3];
        int spanX = Math.max(1, right - left - 1);
        int spanY = Math.max(1, bottom - top - 1);
        int index = 0;
        for (int row = 0; row < GRID_ROWS; row++) {
            int y = top + (int) Math.round(row * (double) spanY / Math.max(1, GRID_ROWS - 1));
            int rowBase = y * rowStride;
            for (int col = 0; col < GRID_COLS; col++) {
                int x = left + (int) Math.round(col * (double) spanX / Math.max(1, GRID_COLS - 1));
                if (!excluded(x, y, exLeft, exTop, exRight, exBottom)) {
                    int offset = rowBase + x * pixelStride;
                    int r = buffer.get(offset) & 0xFF;
                    int g = buffer.get(offset + 1) & 0xFF;
                    int b = buffer.get(offset + 2) & 0xFF;
                    rgb[index] = (r << 16) | (g << 8) | b;
                }
                index++;
            }
        }
        return new Sample(width, height, rgb);
    }

    static Sample sampleArgb(int[] pixels, int width, int height,
                             int exLeft, int exTop, int exRight, int exBottom) {
        if (pixels == null || width <= 0 || height <= 0 || pixels.length < width * height) {
            return null;
        }
        return sampleGrid(width, height, exLeft, exTop, exRight, exBottom,
                (x, y) -> pixels[y * width + x]);
    }

    static Sample sampleGrid(int width, int height,
                             int exLeft, int exTop, int exRight, int exBottom,
                             PixelLookup lookup) {
        if (lookup == null || width <= 0 || height <= 0) {
            return null;
        }
        int[] rgb = new int[GRID_COLS * GRID_ROWS];
        Arrays.fill(rgb, -1);
        int[] bounds = centerBounds(width, height);
        int left = bounds[0], top = bounds[1], right = bounds[2], bottom = bounds[3];
        int spanX = Math.max(1, right - left - 1);
        int spanY = Math.max(1, bottom - top - 1);
        int index = 0;
        for (int row = 0; row < GRID_ROWS; row++) {
            int y = top + (int) Math.round(row * (double) spanY / Math.max(1, GRID_ROWS - 1));
            for (int col = 0; col < GRID_COLS; col++) {
                int x = left + (int) Math.round(col * (double) spanX / Math.max(1, GRID_COLS - 1));
                if (!excluded(x, y, exLeft, exTop, exRight, exBottom)) {
                    rgb[index] = lookup.pixelAt(x, y) & 0x00FFFFFF;
                }
                index++;
            }
        }
        return new Sample(width, height, rgb);
    }

    static int[] centerBounds(int width, int height) {
        int marginX = width / 10;
        int marginY = height / 10;
        int left = Math.max(0, Math.min(width - 1, marginX));
        int right = Math.max(left + 1, width - marginX);
        int top = Math.max(0, Math.min(height - 1, marginY));
        int bottom = Math.max(top + 1, height - marginY);
        return new int[]{left, top, right, bottom};
    }

    private static boolean excluded(int x, int y, int exLeft, int exTop, int exRight, int exBottom) {
        return exRight > exLeft && exBottom > exTop
                && x >= exLeft && x < exRight && y >= exTop && y < exBottom;
    }

    static Diff compare(Sample a, Sample b) {
        if (a == null || b == null || a.width != b.width || a.height != b.height
                || a.rgb.length != b.rgb.length) {
            return new Diff(0, 0, 1.0, 255.0);
        }
        int comparable = 0;
        int changed = 0;
        double sumAvgDiff = 0;
        for (int i = 0; i < a.rgb.length; i++) {
            int ca = a.rgb[i];
            int cb = b.rgb[i];
            if (ca < 0 || cb < 0) continue;
            comparable++;
            int dr = Math.abs(((ca >> 16) & 255) - ((cb >> 16) & 255));
            int dg = Math.abs(((ca >> 8) & 255) - ((cb >> 8) & 255));
            int db = Math.abs((ca & 255) - (cb & 255));
            int maxChannel = Math.max(dr, Math.max(dg, db));
            if (maxChannel > PIXEL_TOLERANCE) changed++;
            sumAvgDiff += (dr + dg + db) / 3.0;
        }
        if (comparable == 0) return new Diff(0, 0, 1.0, 255.0);
        return new Diff(comparable, changed, changed / (double) comparable, sumAvgDiff / comparable);
    }

    static boolean isFrameSteady(Sample a, Sample b) {
        Diff diff = compare(a, b);
        return diff.comparableCount > 0
                && diff.changedRatio <= STEADY_CHANGED_RATIO
                && diff.meanChannelDiff <= STEADY_MEAN_DIFF;
    }

    static boolean isSamePage(Sample lastPage, Sample current) {
        Diff diff = compare(lastPage, current);
        return diff.comparableCount > 0
                && diff.changedRatio <= SAME_PAGE_CHANGED_RATIO
                && diff.meanChannelDiff <= SAME_PAGE_MEAN_DIFF;
    }

    static boolean isMovedWhileShowing(Sample baseline, Sample current) {
        Diff diff = compare(baseline, current);
        return diff.comparableCount > 0
                && diff.changedRatio >= SHOWING_MOVED_CHANGED_RATIO
                && diff.meanChannelDiff >= SHOWING_MOVED_MEAN_DIFF;
    }
}

package com.mangaglass.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DB detector post-processing kept free of Android types so JVM tests exercise the whole pass.
 * Threshold pixels are grouped into components, each component is expanded by the unclip ratio,
 * and touching neighbours split their shared margin at the centre midpoint.
 */
final class DbPostProcessor {
    static final class Params {
        final float threshold, minAverage, unclip;
        final int minPixels;
        Params(float threshold, float minAverage, float unclip, int minPixels) {
            this.threshold = threshold; this.minAverage = minAverage; this.unclip = unclip; this.minPixels = minPixels;
        }
        static final Params GENERAL = new Params(.3f, .55f, 1.6f, 6);
        static final Params MANGA = new Params(.15f, .25f, 1.4f, 4);
    }

    private DbPostProcessor() {}

    static List<int[]> boxes(float[] map, int mapWidth, int mapHeight, int imageWidth, int imageHeight, Params params) {
        if (mapWidth <= 0 || mapHeight <= 0 || (long) mapWidth * mapHeight > 4096L * 4096L)
            throw new IllegalArgumentException("检测模型输出无效");
        byte[] seen = new byte[mapWidth * mapHeight];
        int[] queue = new int[mapWidth * mapHeight];
        List<int[]> boxes = new ArrayList<>();
        for (int start = 0; start < mapWidth * mapHeight; start++) {
            if (seen[start] != 0 || map[start] < params.threshold) continue;
            int head = 0, tail = 1;
            queue[0] = start;
            seen[start] = 1;
            int l = start % mapWidth, r = l, t = start / mapWidth, b = t;
            double score = 0;
            while (head < tail) {
                int at = queue[head++], x = at % mapWidth, y = at / mapWidth;
                l = Math.min(l, x); r = Math.max(r, x);
                t = Math.min(t, y); b = Math.max(b, y);
                score += map[at];
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || nx >= mapWidth || ny < 0 || ny >= mapHeight) continue;
                        int next = ny * mapWidth + nx;
                        if (seen[next] == 0 && map[next] >= params.threshold) {
                            seen[next] = 1;
                            queue[tail++] = next;
                        }
                    }
                }
            }
            if (tail < params.minPixels || score / tail < params.minAverage || b - t < 2 || r - l < 2) continue;
            if (boxes.size() >= 512) throw new IllegalArgumentException("画面文字区域过多");
            float pad = tail * params.unclip / (2f * ((r - l + 1) + (b - t + 1)));
            int[] rect = {
                    (int) Math.floor((l - pad) * imageWidth / (float) mapWidth),
                    (int) Math.floor((t - pad) * imageHeight / (float) mapHeight),
                    (int) Math.ceil((r + 1 + pad) * imageWidth / (float) mapWidth),
                    (int) Math.ceil((b + 1 + pad) * imageHeight / (float) mapHeight)};
            rect[0] = Math.max(0, rect[0]); rect[1] = Math.max(0, rect[1]);
            rect[2] = Math.min(imageWidth, rect[2]); rect[3] = Math.min(imageHeight, rect[3]);
            if (rect[2] - rect[0] < 3 || rect[3] - rect[1] < 5) continue;
            boxes.add(rect);
        }
        splitTouchingNeighbours(boxes);
        boxes.sort(Comparator.comparingInt((int[] box) -> box[1]).thenComparingInt(box -> box[0]));
        return boxes;
    }

    /** Recognition expansion can touch adjacent baselines; each label keeps its own ink band. */
    private static void splitTouchingNeighbours(List<int[]> boxes) {
        List<int[]> original = new ArrayList<>(boxes.size());
        for (int[] box : boxes) original.add(box.clone());
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = 0; j < boxes.size(); j++) {
                if (i == j) continue;
                int[] a = original.get(i), b = original.get(j);
                int overlap = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
                int centreA = (a[1] + a[3]) / 2, centreB = (b[1] + b[3]) / 2, delta = centreB - centreA;
                if (overlap < Math.min(a[2] - a[0], b[2] - b[0]) * .5f
                        || Math.abs(delta) < Math.min(a[3] - a[1], b[3] - b[1]) * .5f) continue;
                if (a[3] > b[1] && b[3] > a[1]) {
                    int split = (centreA + centreB) / 2;
                    // A split only applies while it keeps a usable box; a margin wider than the
                    // measured band leaves the detector rectangle untouched.
                    if (delta > 0) {
                        int value = Math.min(boxes.get(i)[3], split);
                        if (value > boxes.get(i)[1]) boxes.get(i)[3] = value;
                    } else {
                        int value = Math.max(boxes.get(i)[1], split);
                        if (value < boxes.get(i)[3]) boxes.get(i)[1] = value;
                    }
                }
            }
        }
    }
}

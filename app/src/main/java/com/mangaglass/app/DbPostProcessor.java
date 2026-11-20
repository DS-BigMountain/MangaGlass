package com.mangaglass.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DB detector post-processing kept free of Android types so JVM tests exercise the whole pass.
 * Threshold pixels are grouped into components, each component is expanded by the unclip ratio,
 * touching neighbours split their shared margin at their midpoint, and wide horizontal boxes that
 * bridge across separate UI buttons or column gaps split along empty vertical ink valleys.
 */
final class DbPostProcessor {
    static final class Params {
        final float threshold, minAverage, unclip;
        final int minPixels;
        Params(float threshold, float minAverage, float unclip, int minPixels) {
            this.threshold = threshold; this.minAverage = minAverage; this.unclip = unclip; this.minPixels = minPixels;
        }
        static final Params GENERAL = new Params(.3f, .48f, 1.6f, 4);
        static final Params MANGA = new Params(.15f, .25f, 1.4f, 4);
    }

    private DbPostProcessor() {}

    static List<int[]> boxes(float[] map, int mapWidth, int mapHeight, int imageWidth, int imageHeight, Params params) {
        return boxes(map, mapWidth, mapHeight, imageWidth, imageHeight, params, null);
    }

    static List<int[]> boxes(float[] map, int mapWidth, int mapHeight, int imageWidth, int imageHeight,
                             Params params, OcrGrouping.Pixels pixels) {
        if (mapWidth <= 0 || mapHeight <= 0 || (long) mapWidth * mapHeight > 4096L * 4096L)
            throw new IllegalArgumentException("检测模型输出无效");
        byte[] seen = new byte[mapWidth * mapHeight];
        int[] queue = new int[mapWidth * mapHeight];
        List<int[]> boxes = new ArrayList<>();
        List<int[]> rawBoxes = new ArrayList<>();
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
            if (tail < params.minPixels || score / tail < params.minAverage || b - t < 1 || r - l < 1) continue;
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
            int[] raw = {
                    clamp((int) Math.floor((double) l * imageWidth / mapWidth), rect[0], rect[2]),
                    clamp((int) Math.floor((double) t * imageHeight / mapHeight), rect[1], rect[3]),
                    clamp((int) Math.ceil((double) (r + 1) * imageWidth / mapWidth), rect[0], rect[2]),
                    clamp((int) Math.ceil((double) (b + 1) * imageHeight / mapHeight), rect[1], rect[3])};
            boxes.add(rect);
            rawBoxes.add(raw);
        }
        splitTouchingNeighbours(boxes, rawBoxes);
        if (pixels != null) {
            boxes = splitWideHorizontalBoxes(boxes, pixels, imageWidth, imageHeight);
        }
        boxes.sort(Comparator.comparingInt((int[] box) -> box[1]).thenComparingInt(box -> box[0]));
        return boxes;
    }

    /**
     * Recognition expansion can touch adjacent baselines or neighbouring UI pills on the same row.
     * Split the shared margin at their midpoint so a crop cannot import strokes from its neighbour.
     */
    private static void splitTouchingNeighbours(List<int[]> boxes, List<int[]> rawBoxes) {
        List<int[]> original = new ArrayList<>(boxes.size());
        for (int[] box : boxes) original.add(box.clone());
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = 0; j < boxes.size(); j++) {
                if (i == j) continue;
                int[] a = original.get(i), b = original.get(j);
                int wA = a[2] - a[0], wB = b[2] - b[0];
                int hA = a[3] - a[1], hB = b[3] - b[1];
                int overlapX = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
                int overlapY = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
                int centreA = (a[1] + a[3]) / 2, centreB = (b[1] + b[3]) / 2, deltaY = centreB - centreA;
                if (overlapX >= Math.min(wA, wB) * .5f
                        && Math.min(hA, hB) >= Math.max(hA, hB) * .45f
                        && Math.abs(deltaY) >= Math.min(hA, hB) * .5f) {
                    if (a[3] > b[1] && b[3] > a[1]) {
                        int split = (centreA + centreB) / 2;
                        if (deltaY > 0) {
                            int value = Math.min(boxes.get(i)[3], split);
                            if (value > boxes.get(i)[1]) boxes.get(i)[3] = value;
                        } else {
                            int value = Math.max(boxes.get(i)[1], split);
                            if (value < boxes.get(i)[3]) boxes.get(i)[1] = value;
                        }
                    }
                }
                // Same-row horizontal neighbours whose unexpanded kernels were disjoint must not cross.
                if (overlapY >= Math.min(hA, hB) * .45f
                        && Math.abs(deltaY) <= Math.min(hA, hB) * .55f) {
                    int[] rawA = rawBoxes.get(i), rawB = rawBoxes.get(j);
                    if (rawA[2] <= rawB[0] && a[2] > b[0] - 1) {
                        int splitX = (rawA[2] + rawB[0]) / 2;
                        if (splitX > boxes.get(i)[0] + 3) boxes.get(i)[2] = Math.min(boxes.get(i)[2], splitX);
                    } else if (rawB[2] <= rawA[0] && b[2] > a[0] - 1) {
                        int splitX = (rawB[2] + rawA[0] + 1) / 2;
                        if (splitX < boxes.get(i)[2] - 3) boxes.get(i)[0] = Math.max(boxes.get(i)[0], splitX);
                    }
                }
            }
        }
    }

    /** Split wide horizontal boxes that bridge across separate UI buttons, pill borders or column gaps. */
    static List<int[]> splitWideHorizontalBoxes(List<int[]> boxes, OcrGrouping.Pixels pixels,
                                                int imageWidth, int imageHeight) {
        if (pixels == null || imageWidth <= 0 || imageHeight <= 0) return boxes;
        List<int[]> splitBoxes = new ArrayList<>(boxes.size());
        int[] hist = new int[4096];
        for (int[] rect : boxes) {
            int bw = rect[2] - rect[0], bh = rect[3] - rect[1];
            if (bh < 10 || bh > 72 || bw <= bh * 2) {
                splitBoxes.add(rect);
                continue;
            }
            java.util.Arrays.fill(hist, 0);
            int totalSampled = 0;
            for (int y = rect[1]; y < rect[3]; y++) {
                for (int x = rect[0]; x < rect[2]; x++) {
                    int c = pixels.at(x, y);
                    int key = (((c >> 20) & 15) << 8) | (((c >> 12) & 15) << 4) | ((c >> 4) & 15);
                    hist[key]++;
                    totalSampled++;
                }
            }
            if (totalSampled == 0) {
                splitBoxes.add(rect);
                continue;
            }
            int mode = 0;
            for (int k = 1; k < 4096; k++) if (hist[k] > hist[mode]) mode = k;
            int br = ((mode >> 8) & 15) * 16 + 8;
            int bg = ((mode >> 4) & 15) * 16 + 8;
            int bb = (mode & 15) * 16 + 8;
            int y0 = Math.min(rect[3] - 1, rect[1] + Math.max(1, bh / 8));
            int y1 = Math.max(y0 + 1, rect[3] - Math.max(1, bh / 8));
            int[] colInk = new int[bw];
            for (int x = 0; x < bw; x++) {
                int sx = rect[0] + x;
                int ink = 0, minDiff = 999, maxDiff = 0;
                for (int sy = y0; sy < y1; sy++) {
                    int c = pixels.at(sx, sy);
                    int d = Math.abs(((c >> 16) & 255) - br)
                            + Math.abs(((c >> 8) & 255) - bg)
                            + Math.abs((c & 255) - bb);
                    if (d >= 56) ink++;
                    if (d < minDiff) minDiff = d;
                    if (d > maxDiff) maxDiff = d;
                }
                boolean uniformVerticalLine = (y1 - y0 >= 8) && (maxDiff - minDiff <= 24) && maxDiff < 160;
                colInk[x] = uniformVerticalLine ? 0 : ink;
            }
            // Suppress isolated 1-2 px vertical border strokes flanked by empty padding on both sides.
            for (int x = 3; x + 4 < bw; x++) {
                if (colInk[x] > 0 && colInk[x - 1] == 0 && colInk[x - 2] == 0 && colInk[x - 3] == 0) {
                    int run = 1;
                    while (x + run < bw && colInk[x + run] > 0 && run <= 3) run++;
                    if (run <= 2 && x + run + 2 < bw && colInk[x + run] == 0
                            && colInk[x + run + 1] == 0 && colInk[x + run + 2] == 0) {
                        for (int k = 0; k < run; k++) colInk[x + k] = 0;
                    }
                }
            }
            int minSplitGap = Math.max(12, Math.round(bh * .62f));
            List<int[]> segments = new ArrayList<>();
            int segStart = -1, gapStart = -1;
            for (int x = 0; x < bw; x++) {
                if (colInk[x] > 0) {
                    if (segStart < 0) {
                        segStart = x;
                    } else if (gapStart >= 0 && x - gapStart >= minSplitGap) {
                        if (gapStart - segStart >= 4 && segments.size() + 1 < 8) {
                            segments.add(new int[]{segStart, gapStart});
                            segStart = x;
                        }
                    }
                    gapStart = -1;
                } else if (segStart >= 0 && gapStart < 0) {
                    gapStart = x;
                }
            }
            if (segStart >= 0) {
                int segEnd = gapStart >= 0 ? gapStart : bw;
                if (segEnd - segStart >= 4) segments.add(new int[]{segStart, segEnd});
            }
            if (segments.size() <= 1) {
                splitBoxes.add(rect);
            } else {
                int padX = Math.max(2, bh / 5);
                for (int s = 0; s < segments.size(); s++) {
                    int leftLimit = s == 0 ? 0 : (segments.get(s - 1)[1] + segments.get(s)[0]) / 2;
                    int rightLimit = s + 1 == segments.size() ? bw : (segments.get(s)[1] + segments.get(s + 1)[0]) / 2;
                    int subLeft = rect[0] + Math.max(leftLimit, segments.get(s)[0] - padX);
                    int subRight = rect[0] + Math.min(rightLimit, segments.get(s)[1] + padX);
                    if (subRight - subLeft >= 4 && splitBoxes.size() < 512)
                        splitBoxes.add(new int[]{subLeft, rect[1], subRight, rect[3]});
                }
            }
        }
        return splitBoxes;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

package com.mangaglass.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 源行距比例估计。沿阅读方向统计墨迹带，用相邻带起点差的中值作为源行距，
 * 再用带高×1.15 换算成与字号的比值，夹在 [1.00, 1.35]；估计失效时回落 1.08。
 * 本类保持零 Android 依赖，可直接在 JVM 单测中驱动。
 */
final class LineSpacing {
    /** 估计失效时的兜底行距比例，保持 V1.2.1 现状。 */
    static final float FALLBACK = 1.08f;
    /** 行距比例下限。 */
    static final float MIN = 1.00f;
    /** 行距比例上限。 */
    static final float MAX = 1.35f;
    /** 墨迹带中位数到字号的换算系数，与字号估计口径一致。 */
    static final float BAND_TO_SIZE = 1.15f;
    private static final int INK_DISTANCE = 65;

    private LineSpacing() {}

    /** 估计源行距比例；vertical 为真时沿列方向测量。 */
    static float ratio(int[] box, BubbleGeometry.Pixels pixels, int top, int bottom,
                       boolean vertical) throws InterruptedException {
        if (box == null || box.length < 4 || pixels == null) return FALLBACK;
        int width = box[2] - box[0], height = box[3] - box[1];
        if (width < 4 || height < 4) return FALLBACK;
        int rows = vertical ? width : height;
        int columns = vertical ? height : width;
        List<int[]> bands = new ArrayList<>();
        int start = -1, last = -1;
        long ink = 0;
        for (int row = 0; row < rows; row++) {
            if ((row & 31) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int count = 0;
            for (int column = 0; column < columns; column++) {
                int x = box[0] + (vertical ? row : column);
                int y = box[1] + (vertical ? column : row);
                float fraction = (y - box[1]) / (float) Math.max(1, height - 1);
                int color = pixels.at(x, y), distance = 0;
                for (int shift = 0; shift <= 16; shift += 8) {
                    int a = (top >>> shift) & 255, b = (bottom >>> shift) & 255;
                    distance += Math.abs(((color >>> shift) & 255) - (a + (b - a) * fraction));
                }
                if (distance >= INK_DISTANCE) count++;
            }
            ink += count;
            // 统计只针对笔画行；宽条与边框在此跳过。
            if (count >= 2 && count < columns * .85f) {
                if (start < 0) start = row;
                last = row;
            } else if (start >= 0 && row - last > 1) {
                if (last - start >= 3) bands.add(new int[]{start, last - start + 1});
                start = -1;
            }
        }
        if (start >= 0 && last - start >= 3) bands.add(new int[]{start, last - start + 1});
        if (bands.size() < 2 || bands.size() > 100) return FALLBACK;
        if (ink < 4 || ink >= (long) rows * columns * .55f) return FALLBACK;
        int[] heights = new int[bands.size()];
        for (int i = 0; i < bands.size(); i++) heights[i] = bands.get(i)[1];
        int medianHeight = median(heights);
        if (medianHeight < 3) return FALLBACK;
        // 带高一致性：混入气泡边框或插图时，带宽方差偏大，此时放弃比例估计。
        int consistent = 0;
        for (int heightValue : heights)
            if (heightValue >= medianHeight * .6f && heightValue <= medianHeight * 1.5f) consistent++;
        if (consistent * 10 < bands.size() * 8) return FALLBACK;
        int[] pitches = new int[bands.size() - 1];
        for (int i = 1; i < bands.size(); i++) pitches[i - 1] = bands.get(i)[0] - bands.get(i - 1)[0];
        int medianPitch = median(pitches);
        if (medianPitch < 3) return FALLBACK;
        float ratio = medianPitch / (medianHeight * BAND_TO_SIZE);
        return Math.max(MIN, Math.min(MAX, ratio));
    }

    private static int median(int[] values) {
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}

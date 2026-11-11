package com.mangaglass.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原文墨迹掩码。逐像素对照 {@link Backdrop} 的本地块色识别笔画，向四周膨胀 ±2px，
 * 再把逐行游程按相同横向区间纵向合并成矩形，供覆盖层用 Path 绘制。
 * 该掩码只覆盖字形笔画及其紧邻抗锯齿边缘，气泡轮廓与画面其余像素保持原样。
 */
final class InkMask {
    /** 膨胀半径（像素）：笔画向四周各扩 2px。 */
    static final int DILATION = 2;
    /** 墨迹判据：与本地背景的通道差之和，沿用既有墨迹量测口径。 */
    static final int INK_DISTANCE = 65;

    private InkMask() {}

    /**
     * 生成墨迹矩形表。检测范围由 top/bottom 与逐行 left/right 给出（right 为开区间），
     * 绘制范围在此基础上向四周各外扩 {@link #DILATION} 像素并裁到页面之内。
     *
     * @return 矩形数组，元素为 {left, top, right, bottom}；笔画识别结果为空时返回空数组。
     */
    static int[][] rects(BubbleGeometry.Pixels pixels, int pageWidth, int pageHeight, Backdrop backdrop,
                         int top, int bottom, int[] left, int[] right) throws InterruptedException {
        if (pixels == null || backdrop == null || pageWidth <= 0 || pageHeight <= 0) return new int[0][];
        int depth = bottom - top;
        if (depth <= 0 || left == null || right == null || left.length < depth || right.length < depth)
            return new int[0][];
        int minLeft = Integer.MAX_VALUE, maxRight = 0;
        for (int row = 0; row < depth; row++) {
            if (right[row] <= left[row]) continue;
            minLeft = Math.min(minLeft, Math.max(0, left[row]));
            maxRight = Math.max(maxRight, Math.min(pageWidth, right[row]));
        }
        if (minLeft == Integer.MAX_VALUE) return new int[0][];
        int x0 = Math.max(0, minLeft - DILATION), x1 = Math.min(pageWidth, maxRight + DILATION);
        int y0 = Math.max(0, top - DILATION), y1 = Math.min(pageHeight, bottom + DILATION);
        if (x1 <= x0 || y1 <= y0) return new int[0][];
        int width = x1 - x0, height = y1 - y0;
        byte[] grid = new byte[width * height];
        int detected = 0;
        int firstRow = Math.max(0, top), lastRow = Math.min(pageHeight, bottom);
        for (int y = firstRow; y < lastRow; y++) {
            if (((y - firstRow) & 31) == 0 && Thread.currentThread().isInterrupted())
                throw new InterruptedException();
            int index = y - top;
            int from = Math.max(0, Math.min(pageWidth, left[index]));
            int to = Math.min(pageWidth, right[index]);
            if (to <= from) continue;
            int base = (y - y0) * width + (from - x0);
            for (int x = from; x < to; x++, base++) {
                if (distance(pixels.at(x, y), backdrop.colorAt(x, y)) >= INK_DISTANCE) {
                    grid[base] = 1;
                    detected++;
                }
            }
        }
        // 笔画识别结果为空时返回空表，调用方沿用整段擦除的既有兜底语义。
        if (detected == 0) return new int[0][];
        dilate(grid, width, height, DILATION);
        return merge(grid, width, height, x0, y0);
    }

    private static int distance(int color, int background) {
        int distance = 0;
        for (int shift = 0; shift <= 16; shift += 8)
            distance += Math.abs(((color >>> shift) & 255) - ((background >>> shift) & 255));
        return distance;
    }

    /** 可分离膨胀：先横向扩 radius，再纵向扩 radius，得到方形邻域。 */
    private static void dilate(byte[] grid, int width, int height, int radius) {
        byte[] temp = new byte[grid.length];
        int[] prefix = new int[Math.max(width, height) + 2];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) prefix[x + 1] = prefix[x] + (grid[row + x] & 1);
            for (int x = 0; x < width; x++) {
                int low = Math.max(0, x - radius), high = Math.min(width - 1, x + radius);
                temp[row + x] = (byte) (prefix[high + 1] - prefix[low] > 0 ? 1 : 0);
            }
        }
        for (int x = 0; x < width; x++) {
            prefix[0] = 0;
            for (int y = 0; y < height; y++) prefix[y + 1] = prefix[y] + (temp[y * width + x] & 1);
            for (int y = 0; y < height; y++) {
                int low = Math.max(0, y - radius), high = Math.min(height - 1, y + radius);
                grid[y * width + x] = (byte) (prefix[high + 1] - prefix[low] > 0 ? 1 : 0);
            }
        }
    }

    /** 逐行游程按相同横向区间纵向合并，减少绘制期的矩形数量。 */
    private static int[][] merge(byte[] grid, int width, int height, int originX, int originY) {
        Map<Long, int[]> running = new HashMap<>();
        List<int[]> rects = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            Map<Long, int[]> next = new HashMap<>();
            int row = y * width;
            int x = 0;
            while (x < width) {
                if ((grid[row + x] & 1) == 0) { x++; continue; }
                int start = x;
                while (x < width && (grid[row + x] & 1) != 0) x++;
                long key = (((long) start) << 32) | (x & 0xffffffffL);
                int[] open = running.get(key);
                if (open != null) {
                    open[3] = originY + y + 1;
                    next.put(key, open);
                } else {
                    int[] created = {originX + start, originY + y, originX + x, originY + y + 1};
                    next.put(key, created);
                }
            }
            for (Map.Entry<Long, int[]> entry : running.entrySet())
                if (!next.containsKey(entry.getKey())) rects.add(entry.getValue());
            running = next;
        }
        rects.addAll(running.values());
        return rects.toArray(new int[0][]);
    }
}

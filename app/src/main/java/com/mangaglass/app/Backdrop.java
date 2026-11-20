package com.mangaglass.app;

import java.util.Arrays;

/**
 * 分块中值背景重建。把源墨迹框向外扩一圈上下文后切成 24×24 像素块，逐块取红绿蓝通道中值，
 * 再用 3×3 块级中值滤波压掉被墨迹占多数的块，得到逐块的颜色表。
 * 该颜色表同时服务两处：覆盖层背景绘制替换单一主色采样；墨迹掩码用本地块色判定笔画。
 * 本类保持零 Android 依赖，可直接在 JVM 单测中驱动。
 */
final class Backdrop {
    /** 分块边长（像素）。 */
    static final int BLOCK = 24;
    /** 源框外扩的上下文宽度：一圈分块。 */
    static final int CONTEXT = BLOCK;

    final int left, top, block, columns, rows;
    /** 行优先块色表，长度 columns×rows。 */
    final int[] colors;

    private Backdrop(int left, int top, int block, int columns, int rows, int[] colors) {
        this.left = left;
        this.top = top;
        this.block = block;
        this.columns = columns;
        this.rows = rows;
        this.colors = colors;
    }

    /** 以源墨迹框为中心重建页面背景；页面之外的上下文按边界裁齐。 */
    static Backdrop of(int[] box, int width, int height, BubbleGeometry.Pixels pixels) throws InterruptedException {
        if (box == null || box.length < 4) throw new IllegalArgumentException("Backdrop needs a source box");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Backdrop needs a page size");
        if (pixels == null) throw new IllegalArgumentException("Backdrop needs a pixel source");
        int left = Math.max(0, Math.min(width - 1, box[0] - CONTEXT));
        int top = Math.max(0, Math.min(height - 1, box[1] - CONTEXT));
        int right = Math.max(left + 1, Math.min(width, box[2] + CONTEXT));
        int bottom = Math.max(top + 1, Math.min(height, box[3] + CONTEXT));
        int columns = (right - left + BLOCK - 1) / BLOCK;
        int rows = (bottom - top + BLOCK - 1) / BLOCK;
        int[] raw = new int[columns * rows];
        int[] reds = new int[BLOCK * BLOCK];
        int[] greens = new int[BLOCK * BLOCK];
        int[] blues = new int[BLOCK * BLOCK];
        for (int row = 0; row < rows; row++) {
            if ((row & 7) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int y0 = top + row * BLOCK, y1 = Math.min(bottom, y0 + BLOCK);
            for (int column = 0; column < columns; column++) {
                int x0 = left + column * BLOCK, x1 = Math.min(right, x0 + BLOCK);
                int count = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int color = pixels.at(x, y);
                        reds[count] = (color >>> 16) & 255;
                        greens[count] = (color >>> 8) & 255;
                        blues[count] = color & 255;
                        count++;
                    }
                }
                int index = row * columns + column;
                raw[index] = count == 0 ? 0 : medianColor(reds, greens, blues, count);
            }
        }
        int[] filtered = new int[raw.length];
        for (int row = 0; row < rows; row++)
            for (int column = 0; column < columns; column++)
                filtered[row * columns + column] = neighborMedian(raw, columns, rows, column, row);
        return new Backdrop(left, top, BLOCK, columns, rows, filtered);
    }

    /** 查询像素所在块的颜色；页面边缘与重建范围之外按最近块回退。 */
    int colorAt(int x, int y) {
        int column = Math.max(0, Math.min(columns - 1, (x - left) / block));
        int row = Math.max(0, Math.min(rows - 1, (y - top) / block));
        return colors[row * columns + column];
    }

    /** 整片重建区域的中值色：代表该段译文的本地背景，用于文字与描边的明暗选择。 */
    int medianColor() {
        int count = colors.length;
        int[] reds = new int[count], greens = new int[count], blues = new int[count];
        for (int i = 0; i < count; i++) {
            reds[i] = (colors[i] >>> 16) & 255;
            greens[i] = (colors[i] >>> 8) & 255;
            blues[i] = colors[i] & 255;
        }
        return medianColor(reds, greens, blues, count);
    }

    /** 单块颜色查询，供单测核对块级中值结果。 */
    int blockColor(int column, int row) {
        return colors[row * columns + column];
    }

    /**
     * 外围边界一致且为非纯白底板（灰网点或黑底）时，把被原文白描边污染的内部块还原为外围底板色。
     * 渐变与复杂插画背景因外围不一致而保持原貌。
     */
    Backdrop cleaned() {
        if (rows < 3 || columns < 3) return this;
        int perimeter = 2 * columns + 2 * (rows - 2);
        int[] reds = new int[perimeter], greens = new int[perimeter], blues = new int[perimeter];
        int[] border = new int[perimeter];
        int count = 0;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (row != 0 && row != rows - 1 && column != 0 && column != columns - 1) continue;
                int c = colors[row * columns + column];
                border[count] = c;
                reds[count] = (c >>> 16) & 255;
                greens[count] = (c >>> 8) & 255;
                blues[count] = c & 255;
                count++;
            }
        }
        int base = consistentBase(border, reds, greens, blues, count, 7, 10);
        if (base == 0) {
            // 矮分镜内上下外扩块可能跨越白沟槽，回退检查左右两侧内行块是否同色。
            int sideCount = 2 * (rows - 2);
            if (sideCount >= 2) {
                int[] sideColors = new int[sideCount];
                int[] sR = new int[sideCount], sG = new int[sideCount], sB = new int[sideCount];
                int sc = 0;
                for (int row = 1; row < rows - 1; row++) {
                    for (int column : new int[]{0, columns - 1}) {
                        int c = colors[row * columns + column];
                        sideColors[sc] = c;
                        sR[sc] = (c >>> 16) & 255;
                        sG[sc] = (c >>> 8) & 255;
                        sB[sc] = c & 255;
                        sc++;
                    }
                }
                base = consistentBase(sideColors, sR, sG, sB, sc, 8, 10);
            }
        }
        if (base == 0) return this;
        int[] updated = colors.clone();
        boolean changed = false;
        for (int row = 1; row < rows - 1; row++) {
            for (int column = 1; column < columns - 1; column++) {
                int idx = row * columns + column;
                if (colorDistance(updated[idx], base) > 48) {
                    updated[idx] = base;
                    changed = true;
                }
            }
        }
        return changed ? new Backdrop(left, top, block, columns, rows, updated) : this;
    }

    /** 识别亮暗混杂的网点或背景干扰块；均一纸色与平缓渐变返回 false。 */
    boolean isMottled() {
        int median = medianColor();
        int total = 0, noisy = 0;
        boolean useInterior = rows >= 3 && columns >= 3;
        int rStart = useInterior ? 1 : 0, rEnd = useInterior ? rows - 1 : rows;
        int cStart = useInterior ? 1 : 0, cEnd = useInterior ? columns - 1 : columns;
        for (int r = rStart; r < rEnd; r++) {
            for (int c = cStart; c < cEnd; c++) {
                int color = colors[r * columns + c];
                total++;
                int lum = (((color >>> 16) & 255) * 3 + ((color >>> 8) & 255) * 6 + (color & 255)) / 10;
                if (lum < 165 || colorDistance(color, median) > 75) noisy++;
            }
        }
        return total > 0 && noisy * 4 >= total;
    }

    private static int consistentBase(int[] samples, int[] reds, int[] greens, int[] blues,
                                      int count, int num, int den) {
        if (count <= 0) return 0;
        int base = medianColor(reds, greens, blues, count);
        int lum = (((base >>> 16) & 255) * 3 + ((base >>> 8) & 255) * 6 + (base & 255)) / 10;
        if (lum >= 200) return 0;
        int matching = 0;
        for (int i = 0; i < count; i++) {
            if (colorDistance(samples[i], base) <= 48) matching++;
        }
        return matching * den >= count * num ? base : 0;
    }

    private static int colorDistance(int a, int b) {
        return Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255))
                + Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255))
                + Math.abs((a & 255) - (b & 255));
    }

    private static int medianColor(int[] reds, int[] greens, int[] blues, int count) {
        Arrays.sort(reds, 0, count);
        Arrays.sort(greens, 0, count);
        Arrays.sort(blues, 0, count);
        int middle = count / 2;
        return 0xff000000 | (reds[middle] << 16) | (greens[middle] << 8) | blues[middle];
    }

    /** 3×3 块级中值滤波：墨迹占多数的孤立块由周围纸色块纠正。 */
    private static int neighborMedian(int[] raw, int columns, int rows, int column, int row) {
        int[] reds = new int[9], greens = new int[9], blues = new int[9];
        int count = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int y = Math.max(0, Math.min(rows - 1, row + dy));
            for (int dx = -1; dx <= 1; dx++) {
                int x = Math.max(0, Math.min(columns - 1, column + dx));
                int color = raw[y * columns + x];
                reds[count] = (color >>> 16) & 255;
                greens[count] = (color >>> 8) & 255;
                blues[count] = color & 255;
                count++;
            }
        }
        Arrays.sort(reds, 0, count);
        Arrays.sort(greens, 0, count);
        Arrays.sort(blues, 0, count);
        int middle = count / 2;
        return 0xff000000 | (reds[middle] << 16) | (greens[middle] << 8) | blues[middle];
    }
}

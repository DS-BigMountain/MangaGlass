package com.mangaglass.app;

/**
 * Bilinear sampling that matches the Paddle OCR preprocessing: detection keeps the Paddle RGB
 * mean and standard deviation, recognition maps to the [-1, 1] range. The crop, content width,
 * quarter turn and column block count follow the runtime geometry of every exported graph.
 */
final class OcrTensor {
    private static final float[] MEAN = {.485f, .456f, .406f};
    private static final float[] STD = {.229f, .224f, .225f};
    private OcrTensor() {}

    /** Sample the full image into a detection tensor of outWidth by outHeight. */
    static float[] detection(int[] argb, int width, int height, int outWidth, int outHeight) {
        return sample(argb, width, height, new int[]{0, 0, width, height}, outWidth, outHeight,
                outWidth, true, false, false, false, 0, false);
    }

    /**
     * Sample a crop into a recognition tensor. rotate180 reads the crop upside down, vertical folds
     * a column of upright characters into one horizontal line, quarter reads a rotated line, and
     * turnLeft applies the counter-clockwise quarter turn the manga recognizer expects.
     */
    static float[] recognition(int[] argb, int width, int height, int[] crop, int outWidth, int outHeight,
                               int contentWidth, boolean rotate180, boolean vertical, boolean quarter,
                               int blocks, boolean turnLeft) {
        return sample(argb, width, height, crop, outWidth, outHeight, contentWidth, false,
                rotate180, vertical, quarter, blocks, turnLeft);
    }

    private static float[] sample(int[] argb, int width, int height, int[] crop, int outWidth, int outHeight,
                                  int contentWidth, boolean detection, boolean rotate180, boolean vertical,
                                  boolean quarter, int blocks, boolean turnLeft) {
        int left = Math.max(0, Math.min(crop[0], width - 1));
        int top = Math.max(0, Math.min(crop[1], height - 1));
        int right = Math.max(left + 1, Math.min(crop[2], width));
        int bottom = Math.max(top + 1, Math.min(crop[3], height));
        int cropWidth = right - left, cropHeight = bottom - top;
        int columns = Math.max(1, Math.min(contentWidth, outWidth));
        int chars = vertical ? Math.max(1, blocks > 0 ? blocks : (int) Math.round((double) cropHeight / cropWidth)) : 1;
        double charSpan = (double) cropHeight / chars;
        double unitSpan = (double) columns / chars;
        float[] out = new float[3 * outWidth * outHeight];
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < columns; x++) {
                float px, py;
                if (turnLeft) {
                    px = right - 1 - (y + .5f) * cropWidth / outHeight;
                    py = top + (x + .5f) * cropHeight / columns - .5f;
                } else if (quarter) {
                    float along = (x + .5f) * cropHeight / columns - .5f;
                    px = left + (y + .5f) * cropWidth / outHeight - .5f;
                    py = (bottom - 1) - along;
                } else if (vertical) {
                    int block = Math.min(chars - 1, (int) (x / unitSpan));
                    double within = (x - block * unitSpan) / unitSpan;
                    px = left + (float) (within * cropWidth) - .5f;
                    py = top + (float) (block * charSpan) + (y + .5f) * (float) charSpan / outHeight - .5f;
                } else {
                    py = top + (y + .5f) * cropHeight / outHeight - .5f;
                    px = left + (x + .5f) * cropWidth / columns - .5f;
                }
                py = Math.max(top, Math.min(py, bottom - 1));
                px = Math.max(left, Math.min(px, right - 1));
                float sampleY = rotate180 ? (bottom - 1) - py : py;
                float sampleX = rotate180 ? (right - 1) - px : px;
                int y0 = (int) sampleY, y1 = Math.min(y0 + 1, bottom - 1);
                float fy = sampleY - y0;
                int x0 = (int) sampleX, x1 = Math.min(x0 + 1, right - 1);
                float fx = sampleX - x0;
                int at00 = argb[y0 * width + x0], at10 = argb[y0 * width + x1];
                int at01 = argb[y1 * width + x0], at11 = argb[y1 * width + x1];
                for (int c = 0; c < 3; c++) {
                    int shift = 16 - c * 8;
                    float p00 = ((at00 >> shift) & 255) / 255f, p10 = ((at10 >> shift) & 255) / 255f;
                    float p01 = ((at01 >> shift) & 255) / 255f, p11 = ((at11 >> shift) & 255) / 255f;
                    float value = ((p00 * (1 - fx) + p10 * fx) * (1 - fy) + (p01 * (1 - fx) + p11 * fx) * fy);
                    out[(c * outHeight + y) * outWidth + x] = detection
                            ? (value - MEAN[c]) / STD[c] : value * 2 - 1;
                }
            }
        }
        return out;
    }
}

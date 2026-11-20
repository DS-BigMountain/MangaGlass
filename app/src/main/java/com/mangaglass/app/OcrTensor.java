package com.mangaglass.app;

/**
 * Fast bilinear sampling matching PaddleOCR preprocessing: detection applies ImageNet RGB
 * mean/std normalization, recognition maps to [-1, 1]. Supports direct-into-buffer batch writing
 * and hoists row/column coordinate math for horizontal crops and full-frame detection.
 */
final class OcrTensor {
    private static final float DET_SCALE_R = 1f / (255f * .229f);
    private static final float DET_SCALE_G = 1f / (255f * .224f);
    private static final float DET_SCALE_B = 1f / (255f * .225f);
    private static final float DET_BIAS_R = -.485f / .229f;
    private static final float DET_BIAS_G = -.456f / .224f;
    private static final float DET_BIAS_B = -.406f / .225f;
    private static final float REC_SCALE = 2f / 255f;
    private static final float REC_BIAS = -1f;

    private OcrTensor() {}

    /** Sample the full image into a detection tensor of outWidth by outHeight. */
    static float[] detection(int[] argb, int width, int height, int outWidth, int outHeight) {
        float[] out = new float[3 * outWidth * outHeight];
        sampleInto(out, 0, argb, width, height, new int[]{0, 0, width, height}, outWidth, outHeight,
                outWidth, true, false, false, false, 0, false);
        return out;
    }

    /** Sample a single crop into a new recognition tensor [1, 3, outHeight, outWidth]. */
    static float[] recognition(int[] argb, int width, int height, int[] crop, int outWidth, int outHeight,
                               int contentWidth, boolean rotate180, boolean vertical, boolean quarter,
                               int blocks, boolean turnLeft) {
        float[] out = new float[3 * outWidth * outHeight];
        sampleInto(out, 0, argb, width, height, crop, outWidth, outHeight, contentWidth, false,
                rotate180, vertical, quarter, blocks, turnLeft);
        return out;
    }

    /** Sample one recognition crop directly into a batch tensor slice at {@code offset}. */
    static void recognitionInto(float[] out, int offset, int[] argb, int width, int height, int[] crop,
                                int outWidth, int outHeight, int contentWidth, boolean rotate180,
                                boolean vertical, boolean quarter, int blocks, boolean turnLeft) {
        sampleInto(out, offset, argb, width, height, crop, outWidth, outHeight, contentWidth, false,
                rotate180, vertical, quarter, blocks, turnLeft);
    }

    private static void sampleInto(float[] out, int offset, int[] argb, int width, int height, int[] crop,
                                   int outWidth, int outHeight, int contentWidth, boolean detection,
                                   boolean rotate180, boolean vertical, boolean quarter, int blocks,
                                   boolean turnLeft) {
        int left = Math.max(0, Math.min(crop[0], width - 1));
        int top = Math.max(0, Math.min(crop[1], height - 1));
        int right = Math.max(left + 1, Math.min(crop[2], width));
        int bottom = Math.max(top + 1, Math.min(crop[3], height));
        int cropWidth = right - left, cropHeight = bottom - top;
        int columns = Math.max(1, Math.min(contentWidth, outWidth));
        int plane = outWidth * outHeight;
        int rBase = offset, gBase = offset + plane, bBase = offset + 2 * plane;

        float sR = detection ? DET_SCALE_R : REC_SCALE;
        float sG = detection ? DET_SCALE_G : REC_SCALE;
        float sB = detection ? DET_SCALE_B : REC_SCALE;
        float bR = detection ? DET_BIAS_R : REC_BIAS;
        float bG = detection ? DET_BIAS_G : REC_BIAS;
        float bB = detection ? DET_BIAS_B : REC_BIAS;

        // Fast path: standard horizontal detection or recognition (separable X/Y coordinate tables).
        if (!turnLeft && !quarter && !vertical) {
            int maxX = right - 1, maxY = bottom - 1;
            int[] x0Arr = new int[columns];
            int[] x1Arr = new int[columns];
            float[] fxArr = new float[columns];
            float scaleX = cropWidth / (float) columns;
            float scaleY = cropHeight / (float) outHeight;
            for (int x = 0; x < columns; x++) {
                float px = topClamp(left + (x + .5f) * scaleX - .5f, left, maxX);
                float sx = rotate180 ? maxX - px : px;
                int x0 = (int) sx;
                x0Arr[x] = x0;
                x1Arr[x] = Math.min(x0 + 1, maxX);
                fxArr[x] = sx - x0;
            }
            for (int y = 0; y < outHeight; y++) {
                float py = topClamp(top + (y + .5f) * scaleY - .5f, top, maxY);
                float sy = rotate180 ? maxY - py : py;
                int y0 = (int) sy;
                int y1 = Math.min(y0 + 1, maxY);
                float fy = sy - y0, invFy = 1f - fy;
                int row0 = y0 * width, row1 = y1 * width;
                int dstRow = y * outWidth;
                for (int x = 0; x < columns; x++) {
                    int x0 = x0Arr[x], x1 = x1Arr[x];
                    float fx = fxArr[x], invFx = 1f - fx;
                    float w00 = invFx * invFy, w10 = fx * invFy, w01 = invFx * fy, w11 = fx * fy;
                    int at00 = argb[row0 + x0], at10 = argb[row0 + x1];
                    int at01 = argb[row1 + x0], at11 = argb[row1 + x1];
                    float r = ((at00 >> 16) & 255) * w00 + ((at10 >> 16) & 255) * w10
                            + ((at01 >> 16) & 255) * w01 + ((at11 >> 16) & 255) * w11;
                    float g = ((at00 >> 8) & 255) * w00 + ((at10 >> 8) & 255) * w10
                            + ((at01 >> 8) & 255) * w01 + ((at11 >> 8) & 255) * w11;
                    float b = (at00 & 255) * w00 + (at10 & 255) * w10
                            + (at01 & 255) * w01 + (at11 & 255) * w11;
                    int idx = dstRow + x;
                    out[rBase + idx] = r * sR + bR;
                    out[gBase + idx] = g * sG + bG;
                    out[bBase + idx] = b * sB + bB;
                }
            }
            return;
        }

        int chars = vertical ? Math.max(1, blocks > 0 ? blocks : (int) Math.round((double) cropHeight / cropWidth)) : 1;
        double charSpan = (double) cropHeight / chars;
        double unitSpan = (double) columns / chars;
        int maxX = right - 1, maxY = bottom - 1;
        for (int y = 0; y < outHeight; y++) {
            int dstRow = y * outWidth;
            for (int x = 0; x < columns; x++) {
                float px, py;
                if (turnLeft) {
                    px = maxX - (y + .5f) * cropWidth / outHeight;
                    py = top + (x + .5f) * cropHeight / columns - .5f;
                } else if (quarter) {
                    float along = (x + .5f) * cropHeight / columns - .5f;
                    px = left + (y + .5f) * cropWidth / outHeight - .5f;
                    py = maxY - along;
                } else {
                    int block = Math.min(chars - 1, (int) (x / unitSpan));
                    double within = (x - block * unitSpan) / unitSpan;
                    px = left + (float) (within * cropWidth) - .5f;
                    py = top + (float) (block * charSpan) + (y + .5f) * (float) charSpan / outHeight - .5f;
                }
                py = topClamp(py, top, maxY);
                px = topClamp(px, left, maxX);
                float sampleY = rotate180 ? maxY - py : py;
                float sampleX = rotate180 ? maxX - px : px;
                int y0 = (int) sampleY, y1 = Math.min(y0 + 1, maxY);
                float fy = sampleY - y0, invFy = 1f - fy;
                int x0 = (int) sampleX, x1 = Math.min(x0 + 1, maxX);
                float fx = sampleX - x0, invFx = 1f - fx;
                float w00 = invFx * invFy, w10 = fx * invFy, w01 = invFx * fy, w11 = fx * fy;
                int row0 = y0 * width, row1 = y1 * width;
                int at00 = argb[row0 + x0], at10 = argb[row0 + x1];
                int at01 = argb[row1 + x0], at11 = argb[row1 + x1];
                float r = ((at00 >> 16) & 255) * w00 + ((at10 >> 16) & 255) * w10
                        + ((at01 >> 16) & 255) * w01 + ((at11 >> 16) & 255) * w11;
                float g = ((at00 >> 8) & 255) * w00 + ((at10 >> 8) & 255) * w10
                        + ((at01 >> 8) & 255) * w01 + ((at11 >> 8) & 255) * w11;
                float b = (at00 & 255) * w00 + (at10 & 255) * w10
                        + (at01 & 255) * w01 + (at11 & 255) * w11;
                int idx = dstRow + x;
                out[rBase + idx] = r * sR + bR;
                out[gBase + idx] = g * sG + bG;
                out[bBase + idx] = b * sB + bB;
            }
        }
    }

    private static float topClamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }
}

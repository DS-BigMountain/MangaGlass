package com.mangaglass.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class OcrTensorTest {
    private static final int W = 8, H = 4;

    @Test public void detectionPlacesRedFirstAndNormalizes() {
        int[] red = new int[W * H];
        java.util.Arrays.fill(red, 0xffff0000);
        float[] out = OcrTensor.detection(red, W, H, 4, 2);
        assertEquals(3 * 4 * 2, out.length);
        assertEquals(2.248f, out[0], .01f);
        assertEquals(-1.804f, out[2 * 4 * 2], .01f);
    }

    @Test public void recognitionMapsWhiteToOneAndBlackToMinusOne() {
        int[] white = new int[W * H];
        java.util.Arrays.fill(white, 0xffffffff);
        float[] out = OcrTensor.recognition(white, W, H, new int[]{0, 0, W, H}, 4, 2, 4, false, false, false, 0, false);
        assertEquals(1f, out[0], .001f);
        int[] black = new int[W * H];
        java.util.Arrays.fill(black, 0xff000000);
        float[] dark = OcrTensor.recognition(black, W, H, new int[]{0, 0, W, H}, 4, 2, 4, false, false, false, 0, false);
        assertEquals(-1f, dark[0], .001f);
    }

    @Test public void rotate180ReadsTheCropUpsideDown() {
        int[] pixels = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) pixels[y * W + x] = y == 0 ? 0xffffffff : 0xff000000;
        float[] upright = OcrTensor.recognition(pixels, W, H, new int[]{0, 0, W, H}, 1, 4, 1, false, false, false, 0, false);
        float[] flipped = OcrTensor.recognition(pixels, W, H, new int[]{0, 0, W, H}, 1, 4, 1, true, false, false, 0, false);
        assertEquals(1f, upright[0], .01f);
        assertEquals(-1f, flipped[0], .01f);
        assertEquals(1f, flipped[3], .01f);
    }

    @Test public void verticalFoldingStaysInsideTheCrop() {
        int[] pixels = new int[W * H];
        java.util.Arrays.fill(pixels, 0xffffffff);
        float[] out = OcrTensor.recognition(pixels, W, H, new int[]{1, 0, 5, H}, 8, 2, 8, false, true, false, 3, false);
        assertEquals(3 * 8 * 2, out.length);
        for (float value : out) assertTrue(value >= -1.001f && value <= 1.001f);
    }

    @Test public void quarterTurnAndTurnLeftKeepTheTensorSize() {
        int[] pixels = new int[W * H];
        java.util.Arrays.fill(pixels, 0xff808080);
        float[] quarter = OcrTensor.recognition(pixels, W, H, new int[]{0, 0, W, H}, 6, 2, 6, false, false, true, 0, false);
        float[] left = OcrTensor.recognition(pixels, W, H, new int[]{0, 0, W, H}, 6, 2, 6, false, false, false, 0, true);
        assertEquals(3 * 6 * 2, quarter.length);
        assertEquals(3 * 6 * 2, left.length);
    }

    @Test public void paddingColumnsStayZeroWhenContentIsNarrower() {
        int[] pixels = new int[W * H];
        java.util.Arrays.fill(pixels, 0xffffffff);
        float[] out = OcrTensor.recognition(pixels, W, H, new int[]{0, 0, W, H}, 8, 2, 2, false, false, false, 0, false);
        for (int c = 0; c < 3; c++) for (int y = 0; y < 2; y++) for (int x = 2; x < 8; x++)
            assertEquals(0f, out[(c * 2 + y) * 8 + x], .0001f);
    }
}

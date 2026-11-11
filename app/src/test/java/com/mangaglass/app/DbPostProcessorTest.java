package com.mangaglass.app;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class DbPostProcessorTest {
    /** A probability map with two filled rectangles at known positions. */
    private static float[] map(int width, int height, int[][] blocks) {
        float[] map = new float[width * height];
        for (int[] block : blocks)
            for (int y = block[1]; y < block[3]; y++)
                for (int x = block[0]; x < block[2]; x++) map[y * width + x] = .9f;
        return map;
    }

    @Test public void oneFilledBlockBecomesOneBox() {
        float[] map = map(64, 64, new int[][]{{10, 12, 40, 30}});
        List<int[]> boxes = DbPostProcessor.boxes(map, 64, 64, 1280, 1280, DbPostProcessor.Params.GENERAL);
        assertEquals(1, boxes.size());
        int[] box = boxes.get(0);
        assertTrue(box[0] <= 200 && box[2] >= 780);
        assertTrue(box[1] <= 240 && box[3] >= 580);
        assertTrue(box[2] > box[0] && box[3] > box[1]);
    }

    @Test public void smallComponentsAreDropped() {
        int[][] blocks = new int[8][];
        for (int i = 0; i < blocks.length; i++) blocks[i] = new int[]{i * 8, 0, i * 8 + 1, 1};
        List<int[]> boxes = DbPostProcessor.boxes(map(64, 16, blocks), 64, 16, 640, 160, DbPostProcessor.Params.GENERAL);
        assertEquals(0, boxes.size());
    }

    @Test public void weakAverageIsDropped() {
        float[] map = new float[64 * 64];
        for (int y = 10; y < 20; y++) for (int x = 10; x < 30; x++) map[y * 64 + x] = .31f;
        assertEquals(0, DbPostProcessor.boxes(map, 64, 64, 640, 640, DbPostProcessor.Params.GENERAL).size());
    }

    @Test public void mangaParamsAcceptFainterRegions() {
        float[] map = new float[64 * 64];
        for (int y = 10; y < 20; y++) for (int x = 10; x < 30; x++) map[y * 64 + x] = .3f;
        assertEquals(1, DbPostProcessor.boxes(map, 64, 64, 640, 640, DbPostProcessor.Params.MANGA).size());
    }

    @Test public void twoDistantBlocksStaySeparate() {
        float[] map = map(128, 64, new int[][]{{4, 4, 20, 20}, {90, 30, 120, 60}});
        List<int[]> boxes = DbPostProcessor.boxes(map, 128, 64, 1280, 640, DbPostProcessor.Params.GENERAL);
        assertEquals(2, boxes.size());
        assertTrue(boxes.get(1)[1] >= boxes.get(0)[1]);
    }

    @Test public void stackedNeighboursSplitTheirSharedMargin() {
        float[] map = map(64, 200, new int[][]{{4, 10, 60, 80}, {4, 86, 60, 170}});
        List<int[]> boxes = DbPostProcessor.boxes(map, 64, 200, 640, 2000, DbPostProcessor.Params.GENERAL);
        assertTrue(boxes.size() >= 2);
        for (int[] box : boxes) {
            assertTrue(box[2] > box[0]);
            assertTrue(box[3] > box[1]);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void oversizedMapIsRejected() {
        DbPostProcessor.boxes(new float[1], 0, 1, 10, 10, DbPostProcessor.Params.GENERAL);
    }
}

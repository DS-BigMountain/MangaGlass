package com.mangaglass.app;

/** Conservative, bounded expansion into near-uniform light pixels; never crosses another OCR box. */
public final class BubbleGeometry {
    public interface Pixels { int at(int x, int y); }
    public static int[] expand(int[] box, int width, int height, Pixels pixels, int[][] others) {
        int[] out = box.clone();
        int margin = Math.min(48, Math.max(4, Math.min(box[2] - box[0], box[3] - box[1]) / 3));
        for (int side = 0; side < 4; side++) {
            for (int step = 0; step < margin; step++) {
                int[] next = out.clone();
                next[side] += side < 2 ? -1 : 1;
                if (next[0] < 1 || next[1] < 1 || next[2] >= width || next[3] >= height) break;
                boolean collision = false;
                for (int[] other : others) {
                    if (other == box) continue;
                    boolean sameRows = box[1] < other[3] && box[3] > other[1];
                    boolean sameColumns = box[0] < other[2] && box[2] > other[0];
                    // Each label owns at most half of a gap, so two growing labels cannot overlap.
                    if (side == 0 && sameRows && other[2] <= box[0] && next[0] < (other[2] + box[0] + 1) / 2) collision = true;
                    if (side == 2 && sameRows && other[0] >= box[2] && next[2] > (other[0] + box[2]) / 2) collision = true;
                    if (side == 1 && sameColumns && other[3] <= box[1] && next[1] < (other[3] + box[1] + 1) / 2) collision = true;
                    if (side == 3 && sameColumns && other[1] >= box[3] && next[3] > (other[1] + box[3]) / 2) collision = true;
                    if (next[0] < other[2] && next[2] > other[0] && next[1] < other[3] && next[3] > other[1]) {
                        // Existing overlapping OCR boxes must not prevent rendering the original bounds.
                        if (!(out[0] < other[2] && out[2] > other[0] && out[1] < other[3] && out[3] > other[1])) collision = true;
                    }
                }
                if (collision || !lightEdge(next, side, pixels)) break;
                out = next;
            }
        }
        return out;
    }
    private static boolean lightEdge(int[] b, int side, Pixels pixels) {
        int length = side % 2 == 0 ? b[3] - b[1] : b[2] - b[0];
        int dark = 0, total = 0;
        for (int i = 0; i < length; i += Math.max(1, length / 100)) {
            int x = side % 2 == 0 ? (side == 0 ? b[0] : b[2] - 1) : b[0] + i;
            int y = side % 2 == 1 ? (side == 1 ? b[1] : b[3] - 1) : b[1] + i;
            int color = pixels.at(x, y);
            int r = (color >> 16) & 255, g = (color >> 8) & 255, blue = color & 255;
            if (Math.min(r, Math.min(g, blue)) < 222) dark++;
            total++;
        }
        return total > 0 && dark <= total / 40;
    }
}

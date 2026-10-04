package com.mangaglass.app;

/** Conservative expansion into blank bubble pixels; never crosses another source or placed box. */
public final class BubbleGeometry {
    public interface Pixels { int at(int x, int y); }
    public static int[] expand(int[] box, int width, int height, Pixels pixels, int[][] others) {
        return expand(box,width,height,pixels,others,1f,0xffffffff,0xffffffff);
    }
    public static int[] expand(int[] box, int width, int height, Pixels pixels, int[][] others,
                               float growth, int topColor, int bottomColor) {
        int[] out = box.clone();
        boolean light=light(topColor) && light(bottomColor);
        for (int side = 0; side < 4; side++) {
            // Vertical source columns need some horizontal room for Chinese prose. Only
            // blank pixels may be claimed; the bubble outline and neighboring regions stop us.
            int margin = side % 2 == 0
                    ? Math.min(64, Math.max(4, Math.max((box[2]-box[0])/3, (box[3]-box[1])/2)))
                    : Math.min(24, Math.max(4, Math.min(box[2]-box[0], box[3]-box[1])/3));
            if(!light) margin=side%2==0 ? Math.min(24,Math.max(2,(box[2]-box[0])/8))
                    : Math.min(16,Math.max(2,(box[3]-box[1])/10));
            margin=Math.round(margin*Math.max(0,Math.min(1,growth)));
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
                        // Existing overlapping model boxes must not prevent rendering the original bounds.
                        if (!(out[0] < other[2] && out[2] > other[0] && out[1] < other[3] && out[3] > other[1])) collision = true;
                    }
                }
                if (collision || !blankEdge(next, side, pixels, box, topColor, bottomColor, light)) break;
                out = next;
            }
        }
        return out;
    }
    private static boolean light(int color) {
        return Math.min((color>>16)&255,Math.min((color>>8)&255,color&255))>=222;
    }
    private static boolean blankEdge(int[] b, int side, Pixels pixels, int[] source, int topColor, int bottomColor, boolean lightBackground) {
        int length = side % 2 == 0 ? b[3] - b[1] : b[2] - b[0];
        int dark = 0, total = 0;
        for (int i = 0; i < length; i += Math.max(1, length / 100)) {
            int x = side % 2 == 0 ? (side == 0 ? b[0] : b[2] - 1) : b[0] + i;
            int y = side % 2 == 1 ? (side == 1 ? b[1] : b[3] - 1) : b[1] + i;
            int color = pixels.at(x, y);
            if(lightBackground) { if(!light(color)) dark++; }
            else {
                float fraction=Math.max(0,Math.min(1,(y-source[1])/(float)Math.max(1,source[3]-source[1]-1)));
                boolean matches=true;
                for(int shift=0;shift<=16;shift+=8) {
                    int a=(topColor>>shift)&255,bottom=(bottomColor>>shift)&255;
                    int expected=Math.round(a+(bottom-a)*fraction);
                    if(Math.abs(((color>>shift)&255)-expected)>18) matches=false;
                }
                if(!matches) dark++;
            }
            total++;
        }
        return total > 0 && dark <= total / 40;
    }
}

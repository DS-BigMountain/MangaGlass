package com.mangaglass.app;

import java.nio.ByteBuffer;

/** Rejects unpainted letterbox bands in a full-display RGBA frame, not black image content. */
final class CapturePixels {
    private CapturePixels() {}
    static boolean fillsEdges(ByteBuffer pixels,int width,int height,int rowStride) {
        if (width<=0 || height<=0 || rowStride<(long)width*4
                || (long)(height-1)*rowStride+(long)width*4>pixels.limit()) return false;
        for (int side=0;side<4;side++) {
            boolean painted=false;
            // Sampling along each side tolerates rounded corners and a camera cutout.
            for (int part=1;part<=8;part++) {
                int x=side<2 ? (side==0 ? 0 : width-1) : (int)((long)(width-1)*part/9);
                int y=side<2 ? (int)((long)(height-1)*part/9) : (side==2 ? 0 : height-1);
                if ((pixels.get(y*rowStride+x*4+3)&255)!=0) { painted=true; break; }
            }
            if (!painted) return false;
        }
        return true;
    }
}

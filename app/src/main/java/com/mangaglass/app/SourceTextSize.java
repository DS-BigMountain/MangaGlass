package com.mangaglass.app;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Estimates each passage's type size from ink bands, with source-text measurement as fallback. */
final class SourceTextSize {
    private SourceTextSize() {}

    static float estimate(TextRegion region, BubbleGeometry.Pixels pixels, int top, int bottom, float fallback) throws InterruptedException {
        Rect box=region.bounds;
        boolean vertical=box.height()>box.width()*1.8f
                && region.source.codePoints().anyMatch(c -> Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN
                    || Character.UnicodeScript.of(c)==Character.UnicodeScript.HIRAGANA
                    || Character.UnicodeScript.of(c)==Character.UnicodeScript.KATAKANA);
        float measured=inkSize(new int[]{box.left,box.top,box.right,box.bottom},pixels,top,bottom,vertical);
        if(measured>0) return Math.max(8,Math.min(fallback*2,measured));
        if(region.source.trim().isEmpty()) return fallback;
        // A tall Japanese column is measured along its reading direction. Its full
        // paragraph height must not be mistaken for the size of a single glyph.
        Rect measure=vertical ? new Rect(0,0,box.height(),box.width()) : box;
        return Math.max(8,ParagraphLayout.fit(region.source,measure,fallback,0xff000000,false).fontSize);
    }

    static float inkSize(int[] box, BubbleGeometry.Pixels pixels, int top, int bottom, boolean vertical) throws InterruptedException {
        int width=box[2]-box[0],height=box[3]-box[1];
        int rows=vertical ? width : height, columns=vertical ? height : width;
        List<Integer> bands=new ArrayList<>();
        int start=-1,last=-1; long ink=0;
        boolean clipped=false;
        for(int row=0;row<rows;row++) {
            if((row&31)==0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int count=0;
            for(int column=0;column<columns;column++) {
                int x=box[0]+(vertical ? row : column), y=box[1]+(vertical ? column : row);
                float fraction=(y-box[1])/(float)Math.max(1,height-1);
                int color=pixels.at(x,y),distance=0;
                for(int shift=0;shift<=16;shift+=8) {
                    int a=(top>>>shift)&255,b=(bottom>>>shift)&255;
                    distance+=Math.abs(((color>>>shift)&255)-(a+(b-a)*fraction));
                }
                if(distance>=65) count++;
            }
            ink+=count;
            // Broad bars and borders do not provide a text baseline.
            if(count>=2 && count<columns*.85f) {
                if(start<0) start=row;
                last=row;
            } else if(start>=0 && row-last>1) {
                if(last-start>=3) { bands.add(last-start+1); clipped|=start==0; }
                start=-1;
            }
        }
        if(start>=0 && last-start>=3) { bands.add(last-start+1); clipped|=start==0 || last==rows-1; }
        if(!clipped && !bands.isEmpty() && bands.size()<=100 && ink>=4 && ink<(long)rows*columns*.55f) {
            Collections.sort(bands);
            int median=bands.get(bands.size()/2),consistent=0;
            for(int band:bands) if(band>=median*.6f && band<=median*1.5f) consistent++;
            if(median>=5 && consistent>=Math.ceil(bands.size()*.8))
                return median*1.15f;
        }
        return 0;
    }
}

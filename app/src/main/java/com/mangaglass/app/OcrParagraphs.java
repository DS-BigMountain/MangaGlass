package com.mangaglass.app;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.util.*;

/** Joins local OCR columns before translation so each bubble keeps its sentence context. */
final class OcrParagraphs {
    private OcrParagraphs() {}
    static List<TextRegion> merge(List<TextRegion> regions,Bitmap bitmap,String language) {
        int width=bitmap.getWidth(), height=bitmap.getHeight();
        int[][] boxes=new int[regions.size()][];
        for(int i=0;i<regions.size();i++) { Rect b=regions.get(i).bounds; boxes[i]=new int[]{b.left,b.top,b.right,b.bottom}; }
        int[] pixels=new int[width*height]; bitmap.getPixels(pixels,0,width,0,0,width,height);
        List<BubbleGroups.Group> groups=BubbleGroups.find(boxes,width,height,(x,y)->pixels[y*width+x],"ja".equals(language));
        List<TextRegion> merged=new ArrayList<>();
        for(BubbleGroups.Group group:groups) {
            StringBuilder source=new StringBuilder(), translated=new StringBuilder();
            for(int id:group.members) {
                if(source.length()>0) source.append('\n'); source.append(regions.get(id).source);
                if(translated.length()>0) translated.append(' '); translated.append(regions.get(id).translated);
            }
            int[] b=group.bounds; merged.add(new TextRegion(new Rect(b[0],b[1],b[2],b[3]),source.toString(),translated.toString().trim()));
        }
        return merged;
    }
}

package com.mangaglass.app;

import android.graphics.Bitmap;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import java.util.ArrayList;
import java.util.List;

/** Prepared on the capture worker; the view only paints the resulting paths and layouts. */
final class OverlayLayout {
    final List<Label> labels=new ArrayList<>();
    final List<TextRegion> unplaced=new ArrayList<>();

    static final class Label {
        final ParagraphLayout.Placement layout;
        final Path mask;
        final Shader background;
        Label(ParagraphLayout.Placement layout,Path mask,Rect source,int top,int bottom) {
            this.layout=layout; this.mask=mask;
            // Keep the gradient anchored to its sampled source, independent of text expansion.
            background=new LinearGradient(0,source.top,0,source.bottom,top,bottom,Shader.TileMode.CLAMP);
        }
    }

    static OverlayLayout prepare(Bitmap bitmap,List<TextRegion> regions,float preferred) throws InterruptedException {
        if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
        OverlayLayout result=new OverlayLayout();
        int width=bitmap.getWidth(),height=bitmap.getHeight();
        int[] data=new int[width*height]; bitmap.getPixels(data,0,width,0,0,width,height);
        BubbleGeometry.Pixels pixels=(x,y) -> data[y*width+x];
        List<TextRegion> valid=new ArrayList<>(); List<int[]> occupied=new ArrayList<>();
        for(TextRegion region:regions) {
            if(region.translated.trim().isEmpty()) continue;
            Rect r=new Rect(region.bounds);
            if(r.isEmpty() || !r.intersect(0,0,width,height)) { result.unplaced.add(region); continue; }
            valid.add(new TextRegion(r,region.source,region.translated));
            occupied.add(new int[]{r.left,r.top,r.right,r.bottom});
        }
        int[][] sources=occupied.toArray(new int[0][]);
        for(int i=0;i<valid.size();i++) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
            TextRegion region=valid.get(i); int[] anchor=sources[i];
            boolean ambiguous=false;
            for(int j=0;j<sources.length;j++) if(i!=j && intersects(anchor,sources[j])) { ambiguous=true; break; }
            if(ambiguous) { result.unplaced.add(region); continue; }
            int top=BubbleInterior.background(anchor,width,height,pixels,false);
            int bottom=BubbleInterior.background(anchor,width,height,pixels,true);
            BubbleInterior interior=similar(top,bottom) ? BubbleInterior.detect(anchor,width,height,pixels,occupied.toArray(new int[0][]),top) : null;
            if(interior!=null) bottom=top;
            int foreground=(luminance(top)+luminance(bottom))/2<145 ? 0xfffafafa : 0xff202124;
            float sourceSize=SourceTextSize.estimate(region,pixels,top,bottom,preferred);
            ParagraphLayout.Placement best=null;
            if(interior!=null) {
                for(int[] area:interior.textAreas()) {
                    if(collides(area,occupied,anchor)) continue;
                    ParagraphLayout.Placement candidate=ParagraphLayout.fit(region.translated,rect(area),sourceSize,foreground,true);
                    if(better(candidate,best)) best=candidate;
                }
            }
            if(best==null) {
                interior=null;
                best=ParagraphLayout.fit(region.translated,region.bounds,sourceSize,foreground);
                // Blank expansion only affects text layout. It never enlarges the source-erasure mask.
                int[] expanded=BubbleGeometry.expand(anchor,width,height,pixels,occupied.toArray(new int[0][]),1f,top,bottom);
                ParagraphLayout.Placement candidate=ParagraphLayout.fit(region.translated,rect(expanded),sourceSize,foreground);
                if(better(candidate,best)) best=candidate;
            }
            Path mask=new Path();
            if(interior!=null) {
                // Erase the complete verified interior, including glyphs just outside the AI text box.
                // The eroded contour preserves the outline instead of painting a rectangular patch.
                for(int y=interior.top;y<interior.bottom;y++) {
                    int row=y-interior.top;
                    if(interior.right[row]>interior.left[row])
                        mask.addRect(interior.left[row],y,interior.right[row],y+1,Path.Direction.CW);
                }
                int l=width,r=0;
                for(int row=0;row<interior.left.length;row++) {
                    l=Math.min(l,interior.left[row]); r=Math.max(r,interior.right[row]);
                }
                occupied.add(new int[]{l,interior.top,r,interior.bottom});
            } else {
                mask.addRect(region.bounds.left,region.bounds.top,region.bounds.right,region.bounds.bottom,Path.Direction.CW);
            }
            Rect b=best.bounds; occupied.add(new int[]{b.left,b.top,b.right,b.bottom});
            result.labels.add(new Label(best,mask,region.bounds,top,bottom));
        }
        return result;
    }

    private static boolean better(ParagraphLayout.Placement candidate,ParagraphLayout.Placement best) {
        if(best==null) return true;
        if(Math.abs(candidate.fontSize-best.fontSize)>.25f) return candidate.fontSize>best.fontSize;
        // At equivalent legibility prefer a compact paragraph, with fewer unnecessary line breaks.
        if(candidate.text.getLineCount()!=best.text.getLineCount()) return candidate.text.getLineCount()<best.text.getLineCount();
        return (long)candidate.bounds.width()*candidate.bounds.height()<(long)best.bounds.width()*best.bounds.height();
    }
    private static boolean collides(int[] area,List<int[]> occupied,int[] anchor) {
        for(int[] other:occupied) if(other!=anchor && intersects(area,other)) return true;
        return false;
    }
    private static boolean intersects(int[] a,int[] b) { return a[0]<b[2] && a[2]>b[0] && a[1]<b[3] && a[3]>b[1]; }
    private static Rect rect(int[] b) { return new Rect(b[0],b[1],b[2],b[3]); }
    private static int luminance(int c) { return (((c>>16)&255)*3+((c>>8)&255)*6+(c&255))/10; }
    private static boolean similar(int a,int b) {
        for(int shift=0;shift<=16;shift+=8) if(Math.abs(((a>>>shift)&255)-((b>>>shift)&255))>16) return false;
        return true;
    }
}

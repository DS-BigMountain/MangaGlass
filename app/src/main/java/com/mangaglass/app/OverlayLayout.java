package com.mangaglass.app;

import android.graphics.Bitmap;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import java.util.ArrayList;
import java.util.List;

/**
 * Prepared on the capture worker; the view only paints the resulting paths and layouts.
 * Every translation starts at its balloon top edge and grows downward without a height limit.
 * Side-by-side translations are pushed apart until they meet the page edge, and the whole
 * page shifts upward when the lowest block crosses the bottom edge.
 */
final class OverlayLayout {
    final List<Label> labels = new ArrayList<>();
    /** Regions with no usable position; the overlay keeps one compact fallback panel for them. */
    final List<TextRegion> unplaced = new ArrayList<>();

    static final class Label {
        final ParagraphLayout.Flow flow;
        final Rect bounds;
        /** Same-colour rectangle locked to the text block; the text always paints inside it. */
        final Rect backdrop;
        final Path mask;
        final Rect source;
        final Shader background;
        Label(ParagraphLayout.Flow flow,Rect bounds,Path mask,Rect source,Shader background) {
            this.flow=flow; this.bounds=new Rect(bounds); this.mask=mask;
            this.source=new Rect(source); this.background=background;
            // Two pixels of slack keep antialiased glyph edges inside their own backdrop.
            this.backdrop=new Rect(bounds.left-2,bounds.top-2,bounds.right+2,bounds.bottom+2);
        }
        String fullText() { return flow.fullText; }
        float fontSize() { return flow.fontSize; }
        int height() { return bounds.height(); }
    }

    /** A placed paragraph with mutable horizontal limits while neighbours are separated. */
    private static final class Block implements PageFlow.Block {
        final TextRegion region;
        final float preferred;
        final int color;
        final boolean centered;
        final int balloonTop, balloonHeight;
        int left, right, top;
        ParagraphLayout.Flow flow;
        Rect bounds;
        Path mask;
        Shader background;
        Block(TextRegion region,int left,int right,int top,int balloonHeight,float preferred,int color,boolean centered) {
            this.region=region; this.left=left; this.right=right; this.top=top;
            this.balloonTop=top; this.balloonHeight=Math.max(2,balloonHeight);
            this.preferred=preferred; this.color=color; this.centered=centered;
        }
        void place() {
            int width=Math.max(2,right-left);
            flow=ParagraphLayout.flow(region.translated,width,preferred,color,centered);
            bounds=new Rect(left,top,right,top+2*flow.padding+flow.height());
        }
        @Override public int left() { return left; }
        @Override public int right() { return right; }
        @Override public int top() { return bounds.top; }
        @Override public int bottom() { return bounds.bottom; }
        @Override public int balloonTop() { return balloonTop; }
        @Override public int balloonHeight() { return balloonHeight; }
        @Override public boolean move(int dx) {
            if(dx==0) return false;
            left+=dx; right+=dx; bounds.offset(dx,0); return true;
        }
        @Override public boolean range(int newLeft,int newRight) {
            if(newLeft==left && newRight==right) return false;
            left=newLeft; right=newRight; place(); return true;
        }
        @Override public boolean lift(int dy) {
            if(dy==0) return false;
            top+=dy; bounds.offset(0,dy); return true;
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
            valid.add(new TextRegion(r,region.bubble,region.source,region.translated));
            occupied.add(new int[]{r.left,r.top,r.right,r.bottom});
        }
        int[][] sources=occupied.toArray(new int[0][]);
        List<Block> blocks=new ArrayList<>();
        for(int i=0;i<valid.size();i++) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
            TextRegion region=valid.get(i); int[] source=sources[i];
            int topColor=BubbleInterior.background(source,width,height,pixels,false);
            int bottomColor=BubbleInterior.background(source,width,height,pixels,true);
            BubbleInterior interior=similar(topColor,bottomColor)
                    ? BubbleInterior.detect(source,width,height,pixels,sources,topColor) : null;
            if(interior!=null) bottomColor=topColor;
            int foreground=(luminance(topColor)+luminance(bottomColor))/2<145 ? 0xfffafafa : 0xff202124;
            float sourceSize=SourceTextSize.estimate(region,pixels,topColor,bottomColor,preferred);
            Rect balloon=balloon(region,interior,source,width,height,pixels,sources,topColor,bottomColor);
            int balloonHeight=Math.max(2,balloon.height());
            boolean centered=balloonHeight>balloon.width()*.85f
                    || region.translated.codePointCount(0,region.translated.length())<24;
            Block block=new Block(region,balloon.left,balloon.right,balloon.top,balloonHeight,sourceSize,foreground,centered);
            block.place();
            block.mask=eraseMask(interior,source);
            block.background=new LinearGradient(0,source[1],0,source[3],topColor,bottomColor,Shader.TileMode.CLAMP);
            blocks.add(block);
        }
        PageFlow.separate(blocks,width);
        PageFlow.shiftUp(blocks,height);
        for(Block block:blocks)
            result.labels.add(new Label(block.flow,block.bounds,block.mask,block.region.bounds,block.background));
        return result;
    }

    /** Container anchor: model balloon first, then the verified bubble interior, then outward blank expansion. */
    private static Rect balloon(TextRegion region,BubbleInterior interior,int[] source,int width,int height,
                                BubbleGeometry.Pixels pixels,int[][] others,int topColor,int bottomColor) {
        int left,right,top,bottom;
        Rect bubble=region.bubble;
        if(container(bubble,source,width,height)) {
            left=Math.max(0,bubble.left); right=Math.min(width,bubble.right);
            top=Math.min(bubble.top,source[1]);
            bottom=Math.max(bubble.bottom,source[3]);
            // The translation needs at least the original ink width to keep the source reading order.
            left=Math.min(left,source[0]); right=Math.max(right,source[2]);
            // A container that hugs the ink leaves no room for a horizontal paraphrase.
            // Claim verified blank neighbours, which the balloon outline keeps inside the balloon.
            if(right-left<(source[2]-source[0])*1.4f) {
                int[] expanded=BubbleGeometry.expand(source,width,height,pixels,others,1f,topColor,bottomColor);
                left=Math.min(left,expanded[0]); right=Math.max(right,expanded[2]);
            }
        } else if(interior!=null) {
            left=width; right=0;
            for(int row=0;row<interior.left.length;row++) {
                left=Math.min(left,interior.left[row]); right=Math.max(right,interior.right[row]);
            }
            top=interior.top; bottom=interior.bottom;
        } else {
            int[] expanded=BubbleGeometry.expand(source,width,height,pixels,others,1f,topColor,bottomColor);
            left=expanded[0]; right=expanded[2]; top=source[1]; bottom=source[3];
        }
        left=Math.max(0,Math.min(left,width-2)); right=Math.min(width,Math.max(left+2,right));
        top=Math.max(0,Math.min(top,height-1));
        bottom=Math.max(top+2,Math.min(height,bottom));
        return new Rect(left,top,right,bottom);
    }

    private static boolean container(Rect bubble,int[] source,int width,int height) {
        if(bubble==null || bubble.isEmpty() || bubble.width()<8 || bubble.height()<8) return false;
        long area=(long)(source[2]-source[0])*(source[3]-source[1]);
        long overlap=overlapArea(bubble,source);
        // The reported container must hold the ink box. A detached or undersized box
        // would start the translation at an unrelated place, so pixels take over.
        return area>0 && overlap*2>=area && bubble.width()>=(source[2]-source[0])*.8f;
    }

    private static long overlapArea(Rect a,int[] b) {
        int l=Math.max(a.left,b[0]),t=Math.max(a.top,b[1]),r=Math.min(a.right,b[2]),bottom=Math.min(a.bottom,b[3]);
        return r>l && bottom>t ? (long)(r-l)*(bottom-t) : 0;
    }

    private static Path eraseMask(BubbleInterior interior,int[] source) {
        Path mask=new Path();
        if(interior!=null) {
            // Erase the complete verified interior, including glyphs just outside the AI text box.
            // The eroded contour preserves the outline instead of painting a rectangular patch.
            for(int y=interior.top;y<interior.bottom;y++) {
                int row=y-interior.top;
                if(interior.right[row]>interior.left[row])
                    mask.addRect(interior.left[row],y,interior.right[row],y+1,Path.Direction.CW);
            }
        } else {
            mask.addRect(source[0],source[1],source[2],source[3],Path.Direction.CW);
        }
        return mask;
    }

    private static int luminance(int c) { return (((c>>16)&255)*3+((c>>8)&255)*6+(c&255))/10; }
    private static boolean similar(int a,int b) {
        for(int shift=0;shift<=16;shift+=8) if(Math.abs(((a>>>shift)&255)-((b>>>shift)&255))>16) return false;
        return true;
    }
}

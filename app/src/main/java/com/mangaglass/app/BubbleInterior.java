package com.mangaglass.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pixel-derived interior of a closed, approximately uniform speech bubble. No Android dependencies. */
final class BubbleInterior {
    final int top, bottom, color;
    final int[] left, right; // Exclusive right edge; inset from the visible outline.

    private BubbleInterior(int top, int bottom, int color, int[] left, int[] right) {
        this.top=top; this.bottom=bottom; this.color=color; this.left=left; this.right=right;
    }

    static int background(int[] box, int width, int height, BubbleGeometry.Pixels pixels, boolean bottom) {
        // Sample inside the text box too: outside-only samples can land on the outline or artwork.
        int[] counts=new int[4096], reds=new int[4096], greens=new int[4096], blues=new int[4096];
        int band=Math.max(2,(box[3]-box[1])/3), winner=0;
        int y0=bottom ? box[3]-band : box[1], y1=bottom ? box[3] : box[1]+band;
        int step=Math.max(1,(box[2]-box[0])/48);
        for(int y=Math.max(0,y0-2);y<Math.min(height,y1+2);y+=Math.max(1,band/12)) {
            for(int x=Math.max(0,box[0]-2);x<Math.min(width,box[2]+2);x+=step) {
                int c=pixels.at(x,y), r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
                int key=(r>>4)*256+(g>>4)*16+(b>>4);
                reds[key]+=r; greens[key]+=g; blues[key]+=b;
                if(++counts[key]>counts[winner]) winner=key;
            }
        }
        int n=Math.max(1,counts[winner]);
        return 0xff000000 | (reds[winner]/n)<<16 | (greens[winner]/n)<<8 | blues[winner]/n;
    }

    static BubbleInterior detect(int[] box,int width,int height,BubbleGeometry.Pixels pixels,
                                 int[][] others,int color) throws InterruptedException {
        int bw=box[2]-box[0],bh=box[3]-box[1];
        int margin=Math.max(16,Math.min(240,Math.max(bw,bh)));
        int x0=Math.max(0,box[0]-margin),y0=Math.max(0,box[1]-margin);
        int x1=Math.min(width,box[2]+margin),y1=Math.min(height,box[3]+margin);
        int w=x1-x0,h=y1-y0;
        // Bound memory and CPU for full-page paragraphs. Those use rectangular paragraph layout.
        if(w<5 || h<5 || (long)w*h>900_000) return null;
        byte[] state=new byte[w*h];
        int[] queue=new int[w*h];
        int seed=-1;
        // Find background near the source perimeter, avoiding the ink at its center.
        for(int y=box[1];y<box[3] && seed<0;y+=Math.max(1,bh/12)) {
            for(int x=box[0];x<box[2];x+=Math.max(1,bw/12)) {
                if(matches(pixels.at(x,y),color)) { seed=(y-y0)*w+x-x0; break; }
            }
        }
        if(seed<0) return null;
        int head=0,tail=1; queue[0]=seed; state[seed]=1;
        int minY=h,maxY=-1,sourceBackground=0;
        int[] left=new int[h],right=new int[h]; Arrays.fill(left,Integer.MAX_VALUE);
        while(head<tail) {
            if((head&4095)==0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int p=queue[head++],x=p%w,y=p/w;
            // A component reaching the search boundary is open or too large to infer safely.
            if(x==0 || y==0 || x==w-1 || y==h-1) return null;
            int sx=x+x0,sy=y+y0;
            if(sx>=box[0] && sx<box[2] && sy>=box[1] && sy<box[3]) sourceBackground++;
            left[y]=Math.min(left[y],sx); right[y]=Math.max(right[y],sx+1);
            minY=Math.min(minY,y); maxY=Math.max(maxY,y);
            for(int direction=0;direction<4;direction++) {
                int next=p+(direction==0 ? -1 : direction==1 ? 1 : direction==2 ? -w : w);
                if(state[next]!=0) continue;
                state[next]=2;
                if(matches(pixels.at(next%w+x0,next/w+y0),color)) {
                    state[next]=1; queue[tail++]=next;
                }
            }
        }
        if(sourceBackground<(long)bw*bh*.48 || maxY-minY<8) return null;
        int envelope=0;
        for(int y=minY;y<=maxY;y++) envelope+=Math.max(0,right[y]-left[y]);
        // Reject textured/art-filled enclosures and very large page/panel components.
        if(tail<envelope*.68 || envelope>(long)width*height*.65 || envelope>(long)bw*bh*18) return null;
        int inkMargin=Math.max(4,Math.min(14,Math.min(bw,bh)/6));
        for(int y=minY;y<=maxY;y++) {
            int unexpected=0;
            for(int x=left[y];x<right[y];x++) {
                boolean nearSource=x>=box[0]-inkMargin && x<box[2]+inkMargin
                        && y+y0>=box[1]-inkMargin && y+y0<box[3]+inkMargin;
                // Row envelopes must not bridge a concave outline or erase distant illustrations.
                if(!nearSource && state[y*w+x-x0]!=1) {
                    if(++unexpected>2) return null;
                } else unexpected=0;
            }
        }
        for(int[] other:others) {
            if(other==box) continue;
            for(int y=Math.max(minY+y0,other[1]);y<Math.min(maxY+y0+1,other[3]);y++) {
                if(left[y-y0]<other[2] && right[y-y0]>other[0]) return null;
            }
        }
        // Erode the silhouette in both axes; corners and one-pixel outlines retain a safety gap.
        int inset=Math.max(2,Math.min(6,Math.min(bw,bh)/16));
        int top=minY+y0+inset,bottom=maxY+y0+1-inset;
        if(bottom<=top) return null;
        int[] safeLeft=new int[bottom-top],safeRight=new int[bottom-top];
        for(int y=top;y<bottom;y++) {
            int l=0,r=width;
            for(int dy=-inset;dy<=inset;dy++) {
                l=Math.max(l,left[y-y0+dy]+inset); r=Math.min(r,right[y-y0+dy]-inset);
            }
            safeLeft[y-top]=l; safeRight[y-top]=Math.max(l,r);
        }
        // If the original text box lies substantially outside, the component is unrelated.
        long covered=0;
        for(int y=Math.max(top,box[1]);y<Math.min(bottom,box[3]);y++)
            covered+=Math.max(0,Math.min(box[2],safeRight[y-top])-Math.max(box[0],safeLeft[y-top]));
        if(covered<(long)bw*bh*.88) return null;
        return new BubbleInterior(top,bottom,color,safeLeft,safeRight);
    }

    List<int[]> textAreas() {
        List<int[]> areas=new ArrayList<>();
        int h=bottom-top; long weight=0,moment=0;
        for(int y=top;y<bottom;y++) {
            int span=right[y-top]-left[y-top]; weight+=span; moment+=(long)span*y;
        }
        int center=weight==0 ? (top+bottom)/2 : (int)(moment/weight);
        // Several heights avoid a tall, narrow rectangle in an oval bubble.
        for(float fraction:new float[]{.40f,.55f,.70f,.85f,1f}) {
            int height=Math.max(1,Math.round(h*fraction));
            int y0=Math.max(top,Math.min(bottom-height,center-height/2)),y1=y0+height;
            int l=0,r=Integer.MAX_VALUE;
            for(int y=y0;y<y1;y++) { l=Math.max(l,left[y-top]); r=Math.min(r,right[y-top]); }
            if(r>l) areas.add(new int[]{l,y0,r,y1});
        }
        return areas;
    }

    private static boolean matches(int a,int b) {
        for(int shift=0;shift<=16;shift+=8) if(Math.abs(((a>>>shift)&255)-((b>>>shift)&255))>22) return false;
        return true;
    }
}

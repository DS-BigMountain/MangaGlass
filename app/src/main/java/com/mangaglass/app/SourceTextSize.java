package com.mangaglass.app;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 字号估计：先从墨迹带取字高中位数，再从框内取密集核心高度，两项按文字脚本加权融合。
 * CJK 文本偏向墨迹带，拉丁文本偏向核心高度；结果始终限制在 [8, fallback×2]。
 * 墨迹带量测返回零时，改用文本排版拟合出的字号作为后备。
 */
final class SourceTextSize {
    /** 墨迹带中位数到字号的换算系数。 */
    static final float BAND_TO_SIZE=1.15f;
    /** CJK 文本的墨迹带权重。 */
    static final float CJK_INK_WEIGHT=.7f;
    /** 拉丁文本的墨迹带权重。 */
    static final float LATIN_INK_WEIGHT=.3f;
    /** 密集核心的行判定阈值：行墨迹达到峰值的这一比例即算作笔画行。 */
    static final float CORE_ROW_SHARE=.5f;

    private SourceTextSize() {}

    static float estimate(TextRegion region, BubbleGeometry.Pixels pixels, int top, int bottom, float fallback) throws InterruptedException {
        Rect box=region.bounds;
        boolean eastAsian=eastAsian(region.source);
        boolean vertical=box.height()>box.width()*1.8f && eastAsian;
        int[] bounds={box.left,box.top,box.right,box.bottom};
        float measured=inkSize(bounds,pixels,top,bottom,vertical);
        if(measured>0) {
            // 墨迹带对断行敏感，密集核心对零星笔画敏感，两项按文字脚本加权后互补。
            float core=coreSize(bounds,pixels,top,bottom,vertical);
            float fused=core>0 ? fuse(measured,core,region.source) : measured;
            return Math.max(8,Math.min(fallback*2,fused));
        }
        if(region.source.trim().isEmpty()) return fallback;
        // 高瘦的日文列沿阅读方向量测，整段高度会偏离单个字高。
        Rect measure=vertical ? new Rect(0,0,box.height(),box.width()) : box;
        return Math.max(8,ParagraphLayout.fit(region.source,measure,fallback,0xff000000,false).fontSize);
    }

    /** 按文字脚本融合两项估计：CJK 偏向墨迹带，拉丁偏向密集核心。 */
    static float fuse(float ink,float core,String source) {
        float weight=eastAsian(source) ? CJK_INK_WEIGHT : LATIN_INK_WEIGHT;
        return ink*weight+core*(1-weight);
    }

    /** 墨迹带字高：沿阅读方向统计墨迹行，取笔画带长度的中位数并换算到字号。 */
    static float inkSize(int[] box, BubbleGeometry.Pixels pixels, int top, int bottom, boolean vertical) throws InterruptedException {
        int width=box[2]-box[0],height=box[3]-box[1];
        int rows=vertical ? width : height,columns=vertical ? height : width;
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
            // 宽条与边框属于非笔画行，统计时跳过。
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
                return median*BAND_TO_SIZE;
        }
        return 0;
    }

    /** 密集核心高度：沿阅读方向逐行统计墨迹，取墨迹总量最大的连续笔画区间，返回该区间的行高。 */
    static float coreSize(int[] box, BubbleGeometry.Pixels pixels, int top, int bottom, boolean vertical) throws InterruptedException {
        int width=box[2]-box[0],height=box[3]-box[1];
        int rows=vertical ? width : height,columns=vertical ? height : width;
        if(rows<4 || columns<4) return 0;
        int[] ink=new int[rows];
        int peak=0;
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
            ink[row]=count;
            peak=Math.max(peak,count);
        }
        if(peak<2) return 0;
        // 宽条与边框的行墨迹远低于峰值，阈值把它们挡在笔画行之外。
        int threshold=Math.max(2,Math.round(peak*CORE_ROW_SHARE));
        int bestStart=-1,bestEnd=-1,bestInk=-1,start=-1;
        for(int row=0;row<=rows;row++) {
            boolean dense=row<rows && ink[row]>=threshold;
            if(dense) {
                if(start<0) start=row;
                continue;
            }
            if(start<0) continue;
            int end=row-1;
            if(end-start>=3) {
                int sum=0;
                for(int r=start;r<=end;r++) sum+=ink[r];
                // 最密集区间取墨迹总量最大的连续笔画段。
                if(sum>bestInk) { bestInk=sum; bestStart=start; bestEnd=end; }
            }
            start=-1;
        }
        if(bestStart<0) return 0;
        // 笔画段贴住量测框边缘说明笔画被裁切，此时退回墨迹带结果。
        if(bestStart==0 || bestEnd==rows-1) return 0;
        return bestEnd-bestStart+1;
    }

    private static boolean eastAsian(String text) {
        return text!=null && text.codePoints().anyMatch(c -> Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN
                || Character.UnicodeScript.of(c)==Character.UnicodeScript.HIRAGANA
                || Character.UnicodeScript.of(c)==Character.UnicodeScript.KATAKANA);
    }
}

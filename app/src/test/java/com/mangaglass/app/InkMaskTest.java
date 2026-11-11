package com.mangaglass.app;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** 墨迹掩码的 JVM 单测：检测、±2px 膨胀、逐行区间收缩与纵向合并。 */
public class InkMaskTest {
    private static final int PAPER=0xffffffff, INK=0xff101010;

    private static int[] constant(int depth,int value) {
        int[] values=new int[depth];
        Arrays.fill(values,value);
        return values;
    }

    private static int[][] rects(BubbleGeometry.Pixels pixels,int top,int bottom,int[] left,int[] right) throws Exception {
        Backdrop backdrop=Backdrop.of(new int[]{60,70,140,130},200,200,pixels);
        return InkMask.rects(pixels,200,200,backdrop,top,bottom,left,right);
    }

    @Test public void glyphStrokeIsDetectedAndDilatedByTwoPixels() throws Exception {
        BubbleGeometry.Pixels pixels=(x,y) -> x>=80 && x<100 && y>=80 && y<120 ? INK : PAPER;
        int[][] rects=rects(pixels,70,130,constant(60,60),constant(60,140));
        assertEquals(1,rects.length);
        assertArrayEquals(new int[]{78,78,102,122},rects[0]);
    }

    @Test public void rowSpansLimitWhatCanBeErased() throws Exception {
        BubbleGeometry.Pixels pixels=(x,y) -> {
            if(x>=80 && x<100 && y>=80 && y<120) return INK;
            if(x>=120 && x<130 && y>=105 && y<115) return INK;
            return PAPER;
        };
        int[] right=new int[60];
        for(int i=0;i<60;i++) right[i]=70+i>=100 ? 100 : 140;
        int[][] rects=rects(pixels,70,130,constant(60,60),right);
        assertTrue(rects.length>0);
        // 右侧区间之外的高对比色块留在掩码之外，掩码右边界由已识别笔画决定。
        for(int[] rect:rects) assertTrue(rect[2]<=102);
    }

    @Test public void blankRegionProducesNoStroke() throws Exception {
        assertEquals(0,rects((x,y)->PAPER,70,130,constant(60,60),constant(60,140)).length);
    }

    @Test public void holesInsideASolidGlyphStayCovered() throws Exception {
        // 膨胀后的掩码覆盖空心笔画内部，原稿字形被完整压掉。
        BubbleGeometry.Pixels pixels=(x,y) -> x>=80 && x<100 && y>=80 && y<120
                && !(x>=88 && x<92 && y>=88 && y<112) ? INK : PAPER;
        int[][] rects=rects(pixels,70,130,constant(60,60),constant(60,140));
        boolean covered=false;
        for(int[] rect:rects)
            covered|=rect[0]<=90 && rect[2]>=90 && rect[1]<=100 && rect[3]>=100;
        assertTrue(covered);
    }

    @Test public void compositeCoversGlyphPixelsAndKeepsTheFrame() throws Exception {
        int size=120;
        BubbleGeometry.Pixels pixels=(x,y) -> {
            if(x==20 || x==99 || y==20 || y==99) return 0xff202020;
            if(x>=45 && x<70 && y>=40 && y<80 && x%8<2) return 0xff101010;
            return 0xfff5eedf;
        };
        Backdrop backdrop=Backdrop.of(new int[]{40,35,75,85},size,size,pixels);
        int[][] rects=InkMask.rects(pixels,size,size,backdrop,30,90,constant(60,40),constant(60,75));
        assertTrue(rects.length>0);
        // 字形笔画位于掩码覆盖范围之内，填充背景色后原字被压掉。
        assertTrue(covered(rects,48,50));
        assertTrue(covered(rects,56,70));
        // 掩码限制在检测区域外扩 2px 的范围内，画面四边的边框保持原样。
        for(int[] rect:rects) {
            assertTrue(rect[0]>=38);
            assertTrue(rect[2]<=77);
            assertTrue(rect[1]>=28);
            assertTrue(rect[3]<=92);
        }
        assertEquals(0xfff5eedf,backdrop.medianColor());
    }

    private static boolean covered(int[][] rects,int x,int y) {
        for(int[] rect:rects)
            if(x>=rect[0] && x<rect[2] && y>=rect[1] && y<rect[3]) return true;
        return false;
    }

    @Test public void cancelledMaskAnalysisStops() {
        Thread.currentThread().interrupt();
        try {
            Backdrop backdrop=Backdrop.of(new int[]{60,70,140,130},200,200,(x,y)->PAPER);
            InkMask.rects((x,y)->PAPER,200,200,backdrop,70,130,constant(60,60),constant(60,140));
            fail("Expected cancellation");
        } catch(InterruptedException expected) { }
        finally { Thread.interrupted(); }
    }
}

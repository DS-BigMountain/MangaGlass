package com.mangaglass.app;

import org.junit.Test;
import static org.junit.Assert.*;

/** 分块中值背景重建的 JVM 单测：块级中值与块级滤波在合成像素上可验证。 */
public class BackdropTest {
    private static final int PAPER=0xffffffff, INK=0xff101010;

    private static BubbleGeometry.Pixels solid(int color) {
        return (x,y) -> color;
    }

    @Test public void chunkMedianKeepsPaperWhenInkCoversLessThanHalf() throws Exception {
        // 每块内 10×24 竖条只占 42%，块中值仍为纸色。
        BubbleGeometry.Pixels pixels=(x,y) -> x>=148 && x<158 ? INK : PAPER;
        Backdrop backdrop=Backdrop.of(new int[]{100,60,200,140},300,200,pixels);
        assertEquals(PAPER,backdrop.blockColor(3,2));
        assertEquals(PAPER,backdrop.medianColor());
    }

    @Test public void neighborMedianFilterRepairsAnInkDominatedBlock() throws Exception {
        // 单个 24×24 块被墨迹填满，3×3 块级中值滤波由周围纸色块纠正。
        int left=Math.max(0,100-Backdrop.CONTEXT), top=Math.max(0,60-Backdrop.CONTEXT);
        int x0=left+3*Backdrop.BLOCK, y0=top+2*Backdrop.BLOCK;
        BubbleGeometry.Pixels pixels=(x,y) -> x>=x0 && x<x0+Backdrop.BLOCK
                && y>=y0 && y<y0+Backdrop.BLOCK ? INK : PAPER;
        Backdrop backdrop=Backdrop.of(new int[]{100,60,200,140},300,200,pixels);
        assertEquals(PAPER,backdrop.blockColor(3,2));
    }

    @Test public void verticalGradientKeepsItsDirectionAfterReconstruction() throws Exception {
        BubbleGeometry.Pixels pixels=(x,y) -> {
            int gray=Math.max(0,Math.min(255,y*255/199));
            return 0xff000000|(gray<<16)|(gray<<8)|gray;
        };
        Backdrop backdrop=Backdrop.of(new int[]{100,60,200,140},300,200,pixels);
        int upper=ParagraphLayout.luminance(backdrop.colorAt(120,40));
        int lower=ParagraphLayout.luminance(backdrop.colorAt(120,160));
        assertTrue(upper<lower);
    }

    @Test public void colorOutsideTheReconstructedRegionClampsToTheNearestBlock() throws Exception {
        BubbleGeometry.Pixels pixels=(x,y) -> x<50 ? 0xff000000 : PAPER;
        Backdrop backdrop=Backdrop.of(new int[]{60,40,100,80},200,200,pixels);
        int corner=backdrop.colorAt(0,0);
        assertEquals(0xff000000,corner);
        assertEquals(PAPER,backdrop.colorAt(199,199));
    }

    @Test public void cancelledReconstructionStops() {
        Thread.currentThread().interrupt();
        try {
            Backdrop.of(new int[]{0,0,10,10},100,100,solid(PAPER));
            fail("Expected cancellation");
        } catch(InterruptedException expected) { }
        finally { Thread.interrupted(); }
    }

    @Test public void emptySourceBoxStillProducesAUsableBlock() throws Exception {
        Backdrop backdrop=Backdrop.of(new int[]{40,40,41,41},80,60,solid(0xff3366cc));
        assertEquals(0xff3366cc,backdrop.medianColor());
        assertTrue(backdrop.columns>=1 && backdrop.rows>=1);
    }
}

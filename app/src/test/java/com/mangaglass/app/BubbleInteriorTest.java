package com.mangaglass.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class BubbleInteriorTest {
    private final int[] source={125,115,175,185};
    private BubbleGeometry.Pixels oval(int background) {
        return (x,y) -> {
            double d=Math.pow((x-150)/95.0,2)+Math.pow((y-150)/105.0,2);
            if(d>=1) return 0xff555555;
            if(d>.94) return 0xff202020;
            if(x>=140 && x<146 && y>=125 && y<177) return 0xff252525;
            return background;
        };
    }
    @Test public void ovalAreasRespectEveryRowOfOutline() throws Exception {
        BubbleInterior interior=BubbleInterior.detect(source,300,300,oval(0xffffffff),new int[][]{source},0xffffffff);
        assertNotNull(interior);
        boolean widened=false;
        for(int[] area:interior.textAreas()) {
            widened|=area[2]-area[0]>source[2]-source[0];
            for(int y=area[1];y<area[3];y++) {
                assertTrue(area[0]>=interior.left[y-interior.top]);
                assertTrue(area[2]<=interior.right[y-interior.top]);
                assertTrue(Math.pow((area[0]-150)/95.0,2)+Math.pow((y-150)/105.0,2)<.94);
                assertTrue(Math.pow((area[2]-1-150)/95.0,2)+Math.pow((y-150)/105.0,2)<.94);
            }
        }
        assertTrue(widened);
    }
    @Test public void openBackgroundDoesNotBecomeBubble() throws Exception {
        assertNull(BubbleInterior.detect(source,300,300,(x,y)->0xffffffff,new int[][]{source},0xffffffff));
    }
    @Test public void sharedBubbleIsNotErasedAcrossAnotherSource() throws Exception {
        int[] neighbor={185,135,200,170};
        assertNull(BubbleInterior.detect(source,300,300,oval(0xffffffff),new int[][]{source,neighbor},0xffffffff));
    }
    @Test public void backgroundKeepsPaperTint() throws Exception {
        int paper=0xfff5eedf;
        assertEquals(paper,BubbleInterior.background(source,300,300,oval(paper),false));
        assertNotNull(BubbleInterior.detect(source,300,300,oval(paper),new int[][]{source},paper));
    }
    @Test public void onePixelObstacleStopsExpansionBetweenOldSamplePoints() {
        int[] tall={80,20,120,320};
        int[] expanded=BubbleGeometry.expand(tall,400,400,(x,y)->x==120 && y==21 ? 0xff000000 : 0xffffffff,new int[][]{tall});
        assertEquals(120,expanded[2]);
    }
    @Test public void adjacentRegionsKeepTheirOwnGap() {
        int[] a={40,60,80,120},b={100,60,140,120};
        int[] ea=BubbleGeometry.expand(a,300,300,(x,y)->0xffffffff,new int[][]{a,b});
        int[] eb=BubbleGeometry.expand(b,300,300,(x,y)->0xffffffff,new int[][]{a,b});
        assertTrue(ea[2]<=eb[0]);
    }
    @Test public void cancelledPixelAnalysisStops() {
        Thread.currentThread().interrupt();
        try {
            BubbleInterior.detect(source,300,300,oval(0xffffffff),new int[][]{source},0xffffffff);
            fail("Expected cancellation");
        } catch(InterruptedException expected) { }
        finally { Thread.interrupted(); }
    }
    @Test public void distantIllustrationIsNotTreatedAsTextToErase() throws Exception {
        BubbleGeometry.Pixels pixels=(x,y) -> x>=200 && x<213 && y>=130 && y<160 ? 0xff202020 : oval(0xffffffff).at(x,y);
        assertNull(BubbleInterior.detect(source,300,300,pixels,new int[][]{source},0xffffffff));
    }
}

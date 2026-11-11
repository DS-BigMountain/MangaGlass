package com.mangaglass.app;

import org.junit.Test;
import static org.junit.Assert.*;

/** 源行距比例估计的 JVM 单测：墨迹带间隙、夹取区间、竖排与兜底。 */
public class LineSpacingTest {
    private static final int WHITE=0xffffffff, BLACK=0xff000000;

    private static BubbleGeometry.Pixels lines(int firstStart,int firstHeight,int secondStart,int secondHeight) {
        return (x,y) -> ((y>=firstStart && y<firstStart+firstHeight)
                || (y>=secondStart && y<secondStart+secondHeight))
                && x>=5 && x<85 && x%8<4 ? BLACK : WHITE;
    }

    private final BubbleGeometry.Pixels normal=lines(5,12,25,12);

    @Test public void wideGapRaisesSpacingUpToTheUpperBound() throws Exception {
        // 带高 12、带距 20：20 / (12×1.15) = 1.449，按上限夹取 1.35。
        assertEquals(1.35f,LineSpacing.ratio(new int[]{0,0,100,45},normal,WHITE,WHITE,false),.001f);
    }

    @Test public void measuredGapConvertsToAFractionalRatio() throws Exception {
        // 带距 18：18 / 13.8 = 1.304。
        assertEquals(1.304f,LineSpacing.ratio(new int[]{0,0,100,45},lines(5,12,23,12),WHITE,WHITE,false),.01f);
    }

    @Test public void singleInkBandFallsBackToTheExistingSpacing() throws Exception {
        BubbleGeometry.Pixels one=(x,y) -> y>=5 && y<17 && x>=5 && x<85 && x%8<4 ? BLACK : WHITE;
        assertEquals(LineSpacing.FALLBACK,LineSpacing.ratio(new int[]{0,0,100,45},one,WHITE,WHITE,false),.001f);
    }

    @Test public void blankRegionAndSolidFillFallBack() throws Exception {
        assertEquals(LineSpacing.FALLBACK,LineSpacing.ratio(new int[]{0,0,100,45},(x,y)->WHITE,WHITE,WHITE,false),.001f);
        assertEquals(LineSpacing.FALLBACK,LineSpacing.ratio(new int[]{0,0,100,45},(x,y)->BLACK,WHITE,WHITE,false),.001f);
    }

    @Test public void verticalColumnsMeasurePitchAlongTheReadingDirection() throws Exception {
        BubbleGeometry.Pixels vertical=(x,y) -> normal.at(y,x);
        assertEquals(1.35f,LineSpacing.ratio(new int[]{0,0,45,100},vertical,WHITE,WHITE,true),.001f);
    }

    @Test public void tightGapClampsToTheLowerBound() throws Exception {
        // 带高 20、带距 22：22 / 23 = 0.956，按下限夹取 1.00。
        assertEquals(1.0f,LineSpacing.ratio(new int[]{0,0,100,60},lines(5,20,27,20),WHITE,WHITE,false),.001f);
    }

    @Test public void cancelledAnalysisStops() {
        Thread.currentThread().interrupt();
        try {
            LineSpacing.ratio(new int[]{0,0,100,45},normal,WHITE,WHITE,false);
            fail("Expected cancellation");
        } catch(InterruptedException expected) { }
        finally { Thread.interrupted(); }
    }
}

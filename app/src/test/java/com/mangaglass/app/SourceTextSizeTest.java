package com.mangaglass.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceTextSizeTest {
    private static final int WHITE=0xffffffff,BLACK=0xff000000;
    private final BubbleGeometry.Pixels lines=(x,y) ->
            ((y>=5 && y<17) || (y>=25 && y<37)) && x>=5 && x<85 && x%8<4 ? BLACK : WHITE;
    private final BubbleGeometry.Pixels twoBands=(x,y) -> {
        boolean wide=y>=5 && y<21 && x>=5 && x<30;
        boolean dense=y>=26 && y<38 && x>=40 && x<80;
        return wide || dense ? BLACK : WHITE;
    };

    @Test public void horizontalParagraphUsesGlyphHeightRatherThanBoxHeight() throws Exception {
        assertEquals(13.8f,SourceTextSize.inkSize(new int[]{0,0,100,45},lines,WHITE,WHITE,false),.01f);
    }
    @Test public void verticalJapaneseUsesColumnWidth() throws Exception {
        assertEquals(13.8f,SourceTextSize.inkSize(new int[]{0,0,45,100},(x,y)->lines.at(y,x),WHITE,WHITE,true),.01f);
    }
    @Test public void invertedTextHasSameSize() throws Exception {
        assertEquals(13.8f,SourceTextSize.inkSize(new int[]{0,0,100,45},
                (x,y)->lines.at(x,y)==WHITE ? BLACK : WHITE,BLACK,BLACK,false),.01f);
    }
    @Test public void blankBackgroundAndSolidPanelDoNotBecomeGlyphs() throws Exception {
        assertEquals(0,SourceTextSize.inkSize(new int[]{0,0,100,45},(x,y)->WHITE,WHITE,WHITE,false),0);
        assertEquals(0,SourceTextSize.inkSize(new int[]{0,0,100,45},(x,y)->BLACK,WHITE,WHITE,false),0);
    }
    @Test public void croppedGlyphRowsUseFallback() throws Exception {
        assertEquals(0,SourceTextSize.inkSize(new int[]{0,5,100,45},lines,WHITE,WHITE,false),0);
    }
    @Test public void cancelledAnalysisStops() {
        Thread.currentThread().interrupt();
        try {
            SourceTextSize.inkSize(new int[]{0,0,100,45},lines,WHITE,WHITE,false);
            fail("Expected cancellation");
        } catch(InterruptedException expected) { }
        finally { Thread.interrupted(); }
    }

    @Test public void denseCoreUsesTheGlyphHeightOfTheInkBand() throws Exception {
        assertEquals(12f,SourceTextSize.coreSize(new int[]{0,0,100,45},lines,WHITE,WHITE,false),.01f);
    }
    @Test public void denseCoreTakesTheMostInkedStrokeInterval() throws Exception {
        assertEquals(12f,SourceTextSize.coreSize(new int[]{0,0,100,45},twoBands,WHITE,WHITE,false),.01f);
    }
    @Test public void verticalDenseCoreUsesTheReadingDirection() throws Exception {
        assertEquals(12f,SourceTextSize.coreSize(new int[]{0,0,45,100},(x,y)->lines.at(y,x),WHITE,WHITE,true),.01f);
    }
    @Test public void croppedCoreRowsUseFallback() throws Exception {
        BubbleGeometry.Pixels topBand=(x,y) -> y<12 && x>=5 && x<45 ? BLACK : WHITE;
        assertEquals(0,SourceTextSize.coreSize(new int[]{0,0,100,45},topBand,WHITE,WHITE,false),0);
    }
    @Test public void blankBackgroundHasNoDenseCore() throws Exception {
        assertEquals(0,SourceTextSize.coreSize(new int[]{0,0,100,45},(x,y)->WHITE,WHITE,WHITE,false),0);
    }
    @Test public void cjkFusionFavorsTheInkBand() {
        assertEquals(13f,SourceTextSize.fuse(10f,20f,"漫画"),.001f);
    }
    @Test public void latinFusionFavorsTheDenseCore() {
        assertEquals(17f,SourceTextSize.fuse(10f,20f,"manga"),.001f);
    }
    @Test public void cancelledCoreAnalysisStops() {
        Thread.currentThread().interrupt();
        try {
            SourceTextSize.coreSize(new int[]{0,0,100,45},lines,WHITE,WHITE,false);
            fail("Expected cancellation");
        } catch(InterruptedException expected) { }
        finally { Thread.interrupted(); }
    }
}

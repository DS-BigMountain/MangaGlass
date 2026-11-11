package com.mangaglass.app;

import org.junit.Test;
import static org.junit.Assert.*;

/** 竖排段落几何的 JVM 单测：列数、行数、字高、行步与选用条件。 */
public class VerticalParagraphLayoutTest {
    @Test public void twoColumnsSplitTwelveGlyphsIntoSixRows() {
        VerticalParagraphLayout.Metrics metrics=VerticalParagraphLayout.metrics(12,100,30);
        assertEquals(7,metrics.padding);
        assertEquals(2,metrics.columns);
        assertEquals(6,metrics.rows);
        assertEquals(2,metrics.usedColumns);
        assertEquals(30f,metrics.fontSize,.01f);
        assertEquals(30*1.12f,metrics.step,.01f);
        assertEquals(metrics.step*.90f,metrics.cellHeight,.001f);
        assertEquals(181,metrics.height());
    }

    @Test public void oneGlyphPerLineAcrossColumns() {
        VerticalParagraphLayout.Metrics metrics=VerticalParagraphLayout.metrics(5,300,20);
        assertEquals(12,metrics.columns);
        assertEquals(1,metrics.rows);
        assertEquals(5,metrics.usedColumns);
        assertEquals(20,metrics.height());
    }

    @Test public void narrowRegionShrinksTheFontUntilTheStepFitsTheWidth() {
        VerticalParagraphLayout.Metrics metrics=VerticalParagraphLayout.metrics(8,20,40);
        assertEquals(1,metrics.columns);
        assertEquals(8,metrics.rows);
        assertTrue(metrics.fontSize<40);
        assertTrue(metrics.step<=metrics.available+.01f);
    }

    @Test public void heightCoversEveryCharacter() {
        for(int count:new int[]{1,2,7,13,40}) {
            VerticalParagraphLayout.Metrics metrics=VerticalParagraphLayout.metrics(count,120,24);
            assertTrue(metrics.rows*metrics.usedColumns>=count);
            assertTrue(metrics.height()>0);
        }
    }

    @Test public void verticalSelectionNeedsATallRegionAndCjkSource() {
        assertTrue(VerticalParagraphLayout.verticalSource(50,100,true));
        assertFalse(VerticalParagraphLayout.verticalSource(50,90,true));
        assertFalse(VerticalParagraphLayout.verticalSource(50,100,false));
        assertTrue(VerticalParagraphLayout.hasCjk("漢字かな"));
        assertFalse(VerticalParagraphLayout.hasCjk("plain latin"));
    }
}

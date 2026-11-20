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

    @Test public void heightAwareMetricsFillsVerticalColumnsInsteadOfStubbyRows() {
        // Wide bubble (220x240) with 10 chars at 24px: uses 2 vertical columns of 5 rows, not 6 stubby columns.
        VerticalParagraphLayout.Metrics metrics = VerticalParagraphLayout.metrics(10, 220, 240, 24);
        assertEquals(2, metrics.usedColumns);
        assertEquals(5, metrics.rows);
    }

    @Test public void verticalColumnLanesDistinguishesVerticalColumnsFromHorizontalRows() {
        // 3 vertical columns separated by blank x-gutters inside a square 90x90 box.
        BubbleGeometry.Pixels verticalCols = (x, y) -> {
            if (y >= 10 && y < 80 && ((x >= 12 && x < 24) || (x >= 38 && x < 50) || (x >= 64 && x < 76))) {
                return 0xff000000;
            }
            return 0xffffffff;
        };
        assertTrue(VerticalParagraphLayout.hasVerticalColumnLanes(0, 0, 90, 90, verticalCols, 0xffffffff, 0xffffffff));

        // 3 horizontal rows separated by blank y-gutters inside the same 90x90 box.
        BubbleGeometry.Pixels horizontalRows = (x, y) -> {
            if (x >= 10 && x < 80 && ((y >= 12 && y < 24) || (y >= 38 && y < 50) || (y >= 64 && y < 76))) {
                return 0xff000000;
            }
            return 0xffffffff;
        };
        assertFalse(VerticalParagraphLayout.hasVerticalColumnLanes(0, 0, 90, 90, horizontalRows, 0xffffffff, 0xffffffff));
    }

    @Test public void horizontalUsedWheneverThreeOrMoreCharsFitAndVerticalOnlyForTwoOrFewer() {
        // 24px font: 2-char wide box (56px, available=48 < 70.8) uses vertical; 3-char wide box (88px, available=76 >= 70.8) uses horizontal.
        assertTrue(VerticalParagraphLayout.preferVertical(56, 140, 24f, true));
        assertTrue(VerticalParagraphLayout.preferVertical(70, 140, 24f, true));
        assertFalse(VerticalParagraphLayout.preferVertical(88, 140, 24f, true));
        assertFalse(VerticalParagraphLayout.preferVertical(160, 200, 24f, true));
        // Wide short box or non-CJK never switches to vertical.
        assertFalse(VerticalParagraphLayout.preferVertical(56, 40, 24f, true));
        assertFalse(VerticalParagraphLayout.preferVertical(56, 140, 24f, false));
    }
}

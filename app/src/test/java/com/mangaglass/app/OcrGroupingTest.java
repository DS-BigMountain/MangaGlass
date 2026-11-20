package com.mangaglass.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class OcrGroupingTest {
    private static final int WHITE = 0xffffffff, BLACK = 0xff000000;

    private static OcrGrouping.Pixels lines(int[][] bands, int width) {
        return (x, y) -> {
            for (int[] band : bands) if (y >= band[0] && y < band[1]) return BLACK;
            return WHITE;
        };
    }

    @Test public void tighteningRecoversTheInkBandOfAPaddedBox() {
        int[] rect = {0, 0, 100, 100};
        int[] ink = OcrGrouping.tighten(rect, lines(new int[][]{{5, 17}, {25, 37}}, 100), 100, 100);
        assertTrue(ink[1] >= 4 && ink[1] <= 6);
        assertTrue(ink[3] >= 36 && ink[3] <= 38);
        assertEquals(0, ink[0]);
        assertEquals(100, ink[2]);
    }

    @Test public void tighteningKeepsASolidBoxIntact() {
        int[] rect = {0, 0, 40, 20};
        int[] ink = OcrGrouping.tighten(rect, (x, y) -> BLACK, 100, 100);
        assertArrayEquals(rect, ink);
    }

    @Test public void horizontalPageOrdersRowsTopThenLeft() {
        List<OcrLine> lines = new ArrayList<>(Arrays.asList(
                new OcrLine(new int[]{50, 40, 90, 60}, "b", .9f),
                new OcrLine(new int[]{10, 40, 40, 60}, "a", .9f),
                new OcrLine(new int[]{10, 5, 90, 25}, "c", .9f)));
        assertFalse(OcrGrouping.isColumnPage(lines));
        lines.sort(OcrGrouping.ROW_ORDER);
        assertEquals("c", lines.get(0).text);
        assertEquals("a", lines.get(1).text);
    }

    @Test public void verticalPageReadsColumnsRightToLeft() {
        List<OcrLine> lines = new ArrayList<>(Arrays.asList(
                new OcrLine(new int[]{10, 10, 30, 110}, "left", .9f),
                new OcrLine(new int[]{80, 10, 100, 110}, "right", .9f),
                new OcrLine(new int[]{140, 10, 160, 110}, "far", .9f)));
        assertTrue(OcrGrouping.isColumnPage(lines));
        lines.sort(OcrGrouping.COLUMN_ORDER);
        assertEquals("far", lines.get(0).text);
        assertEquals("right", lines.get(1).text);
        assertEquals("left", lines.get(2).text);
    }

    @Test public void nearbyRowsOfOneColumnFormOnePassage() {
        List<OcrLine> lines = Arrays.asList(
                new OcrLine(new int[]{10, 10, 30, 40}, "one", .9f),
                new OcrLine(new int[]{10, 44, 30, 74}, "two", .9f));
        List<OcrRegion> regions = OcrGrouping.groupParagraphs(new ArrayList<>(lines),
                (x, y) -> WHITE, 200, 200);
        assertEquals(1, regions.size());
        assertEquals("one\ntwo", regions.get(0).text);
        assertEquals(2, regions.get(0).lines.size());
    }

    @Test public void distantRowsStaySeparate() {
        List<OcrLine> lines = Arrays.asList(
                new OcrLine(new int[]{10, 10, 90, 34}, "first", .9f),
                new OcrLine(new int[]{10, 80, 90, 104}, "second", .9f));
        List<OcrRegion> regions = OcrGrouping.groupParagraphs(new ArrayList<>(lines),
                (x, y) -> WHITE, 200, 200);
        assertEquals(2, regions.size());
    }

    @Test public void redundantWeakBoxInsideReliableBoxIsDropped() {
        List<OcrLine> lines = Arrays.asList(
                new OcrLine(new int[]{10, 10, 120, 60}, "reliable", .9f),
                new OcrLine(new int[]{20, 20, 60, 50}, "weak", .3f));
        List<OcrRegion> regions = OcrGrouping.group(new ArrayList<>(lines), (x, y) -> WHITE, 200, 200);
        assertEquals(1, regions.size());
        assertEquals("reliable", regions.get(0).text);
    }

    @Test public void neighboursOnOneBaselineJoinIntoOneLine() {
        List<OcrLine> lines = Arrays.asList(
                new OcrLine(new int[]{10, 10, 40, 30}, "Angelic", .9f),
                new OcrLine(new int[]{41, 11, 70, 31}, "Bazaar", .9f));
        List<OcrRegion> regions = OcrGrouping.group(new ArrayList<>(lines), (x, y) -> WHITE, 200, 200);
        assertEquals(1, regions.size());
        assertTrue(regions.get(0).text.contains("Angelic"));
        assertTrue(regions.get(0).text.contains("Bazaar"));
    }

    @Test public void stripsOfOneBalloonJoinAndTakeTheColumnWidthAsGlyphSize() {
        OcrLine right = new OcrLine(new int[]{42, 10, 62, 110}, "right", .9f);
        OcrLine left = new OcrLine(new int[]{10, 10, 30, 110}, "left", .9f);
        List<OcrRegion> regions = OcrGrouping.group(new ArrayList<>(Arrays.asList(right, left)),
                (x, y) -> WHITE, 200, 200);
        assertEquals(1, regions.size());
        assertTrue(regions.get(0).text.contains("right"));
        assertTrue(regions.get(0).text.contains("left"));
        assertEquals(20f, regions.get(0).lineHeight, .01f);
    }

    @Test public void separateBalloonsKeepTheirOwnColumn() {
        OcrLine right = new OcrLine(new int[]{150, 10, 170, 110}, "right", .9f);
        OcrLine left = new OcrLine(new int[]{10, 10, 30, 110}, "left", .9f);
        List<OcrRegion> regions = OcrGrouping.group(new ArrayList<>(Arrays.asList(right, left)),
                (x, y) -> WHITE, 200, 200);
        assertEquals(2, regions.size());
    }

    @Test public void passageMarkerStartsANewBlock() {
        List<OcrLine> lines = Arrays.asList(
                new OcrLine(new int[]{10, 10, 190, 34}, "正文一行", .9f),
                new OcrLine(new int[]{10, 40, 190, 64}, "◆ 条目", .9f),
                new OcrLine(new int[]{10, 70, 190, 94}, "条目说明", .9f));
        List<OcrRegion> regions = OcrGrouping.groupParagraphs(new ArrayList<>(lines), (x, y) -> WHITE, 200, 200);
        assertEquals(2, regions.size());
        assertEquals("◆ 条目\n条目说明", regions.get(1).text);
    }

    @Test public void stripsSeparatedByPanelBorderStaySeparate() {
        OcrLine rightPanel = new OcrLine(new int[]{108, 20, 128, 140}, "右格", .9f);
        OcrLine leftPanel = new OcrLine(new int[]{72, 20, 92, 140}, "左格", .9f);
        // Vertical black panel borders + white gutter between x=92 and x=108.
        OcrGrouping.Pixels pixels = (x, y) -> (x == 96 || x == 97 || x == 104 || x == 105) ? BLACK : WHITE;
        List<OcrRegion> regions = OcrGrouping.group(new ArrayList<>(Arrays.asList(rightPanel, leftPanel)),
                pixels, 200, 200);
        assertEquals(2, regions.size());
        assertTrue(regions.get(0).vertical);
        assertTrue(regions.get(1).vertical);
    }

    @Test public void stripsSeparatedByCurvedBubbleOutlineStaySeparate() {
        OcrLine rightBubble = new OcrLine(new int[]{106, 20, 126, 140}, "右泡", .9f);
        OcrLine leftBubble = new OcrLine(new int[]{74, 20, 94, 140}, "左泡", .9f);
        // Curved speech bubble outline drifting across x=98..102 over y=20..140.
        OcrGrouping.Pixels pixels = (x, y) -> {
            int curveX = 100 + Math.round((float) Math.sin((y - 20) * Math.PI / 120.0) * 2f);
            return Math.abs(x - curveX) <= 1 ? BLACK : WHITE;
        };
        List<OcrRegion> regions = OcrGrouping.group(new ArrayList<>(Arrays.asList(rightBubble, leftBubble)),
                pixels, 200, 200);
        assertEquals(2, regions.size());
    }

    @Test public void stripsWithBrokenFuriganaBetweenColumnsStillJoin() {
        OcrLine rightCol = new OcrLine(new int[]{108, 20, 128, 140}, "右列", .9f);
        OcrLine leftCol = new OcrLine(new int[]{76, 20, 96, 140}, "左列", .9f);
        // Broken small ruby glyphs (height 7px separated by 12px gaps) between x=99..103.
        OcrGrouping.Pixels pixels = (x, y) -> {
            if (x >= 99 && x <= 103 && y >= 25 && y <= 130 && ((y - 25) % 19) < 7) return BLACK;
            return WHITE;
        };
        List<OcrRegion> regions = OcrGrouping.group(new ArrayList<>(Arrays.asList(rightCol, leftCol)),
                pixels, 200, 200);
        assertEquals(1, regions.size());
        assertEquals("右列 左列", regions.get(0).text);
        assertTrue(regions.get(0).vertical);
    }

    @Test public void multiColumnWideSpeechBubbleStaysMarkedVertical() {
        List<OcrLine> cols = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int x = 140 - i * 32;
            cols.add(new OcrLine(new int[]{x, 20, x + 22, 130}, "列" + i, .9f));
        }
        List<OcrRegion> regions = OcrGrouping.group(cols, (x, y) -> WHITE, 200, 200);
        assertEquals(1, regions.size());
        // Merged box is ~118x110 (width > height), yet originates from vertical strips.
        assertTrue(regions.get(0).width() > regions.get(0).height());
        assertTrue(regions.get(0).vertical);
    }
}

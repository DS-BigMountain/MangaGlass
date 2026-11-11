package com.mangaglass.app;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class BubbleMergerTest {
    private static int[] box(int left,int top,int right,int bottom) {
        return new int[]{left,top,right,bottom};
    }

    @Test public void singleColumnBecomesOneParagraph() {
        List<BubbleMerger.Paragraph> paragraphs=BubbleMerger.merge(new int[][]{box(100,0,140,200)});
        assertEquals(1,paragraphs.size());
        assertArrayEquals(new int[]{100,0,140,200},paragraphs.get(0).bounds);
        assertArrayEquals(new int[]{0},paragraphs.get(0).indices);
        assertEquals(40f,paragraphs.get(0).fontSize,.01f);
    }

    @Test public void adjacentAlignedColumnsMergeIntoOneBubble() {
        int[][] boxes={box(0,0,40,100),box(60,20,100,120)};
        List<BubbleMerger.Paragraph> paragraphs=BubbleMerger.merge(boxes);
        assertEquals(1,paragraphs.size());
        assertArrayEquals(new int[]{0,0,100,120},paragraphs.get(0).bounds);
        assertArrayEquals(new int[]{1,0},paragraphs.get(0).indices);
        assertEquals(40f,paragraphs.get(0).fontSize,.01f);
    }

    @Test public void gapAtTheOnePointNineColumnLimitMerges() {
        // 列宽 40 像素，间隙上限为 76 像素：间隙取 76 像素时合并。
        int[][] boxes={box(0,0,40,100),box(116,0,156,100)};
        assertEquals(1,BubbleMerger.merge(boxes).size());
    }

    @Test public void gapBeyondTheLimitSplitsParagraphs() {
        int[][] boxes={box(0,0,40,100),box(118,0,158,100)};
        List<BubbleMerger.Paragraph> paragraphs=BubbleMerger.merge(boxes);
        assertEquals(2,paragraphs.size());
        assertArrayEquals(new int[]{118,0,158,100},paragraphs.get(0).bounds);
        assertArrayEquals(new int[]{0,0,40,100},paragraphs.get(1).bounds);
    }

    @Test public void halfHeightOffsetStaysInTheSameBubble() {
        // 两列高度 100 像素，纵向错开 50 像素：重叠 50%、中心距等于高度的一半，两项都落在合并范围内。
        int[][] boxes={box(0,0,40,100),box(0,50,40,150)};
        List<BubbleMerger.Paragraph> paragraphs=BubbleMerger.merge(boxes);
        assertEquals(1,paragraphs.size());
        assertArrayEquals(new int[]{0,0,40,150},paragraphs.get(0).bounds);
        assertArrayEquals(new int[]{0,1},paragraphs.get(0).indices);
    }

    @Test public void deepVerticalShiftKeepsColumnsApart() {
        // 列高重叠 40%，低于 45% 的门槛。
        int[][] boxes={box(0,0,40,100),box(60,60,100,160)};
        assertEquals(2,BubbleMerger.merge(boxes).size());
    }

    @Test public void overlapAtTheThresholdStaysApart() {
        // 列高重叠恰好 45%，合并条件要求严格大于该比例。
        int[][] boxes={box(0,0,40,100),box(60,55,100,155)};
        assertEquals(2,BubbleMerger.merge(boxes).size());
    }

    @Test public void stricterOverlapRuleSplitsHalfOverlappingColumns() {
        int[][] boxes={box(0,0,40,100),box(0,50,40,150)};
        BubbleMerger.Rules rules=new BubbleMerger.Rules(1.9f,.6f,.5f);
        assertEquals(1,BubbleMerger.merge(boxes).size());
        assertEquals(2,BubbleMerger.merge(boxes,rules).size());
    }

    @Test public void tighterAlignmentRuleSplitsOffsetColumns() {
        int[][] boxes={box(0,0,40,100),box(60,25,100,125)};
        BubbleMerger.Rules rules=new BubbleMerger.Rules(1.9f,.45f,.2f);
        assertEquals(1,BubbleMerger.merge(boxes).size());
        assertEquals(2,BubbleMerger.merge(boxes,rules).size());
    }

    @Test public void tighterGapRuleSplitsColumnsThatDefaultJoins() {
        int[][] boxes={box(0,0,40,100),box(70,0,110,100)};
        BubbleMerger.Rules rules=new BubbleMerger.Rules(.5f,.45f,.5f);
        assertEquals(1,BubbleMerger.merge(boxes).size());
        assertEquals(2,BubbleMerger.merge(boxes,rules).size());
    }

    @Test public void columnChainMergesIntoOneParagraph() {
        // 相邻两对的间隙都落在上限内，首尾两列的距离超出上限，合并沿列链传递。
        int[][] boxes={box(0,0,40,100),box(60,0,100,100),box(140,0,180,100)};
        List<BubbleMerger.Paragraph> paragraphs=BubbleMerger.merge(boxes);
        assertEquals(1,paragraphs.size());
        assertArrayEquals(new int[]{0,0,180,100},paragraphs.get(0).bounds);
        assertArrayEquals(new int[]{2,1,0},paragraphs.get(0).indices);
    }

    @Test public void distantColumnKeepsItsOwnParagraph() {
        int[][] boxes={box(0,0,40,100),box(60,0,100,100),box(200,0,240,100)};
        List<BubbleMerger.Paragraph> paragraphs=BubbleMerger.merge(boxes);
        assertEquals(2,paragraphs.size());
        assertArrayEquals(new int[]{200,0,240,100},paragraphs.get(0).bounds);
        assertArrayEquals(new int[]{2},paragraphs.get(0).indices);
        assertArrayEquals(new int[]{0,0,100,100},paragraphs.get(1).bounds);
        assertArrayEquals(new int[]{1,0},paragraphs.get(1).indices);
    }

    @Test public void mergedFontSizeUsesTheColumnWidthMedian() {
        // 列宽 40、42、44 像素，中位数为 42 像素。
        int[][] boxes={box(0,0,40,100),box(48,0,90,100),box(98,0,142,100)};
        List<BubbleMerger.Paragraph> paragraphs=BubbleMerger.merge(boxes);
        assertEquals(1,paragraphs.size());
        assertEquals(42f,paragraphs.get(0).fontSize,.01f);
        assertArrayEquals(new int[]{0,0,142,100},paragraphs.get(0).bounds);
        assertArrayEquals(new int[]{2,1,0},paragraphs.get(0).indices);
    }

    @Test public void emptyInputProducesEmptyResult() {
        assertTrue(BubbleMerger.merge(new int[0][]).isEmpty());
    }
}

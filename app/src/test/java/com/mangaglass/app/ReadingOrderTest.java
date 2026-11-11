package com.mangaglass.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReadingOrderTest {
    private static final String CJK="漫画の文章です",LATIN="manga text";

    @Test public void verticalPageReadsColumnsRightToLeft() {
        int[][] boxes={
                {160,120,200,220},
                {220,0,260,100},
                {160,0,200,100},
                {220,120,260,220}};
        String[] texts={CJK,CJK,CJK,CJK};
        assertTrue(ReadingOrder.verticalPage(boxes,texts));
        assertArrayEquals(new int[]{1,3,2,0},ReadingOrder.order(boxes,texts));
    }

    @Test public void horizontalPageReadsRowsTopToBottom() {
        int[][] boxes={
                {120,0,220,40},
                {0,0,100,40},
                {120,60,220,100},
                {0,60,100,100}};
        String[] texts={CJK,CJK,CJK,CJK};
        assertFalse(ReadingOrder.verticalPage(boxes,texts));
        assertArrayEquals(new int[]{1,0,3,2},ReadingOrder.order(boxes,texts));
    }

    @Test public void thirtyPercentTallCjkBoxesMakeThePageVertical() {
        int[][] boxes=new int[10][];
        String[] texts=new String[10];
        for(int i=0;i<10;i++) {
            boxes[i]=new int[]{i*30,0,i*30+20,100};
            texts[i]=i<3 ? CJK : LATIN;
        }
        assertTrue(ReadingOrder.verticalPage(boxes,texts));
    }

    @Test public void fewerTallCjkBoxesLeaveThePageHorizontal() {
        int[][] boxes=new int[10][];
        String[] texts=new String[10];
        for(int i=0;i<10;i++) {
            boxes[i]=new int[]{i*30,0,i*30+20,100};
            texts[i]=i<2 ? CJK : LATIN;
        }
        assertFalse(ReadingOrder.verticalPage(boxes,texts));
    }

    @Test public void tallLatinBoxesKeepThePageHorizontal() {
        int[][] boxes=new int[10][];
        String[] texts=new String[10];
        for(int i=0;i<10;i++) {
            boxes[i]=new int[]{i*30,0,i*30+20,100};
            texts[i]=LATIN;
        }
        assertFalse(ReadingOrder.verticalPage(boxes,texts));
    }

    @Test public void narrowColumnsUseTheMinimumColumnTolerance() {
        // 列宽 10 像素，容差取最小 8 像素：中心差 8 归入同一列，中心差 18 分成两列。
        int[][] boxes={
                {0,0,10,100},
                {8,120,18,220},
                {26,0,36,100},
                {34,120,44,220}};
        String[] texts={CJK,CJK,CJK,CJK};
        assertTrue(ReadingOrder.verticalPage(boxes,texts));
        assertArrayEquals(new int[]{2,3,0,1},ReadingOrder.order(boxes,texts));
    }

    @Test public void slightVerticalOffsetStaysInTheSameRow() {
        // 行高 40 像素，容差为 20 像素：中心差 10 归入同一行。
        int[][] boxes={{0,0,100,40},{120,10,220,50}};
        String[] texts={LATIN,LATIN};
        assertArrayEquals(new int[]{0,1},ReadingOrder.order(boxes,texts));
    }

    @Test public void distantRowsReadFromTheTop() {
        int[][] boxes={{0,80,100,120},{120,0,220,40}};
        String[] texts={LATIN,LATIN};
        assertArrayEquals(new int[]{1,0},ReadingOrder.order(boxes,texts));
    }

    @Test public void shortRowsUseTheMinimumRowTolerance() {
        // 行高 10 像素，容差取最小 8 像素：中心差 8 归入同一行，中心差 20 分行。
        int[][] boxes={{100,0,200,10},{0,8,100,18},{0,20,100,30}};
        String[] texts={LATIN,LATIN,LATIN};
        assertArrayEquals(new int[]{1,0,2},ReadingOrder.order(boxes,texts));
    }

    @Test public void identicalBoxesKeepTheirInputIndexOrder() {
        int[][] boxes={{0,0,100,40},{0,0,100,40},{0,0,100,40}};
        String[] texts={LATIN,LATIN,LATIN};
        assertArrayEquals(new int[]{0,1,2},ReadingOrder.order(boxes,texts));
    }

    @Test public void emptyInputKeepsAnEmptyOrder() {
        assertFalse(ReadingOrder.verticalPage(new int[0][],new String[0]));
        assertArrayEquals(new int[0],ReadingOrder.order(new int[0][],new String[0]));
    }

    @Test public void singleBoxKeepsItsIndex() {
        int[][] boxes={{0,0,40,100}};
        assertArrayEquals(new int[]{0},ReadingOrder.order(boxes,new String[]{LATIN}));
    }
}

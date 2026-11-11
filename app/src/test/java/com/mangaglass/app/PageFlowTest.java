package com.mangaglass.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class PageFlowTest {
    private static final class Box implements PageFlow.Block {
        int left,right,top,bottom,balloonTop,balloonHeight;
        Box(int left,int right,int top,int bottom,int balloonTop,int balloonHeight) {
            this.left=left; this.right=right; this.top=top; this.bottom=bottom;
            this.balloonTop=balloonTop; this.balloonHeight=balloonHeight;
        }
        @Override public int left() { return left; }
        @Override public int right() { return right; }
        @Override public int top() { return top; }
        @Override public int bottom() { return bottom; }
        @Override public int balloonTop() { return balloonTop; }
        @Override public int balloonHeight() { return balloonHeight; }
        @Override public boolean move(int dx) { if(dx==0) return false; left+=dx; right+=dx; return true; }
        @Override public boolean range(int newLeft,int newRight) { if(newLeft==left && newRight==right) return false; left=newLeft; right=newRight; return true; }
        @Override public boolean lift(int dy) { if(dy==0) return false; top+=dy; bottom+=dy; return true; }
    }

    @Test public void rightBlockMovesOutwardFirst() {
        Box a=new Box(100,200,0,60,0,60), b=new Box(180,280,0,60,0,60);
        PageFlow.separate(Arrays.asList(a,b),600);
        assertEquals(100,a.left); assertEquals(200,a.right);
        assertEquals(200,b.left); assertEquals(300,b.right);
    }
    @Test public void pageEdgeTransfersTheRemainderToTheOtherSide() {
        Box a=new Box(0,100,0,60,0,60), b=new Box(80,180,0,60,0,60);
        PageFlow.separate(Arrays.asList(a,b),600);
        assertEquals(0,a.left);
        assertEquals(100,b.left);
    }
    @Test public void bothPageEdgesCompressTowardTheMiddle() {
        Box a=new Box(0,300,0,60,0,60), b=new Box(280,600,0,60,0,60);
        PageFlow.separate(Arrays.asList(a,b),600);
        assertEquals(300,a.right);
        assertEquals(300,b.left);
    }
    @Test public void verticallySeparatedBalloonsKeepTheirColumns() {
        Box upper=new Box(0,100,0,200,0,50), lower=new Box(80,180,100,300,100,50);
        PageFlow.separate(Arrays.asList(upper,lower),600);
        assertEquals(0,upper.left); assertEquals(80,lower.left);
    }
    @Test public void noOverlapLeavesPositionsUntouched() {
        Box a=new Box(0,100,0,60,0,60), b=new Box(300,400,0,60,0,60);
        PageFlow.separate(Arrays.asList(a,b),600);
        assertEquals(0,a.left); assertEquals(300,b.left);
    }
    @Test public void threeOverlappingBlocksSettleApart() {
        Box a=new Box(0,120,0,60,0,60), b=new Box(80,200,0,60,0,60), c=new Box(160,280,0,60,0,60);
        List<Box> blocks=Arrays.asList(a,b,c);
        PageFlow.separate(blocks,800);
        assertTrue(a.right<=b.left);
        assertTrue(b.right<=c.left);
    }
    @Test public void smallOverflowRaisesThePageSlightly() {
        Box a=new Box(0,100,200,1100,200,60);
        PageFlow.shiftUp(Arrays.asList(a),1000);
        // 底部溢出 106 像素，上移量按画面高度的 1.2% 封顶为 12 像素。
        assertEquals(188,a.top); assertEquals(1088,a.bottom);
    }
    @Test public void largeOverflowKeepsAnchorsNearTheirBubbles() {
        Box a=new Box(0,100,300,3000,300,60), b=new Box(200,300,100,200,100,60);
        PageFlow.shiftUp(Arrays.asList(a,b),1000);
        int shift=PageFlow.pageShift(3000,100,1000);
        assertEquals(300-shift,a.top);
        assertEquals(100-shift,b.top);
        assertTrue(shift<=Math.max(4,Math.round(1000*.012f)));
        assertTrue(a.top>=0 && b.top>=0);
    }
    @Test public void pageShiftNeverLiftsAboveThePageTop() {
        assertEquals(3,PageFlow.pageShift(1000,3,1000));
        assertEquals(0,PageFlow.pageShift(500,0,1000));
    }
    @Test public void blocksInsideThePageStayStill() {
        Box a=new Box(0,100,0,200,0,60), b=new Box(200,300,0,120,0,60);
        PageFlow.shiftUp(Arrays.asList(a,b),300);
        assertEquals(0,a.top); assertEquals(0,b.top);
    }
}

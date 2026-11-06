package com.mangaglass.app;

import java.util.List;

/**
 * Pure geometry pass over placed translation blocks, kept free of Android types so the
 * separation rules run under JVM tests. Horizontal separation pushes the right block
 * outward, hands the remainder to the left block when the page edge stops it, and narrows
 * both toward the middle once both edges are reached. The whole page shifts upward when the
 * lowest block crosses the bottom edge.
 */
final class PageFlow {
    interface Block {
        int left();
        int right();
        int top();
        int bottom();
        int balloonTop();
        int balloonHeight();
        /** Horizontal shift; returns true when the block moved. */
        boolean move(int dx);
        /** Horizontal resize followed by content re-measurement; returns true when the range changed. */
        boolean range(int left,int right);
        /** Vertical shift for the whole-page raise; returns true when the block moved. */
        boolean lift(int dy);
    }

    private PageFlow() {}

    /** Resolves horizontal overlap between side-by-side blocks, bounded by the page edges. */
    static void separate(List<? extends Block> blocks,int pageWidth) {
        if(blocks.size()<2) return;
        final int margin=Math.max(2,Math.round(pageWidth*.004f));
        final int minimumWidth=Math.max(24,Math.round(pageWidth*.04f));
        for(int round=0;round<10;round++) {
            boolean changed=false;
            for(int i=0;i<blocks.size();i++) for(int j=i+1;j<blocks.size();j++) {
                Block a=blocks.get(i), b=blocks.get(j);
                // Only balloons that share a horizontal band are side-by-side neighbours.
                // A long translation that reaches the balloon below keeps its own column.
                if(!sideBySide(a,b) || !overlapsVertically(a,b)) continue;
                int overlap=overlapHorizontally(a,b);
                if(overlap<=0) continue;
                Block leftBlock=a.left()<=b.left() ? a : b;
                Block rightBlock=leftBlock==a ? b : a;
                int remaining=overlap;
                // 1. Push the right block outward first. Keeping one direction per pair
                //    removes the back-and-forth that a half-and-half split causes in chains.
                int rightRoom=Math.max(0,(pageWidth-margin)-rightBlock.right());
                int movedRight=Math.min(remaining,rightRoom);
                boolean changedNow=rightBlock.move(movedRight);
                remaining-=movedRight;
                // 2. The page edge stops the right block: transfer the rest to the left side.
                int leftRoom=Math.max(0,leftBlock.left()-margin);
                int movedLeft=Math.min(remaining,leftRoom);
                changedNow|=leftBlock.move(-movedLeft);
                remaining-=movedLeft;
                // 3. Both sides sit at the page edge: narrow the blocks toward the middle.
                if(remaining>0) {
                    int shrinkRight=Math.min(remaining,Math.max(0,rightBlock.right()-rightBlock.left()-minimumWidth));
                    changedNow|=rightBlock.range(rightBlock.left()+shrinkRight,rightBlock.right());
                    remaining-=shrinkRight;
                }
                if(remaining>0) {
                    int shrinkLeft=Math.min(remaining,Math.max(0,leftBlock.right()-leftBlock.left()-minimumWidth));
                    changedNow|=leftBlock.range(leftBlock.left(),leftBlock.right()-shrinkLeft);
                }
                changed|=changedNow;
            }
            if(!changed) break;
        }
    }

    /** Raised canvas: when the lowest block crosses the bottom edge, every block moves up by that distance. */
    static void shiftUp(List<? extends Block> blocks,int pageHeight) {
        int limit=pageHeight-Math.max(4,Math.round(pageHeight*.006f));
        int maxBottom=0;
        for(Block block:blocks) maxBottom=Math.max(maxBottom,block.bottom());
        int shift=maxBottom-limit;
        if(shift<=0) return;
        for(Block block:blocks) block.lift(-shift);
    }

    private static boolean sideBySide(Block a,Block b) {
        return a.balloonTop()<b.balloonTop()+b.balloonHeight() && b.balloonTop()<a.balloonTop()+a.balloonHeight();
    }
    private static boolean overlapsVertically(Block a,Block b) {
        return a.top()<b.bottom() && b.top()<a.bottom();
    }
    private static int overlapHorizontally(Block a,Block b) {
        return Math.min(a.right(),b.right())-Math.max(a.left(),b.left());
    }
}

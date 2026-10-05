package com.mangaglass.app;

/** Remember a screen edge and a relative height, never a previous orientation's pixels. */
final class FloatingBallPosition {
    private boolean right=true;
    private double verticalFraction=1.0/3;

    int[] place(int width,int height,int ballWidth,int ballHeight,int margin,int top,int bottom) {
        int maxX=Math.max(0,width-ballWidth),maxY=Math.max(0,height-ballHeight);
        int edge=Math.min(Math.max(0,margin),maxX/2);
        int minY=Math.min(Math.max(0,top),maxY),endY=Math.max(minY,maxY-Math.max(0,bottom));
        return new int[]{right ? maxX-edge : edge,minY+(int)Math.round((endY-minY)*verticalFraction)};
    }

    void remember(int x,int y,int width,int height,int ballWidth,int ballHeight,int top,int bottom) {
        right=(long)x*2+ballWidth>=width;
        int maxY=Math.max(0,height-ballHeight);
        int minY=Math.min(Math.max(0,top),maxY),endY=Math.max(minY,maxY-Math.max(0,bottom));
        if(endY>minY) verticalFraction=Math.max(0,Math.min(1,(y-minY)/(double)(endY-minY)));
    }
}

package com.mangaglass.app;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small monochrome UI symbols, drawn at the display's native resolution. */
final class UiIcon extends Drawable {
    private final String kind;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    UiIcon(String kind) { this.kind=kind; p.setColor(Ui.INK); p.setStrokeWidth(1.6f); p.setStrokeCap(Paint.Cap.ROUND); }
    @Override public void draw(Canvas c) {
        c.save(); c.translate(getBounds().left,getBounds().top); c.scale(getBounds().width()/24f,getBounds().height()/24f); p.setStyle(Paint.Style.STROKE);
        switch(kind) {
            case "settings":
                c.drawCircle(12,12,7,p); c.drawCircle(12,12,2.8f,p);
                for(int i=0;i<8;i++) { c.save(); c.rotate(i*45,12,12); c.drawLine(12,2,12,5,p); c.restore(); } break;
            case "usage": c.drawLine(5,19,5,11,p); c.drawLine(12,19,12,4,p); c.drawLine(19,19,19,8,p); break;
            case "tile": for(int x:new int[]{3,14}) for(int y:new int[]{3,14}) c.drawRoundRect(x,y,x+7,y+7,1.2f,1.2f,p); break;
            case "back": c.drawLine(4,12,21,12,p); c.drawLine(4,12,11,5,p); c.drawLine(4,12,11,19,p); break;
            default:
                p.setStyle(Paint.Style.FILL); p.setTypeface(Typeface.DEFAULT); p.setTextSize(14); c.drawText("文",1,14,p); c.drawText("A",13,22,p);
        }
        c.restore();
    }
    @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { p.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

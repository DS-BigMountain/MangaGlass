package com.mangaglass.app;

import android.content.Context;
import android.graphics.*;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.ArrayList;
import java.util.List;

/** A touch-consuming frozen page. PEEKING changes painting, never window touch flags. */
public final class TranslationOverlay extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint paragraphFont = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<Label> labels = new ArrayList<>();
    private final int[] screenLocation = new int[2];
    private Bitmap screenshot;
    private boolean peeking;
    private boolean screenCoordinates = true;
    private String message = "正在截取画面…";
    private boolean busy = true;
    private long stageStarted = android.os.SystemClock.elapsedRealtime();
    private float downX, downY;
    private boolean moved;
    private final Runnable toggle;
    public TranslationOverlay(Context context) { this(context, () -> {}); }
    public TranslationOverlay(Context context, Runnable toggle) {
        super(context); this.toggle = toggle; setClickable(true); setContentDescription("翻译画面，点按切换原图预览，点击悬浮球退出");
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
    }
    public void screenshot(Bitmap bitmap) { screenshot = bitmap; invalidate(); }
    public void screenCoordinates(boolean value) { screenCoordinates = value; }
    public void progress(String value) { message = value; busy = true; stageStarted = android.os.SystemClock.elapsedRealtime(); setContentDescription("翻译处理中，点击悬浮球取消"); invalidate(); }
    public void completed(String value) { message = value; busy = false; invalidate(); }
    public void peeking(boolean value) { peeking = value; setContentDescription(value ? "原图预览，点画面恢复译文" : "译文覆盖，点画面预览原图"); invalidate(); }
    public void regions(List<TextRegion> regions) {
        labels.clear();
        if (screenshot == null) return;
        float fontSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,16,getResources().getDisplayMetrics());
        paragraphFont.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        paragraphFont.setTextSize(fontSize); paragraphFont.setColor(0xff202723);
        Paint.FontMetrics metrics = paragraphFont.getFontMetrics();
        float lineHeight = (float)Math.ceil((metrics.descent-metrics.ascent)*1.12f);
        List<ParagraphLayout.Paragraph> paragraphs = new ArrayList<>();
        for (TextRegion region : regions) {
            Rect r=region.bounds;
            paragraphs.add(new ParagraphLayout.Paragraph(new int[]{r.left,r.top,r.right,r.bottom},region.translated));
        }
        List<ParagraphLayout.Placement> placements=ParagraphLayout.arrange(paragraphs,screenshot.getWidth(),screenshot.getHeight(),fontSize,lineHeight,paragraphFont::measureText);
        for(int i=0;i<regions.size();i++) labels.add(new Label(regions.get(i).bounds,background(regions.get(i).bounds),placements.get(i)));
        message=""; busy=false; peeking(false);
    }
    List<ParagraphLayout.Placement> layoutSnapshot() {
        List<ParagraphLayout.Placement> result=new ArrayList<>();
        for(Label label:labels) result.add(label.layout);
        return result;
    }
    private int background(Rect rect) {
        // Median of sampled border pixels avoids treating a black outline or glyph as the bubble fill.
        List<Integer> colors = new ArrayList<>();
        for (int i=0;i<20;i++) {
            int x = Math.min(screenshot.getWidth()-1,Math.max(0,rect.left + i*Math.max(1,rect.width()-1)/19));
            int y1 = Math.max(0,rect.top-2), y2 = Math.min(screenshot.getHeight()-1,rect.bottom+1);
            colors.add(screenshot.getPixel(x,y1)); colors.add(screenshot.getPixel(x,y2));
        }
        colors.sort(java.util.Comparator.comparingInt(TranslationOverlay::luminance));
        return colors.get(colors.size()*2/3) | 0xff000000;
    }
    private static int luminance(int c) { return (((c>>16)&255)*3 + ((c>>8)&255)*6 + (c&255))/10; }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (screenCoordinates) getLocationOnScreen(screenLocation);
        if (screenshot != null && !screenshot.isRecycled()) {
            canvas.save(); canvas.translate(-screenLocation[0],-screenLocation[1]);
            canvas.drawBitmap(screenshot,0,0,paint);
            if (!peeking) {
                // Erase every original region first; a later mask must not erase an earlier paragraph.
                for(Label label:labels) {
                    paint.setColor(label.color); paint.setStyle(Paint.Style.FILL);
                    canvas.drawRect(label.bounds.left-2,label.bounds.top-2,label.bounds.right+2,label.bounds.bottom+2,paint);
                }
                for(Label label:labels) {
                    ParagraphLayout.Placement p=label.layout;
                    paint.setColor(Color.WHITE); canvas.drawRect(p.left,p.top,p.right,p.bottom,paint);
                    float baseline=p.top+p.padding-paragraphFont.getFontMetrics().ascent;
                    for(String line:p.lines) {
                        canvas.drawText(line,p.left+p.padding,baseline,paragraphFont); baseline+=p.lineHeight;
                    }
                }
            }
            canvas.restore();
        }
        if (peeking) {
            paint.setColor(0xff85d5b0); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Ui.dp(getContext(),1.5f));
            paint.setShadowLayer(Ui.dp(getContext(),5),0,0,0xff85d5b0);
            canvas.drawRoundRect(2,2,getWidth()-2,getHeight()-2,Ui.dp(getContext(),18),Ui.dp(getContext(),18),paint);
            paint.clearShadowLayer(); paint.setStyle(Paint.Style.FILL);
            chip(canvas,"原图预览 · 点画面恢复译文",getHeight()-Ui.dp(getContext(),80));
        } else if (!message.isEmpty()) {
            String label = busy ? message + " · " + ((android.os.SystemClock.elapsedRealtime()-stageStarted)/1000) + " 秒 · 点球取消" : message;
            chip(canvas,label,Ui.dp(getContext(),70));
            if (busy) postInvalidateDelayed(1000);
        }
    }
    private void chip(Canvas canvas,String label,float y) {
        text.setTextSize(Ui.dp(getContext(),12)); text.setColor(Color.WHITE); text.setTypeface(Typeface.DEFAULT);
        float width = Math.min(getWidth()-Ui.dp(getContext(),24),text.measureText(label)+Ui.dp(getContext(),28));
        float left = (getWidth()-width)/2;
        paint.setColor(0xeb203e33); paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(left,y,left+width,y+Ui.dp(getContext(),36),Ui.dp(getContext(),18),Ui.dp(getContext(),18),paint);
        canvas.save(); canvas.clipRect(left+8,y,left+width-8,y+Ui.dp(getContext(),36));
        canvas.drawText(label,(getWidth()-text.measureText(label))/2,y+Ui.dp(getContext(),23),text); canvas.restore();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: downX = event.getX(); downY = event.getY(); moved = false; return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(event.getX()-downX,event.getY()-downY)>ViewConfiguration.get(getContext()).getScaledTouchSlop()) moved = true;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN: moved = true; return true;
            case MotionEvent.ACTION_UP: if (!moved) performClick(); return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); toggle.run(); return true; }
    public void release() { screenshot = null; labels.clear(); }
    private static final class Label {
        final Rect bounds; final int color; final ParagraphLayout.Placement layout;
        Label(Rect bounds,int color,ParagraphLayout.Placement layout) { this.bounds=bounds; this.color=color; this.layout=layout; }
    }
}

package com.mangaglass.app;

import android.content.Context;
import android.graphics.*;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import com.mangaglass.app.OverlayLayout.Label;

/** A touch-consuming frozen page. PEEKING changes painting, never window touch flags. */
public final class TranslationOverlay extends FrameLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<Label> labels = new ArrayList<>();
    private final List<TextRegion> unplaced = new ArrayList<>();
    private final int[] screenLocation = new int[2];
    private final Rect bitmapBounds = new Rect();
    private Bitmap screenshot;
    private boolean peeking, screenCoordinates = true;
    private String message = "正在截取画面…";
    private boolean busy = true;
    private long stageStarted = android.os.SystemClock.elapsedRealtime();
    private float downX, downY;
    private boolean moved, longPressed;
    private Label pressed;
    private View reader;
    private View unplacedPanel;
    private TranslationTiming frameTiming;
    private Runnable frameFinished;
    private final Runnable toggle;
    private final Runnable hold = () -> {
        if (!moved && pressed != null && !busy && !peeking) {
            longPressed = true; showReader(pressed);
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }
    };
    public TranslationOverlay(Context context) { this(context, () -> {}); }
    public TranslationOverlay(Context context, Runnable toggle) {
        super(context); this.toggle = toggle; setWillNotDraw(false); setClickable(true);
        setContentDescription("翻译画面，点按切换原图预览，点击悬浮球退出");
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }
    public void screenshot(Bitmap bitmap) {
        frameTiming=null; frameFinished=null;
        screenshot = bitmap; bitmapBounds.set(0,0,bitmap.getWidth(),bitmap.getHeight());
        labels.clear(); unplaced.clear(); removeUnplacedPanel(); closeReader(); invalidate();
    }
    public void screenCoordinates(boolean value) {
        screenCoordinates = value; screenLocation[0] = 0; screenLocation[1] = 0; invalidate();
    }
    public void progress(String value) { message = value; busy = true; stageStarted = android.os.SystemClock.elapsedRealtime(); setContentDescription("翻译处理中，点击悬浮球取消"); invalidate(); }
    public void completed(String value) { message = value; busy = false; invalidate(); }
    void onFirstTranslationFrame(TranslationTiming timing,Runnable finished) {
        frameTiming=timing; frameFinished=finished; invalidate();
    }
    @Override public void draw(Canvas canvas) {
        TranslationTiming timing=!busy && !peeking && screenshot!=null && !screenshot.isRecycled() ? frameTiming : null;
        Runnable finished=frameFinished;
        if(timing!=null) {
            frameTiming=null; frameFinished=null;
            timing.enter(TranslationTiming.Stage.DRAW);
        }
        super.draw(canvas);
        // Include the bitmap, labels and child views. This is CPU drawing completion,
        // not a claim about the later compositor/physical display presentation time.
        if(timing!=null && finished!=null) finished.run();
    }
    public void peeking(boolean value) {
        closeReader(); peeking = value;
        if(unplacedPanel!=null) unplacedPanel.setVisibility(value ? View.GONE : View.VISIBLE);
        setContentDescription(value ? "原图预览，点画面恢复译文" : "译文覆盖，点画面预览原图"); invalidate();
    }
    public void regions(List<TextRegion> regions) {
        if(screenshot==null || screenshot.isRecycled()) return;
        try { prepared(OverlayLayout.prepare(screenshot,regions,preferredSize())); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    float preferredSize() {
        int width=screenshot==null ? getResources().getDisplayMetrics().widthPixels : screenshot.getWidth();
        int height=screenshot==null ? getResources().getDisplayMetrics().heightPixels : screenshot.getHeight();
        return Math.min(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,16,
                getResources().getDisplayMetrics()),Math.min(width,height)*.045f);
    }
    void prepared(OverlayLayout prepared) {
        labels.clear(); unplaced.clear(); removeUnplacedPanel(); closeReader();
        if (screenshot == null || screenshot.isRecycled()) return;
        labels.addAll(prepared.labels); unplaced.addAll(prepared.unplaced);
        if(!unplaced.isEmpty()) {
            StringBuilder content=new StringBuilder();
            for(TextRegion region:unplaced) {
                if(content.length()>0) content.append("\n\n");
                content.append(region.translated);
            }
            String fullText=content.toString();
            unplacedPanel=new View(getContext()) {
                private ParagraphLayout.Placement paragraph;
                @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
                    if(w>0 && h>0) paragraph=ParagraphLayout.fit(fullText,new Rect(0,0,w,h),Ui.dp(getContext(),16),Ui.INK,false);
                }
                @Override protected void onDraw(Canvas canvas) {
                    canvas.drawColor(Color.WHITE);
                    if(paragraph!=null) paragraph.draw(canvas);
                }
            };
            unplacedPanel.setContentDescription(fullText);
            FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-1,
                    Math.max(1,Math.min(Ui.dp(getContext(),160),screenshot.getHeight()/3)),Gravity.BOTTOM);
            params.leftMargin=params.rightMargin=Ui.dp(getContext(),12);
            params.bottomMargin=Ui.dp(getContext(),12); addView(unplacedPanel,params);
        }
        message = ""; busy = false; peeking(false);
    }
    List<ParagraphLayout.Placement> layoutSnapshot() {
        List<ParagraphLayout.Placement> result = new ArrayList<>();
        for (Label label : labels) result.add(label.layout);
        return result;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setAlpha(255);
        if (screenCoordinates) getLocationOnScreen(screenLocation);
        if (screenshot != null && !screenshot.isRecycled()) {
            canvas.save(); canvas.translate(-screenLocation[0], -screenLocation[1]);
            // An explicit pixel rectangle bypasses bitmap/canvas density scaling, so
            // the image, AI boxes and hit targets all share the same coordinates.
            canvas.drawBitmap(screenshot, null, bitmapBounds, paint);
            if (!peeking) {
                for (Label label : labels) {
                    paint.setShader(label.background);
                    canvas.drawPath(label.mask, paint);
                }
                paint.setShader(null);
                for (Label label : labels) {
                    ParagraphLayout.Placement p = label.layout;
                    p.draw(canvas);
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
        if (reader != null) { paint.setColor(0x99000000); canvas.drawRect(0,0,getWidth(),getHeight(),paint); }
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
    private Label labelAt(float x, float y) {
        if (screenCoordinates) getLocationOnScreen(screenLocation);
        for (Label label : labels) if (label.layout.bounds.contains((int)(x + screenLocation[0]), (int)(y + screenLocation[1]))) return label;
        return null;
    }
    private void showReader(Label label) {
        showReader("完整译文",label.layout.fullText);
    }
    private void removeUnplacedPanel() {
        if(unplacedPanel!=null) { removeView(unplacedPanel); unplacedPanel=null; }
    }
    private void showReader(String title,String fullText) {
        closeReader();
        LinearLayout panel = Ui.column(getContext()); panel.setBackground(Ui.shape(Color.WHITE, 12, getContext()));
        int padding = Ui.dp(getContext(), 18); panel.setPadding(padding, padding, padding, padding); panel.setClickable(true);
        panel.addView(Ui.text(getContext(), title, 18, Ui.INK, true));
        ScrollView scroll = new ScrollView(getContext()); scroll.setFillViewport(true);
        TextView content = Ui.text(getContext(), fullText, 18, Ui.INK, false);
        content.setPadding(0, padding, 0, padding); content.setLineSpacing(0, 1.15f); scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button close = Ui.button(getContext(), "返回译文", false); close.setOnClickListener(v -> closeReader()); panel.addView(close);
        reader = panel;
        if(unplacedPanel!=null) unplacedPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.max(1, Math.min(getWidth() - padding * 2, Ui.dp(getContext(), 600))),
                Math.max(1, Math.min(getHeight() - padding * 2, Ui.dp(getContext(), 560))), Gravity.CENTER);
        addView(reader, params); invalidate();
    }
    private void closeReader() {
        if (reader != null) { removeView(reader); reader = null; invalidate(); }
        if(unplacedPanel!=null) unplacedPanel.setVisibility(peeking ? View.GONE : View.VISIBLE);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX(); downY = event.getY(); moved = false; longPressed = false;
                pressed = !busy && !peeking && reader == null ? labelAt(downX, downY) : null;
                if (pressed != null) postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(event.getX()-downX,event.getY()-downY)>ViewConfiguration.get(getContext()).getScaledTouchSlop()) { moved = true; removeCallbacks(hold); }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN: moved = true; removeCallbacks(hold); return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(hold);
                if (!moved && !longPressed) {
                    if (reader != null) closeReader();
                    else performClick();
                }
                pressed = null; return true;
            case MotionEvent.ACTION_CANCEL: removeCallbacks(hold); pressed = null; return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); toggle.run(); return true; }
    public void release() { frameTiming=null; frameFinished=null; removeCallbacks(hold); pressed = null; closeReader(); removeUnplacedPanel(); unplaced.clear(); screenshot = null; labels.clear(); }
}

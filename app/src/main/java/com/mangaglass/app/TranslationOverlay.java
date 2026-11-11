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

/** 冻结画面的触摸代理。原图预览只改变绘制，窗口触摸标志保持固定。 */
public final class TranslationOverlay extends FrameLayout {
    /** 聚焦阅读时译文块之外画面的压暗比例，约 55%。 */
    private static final int FOCUS_DIM = 0x8c000000;
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
    /** 当前聚焦阅读的译文块；为空表示整页译文模式。 */
    private Label focused;
    /** 双击判定：记录上一次点中的译文块与时刻。 */
    private Label lastTapLabel;
    private long lastTapAt;
    private View reader;
    private View unplacedPanel;
    private TranslationTiming frameTiming;
    private Runnable frameFinished;
    private final Runnable toggle;
    private final Runnable hold = () -> {
        if (!moved && pressed != null && !busy && !peeking) {
            longPressed = true; lastTapLabel = null; showReader(pressed);
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
        labels.clear(); unplaced.clear(); removeUnplacedPanel(); closeReader();
        focused=null; lastTapLabel=null; invalidate();
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
        // 位图、译文与子视图的 CPU 绘制在此完成；后续合成器与物理屏幕呈现时刻另行测量。
        if(timing!=null && finished!=null) finished.run();
    }
    public void peeking(boolean value) {
        closeReader(); peeking = value;
        if (value) focused = null;
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
        focused = null; lastTapLabel = null;
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
    List<OverlayLayout.Label> layoutSnapshot() {
        return new ArrayList<>(labels);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setAlpha(255);
        if (screenCoordinates) getLocationOnScreen(screenLocation);
        if (screenshot != null && !screenshot.isRecycled()) {
            canvas.save(); canvas.translate(-screenLocation[0], -screenLocation[1]);
            // 显式像素矩形绕过位图与画布的密度缩放，画面、AI 框与命中区域共用同一坐标系。
            canvas.drawBitmap(screenshot, null, bitmapBounds, paint);
            if (!peeking) {
                // 背景着色器来自 24×24 分块中值位图，开启过滤让块间以双线性插值过渡。
                paint.setFilterBitmap(true);
                for (Label label : labels) {
                    paint.setShader(label.background);
                    canvas.drawPath(label.mask, paint);
                }
                // 背景框随段落移动：译文越长或越往旁边让位，原文气泡底色仍然垫在文字下方。
                for (Label label : labels) {
                    paint.setShader(label.background);
                    canvas.drawRect(label.backdrop, paint);
                }
                paint.setShader(null);
                paint.setFilterBitmap(false);
                for (Label label : labels) {
                    label.flow.draw(canvas, label.bounds);
                }
            }
            canvas.restore();
        }
        if (focused != null && !peeking && !busy && screenshot != null && !screenshot.isRecycled())
            drawFocusDim(canvas);
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
        for (Label label : labels) if (label.bounds.contains((int)(x + screenLocation[0]), (int)(y + screenLocation[1]))) return label;
        return null;
    }
    /** 聚焦阅读：压暗当前译文块之外的画面，点按后恢复整页译文。 */
    private void focus(Label label) {
        if (label == null) return;
        focused = label;
        lastTapLabel = null;
        setContentDescription("聚焦译文，点按恢复整页译文");
        invalidate();
    }
    private void exitFocusMode() {
        if (focused == null) return;
        focused = null;
        setContentDescription(peeking ? "原图预览，点画面恢复译文" : "译文覆盖，点画面预览原图");
        invalidate();
    }
    /** 触摸点与译文块的命中判定；屏幕坐标与截图坐标在此对齐。 */
    private boolean contains(Label label, float x, float y) {
        if (label == null) return false;
        if (screenCoordinates) getLocationOnScreen(screenLocation);
        int offsetX = screenCoordinates ? screenLocation[0] : 0;
        int offsetY = screenCoordinates ? screenLocation[1] : 0;
        return label.bounds.contains((int)(x + offsetX), (int)(y + offsetY));
    }
    /** 聚焦遮罩绘制：四块矩形围出当前译文块，块内画面保持原亮度。 */
    private void drawFocusDim(Canvas canvas) {
        int offsetX = screenCoordinates ? screenLocation[0] : 0;
        int offsetY = screenCoordinates ? screenLocation[1] : 0;
        int left = Math.max(0, Math.min(getWidth(), focused.bounds.left - offsetX));
        int top = Math.max(0, Math.min(getHeight(), focused.bounds.top - offsetY));
        int right = Math.max(0, Math.min(getWidth(), focused.bounds.right - offsetX));
        int bottom = Math.max(0, Math.min(getHeight(), focused.bounds.bottom - offsetY));
        if (right < left) { int swap = left; left = right; right = swap; }
        if (bottom < top) { int swap = top; top = bottom; bottom = swap; }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setColor(FOCUS_DIM);
        canvas.drawRect(0, 0, getWidth(), top, paint);
        canvas.drawRect(0, bottom, getWidth(), getHeight(), paint);
        canvas.drawRect(0, top, left, bottom, paint);
        canvas.drawRect(right, top, getWidth(), bottom, paint);
    }
    private void showReader(Label label) {
        showReader("完整译文",label.fullText());
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
                    else if (focused != null) { exitFocusMode(); lastTapLabel = null; }
                    else if (pressed != null) {
                        long now = android.os.SystemClock.uptimeMillis();
                        if (lastTapLabel == pressed && now - lastTapAt <= ViewConfiguration.getDoubleTapTimeout()) {
                            // 同一译文块上的第二击进入聚焦：先恢复译文，再压暗块外区域。
                            performClick();
                            focus(pressed);
                        } else {
                            lastTapLabel = pressed;
                            lastTapAt = now;
                            performClick();
                        }
                    } else {
                        Label previous = lastTapLabel;
                        long now = android.os.SystemClock.uptimeMillis();
                        if (previous != null && now - lastTapAt <= ViewConfiguration.getDoubleTapTimeout()
                                && contains(previous, event.getX(), event.getY())) {
                            // 首击已切到原图预览，此处第二击恢复译文并聚焦该块。
                            performClick();
                            focus(previous);
                        } else {
                            lastTapLabel = null;
                            performClick();
                        }
                    }
                }
                pressed = null; return true;
            case MotionEvent.ACTION_CANCEL: removeCallbacks(hold); pressed = null; return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); toggle.run(); return true; }
    public void release() { frameTiming=null; frameFinished=null; removeCallbacks(hold); pressed = null; focused = null; lastTapLabel = null; closeReader(); removeUnplacedPanel(); unplaced.clear(); screenshot = null; labels.clear(); }
}

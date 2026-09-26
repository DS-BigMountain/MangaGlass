package com.mangaglass.app;

import android.app.Activity;
import android.graphics.*;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.Arrays;

/** Local interaction sample; clearly labelled, never presented as an AI result. */
public final class SampleActivity extends Activity {
    private FrameLayout root;
    private TranslationOverlay overlay;
    private Bitmap sample;
    private final SessionGate gate=new SessionGate();
    private TextView ball;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); root=new FrameLayout(this); root.setBackgroundColor(Ui.BG);
        getWindow().setStatusBarColor(Ui.BG); getWindow().setNavigationBarColor(Ui.BG);
        View comic=new View(this) {
            private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                super.onDraw(c); drawComic(c,getWidth(),getHeight(),p);
            }
        };
        comic.setContentDescription("漫画交互样例，所有文字为预设示意"); root.addView(comic,new FrameLayout.LayoutParams(-1,-1));
        ball=Ui.text(this,"译",21,Color.WHITE,true); ball.setGravity(Gravity.CENTER); ball.setBackground(Ui.shape(Ui.GREEN,30,this)); ball.setElevation(Ui.dp(this,12));
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(Ui.dp(this,54),Ui.dp(this,54),Gravity.END | Gravity.CENTER_VERTICAL); bp.rightMargin=Ui.dp(this,12); root.addView(ball,bp);
        ball.setContentDescription("示例悬浮球"); ball.setOnClickListener(v -> {
            if(gate.state()!=SessionGate.State.IDLE) { closeOverlay(); return; }
            long token=gate.begin(); int w=comic.getWidth(),h=comic.getHeight();
            sample=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); drawComic(new Canvas(sample),w,h,new Paint(Paint.ANTI_ALIAS_FLAG));
            overlay=new TranslationOverlay(this,() -> { gate.togglePreview(); overlay.peeking(gate.state()==SessionGate.State.PEEKING); });
            overlay.screenCoordinates(false);
            overlay.screenshot(sample); root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
            overlay.regions(Arrays.asList(new TextRegion(new Rect((int)(w*.61f),(int)(h*.20f),(int)(w*.82f),(int)(h*.42f)),"物語はまだ続く。","故事还在继续。"),
                    new TextRegion(new Rect((int)(w*.18f),(int)(h*.67f),(int)(w*.39f),(int)(h*.76f)),"Let's go!","我们走吧！")));
            gate.complete(token); ball.setText("×"); ball.bringToFront();
        });
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets) -> { root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
    }
    private void closeOverlay() { gate.reset(); if(overlay!=null) { overlay.release(); root.removeView(overlay); overlay=null; } if(sample!=null) { sample.recycle(); sample=null; } ball.setText("译"); }
    private void drawComic(Canvas c,int w,int h,Paint p) {
        c.drawColor(Ui.BG); p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL)); p.setColor(Ui.GREEN); p.setTextSize(w*.035f);
        c.drawText("MANGA GLASS  /  交互练习",w*.07f,h*.075f,p);
        p.setColor(Ui.MUTED); p.setTextSize(w*.03f); c.drawText("预设译文示意，不调用 AI · 点右侧悬浮球开始",w*.07f,h*.11f,p);
        p.setColor(Color.WHITE); c.drawRect(w*.06f,h*.15f,w*.94f,h*.55f,p); c.drawRect(w*.06f,h*.57f,w*.94f,h*.85f,p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(Ui.INK); c.drawRect(w*.06f,h*.15f,w*.94f,h*.55f,p); c.drawRect(w*.06f,h*.57f,w*.94f,h*.85f,p);
        // Stylized character and landscape drawn as native vector primitives.
        c.drawCircle(w*.32f,h*.32f,w*.10f,p); c.drawLine(w*.27f,h*.325f,w*.29f,h*.325f,p); c.drawLine(w*.35f,h*.325f,w*.37f,h*.325f,p);
        c.drawArc(w*.29f,h*.34f,w*.35f,h*.36f,0,180,false,p);
        Path coat=new Path(); coat.moveTo(w*.24f,h*.39f); coat.lineTo(w*.16f,h*.53f); coat.lineTo(w*.48f,h*.53f); coat.lineTo(w*.40f,h*.39f); c.drawPath(coat,p);
        Path mountains=new Path(); mountains.moveTo(w*.47f,h*.81f); mountains.lineTo(w*.64f,h*.63f); mountains.lineTo(w*.79f,h*.81f); mountains.lineTo(w*.89f,h*.70f); c.drawPath(mountains,p);
        c.drawOval(w*.55f,h*.18f,w*.89f,h*.46f,p); c.drawOval(w*.12f,h*.62f,w*.46f,h*.80f,p);
        p.setStyle(Paint.Style.FILL); p.setTextSize(w*.055f); p.setTypeface(Typeface.create("serif",Typeface.NORMAL));
        String[] right={"物","語","は"},left={"ま","だ","続","く","。"};
        for(int i=0;i<right.length;i++) c.drawText(right[i],w*.755f,h*.24f+i*h*.04f,p);
        for(int i=0;i<left.length;i++) c.drawText(left[i],w*.65f,h*.24f+i*h*.04f,p);
        p.setTextSize(w*.04f); c.drawText("Let's go!",w*.19f,h*.72f,p);
        p.setTypeface(Typeface.DEFAULT); p.setColor(Ui.MUTED); p.setTextSize(w*.031f);
        c.drawText("点画面：预览原图 / 恢复译文",w*.12f,h*.91f,p); c.drawText("点悬浮球：退出翻译，再继续阅读",w*.12f,h*.95f,p);
    }
    @Override public void onBackPressed() { if(gate.state()!=SessionGate.State.IDLE) closeOverlay(); else super.onBackPressed(); }
    @Override protected void onDestroy() { closeOverlay(); gate.stop(); super.onDestroy(); }
}

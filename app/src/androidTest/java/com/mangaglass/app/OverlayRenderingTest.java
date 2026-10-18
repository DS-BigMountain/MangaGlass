package com.mangaglass.app;

import android.graphics.*;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OverlayRenderingTest {
    private static final String LONG="在有限的对话框内，译文应当保持清晰可读，同时保留全部内容。";
    private static TextRegion region(Rect b,String text) { return new TextRegion(b,"original",text); }

    @Test public void longTranslationUsesReadablePreviewAndPreservesFullText() {
        String value=LONG.repeat(30);
        ParagraphLayout.Placement p=ParagraphLayout.fit(value,new Rect(0,0,180,100),24,18,Color.BLACK,true);
        assertTrue(p.needsReader); assertEquals(value,p.fullText); assertTrue(p.fontSize>=18);
        assertTrue(p.text.getText().toString().endsWith("… 点击展开"));
        assertTrue(ParagraphLayout.fits(p.text,p.bounds.width()-p.padding*2,p.bounds.height()-p.padding*2));
    }
    @Test public void tinyRegionHasNoMicroscopicGlyphs() throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(30,30,Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.BLACK);
        OverlayLayout layout=OverlayLayout.prepare(bitmap,Collections.singletonList(region(new Rect(10,10,12,12),LONG)),24);
        assertEquals(1,layout.unplaced.size()); assertTrue(layout.labels.isEmpty()); bitmap.recycle();
    }
    @Test public void overlappingSourcesRemainAvailableWithoutPainting() throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.WHITE);
        OverlayLayout layout=OverlayLayout.prepare(bitmap,Arrays.asList(region(new Rect(20,20,100,100),"第一段"),
                region(new Rect(80,80,150,150),"第二段")),24);
        assertEquals(2,layout.unplaced.size()); assertTrue(layout.labels.isEmpty()); bitmap.recycle();
    }
    @Test public void blankExpansionDoesNotExpandErasureMask() throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.WHITE);
        Rect source=new Rect(150,80,200,220);
        OverlayLayout layout=OverlayLayout.prepare(bitmap,Collections.singletonList(region(source,LONG)),24);
        assertEquals(1,layout.labels.size());
        RectF mask=new RectF(); layout.labels.get(0).mask.computeBounds(mask,true);
        assertEquals(new RectF(source),mask);
        assertTrue(layout.labels.get(0).layout.bounds.width()>source.width()); bitmap.recycle();
    }
    @Test public void ovalMaskPreservesOutlineAndErasesMissedGlyph() throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff777777);
        Canvas c=new Canvas(bitmap); Paint paint=new Paint(); paint.setColor(Color.BLACK); c.drawOval(65,35,335,365,paint);
        paint.setColor(0xfff5eedf); c.drawOval(69,39,331,361,paint);
        paint.setColor(Color.BLACK); c.drawRect(180,135,192,250,paint); c.drawRect(201,125,206,130,paint);
        OverlayLayout layout=OverlayLayout.prepare(bitmap,Collections.singletonList(region(new Rect(170,135,230,255),LONG)),24);
        assertEquals(1,layout.labels.size());
        Bitmap rendered=bitmap.copy(Bitmap.Config.ARGB_8888,true); draw(rendered,layout);
        assertEquals(bitmap.getPixel(200,36),rendered.getPixel(200,36));
        // The off-box mark is inside the verified interior and must be erased.
        Paint fill=new Paint(); fill.setColor(0xfff5eedf); Canvas mask=new Canvas(rendered); mask.drawPath(layout.labels.get(0).mask,fill);
        assertEquals(0xfff5eedf,rendered.getPixel(203,127));
        ParagraphLayout.Placement p=layout.labels.get(0).layout;
        assertTrue(p.bounds.width()>60); assertFalse(p.needsReader);
        bitmap.recycle(); rendered.recycle();
    }
    @Test public void deepPanelKeepsLightTextAndReadableLayout() throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(500,250,Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff202830);
        OverlayLayout result=OverlayLayout.prepare(bitmap,Collections.singletonList(region(new Rect(25,40,475,210),LONG.repeat(2))),24);
        assertEquals(1,result.labels.size());
        assertEquals(0xfffafafa,result.labels.get(0).layout.text.getPaint().getColor()); bitmap.recycle();
    }
    @Test public void tappingPreviewOpensCompleteTextAndPeekingClosesReader() throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(240,180,Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.WHITE);
        String full=LONG.repeat(20);
        OverlayLayout prepared=OverlayLayout.prepare(bitmap,Collections.singletonList(region(new Rect(50,40,190,140),full)),24);
        assertTrue(prepared.labels.get(0).layout.needsReader);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            TranslationOverlay view=new TranslationOverlay(InstrumentationRegistry.getInstrumentation().getTargetContext());
            view.screenCoordinates(false); view.screenshot(bitmap); view.layout(0,0,240,180); view.prepared(prepared);
            Rect b=prepared.labels.get(0).layout.bounds;
            MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,b.centerX(),b.centerY(),0);
            MotionEvent up=MotionEvent.obtain(0,10,MotionEvent.ACTION_UP,b.centerX(),b.centerY(),0);
            view.onTouchEvent(down); view.onTouchEvent(up); down.recycle(); up.recycle();
            assertEquals(1,view.getChildCount());
            LinearLayout panel=(LinearLayout)view.getChildAt(0);
            TextView text=(TextView)((ScrollView)panel.getChildAt(1)).getChildAt(0);
            assertEquals(full,text.getText().toString());
            view.peeking(true); assertEquals(0,view.getChildCount());
            view.peeking(false); assertEquals(full,view.layoutSnapshot().get(0).fullText);
            view.release();
        });
        bitmap.recycle();
    }
    @Test public void renderRepresentativePage() throws Exception {
        Bitmap original=Bitmap.createBitmap(720,960,Bitmap.Config.ARGB_8888); original.eraseColor(0xffd7d9dc);
        Canvas c=new Canvas(original); Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK); c.drawOval(35,25,325,435,p); c.drawRoundRect(365,30,690,285,45,45,p);
        p.setColor(0xfffaf4e6); c.drawOval(39,29,321,431,p);
        p.setColor(Color.WHITE); c.drawRoundRect(368,33,687,282,42,42,p);
        p.setColor(0xff222c38); c.drawRect(35,490,685,705,p);
        p.setColor(Color.WHITE); c.drawOval(410,325,680,455,p); c.drawRect(35,760,685,915,p);
        p.setTextSize(25); p.setColor(Color.BLACK);
        for(int i=0;i<8;i++) c.drawText("あ",170,120+i*30,p);
        c.drawText("Original dialogue",410,100,p); c.drawText("continued here",410,140,p);
        c.drawText("Too much text",450,392,p); c.drawText("Paragraph on an open background",60,810,p);
        p.setColor(Color.WHITE); c.drawText("A dialogue on a dark game panel.",65,550,p);
        OverlayLayout layout=OverlayLayout.prepare(original,Arrays.asList(
                region(new Rect(165,95,198,340),"先确认对话框的内部边界，再安排译文的位置。这样可以保留轮廓，也能让文字更自然地换行。"),
                region(new Rect(405,75,625,145),"我们明天出发。请把需要的物品准备好。"),
                region(new Rect(445,368,625,400),LONG.repeat(10)),
                region(new Rect(60,520,650,665),"当前任务已完成。接下来请前往城镇入口，与等待的队员会合。"),
                region(new Rect(55,780,655,890),LONG.repeat(3))),28);
        assertEquals(5,layout.labels.size());
        assertTrue(Math.abs(layout.labels.get(1).layout.bounds.centerY()-157)<15);
        Bitmap rendered=original.copy(Bitmap.Config.ARGB_8888,true); draw(rendered,layout);
        File dir=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),"layout-review");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        save(original,new File(dir,"source.png")); save(rendered,new File(dir,"translated.png"));
        original.recycle(); rendered.recycle();
    }
    private static void draw(Bitmap target,OverlayLayout result) {
        Canvas canvas=new Canvas(target); Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        for(OverlayLayout.Label label:result.labels) { paint.setShader(label.background); canvas.drawPath(label.mask,paint); }
        for(OverlayLayout.Label label:result.labels) {
            ParagraphLayout.Placement p=label.layout; canvas.save(); canvas.clipRect(p.bounds);
            canvas.translate(p.textLeft,p.textTop); p.text.draw(canvas); canvas.restore();
        }
    }
    private static void save(Bitmap image,File file) throws Exception {
        try(FileOutputStream out=new FileOutputStream(file)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out)); }
    }
}

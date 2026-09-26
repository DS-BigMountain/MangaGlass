package com.mangaglass.app;

import android.app.*;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import android.widget.Toast;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

public final class CaptureService extends Service {
    public static volatile boolean running;
    public static final String STOP = "com.mangaglass.app.STOP";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SessionGate gate = new SessionGate();
    private final OcrSession ocr = new OcrSession();
    private final TranslationCache translations = new TranslationCache();
    private WindowManager windows;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private TextView ball;
    private WindowManager.LayoutParams ballParams;
    private TranslationOverlay overlay;
    private Bitmap screenshot;
    private Future<?> job;
    private TranslationEngine engine;
    private int width,height;
    private long captureToken = -1;
    private long pageStarted;
    private Runnable captureTimeout, jobTimeout;
    private boolean tearingDown;
    private boolean navigationRegistered;
    private final BroadcastReceiver navigationReceiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            // System navigation/lock must dismiss the frozen page, not just hide its text.
            if (!tearingDown && (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())
                    || Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))) clearPage();
        }
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            if (!tearingDown) { toast("屏幕捕获已结束，回到漫译可重新开启"); stopSelf(); }
        }
        @Override public void onCapturedContentResize(int w,int h) {
            if (display != null && w > 0 && h > 0 && (w != width || h != height)) resize(w,h);
        }
    };
    @Override public void onCreate() {
        super.onCreate(); windows = getSystemService(WindowManager.class);
        IntentFilter events=new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS); events.addAction(Intent.ACTION_SCREEN_OFF);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(navigationReceiver,events,Context.RECEIVER_NOT_EXPORTED);
        else registerNavigationOnOlderAndroid(events);
        navigationRegistered=true;
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerNavigationOnOlderAndroid(IntentFilter events) {
        // Only Android 8–12: RECEIVER_NOT_EXPORTED is unavailable before API 33.
        registerReceiver(navigationReceiver,events);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (projection != null) return START_NOT_STICKY;
        if (intent == null || !android.provider.Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("capture","悬浮翻译",NotificationManager.IMPORTANCE_LOW));
            PendingIntent home = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(this,1,new Intent(this,CaptureService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new Notification.Builder(this,"capture").setSmallIcon(R.drawable.ic_app).setContentTitle("漫译悬浮球已开启")
                    .setContentText("点球翻译 / 退出 · 点画面查看原图").setContentIntent(home).setOngoing(true)
                    .addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(7,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(7,notification);
            Intent consent = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("resultData",Intent.class) : intent.getParcelableExtra("resultData");
            if (consent == null) throw new IllegalArgumentException("缺少截图授权");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("resultCode",Activity.RESULT_CANCELED),consent);
            if (projection == null) throw new IllegalArgumentException("截图授权已失效");
            projection.registerCallback(projectionCallback,main);
            Point size = screenSize(); width=size.x; height=size.y;
            reader = newReader(width,height);
            display = projection.createVirtualDisplay("MangaGlass",width,height,getResources().getConfiguration().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,main);
            showBall(); running = true; TranslationTileService.refresh(this);
        } catch (Exception e) { toast("无法开启悬浮翻译，请检查悬浮窗权限后重新授权"); stopSelf(); }
        return START_NOT_STICKY;
    }
    private Point screenSize() {
        if (Build.VERSION.SDK_INT >= 30) { Rect bounds = windows.getMaximumWindowMetrics().getBounds(); return new Point(bounds.width(),bounds.height()); }
        Point size=new Point(); windows.getDefaultDisplay().getRealSize(size); return size;
    }
    private ImageReader newReader(int w,int h) {
        ImageReader next = ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3);
        next.setOnImageAvailableListener(this::imageAvailable,main); return next;
    }
    private void imageAvailable(ImageReader source) {
        if (source != reader || tearingDown) return;
        if (captureToken == -2) return; // Keep the post-hide frame queued until the compositor has settled.
        try (Image image = source.acquireLatestImage()) {
            if (image == null || captureToken < 0 || !gate.current(captureToken)) return;
            long token = captureToken; captureToken = -1;
            if (captureTimeout != null) main.removeCallbacks(captureTimeout);
            Image.Plane plane = image.getPlanes()[0];
            if (plane.getPixelStride()!=4) throw new IllegalStateException("不支持的截图格式");
            ByteBuffer raw=plane.getBuffer(); ByteBuffer packed=ByteBuffer.allocateDirect(width*height*4);
            for (int row=0;row<height;row++) {
                raw.limit(raw.capacity()); raw.position(row*plane.getRowStride()); raw.limit(raw.position()+width*4); packed.put(raw);
            }
            packed.flip(); screenshot=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); screenshot.copyPixelsFromBuffer(packed);
            overlay.screenshot(screenshot); ball.setVisibility(View.VISIBLE); startTranslation(token);
        } catch (Exception e) { if (gate.state()==SessionGate.State.WORKING) fail("截屏失败，请退出后重新开启"); }
    }
    private WindowManager.LayoutParams fullParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT>=28) params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT>=30) { params.setFitInsetsTypes(0); params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; }
        return params;
    }
    private void showBall() {
        ball = new TextView(this); ball.setGravity(Gravity.CENTER); ball.setTextSize(21); ball.setTextColor(Color.WHITE);
        ball.setTypeface(Typeface.DEFAULT,Typeface.BOLD); ball.setBackground(Ui.shape(Ui.GREEN,28,this)); ball.setElevation(Ui.dp(this,9));
        ball.setText("译"); ball.setContentDescription("漫译悬浮球，点击翻译或退出，拖动移动，长按停止服务");
        ballParams = new WindowManager.LayoutParams(Ui.dp(this,54),Ui.dp(this,54),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        ballParams.gravity=Gravity.TOP | Gravity.LEFT; ballParams.x=width-Ui.dp(this,62); ballParams.y=height/3;
        ball.setOnClickListener(v -> clickBall());
        ball.setOnTouchListener(new View.OnTouchListener() {
            float startX,startY; int originalX,originalY; boolean moved,longPressed;
            final Runnable hold=() -> { if (!moved) { longPressed=true; toast("漫译已停止"); stopSelf(); } };
            @Override public boolean onTouch(View v,MotionEvent event) {
                switch(event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX=event.getRawX(); startY=event.getRawY(); originalX=ballParams.x; originalY=ballParams.y; moved=false; longPressed=false;
                        main.postDelayed(hold,800); return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.hypot(event.getRawX()-startX,event.getRawY()-startY)>ViewConfiguration.get(CaptureService.this).getScaledTouchSlop()) { moved=true; main.removeCallbacks(hold); }
                        if (moved) {
                            ballParams.x=Math.max(0,Math.min(width-ballParams.width,originalX+(int)(event.getRawX()-startX)));
                            ballParams.y=Math.max(Ui.dp(CaptureService.this,24),Math.min(height-ballParams.height-Ui.dp(CaptureService.this,32),originalY+(int)(event.getRawY()-startY)));
                            updateBall();
                        } return true;
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(hold);
                        if (moved) { ballParams.x=ballParams.x+ballParams.width/2<width/2 ? Ui.dp(CaptureService.this,6) : width-ballParams.width-Ui.dp(CaptureService.this,6); updateBall(); }
                        else if (!longPressed) v.performClick(); return true;
                    case MotionEvent.ACTION_CANCEL: main.removeCallbacks(hold); return true;
                    default: return true;
                }
            }
        });
        windows.addView(ball,ballParams);
    }
    private void updateBall() { if (ball != null && ball.isAttachedToWindow()) { try { windows.updateViewLayout(ball,ballParams); } catch (Exception e) { stopSelf(); } } }
    private void clickBall() {
        if (gate.state()!=SessionGate.State.IDLE) { clearPage(); return; }
        try {
            Settings settings=new Settings(this);
            if (!settings.profile(settings.vision()).ready()) { toast("请回到漫译配置此模式的 API"); return; }
            long token=gate.begin();
            pageStarted=SystemClock.elapsedRealtime();
            overlay=new TranslationOverlay(this,() -> {
                if(gate.state()==SessionGate.State.SHOWING || gate.state()==SessionGate.State.PEEKING) {
                    gate.togglePreview();
                    if(overlay!=null) overlay.peeking(gate.state()==SessionGate.State.PEEKING);
                }
            });
            overlay.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            windows.addView(overlay,fullParams());
            // Re-add the ball last so it stays above the touch-consuming overlay.
            windows.removeViewImmediate(ball); windows.addView(ball,ballParams); ball.setText("×");
            captureToken=-2; ball.setVisibility(View.INVISIBLE);
            // Initially the transparent overlay only intercepts touches. No status pixels can enter the screenshot.
            overlay.progress("");
            main.postDelayed(() -> {
                if (!gate.current(token)) return;
                captureToken=token;
                captureTimeout=() -> { if (gate.current(token) && captureToken==token) fail("没有收到屏幕画面，请重新授权；受保护内容无法截取"); };
                main.postDelayed(captureTimeout,4000);
                imageAvailable(reader);
            },220);
        } catch(Exception e) { fail("无法显示翻译层，请检查悬浮窗权限"); }
    }
    private void startTranslation(long token) throws Exception {
        Settings settings=new Settings(this); boolean vision=settings.vision(); String language=settings.source(); Settings.Profile profile=settings.profile(vision);
        Bitmap owned=screenshot.copy(Bitmap.Config.ARGB_8888,false);
        TranslationEngine taskEngine=new TranslationEngine(ocr,translations).recordUsage(TokenUsageStore.get(this)); engine=taskEngine;
        jobTimeout=() -> { if(gate.current(token)) fail("本次处理超时，请检查网络或换一个模型后重试"); };
        main.postDelayed(jobTimeout,105000);
        job=worker.submit(() -> {
            try {
                List<TextRegion> result=taskEngine.translate(owned,vision,language,profile,message -> main.post(() -> { if(gate.current(token) && overlay!=null) overlay.progress(message); }));
                main.post(() -> {
                    if (!gate.current(token)) return;
                    if (jobTimeout!=null) main.removeCallbacks(jobTimeout);
                    if(result.isEmpty()) { fail("没有识别到文字，可放大漫画或切换识别模式再试"); return; }
                    if(gate.complete(token) && overlay!=null) {
                        overlay.regions(result);
                        String elapsed=TranslationEngine.seconds(SystemClock.elapsedRealtime()-pageStarted);
                        overlay.completed("完成 · " + elapsed + " 秒");
                        new Settings(CaptureService.this).saveLastTiming((vision ? "AI 识别" : "本机识别") + " · 总计 " + elapsed + " 秒\n" + taskEngine.timings());
                        main.postDelayed(() -> { if(gate.current(token) && overlay!=null) overlay.completed(""); },4000);
                    }
                });
            } catch(Exception e) { main.post(() -> {
                if(gate.current(token)) {
                    new Settings(CaptureService.this).saveLastTiming("未完成 · " + taskEngine.stage() + " · " + TranslationEngine.seconds(SystemClock.elapsedRealtime()-pageStarted) + " 秒\n" + taskEngine.timings());
                    fail(TranslationEngine.error(e));
                }
            }); }
            finally { owned.recycle(); }
        });
    }
    private void fail(String message) { clearPage(); toast(message); }
    private void clearPage() {
        gate.reset(); captureToken=-1;
        if(captureTimeout!=null) main.removeCallbacks(captureTimeout);
        if(jobTimeout!=null) main.removeCallbacks(jobTimeout);
        if(engine!=null) { engine.cancel(); engine=null; }
        if(job!=null) { job.cancel(true); job=null; }
        if(overlay!=null) { overlay.release(); if(overlay.isAttachedToWindow()) windows.removeViewImmediate(overlay); overlay=null; }
        if(screenshot!=null) { screenshot.recycle(); screenshot=null; }
        if(ball!=null) { ball.setText("译"); ball.setVisibility(View.VISIBLE); }
    }
    private void resize(int w,int h) {
        if(tearingDown || display==null) return;
        clearPage();
        try {
            ImageReader old=reader; width=w; height=h; reader=newReader(w,h);
            display.resize(w,h,getResources().getConfiguration().densityDpi); display.setSurface(reader.getSurface()); old.close();
            if(ballParams!=null) { ballParams.x=Math.min(ballParams.x,width-ballParams.width); ballParams.y=Math.min(ballParams.y,height-ballParams.height-Ui.dp(this,32)); updateBall(); }
        } catch(Exception e) { toast("屏幕尺寸变化，请重新开启漫译"); stopSelf(); }
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config); Point size=screenSize(); if(size.x!=width || size.y!=height) resize(size.x,size.y);
    }
    @Override public void onDestroy() {
        tearingDown=true; running=false; clearPage(); gate.stop(); main.removeCallbacksAndMessages(null);
        if(navigationRegistered) { unregisterReceiver(navigationReceiver); navigationRegistered=false; }
        TranslationTileService.refresh(this);
        if(ball!=null && ball.isAttachedToWindow()) windows.removeViewImmediate(ball);
        if(display!=null) display.release();
        if(reader!=null) reader.close();
        if(projection!=null) { projection.unregisterCallback(projectionCallback); projection.stop(); }
        ocr.close(); translations.close(); worker.shutdownNow(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_LONG).show(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}

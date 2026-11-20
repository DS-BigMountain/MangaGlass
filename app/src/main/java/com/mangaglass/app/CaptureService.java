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
    public static final String SYNC_MODE = "com.mangaglass.app.SYNC_MODE";
    private static final long MODE_SWITCH_HOLD_MS = 1500;
    private static final long STOP_HOLD_MS = 3200;
    private static final long STATUS_DISMISS_MS = 2000;
    private enum ContinuousPhase { OFF, CAPTURING, TRANSLATING, SHOWING, WAIT_STABLE }
    private static final class EmptyOcrPageException extends IllegalStateException {
        EmptyOcrPageException() { super("本地识别未得到文字，可切换 AI 识图翻译后重试"); }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService auxWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mangaglass-aux");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });
    private final SessionGate gate = new SessionGate();
    private final ContinuousStabilityTracker stabilityTracker = new ContinuousStabilityTracker();
    private Context windowContext;
    private DisplayManager displays;
    private WindowManager windows;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private TextView ball;
    private WindowManager.LayoutParams ballParams;
    private final FloatingBallPosition ballPosition=new FloatingBallPosition();
    private TranslationOverlay overlay;
    private Bitmap screenshot;
    private Future<?> job;
    private TranslationEngine engine;
    private TextTranslationEngine textEngine;
    private int width,height,rotation;
    private long captureToken = -1;
    private long captureRequestToken = -1;
    private TranslationTiming activeTiming;
    private Runnable captureTimeout, captureAttempt, jobTimeout, stabilityTick, statusDismiss;
    private boolean tearingDown;
    private boolean navigationRegistered;
    private boolean continuousMode;
    private boolean continuousActive;
    private ContinuousPhase continuousPhase = ContinuousPhase.OFF;
    private CenterSimilarity.Sample pendingPageSample;
    private CenterSimilarity.Sample lastPageSample;
    private CenterSimilarity.Sample showingBaseline;
    private OverlayLayout lastLayout;
    private int lastLayoutWidth, lastLayoutHeight, lastLayoutRotation;
    private long stabilityAcceptSamplesAt, lastSampleCapturedAt, showingEnteredAt, lastShowingCheckAt;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) { if (id == Display.DEFAULT_DISPLAY) stopSelf(); }
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) main.post(() -> syncDisplay());
        }
    };
    private final BroadcastReceiver navigationReceiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            // System navigation/lock must dismiss the frozen page, not just hide its text.
            if (!tearingDown && (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())
                    || Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))) {
                if (continuousMode) pauseContinuousMode();
                else clearPage();
            }
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
        super.onCreate();
        displays = getSystemService(DisplayManager.class);
        // A Service's resources may still describe the background activity's orientation.
        // Bind both metrics and overlay views to the actual display's window configuration.
        windowContext = Build.VERSION.SDK_INT >= 30
                ? createDisplayContext(displays.getDisplay(Display.DEFAULT_DISPLAY))
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                : this;
        windows = windowContext.getSystemService(WindowManager.class);
        displays.registerDisplayListener(displayListener, main);
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
        if (intent != null && SYNC_MODE.equals(intent.getAction())) {
            if (projection != null) syncModeFromSettings();
            return START_NOT_STICKY;
        }
        if (projection != null) return START_NOT_STICKY;
        if (intent == null || !android.provider.Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        try {
            continuousMode = new Settings(this).continuousMode();
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("capture","悬浮翻译",NotificationManager.IMPORTANCE_LOW));
            PendingIntent home = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(this,1,new Intent(this,CaptureService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new Notification.Builder(this,"capture").setSmallIcon(R.drawable.ic_app).setContentTitle("漫译悬浮球已开启")
                    .setContentText("点球翻译 / 退出 · 长按 1.5 秒切模式").setContentIntent(home).setOngoing(true)
                    .addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(7,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(7,notification);
            Intent consent = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("resultData",Intent.class) : intent.getParcelableExtra("resultData");
            if (consent == null) throw new IllegalArgumentException("缺少截图授权");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("resultCode",Activity.RESULT_CANCELED),consent);
            if (projection == null) throw new IllegalArgumentException("截图授权已失效");
            projection.registerCallback(projectionCallback,main);
            Point size = screenSize(); width=size.x; height=size.y;
            rotation=windows.getDefaultDisplay().getRotation();
            reader = newReader(width,height);
            display = projection.createVirtualDisplay("MangaGlass",width,height,windowContext.getResources().getConfiguration().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,main);
            showBall(); running = true; TranslationTileService.refresh(this);
            // Warm up bundled OCR models and API DNS in the background so the first tap feels instant.
            Settings initSettings = new Settings(this);
            boolean initOcr = Settings.MODE_OCR.equals(initSettings.mode());
            String initLang = initSettings.ocrLanguage();
            Settings.Profile initProfile = initSettings.profile();
            if (initProfile.ready()) {
                try { ApiTransport.prewarmDns(TranslationProtocol.endpoint(initProfile.url)); } catch (Exception ignored) { }
            }
            if (initOcr) {
                try { auxWorker.execute(() -> OcrEngine.prewarm(this, initLang)); } catch (RejectedExecutionException ignored) { }
            }
        } catch (Exception e) { toast("无法开启悬浮翻译，请检查悬浮窗权限后重新授权"); stopSelf(); }
        return START_NOT_STICKY;
    }
    private Point screenSize() {
        // This MATCH_PARENT window explicitly opts out of all insets on Android 11+.
        // Its measured size is the final authority if an OEM delivers stale metrics.
        if (Build.VERSION.SDK_INT >= 30 && overlay != null && overlay.isAttachedToWindow()
                && overlay.getWidth() > 0 && overlay.getHeight() > 0) {
            return new Point(overlay.getWidth(),overlay.getHeight());
        }
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
        // Display/configuration callbacks can arrive after the tap. Never send a frame
        // from an obsolete surface to the model or place it on a differently sized screen.
        if (captureToken >= 0 && syncDisplay()) return;
        try (Image image = source.acquireLatestImage()) {
            if (image == null) return;
            int frameWidth=image.getWidth(),frameHeight=image.getHeight();
            if (frameWidth!=width || frameHeight!=height) return;
            Image.Plane plane = image.getPlanes()[0];
            if (plane.getPixelStride()!=4) throw new IllegalStateException("不支持的截图格式");
            if (captureToken >= 0 && gate.current(captureToken)) {
                // SurfaceFlinger may briefly deliver a new-size buffer containing the old
                // letterbox transform. Its empty alpha bands must never become an AI image.
                if (!CapturePixels.fillsEdges(plane.getBuffer(),frameWidth,frameHeight,plane.getRowStride())) return;
                long token = captureToken; captureToken = -1;
                if(activeTiming!=null) activeTiming.enter(TranslationTiming.Stage.CAPTURE_PROCESS);
                if (captureTimeout != null) main.removeCallbacks(captureTimeout);
                ByteBuffer raw=plane.getBuffer(); ByteBuffer packed=ByteBuffer.allocateDirect(frameWidth*frameHeight*4);
                for (int row=0;row<frameHeight;row++) {
                    raw.limit(raw.capacity()); raw.position(row*plane.getRowStride()); raw.limit(raw.position()+frameWidth*4); packed.put(raw);
                }
                packed.flip(); screenshot=Bitmap.createBitmap(frameWidth,frameHeight,Bitmap.Config.ARGB_8888);
                screenshot.setDensity(Bitmap.DENSITY_NONE); screenshot.copyPixelsFromBuffer(packed);
                ball.setVisibility(View.VISIBLE);
                if (continuousMode) {
                    if (!continuousActive) { clearPage(); return; }
                    pendingPageSample = sampleBitmapCenter(screenshot);
                    attachContinuousProgressOverlay();
                    continuousPhase = ContinuousPhase.TRANSLATING;
                } else {
                    overlay.screenshot(screenshot);
                }
                startTranslation(token);
                return;
            }
            if (continuousMode && continuousActive) {
                long now = SystemClock.uptimeMillis();
                if (continuousPhase == ContinuousPhase.WAIT_STABLE) {
                    if (now >= stabilityAcceptSamplesAt && (lastSampleCapturedAt == 0 || now - lastSampleCapturedAt >= 35)) {
                        CenterSimilarity.Sample sample = samplePlaneCenter(plane, frameWidth, frameHeight);
                        if (sample != null) {
                            lastSampleCapturedAt = now;
                            stabilityTracker.onFrameSample(sample);
                        }
                    }
                } else if (continuousPhase == ContinuousPhase.SHOWING) {
                    if (showingBaseline == null) {
                        if (now >= showingEnteredAt + 15) {
                            showingBaseline = samplePlaneCenter(plane, frameWidth, frameHeight);
                            lastShowingCheckAt = now;
                        }
                    } else if (now < showingEnteredAt + 180) {
                        CenterSimilarity.Sample settled = samplePlaneCenter(plane, frameWidth, frameHeight);
                        if (settled != null) {
                            showingBaseline = settled;
                            lastShowingCheckAt = now;
                        }
                    } else if (now - lastShowingCheckAt >= 100) {
                        lastShowingCheckAt = now;
                        CenterSimilarity.Sample current = samplePlaneCenter(plane, frameWidth, frameHeight);
                        if (current != null && CenterSimilarity.isMovedWhileShowing(showingBaseline, current)) {
                            enterStabilityWatch();
                        }
                    }
                }
            }
        } catch (Exception e) { if (gate.state()==SessionGate.State.WORKING) handleFailure("截屏失败，请退出后重新开启"); }
    }
    private WindowManager.LayoutParams fullParams() {
        return overlayParams(false);
    }
    private WindowManager.LayoutParams overlayParams(boolean touchThrough) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (touchThrough) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP | Gravity.LEFT;
        if (touchThrough && Build.VERSION.SDK_INT >= 31) {
            android.hardware.input.InputManager input = getSystemService(android.hardware.input.InputManager.class);
            float maxOpacity = input != null ? input.getMaximumObscuringOpacityForTouch() : 0.8f;
            params.alpha = Math.max(0.05f, Math.min(1f, maxOpacity));
        }
        if (Build.VERSION.SDK_INT>=28) params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT>=30) { params.setFitInsetsTypes(0); params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; }
        return params;
    }
    private void showBall() {
        ball = new TextView(windowContext); ball.setGravity(Gravity.CENTER); ball.setTextSize(21); ball.setTextColor(Color.WHITE);
        ball.setTypeface(Typeface.DEFAULT,Typeface.BOLD); ball.setElevation(Ui.dp(this,9));
        ballParams = new WindowManager.LayoutParams(Ui.dp(this,54),Ui.dp(this,54),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        ballParams.gravity=Gravity.TOP | Gravity.LEFT;
        updateBallAppearance();
        placeBall();
        ball.setOnClickListener(v -> clickBall());
        ball.setOnTouchListener(new View.OnTouchListener() {
            float startX,startY; int originalX,originalY,touchWidth,touchHeight,touchRotation; boolean moved,modeToggled,stopped;
            final Runnable switchHold=() -> {
                if (!moved && !stopped && touchWidth==width && touchHeight==height && touchRotation==rotation) {
                    modeToggled=true;
                    ball.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    toggleOperationMode(true);
                }
            };
            final Runnable stopHold=() -> {
                if (!moved && touchWidth==width && touchHeight==height && touchRotation==rotation) {
                    stopped=true;
                    if (modeToggled) new Settings(CaptureService.this).saveContinuousMode(!continuousMode);
                    toast("漫译已停止");
                    stopSelf();
                }
            };
            @Override public boolean onTouch(View v,MotionEvent event) {
                if (event.getActionMasked()==MotionEvent.ACTION_OUTSIDE) {
                    handleOutsideTouch();
                    return false;
                }
                if(event.getActionMasked()!=MotionEvent.ACTION_DOWN && (touchWidth!=width || touchHeight!=height || touchRotation!=rotation)) {
                    main.removeCallbacks(switchHold); main.removeCallbacks(stopHold); return true;
                }
                switch(event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX=event.getRawX(); startY=event.getRawY(); originalX=ballParams.x; originalY=ballParams.y;
                        moved=false; modeToggled=false; stopped=false;
                        touchWidth=width; touchHeight=height; touchRotation=rotation;
                        main.postDelayed(switchHold,MODE_SWITCH_HOLD_MS);
                        main.postDelayed(stopHold,STOP_HOLD_MS);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.hypot(event.getRawX()-startX,event.getRawY()-startY)>ViewConfiguration.get(CaptureService.this).getScaledTouchSlop()) {
                            moved=true; main.removeCallbacks(switchHold); main.removeCallbacks(stopHold);
                        }
                        if (moved) {
                            ballParams.x=Math.max(0,Math.min(width-ballParams.width,originalX+(int)(event.getRawX()-startX)));
                            ballParams.y=Math.max(Ui.dp(CaptureService.this,24),Math.min(height-ballParams.height-Ui.dp(CaptureService.this,32),originalY+(int)(event.getRawY()-startY)));
                            updateBall();
                        } return true;
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(switchHold); main.removeCallbacks(stopHold);
                        if (moved) rememberBall();
                        else if (!modeToggled && !stopped) v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(switchHold); main.removeCallbacks(stopHold);
                        if(moved) rememberBall();
                        return true;
                    default: return true;
                }
            }
        });
        windows.addView(ball,ballParams);
    }
    private void placeBall() {
        if(ballParams==null) return;
        int[] point=ballPosition.place(width,height,ballParams.width,ballParams.height,
                Ui.dp(windowContext,6),Ui.dp(windowContext,24),Ui.dp(windowContext,32));
        ballParams.x=point[0]; ballParams.y=point[1]; updateBall();
    }
    private void rememberBall() {
        ballPosition.remember(ballParams.x,ballParams.y,width,height,ballParams.width,ballParams.height,
                Ui.dp(windowContext,24),Ui.dp(windowContext,32));
        placeBall();
    }
    private void updateBall() { if (ball != null && ball.isAttachedToWindow()) { try { windows.updateViewLayout(ball,ballParams); } catch (Exception e) { stopSelf(); } } }
    private void updateBallAppearance() {
        if (ball == null || ballParams == null) return;
        if (!continuousMode) {
            boolean active = gate.state() != SessionGate.State.IDLE;
            ball.setBackground(Ui.shape(Ui.GREEN, 28, this));
            ball.setText(active ? "×" : "译");
            ball.setContentDescription("漫译悬浮球，单次模式；点击翻译或退出，拖动移动，长按 1.5 秒切到连续模式，长按 3 秒停止服务");
            ballParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else if (!continuousActive) {
            ball.setBackground(Ui.shape(Ui.CONTINUOUS, 28, this));
            ball.setText("连");
            ball.setContentDescription("漫译悬浮球，连续模式待机；点击开始连续翻译，拖动移动，长按 1.5 秒切回单次模式，长按 3 秒停止服务");
            ballParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            ball.setBackground(Ui.borderedShape(Ui.CONTINUOUS, Color.WHITE, 2f, 28, this));
            ball.setText("停");
            ball.setContentDescription("漫译悬浮球，连续模式运行中；点击暂停，长按 1.5 秒切回单次模式，长按 3 秒停止服务");
            ballParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        }
        updateBall();
    }
    private void syncModeFromSettings() {
        boolean target = new Settings(this).continuousMode();
        if (target != continuousMode) setOperationMode(target, false);
    }
    private void toggleOperationMode(boolean notifyUser) {
        boolean next = !continuousMode;
        new Settings(this).saveContinuousMode(next);
        setOperationMode(next, notifyUser);
    }
    private void setOperationMode(boolean nextContinuous, boolean notifyUser) {
        pauseContinuousMode();
        clearPage();
        continuousMode = nextContinuous;
        updateBallAppearance();
        if (notifyUser) {
            toast(nextContinuous ? "已切到连续翻译模式，点击悬浮球开始" : "已切到单次翻译模式");
        }
    }
    private void clickBall() {
        if (continuousMode) {
            if (continuousActive) { pauseContinuousMode(); return; }
            try {
                syncDisplay();
                if (tearingDown || reader == null) return;
                Settings settings = new Settings(this);
                if (!settings.profile().ready()) { toast("请回到漫译配置 AI 截图翻译 API"); return; }
                continuousActive = true;
                updateBallAppearance();
                beginContinuousCapture();
            } catch (Exception e) { handleFailure("无法开启连续翻译，请检查悬浮窗权限"); }
            return;
        }
        if (gate.state()!=SessionGate.State.IDLE) { clearPage(); return; }
        long tappedAt=System.nanoTime()/1_000_000,wallTime=System.currentTimeMillis();
        try {
            syncDisplay();
            if (tearingDown || reader==null) return;
            Settings settings=new Settings(this);
            if (!settings.profile().ready()) { toast("请回到漫译配置 AI 截图翻译 API"); return; }
            long token=gate.begin();
            captureRequestToken=token;
            activeTiming=new TranslationTiming(wallTime,tappedAt,() -> System.nanoTime()/1_000_000);
            overlay=new TranslationOverlay(windowContext,() -> {
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
            captureTimeout=() -> { if (gate.current(token) && screenshot==null) fail("没有收到屏幕画面，请重新授权；受保护内容无法截取"); };
            main.postDelayed(captureTimeout,4000);
            queueCapture(token,220);
        } catch(Exception e) { fail("无法显示翻译层，请检查悬浮窗权限"); }
    }
    private void beginContinuousCapture() {
        if (!continuousMode || !continuousActive || tearingDown || reader == null) return;
        cancelActiveWork();
        detachOverlay();
        gate.reset();
        if (lastLayoutWidth != width || lastLayoutHeight != height || lastLayoutRotation != rotation) {
            invalidateContinuousCache();
        }
        long token = gate.begin();
        if (token < 0) return;
        captureRequestToken = token;
        continuousPhase = ContinuousPhase.CAPTURING;
        long tappedAt = System.nanoTime() / 1_000_000, wallTime = System.currentTimeMillis();
        activeTiming = new TranslationTiming(wallTime, tappedAt, () -> System.nanoTime() / 1_000_000);
        if (ball != null) ball.setVisibility(View.INVISIBLE);
        captureToken = -2;
        captureTimeout = () -> {
            if (gate.current(token) && screenshot == null) {
                handleFailure("没有收到屏幕画面，请重新授权；受保护内容无法截取");
            }
        };
        main.postDelayed(captureTimeout, 4000);
        queueCapture(token, 160);
    }
    private void attachContinuousProgressOverlay() {
        detachOverlay();
        overlay = new TranslationOverlay(windowContext, () -> {});
        overlay.continuousMode(true);
        overlay.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        overlay.progress(Settings.MODE_OCR.equals(new Settings(this).mode()) ? "识别中" : "翻译中");
        windows.addView(overlay, overlayParams(true));
        if (ball != null && ball.isAttachedToWindow()) {
            windows.removeViewImmediate(ball);
            windows.addView(ball, ballParams);
        }
    }
    private void captureFrame(long token) {
        if (!gate.current(token) || screenshot!=null) return;
        if (!continuousMode) {
            if (overlay==null) return;
            if (overlay.getWidth()==0 || overlay.getHeight()==0) {
                queueCapture(token,32); return;
            }
        }
        Point size=screenSize();
        if (size.x!=width || size.y!=height) {
            try {
                replaceSurface(size.x,size.y);
                // Let MediaProjection redraw at the measured window size. Stretching
                // the old letterboxed bitmap would also stretch the wrong AI coordinates.
                queueCapture(token,220);
            } catch(Exception e) { handleFailure("屏幕尺寸变化，请重新截取"); }
            return;
        }
        captureToken=token; imageAvailable(reader);
    }
    private void queueCapture(long token,long delay) {
        if (captureAttempt!=null) main.removeCallbacks(captureAttempt);
        captureToken=-2;
        captureAttempt=() -> captureFrame(token);
        main.postDelayed(captureAttempt,delay);
    }
    private void startTranslation(long token) throws Exception {
        Settings settings=new Settings(this); Settings.Profile profile=settings.profile();
        boolean localOcr=Settings.MODE_OCR.equals(settings.mode());
        String language=settings.ocrLanguage();
        Bitmap owned=screenshot.copy(Bitmap.Config.ARGB_8888,false);
        TranslationTiming timing=activeTiming;
        float preferredSize=overlay.preferredSize(width, height);
        TranslationEngine taskEngine=localOcr ? null : new TranslationEngine().recordUsage(TokenUsageStore.get(this)).recordTiming(timing);
        TextTranslationEngine textTask=localOcr ? new TextTranslationEngine().recordUsage(TokenUsageStore.get(this)).recordTiming(timing) : null;
        engine=taskEngine; textEngine=textTask;
        jobTimeout=() -> { if(gate.current(token)) handleFailure("本次处理超时，请检查网络或换一个模型后重试"); };
        // The first local run copies the bundled weights, so the limit covers that preparation too.
        main.postDelayed(jobTimeout,localOcr ? 150000 : 105000);
        timing.enter(TranslationTiming.Stage.WORKER_WAIT);
        job=worker.submit(() -> {
            try {
                TranslationEngine.Progress report=message -> main.post(() -> { if(gate.current(token) && overlay!=null) overlay.progress(message); });
                List<TextRegion> translated;
                if (localOcr) {
                    if (profile.ready()) {
                        try { ApiTransport.prewarmDns(TranslationProtocol.endpoint(profile.url)); } catch (Exception ignored) { }
                    }
                    timing.enter(TranslationTiming.Stage.OCR);
                    List<OcrRegion> recognized=OcrEngine.recognize(this,owned,language,
                            message -> main.post(() -> { if(gate.current(token) && overlay!=null) overlay.progress(message); }));
                    if (recognized.isEmpty()) throw new EmptyOcrPageException();
                    translated=textTask.translate(recognized,profile,
                            message -> main.post(() -> { if(gate.current(token) && overlay!=null) overlay.progress(message); }));
                } else {
                    // Run local detector geometry concurrently while the Vision API call is in flight
                    // so row-geometry snapping adds zero extra wall-clock latency.
                    Future<List<OcrRegion>> geomFuture = null;
                    if (OcrEngine.available(this)) {
                        try { geomFuture = auxWorker.submit(() -> OcrEngine.detectGeometry(this, owned)); }
                        catch (RejectedExecutionException ignored) { }
                    }
                    timing.enter(TranslationTiming.Stage.IMAGE_PREPARE);
                    try {
                        translated=taskEngine.translate(owned,profile,report);
                    } finally {
                        if (geomFuture != null && Thread.currentThread().isInterrupted()) geomFuture.cancel(true);
                    }
                    if (!translated.isEmpty() && geomFuture != null) {
                        try {
                            List<OcrRegion> geometry = geomFuture.get(1500, TimeUnit.MILLISECONDS);
                            if (geometry != null && !geometry.isEmpty()) {
                                translated = SourceMatcher.attach(translated, geometry, owned.getWidth(), owned.getHeight());
                            }
                        } catch (InterruptedException interrupted) {
                            geomFuture.cancel(true);
                            throw interrupted;
                        } catch (Exception ignored) {
                            geomFuture.cancel(true);
                        }
                    } else if (geomFuture != null) {
                        geomFuture.cancel(true);
                    }
                }
                final List<TextRegion> result = translated;
                timing.enter(TranslationTiming.Stage.LAYOUT);
                OverlayLayout prepared=OverlayLayout.prepare(owned,result,preferredSize);
                timing.enter(TranslationTiming.Stage.UI_WAIT);
                main.post(() -> {
                    if (!gate.current(token)) return;
                    if (jobTimeout!=null) main.removeCallbacks(jobTimeout);
                    if (continuousMode) {
                        if (!continuousActive) { clearPage(); return; }
                        if (result.isEmpty() || prepared.labels.isEmpty()) {
                            commitContinuousPage(null);
                            finishTiming(false);
                            gate.complete(token);
                            detachOverlay();
                            recycleScreenshot();
                            enterContinuousShowing(null);
                            return;
                        }
                        commitContinuousPage(prepared);
                        recycleScreenshot();
                        if (gate.complete(token) && overlay != null) {
                            TranslationOverlay target = overlay;
                            target.prepared(prepared);
                            timing.enter(TranslationTiming.Stage.FRAME_WAIT);
                            target.onFirstTranslationFrame(timing, () -> {
                                if (activeTiming != timing || overlay != target) return;
                                finishTiming(true);
                                target.completed("已完成");
                                scheduleStatusDismiss(target);
                                enterContinuousShowing(target);
                            });
                        }
                        return;
                    }
                    if(result.isEmpty()) { fail("没有识别到文字，可放大画面或更换支持图片的模型再试"); return; }
                    if(gate.complete(token) && overlay!=null) {
                        TranslationOverlay target=overlay;
                        target.prepared(prepared);
                        timing.enter(TranslationTiming.Stage.FRAME_WAIT);
                        target.onFirstTranslationFrame(timing,() -> {
                            if(activeTiming!=timing || overlay!=target) return;
                            finishTiming(true);
                            target.completed("已完成");
                            scheduleStatusDismiss(target);
                        });
                    }
                });
            } catch(Exception e) { timing.enter(TranslationTiming.Stage.UI_WAIT); main.post(() -> {
                if(gate.current(token)) {
                    if (continuousMode && e instanceof EmptyOcrPageException) {
                        if (!continuousActive) { clearPage(); return; }
                        commitContinuousPage(null);
                        finishTiming(false);
                        gate.complete(token);
                        detachOverlay();
                        recycleScreenshot();
                        enterContinuousShowing(null);
                        return;
                    }
                    handleFailure(TranslationEngine.error(e));
                }
            }); }
            finally { owned.recycle(); }
        });
    }
    private void handleFailure(String message) {
        if (continuousMode) {
            pauseContinuousMode();
            toast(message);
        } else {
            fail(message);
        }
    }
    private void fail(String message) { clearPage(); toast(message); }
    private TranslationTiming.Record finishTiming(boolean frameDrawn) {
        if(activeTiming==null) return null;
        TranslationTiming.Record record=activeTiming.finish(frameDrawn); activeTiming=null;
        if(record!=null) {
            try { TranslationTimingStore.get(this).record(record); }
            catch(android.database.SQLException e) { toast("本次耗时记录未能保存"); }
        }
        return record;
    }
    private void scheduleStatusDismiss(TranslationOverlay target) {
        if (statusDismiss != null) main.removeCallbacks(statusDismiss);
        statusDismiss = () -> {
            statusDismiss = null;
            if (overlay == target) target.completed("");
        };
        main.postDelayed(statusDismiss, STATUS_DISMISS_MS);
    }
    private void cancelActiveWork() {
        finishTiming(false);
        gate.reset(); captureToken=-1; captureRequestToken=-1;
        if(captureAttempt!=null) main.removeCallbacks(captureAttempt);
        if(captureTimeout!=null) main.removeCallbacks(captureTimeout);
        if(jobTimeout!=null) main.removeCallbacks(jobTimeout);
        if(statusDismiss!=null) { main.removeCallbacks(statusDismiss); statusDismiss=null; }
        if(engine!=null) { engine.cancel(); engine=null; }
        if(textEngine!=null) { textEngine.cancel(); textEngine=null; }
        if(job!=null) { job.cancel(true); job=null; }
        pendingPageSample = null;
        recycleScreenshot();
    }
    private void detachOverlay() {
        if(statusDismiss!=null) { main.removeCallbacks(statusDismiss); statusDismiss=null; }
        if(overlay!=null) {
            overlay.release();
            if(overlay.isAttachedToWindow()) windows.removeViewImmediate(overlay);
            overlay=null;
        }
    }
    private void recycleScreenshot() {
        if(screenshot!=null) { screenshot.recycle(); screenshot=null; }
    }
    private void invalidateContinuousCache() {
        lastPageSample = null;
        pendingPageSample = null;
        showingBaseline = null;
        if (lastLayout != null) { lastLayout.release(); lastLayout = null; }
        lastLayoutWidth = 0;
        lastLayoutHeight = 0;
        lastLayoutRotation = 0;
    }
    private void commitContinuousPage(OverlayLayout prepared) {
        lastPageSample = pendingPageSample;
        pendingPageSample = null;
        if (lastLayout != null && lastLayout != prepared) lastLayout.release();
        lastLayout = prepared;
        lastLayoutWidth = width;
        lastLayoutHeight = height;
        lastLayoutRotation = rotation;
    }
    private void enterContinuousShowing(TranslationOverlay target) {
        if (!continuousMode || !continuousActive) return;
        continuousPhase = ContinuousPhase.SHOWING;
        showingEnteredAt = SystemClock.uptimeMillis();
        showingBaseline = target == null ? lastPageSample : null;
        lastShowingCheckAt = 0;
    }
    private void handleOutsideTouch() {
        if (!continuousMode || !continuousActive || tearingDown) return;
        if (continuousPhase == ContinuousPhase.WAIT_STABLE) {
            startStabilityWatch(false);
            return;
        }
        if (continuousPhase == ContinuousPhase.CAPTURING
                || continuousPhase == ContinuousPhase.TRANSLATING
                || continuousPhase == ContinuousPhase.SHOWING) {
            enterStabilityWatch();
        }
    }
    private void enterStabilityWatch() {
        if (!continuousMode || !continuousActive || tearingDown) return;
        boolean hadVisibleOverlay = overlay != null;
        cancelActiveWork();
        detachOverlay();
        showingBaseline = null;
        if (ball != null) ball.setVisibility(View.VISIBLE);
        continuousPhase = ContinuousPhase.WAIT_STABLE;
        startStabilityWatch(hadVisibleOverlay);
    }
    private void startStabilityWatch(boolean hadVisibleOverlay) {
        if (stabilityTick != null) main.removeCallbacks(stabilityTick);
        long now = SystemClock.uptimeMillis();
        stabilityAcceptSamplesAt = hadVisibleOverlay ? now + 30 : now;
        lastSampleCapturedAt = 0;
        CenterSimilarity.Sample fallback = (!hadVisibleOverlay && lastPageSample != null
                && lastLayoutWidth == width && lastLayoutHeight == height) ? lastPageSample : null;
        long watchToken = stabilityTracker.start(fallback);
        long startedAt = now;
        stabilityTick = new Runnable() {
            @Override public void run() {
                if (!continuousMode || !continuousActive || tearingDown || continuousPhase != ContinuousPhase.WAIT_STABLE) return;
                if (SystemClock.uptimeMillis() - startedAt >= 200
                        && lastPageSample != null && lastLayoutWidth == width && lastLayoutHeight == height) {
                    stabilityTracker.provideFallbackIfMissing(lastPageSample);
                }
                ContinuousStabilityTracker.Decision decision = stabilityTracker.onTick(watchToken);
                if (decision == ContinuousStabilityTracker.Decision.IGNORE) return;
                if (decision == ContinuousStabilityTracker.Decision.STABILIZED) {
                    onContinuousStabilized(stabilityTracker.latestSample());
                    return;
                }
                main.postDelayed(this, ContinuousStabilityTracker.POLL_INTERVAL_MS);
            }
        };
        main.postDelayed(stabilityTick, ContinuousStabilityTracker.INITIAL_DELAY_MS);
    }
    private void onContinuousStabilized(CenterSimilarity.Sample settledSample) {
        if (!continuousMode || !continuousActive || tearingDown) return;
        if (syncDisplay()) return;
        boolean unchanged = settledSample != null && lastPageSample != null
                && lastLayoutWidth == width && lastLayoutHeight == height && lastLayoutRotation == rotation
                && CenterSimilarity.isSamePage(lastPageSample, settledSample);
        if (unchanged) {
            restoreCachedContinuousLayout();
        } else {
            beginContinuousCapture();
        }
    }
    private void restoreCachedContinuousLayout() {
        if (lastLayout == null) {
            enterContinuousShowing(null);
            return;
        }
        detachOverlay();
        overlay = new TranslationOverlay(windowContext, () -> {});
        overlay.continuousMode(true);
        overlay.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        overlay.prepared(lastLayout);
        overlay.completed("");
        windows.addView(overlay, overlayParams(true));
        if (ball != null && ball.isAttachedToWindow()) {
            windows.removeViewImmediate(ball);
            windows.addView(ball, ballParams);
        }
        enterContinuousShowing(overlay);
    }
    private void pauseContinuousMode() {
        continuousActive = false;
        continuousPhase = ContinuousPhase.OFF;
        stabilityTracker.stop();
        if (stabilityTick != null) main.removeCallbacks(stabilityTick);
        invalidateContinuousCache();
        clearPage();
    }
    private CenterSimilarity.Sample samplePlaneCenter(Image.Plane plane, int frameWidth, int frameHeight) {
        int pad = Ui.dp(this, 6);
        int exL = ballParams != null ? ballParams.x - pad : 0;
        int exT = ballParams != null ? ballParams.y - pad : 0;
        int exR = ballParams != null ? ballParams.x + ballParams.width + pad : 0;
        int exB = ballParams != null ? ballParams.y + ballParams.height + pad : 0;
        return CenterSimilarity.sampleRgba(plane.getBuffer(), frameWidth, frameHeight,
                plane.getRowStride(), plane.getPixelStride(), exL, exT, exR, exB);
    }
    private CenterSimilarity.Sample sampleBitmapCenter(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        return CenterSimilarity.sampleGrid(bitmap.getWidth(), bitmap.getHeight(), 0, 0, 0, 0, bitmap::getPixel);
    }
    private void clearPage() {
        cancelActiveWork();
        detachOverlay();
        if(ball!=null) { ball.setVisibility(View.VISIBLE); if(!tearingDown) updateBallAppearance(); }
    }
    private void resize(int w,int h) {
        if(tearingDown || display==null) return;
        invalidateContinuousCache();
        boolean pending=!continuousMode && overlay!=null && screenshot==null && gate.current(captureRequestToken);
        // Resizing a surface can itself trigger another projection size callback.
        // Keep the user's pending capture alive until that surface has settled.
        if(continuousMode && continuousActive) {
            cancelActiveWork();
            detachOverlay();
        } else if(!pending) clearPage();
        try {
            replaceSurface(w,h);
            if(continuousMode && continuousActive) enterStabilityWatch();
            else if(pending) queueCapture(captureRequestToken,220);
            placeBall();
        } catch(Exception e) { toast("屏幕尺寸变化，请重新开启漫译"); stopSelf(); }
    }
    private void replaceSurface(int w,int h) {
        ImageReader next=newReader(w,h);
        try {
            // Pause mirroring before changing both logical and surface dimensions, so
            // the old surface's letterbox transform cannot bleed into the new reader.
            display.setSurface(null);
            display.resize(w,h,windowContext.getResources().getConfiguration().densityDpi);
            display.setSurface(next.getSurface());
        } catch(RuntimeException e) { next.close(); throw e; }
        ImageReader old=reader; reader=next; width=w; height=h;
        old.setOnImageAvailableListener(null,null); old.close();
    }
    private boolean syncDisplay() {
        if (tearingDown || display==null) return false;
        Point size=screenSize(); int currentRotation=windows.getDefaultDisplay().getRotation();
        boolean sizeChanged=size.x!=width || size.y!=height;
        if (!sizeChanged && currentRotation==rotation) return false;
        rotation=currentRotation;
        if (sizeChanged) resize(size.x,size.y);
        else {
            invalidateContinuousCache();
            if (continuousMode && continuousActive) enterStabilityWatch();
            else if (overlay!=null && screenshot==null && gate.current(captureRequestToken)) queueCapture(captureRequestToken,220);
            else clearPage(); // A 180-degree turn also invalidates the frozen frame.
        }
        placeBall();
        return true;
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        // Read after the window context has received its own configuration update.
        main.post(() -> syncDisplay());
    }
    @Override public void onDestroy() {
        tearingDown=true; running=false; continuousActive=false; continuousPhase=ContinuousPhase.OFF;
        stabilityTracker.stop(); invalidateContinuousCache(); clearPage(); gate.stop(); main.removeCallbacksAndMessages(null);
        if(displays!=null) displays.unregisterDisplayListener(displayListener);
        if(navigationRegistered) { unregisterReceiver(navigationReceiver); navigationRegistered=false; }
        TranslationTileService.refresh(this);
        if(ball!=null && ball.isAttachedToWindow()) windows.removeViewImmediate(ball);
        if(display!=null) display.release();
        if(reader!=null) reader.close();
        if(projection!=null) { projection.unregisterCallback(projectionCallback); projection.stop(); }
        // Native sessions release on the worker thread, so a running inference finishes first.
        auxWorker.shutdownNow();
        try { worker.execute(OcrEngine::release); } catch (java.util.concurrent.RejectedExecutionException ignored) { }
        worker.shutdown(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_LONG).show(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}

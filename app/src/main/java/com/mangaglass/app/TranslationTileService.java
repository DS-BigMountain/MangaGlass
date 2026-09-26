package com.mangaglass.app;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings toggles the capture service; screen-capture consent stays in a visible Activity. */
public final class TranslationTileService extends TileService {
    static final String START="com.mangaglass.app.QUICK_START";
    private static java.lang.ref.WeakReference<TranslationTileService> listening=new java.lang.ref.WeakReference<>(null);
    static void refresh(Context context) {
        TranslationTileService visible=listening.get();
        if(visible!=null) visible.update();
        try { requestListeningState(context,new ComponentName(context,TranslationTileService.class)); }
        catch(RuntimeException ignored) { /* Tile may be absent or unavailable on this system. */ }
    }
    @Override public void onStartListening() { super.onStartListening(); listening=new java.lang.ref.WeakReference<>(this); update(); }
    @Override public void onStopListening() { if(listening.get()==this) listening.clear(); super.onStopListening(); }
    @Override public void onDestroy() { if(listening.get()==this) listening.clear(); super.onDestroy(); }
    @Override public void onTileAdded() { super.onTileAdded(); update(); }
    private void update() {
        Tile tile=getQsTile(); if(tile==null) return;
        tile.setLabel("漫译"); tile.setState(CaptureService.running?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);
        tile.setContentDescription(CaptureService.running?"漫译已开启，点按停止":"漫译未开启，点按开启");
        if(Build.VERSION.SDK_INT>=29) tile.setSubtitle(CaptureService.running?"已开启":"未开启");
        tile.updateTile();
    }
    @Override public void onClick() {
        super.onClick();
        if(CaptureService.running) { stopService(new Intent(this,CaptureService.class)); return; }
        unlockAndRun(() -> {
            Intent intent=new Intent(this,MainActivity.class).setAction(START).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if(Build.VERSION.SDK_INT>=34) startActivityAndCollapse(PendingIntent.getActivity(this,14,intent,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            else launchOnOlderAndroid(intent);
        });
    }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private void launchOnOlderAndroid(Intent intent) {
        // Only reachable on Android 8–13, where the PendingIntent overload does not exist.
        startActivityAndCollapse(intent);
    }
}

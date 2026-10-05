package com.mangaglass.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

public final class MainActivity extends Activity {
    private static final int OVERLAY = 11, CAPTURE = 12, NOTIFICATIONS = 13;
    private Settings settings;
    private TextView status, statusHint, configuration, languageValue, usageValue;
    private Button start, stop;
    private int languageIndex;
    private boolean vision;
    private boolean quickStart;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        settings=new Settings(this); vision=settings.vision(); languageIndex=settings.languageIndex();
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
        LinearLayout page=Ui.column(this); page.setPadding(Ui.dp(this,22),Ui.dp(this,12),Ui.dp(this,22),Ui.dp(this,12)); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((v,insets) -> { v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
        LinearLayout header=new LinearLayout(this); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.addView(Ui.text(this,"漫译",25,Ui.INK,true),new LinearLayout.LayoutParams(0,-2,1));
        ImageButton gear=new ImageButton(this); gear.setImageDrawable(new UiIcon("settings")); gear.setPadding(Ui.dp(this,11),Ui.dp(this,11),Ui.dp(this,11),Ui.dp(this,11));
        gear.setBackgroundColor(Color.TRANSPARENT); gear.setContentDescription("打开 API 设置"); gear.setOnClickListener(v -> editApi()); header.addView(gear,new LinearLayout.LayoutParams(Ui.dp(this,46),Ui.dp(this,46))); page.addView(header);
        Ui.gap(page,12);
        LinearLayout state=new LinearLayout(this); state.setGravity(android.view.Gravity.CENTER_VERTICAL); state.setPadding(Ui.dp(this,16),Ui.dp(this,12),Ui.dp(this,16),Ui.dp(this,12)); state.setBackground(Ui.shape(Ui.SURFACE,10,this));
        View dot=new View(this); dot.setBackground(Ui.shape(0xff92959b,8,this)); LinearLayout.LayoutParams dotParams=new LinearLayout.LayoutParams(Ui.dp(this,14),Ui.dp(this,14)); dotParams.rightMargin=Ui.dp(this,16); state.addView(dot,dotParams);
        LinearLayout stateText=Ui.column(this); status=Ui.text(this,"",17,Ui.INK,true); statusHint=Ui.text(this,"",12,Ui.MUTED,false); stateText.addView(status); stateText.addView(statusHint); state.addView(stateText,new LinearLayout.LayoutParams(0,-2,1)); page.addView(state);
        Ui.gap(page,19); page.addView(Ui.text(this,"翻译设置",12,Ui.MUTED,false)); Ui.gap(page,4);
        RadioGroup modes=new RadioGroup(this);
        RadioButton local=new RadioButton(this); local.setId(View.generateViewId()); local.setText("本机识别 ＋ AI 翻译"); local.setTextSize(15); local.setTextColor(Ui.INK); local.setMinHeight(Ui.dp(this,44));
        RadioButton cloud=new RadioButton(this); cloud.setId(View.generateViewId()); cloud.setText("AI 识别 ＋ 翻译"); cloud.setTextSize(15); cloud.setTextColor(Ui.INK); cloud.setMinHeight(Ui.dp(this,44));
        modes.addView(local);
        TextView localHint=Ui.text(this,"在本机识别，仅发送文字进行翻译",12,Ui.MUTED,false); localHint.setPadding(Ui.dp(this,34),0,0,Ui.dp(this,5)); modes.addView(localHint);
        modes.addView(cloud);
        TextView cloudHint=Ui.text(this,"发送完整截图，需要支持图片的模型",12,Ui.MUTED,false); cloudHint.setPadding(Ui.dp(this,34),0,0,Ui.dp(this,10)); modes.addView(cloudHint);
        modes.check(vision?cloud.getId():local.getId()); page.addView(modes); Ui.divider(page);
        LinearLayout languageRow=Ui.row(this,"翻译语言","","language",this::chooseLanguage); languageValue=languageRow.findViewWithTag("subtitle"); page.addView(languageRow); Ui.divider(page);
        LinearLayout apiRow=Ui.row(this,"API 设置","","settings",this::editApi); configuration=apiRow.findViewWithTag("subtitle"); page.addView(apiRow); Ui.divider(page);
        LinearLayout usageRow=Ui.row(this,"Token 用量","","usage",() -> TokenUsageDialog.show(this)); usageValue=usageRow.findViewWithTag("subtitle"); page.addView(usageRow); Ui.divider(page);
        page.addView(Ui.row(this,"添加快捷开关","在控制中心开启或停止服务","tile",this::addQuickTile)); Ui.divider(page);
        modes.setOnCheckedChangeListener((group,id) -> { vision=id==cloud.getId(); settings.saveMode(languageIndex,vision); refresh(); });
        Ui.gap(page,18); start=Ui.button(this,"开启悬浮球",true); start.setOnClickListener(v -> begin()); page.addView(start,new LinearLayout.LayoutParams(-1,Ui.dp(this,50)));
        stop=Ui.button(this,"停止服务",false); stop.setOnClickListener(v -> { stopService(new Intent(this,CaptureService.class)); status.postDelayed(this::refresh,250); });
        LinearLayout.LayoutParams stopParams=new LinearLayout.LayoutParams(-1,Ui.dp(this,48)); stopParams.topMargin=Ui.dp(this,8); page.addView(stop,stopParams);
        Button help=Ui.button(this,"使用说明",false); help.setTextColor(Ui.MUTED); help.setBackgroundColor(Color.TRANSPARENT); help.setOnClickListener(v -> help()); page.addView(help,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));
        setContentView(scroll); refresh(); handleQuickStart(getIntent());
    }
    private void chooseLanguage() {
        new AlertDialog.Builder(this).setTitle("原文语言").setSingleChoiceItems(Settings.LANGUAGE_LABELS,languageIndex,(dialog,which) -> {
            languageIndex=which; settings.saveMode(which,vision); refresh(); dialog.dismiss();
        }).setNegativeButton("返回",null).show();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleQuickStart(intent); }
    private void handleQuickStart(Intent intent) {
        if(intent!=null && TranslationTileService.START.equals(intent.getAction())) {
            intent.setAction(null); quickStart=true; getWindow().getDecorView().post(this::begin);
        }
    }
    private void addQuickTile() {
        if(Build.VERSION.SDK_INT>=33) {
            try {
                getSystemService(android.app.StatusBarManager.class).requestAddTileService(
                        new android.content.ComponentName(this,TranslationTileService.class),"漫译",android.graphics.drawable.Icon.createWithResource(this,R.drawable.ic_tile),getMainExecutor(),
                        result -> toast(result==android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result==android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                                ? "快捷开关已添加，可在控制中心使用" : "未添加快捷开关，可在控制中心编辑开关列表"));
                return;
            } catch(RuntimeException ignored) { }
        }
        new AlertDialog.Builder(this).setTitle("添加快捷开关").setMessage("下拉打开控制中心，进入快捷开关编辑页面，将「漫译」添加到常用开关。点按开启悬浮球，再次点按停止服务。").setPositiveButton("知道了",null).show();
    }
    @Override protected void onResume() { super.onResume(); if (status != null) refresh(); }
    private void refresh() {
        languageValue.setText(Settings.LANGUAGE_LABELS[languageIndex].split(" · ")[0]+" → 简体中文");
        try { Settings.Profile p=settings.profile(vision); configuration.setText(p.ready()?p.model:"未配置"); }
        catch(Exception e) { configuration.setText("密钥无法读取，请重新配置"); }
        TokenUsageStore.Day usage=TokenUsageStore.get(this).query(java.time.LocalDate.now());
        usageValue.setText("今日 "+usage.display(usage.total,usage.totalKnown));
        status.setText(CaptureService.running?"已开启":"未开启");
        statusHint.setText(CaptureService.running?"可切换到漫画应用使用":"点击下方按钮开启悬浮球");
        start.setText(CaptureService.running?"返回漫画":"开启悬浮球"); stop.setVisibility(CaptureService.running?View.VISIBLE:View.GONE);
    }
    private void begin() {
        if (CaptureService.running) { moveTaskToBack(true); return; }
        try { if (!settings.profile(vision).ready()) { editApi(); return; } } catch (Exception e) { editApi(); return; }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            try { startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:" + getPackageName())),OVERLAY); }
            catch (Exception e) { toast("请在系统设置中允许漫译显示悬浮窗"); }
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked",false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS); return;
        }
        requestCapture();
    }
    private void requestCapture() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        // Full display coordinates are essential to place the translations on the original bubbles.
        Intent capture = Build.VERSION.SDK_INT >= 34 ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) : manager.createScreenCaptureIntent();
        startActivityForResult(capture,CAPTURE);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request,permissions,results); if (request == NOTIFICATIONS) requestCapture();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (request == OVERLAY) {
            refresh(); boolean allowed=android.provider.Settings.canDrawOverlays(this);
            if(quickStart && allowed) { quickStart=false; begin(); }
            else { quickStart=false; toast(allowed ? "已授权，再点开启悬浮球即可" : "未授权，可再次开启重试"); }
        }
        if (request == CAPTURE) quickStart=false;
        if (request == CAPTURE && result == RESULT_OK && data != null) {
            try {
                startForegroundService(new Intent(this,CaptureService.class).putExtra("resultCode",result).putExtra("resultData",data));
                moveTaskToBack(true);
            } catch (Exception e) { toast("启动失败，请回到前台后重新授权"); }
        } else if (request == CAPTURE) toast("已取消屏幕捕获，未开始翻译");
    }
    private ApiSettingsDialog apiDialog;
    private void editApi() {
        if (apiDialog != null) apiDialog.close();
        apiDialog = new ApiSettingsDialog(this, settings, vision, this::refresh);
        apiDialog.show();
    }
    private void help() {
        String instructions="1. 选择原文语言和识别方式，在 API 设置中保存服务地址、模型和 Key。两种识别方式分别配置。\n\n"
                +"2. 点击「开启悬浮球」，允许悬浮窗和屏幕捕获。授权时选择整个屏幕。\n\n"
                +"3. 在漫画页面点击悬浮球，翻译当前画面；再次点击退出，退出后才可滑动或翻页。\n\n"
                +"4. 点击球外画面切换原图和译文。原图预览带细光圈，页面仍保持锁定。\n\n"
                +"5. 返回桌面、打开最近任务或锁屏时，自动退出当前翻译。长按悬浮球可停止服务。系统结束捕获后需要重新授权。\n\n"
                +"6. 「添加快捷开关」可将漫译放入控制中心，点按开启，再次点按停止。\n\n"
                +"7. 澎湃 OS 若切换应用后悬浮球消失，请检查后台弹出界面权限和省电限制。\n\n"
                +"本机识别只发送文字；AI 识别发送完整截图。截图和原文不保存，API Key 加密保存。受保护画面可能无法截取。";
        if(!settings.lastTiming().isEmpty()) instructions+="\n\n最近一次翻译\n"+settings.lastTiming();
        new AlertDialog.Builder(this).setTitle("使用说明").setMessage(instructions).setPositiveButton("知道了",null).show();
    }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() { if (apiDialog != null) apiDialog.close(); super.onDestroy(); }
}


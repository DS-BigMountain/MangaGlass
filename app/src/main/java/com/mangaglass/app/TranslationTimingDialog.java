package com.mangaglass.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class TranslationTimingDialog {
    private TranslationTimingDialog() { }
    static String duration(long millis) {
        if(millis<0) return "未执行";
        return millis<1000 ? millis+" 毫秒" : String.format(Locale.ROOT,"%.3f 秒",millis/1000.0);
    }
    static void show(Activity activity) {
        TranslationTimingStore store=TranslationTimingStore.get(activity);
        LinearLayout page=Ui.column(activity); int pad=Ui.dp(activity,20); page.setPadding(pad,pad,pad,pad);
        ScrollView scroll=new ScrollView(activity); scroll.addView(page);
        page.addView(Ui.text(activity,"保存最近 30 次；点击记录展开各环节。仅保留发生时间和耗时，不保存图片、原文或译文。",13,Ui.MUTED,false));
        Ui.gap(page,12);
        page.addView(Ui.text(activity,"总耗时：点击翻译至译文首帧绘制完成，未完成记录计到中止。AI 往返分为连接、发送、等待和接收；等待包含服务端处理与网络传输，无法直接测出纯推理耗时。本机合计包含处理和等待。",12,Ui.MUTED,false));
        Ui.gap(page,16);
        LinearLayout records=Ui.column(activity); page.addView(records);
        SimpleDateFormat dates=new SimpleDateFormat("MM-dd HH:mm:ss",Locale.getDefault());
        Runnable refresh=() -> {
            records.removeAllViews();
            List<TranslationTiming.Record> recent=store.recent();
            if(recent.isEmpty()) { records.addView(Ui.text(activity,"暂无记录，完成一次截图翻译后可在这里查看。",15,Ui.INK,false)); return; }
            for(int i=0;i<recent.size();i++) {
                TranslationTiming.Record record=recent.get(i);
                LinearLayout card=Ui.column(activity); card.setPadding(pad,pad/2,pad,pad/2); card.setBackground(Ui.shape(Ui.SURFACE,10,activity));
                Button heading=Ui.button(activity,dates.format(new Date(record.startedAtMillis))+"\n"+(record.completed()?"总计 ":"未完成 · ")+duration(record.totalMillis),false);
                heading.setAllCaps(false); card.addView(heading,new LinearLayout.LayoutParams(-1,-2));
                card.addView(Ui.text(activity,"本机 "+duration(record.localMillis())+"\nAI 请求往返 "+duration(record.duration(TranslationTiming.Stage.AI_REQUEST)),13,Ui.MUTED,false));
                TextView detail=Ui.text(activity,"",14,Ui.INK,false); detail.setLineSpacing(Ui.dp(activity,5),1);
                StringBuilder text=new StringBuilder();
                for(TranslationTiming.Stage stage:TranslationTiming.Stage.values()) {
                    if(text.length()>0) text.append('\n');
                    text.append(stage.title).append("：").append(duration(record.duration(stage)));
                    if(stage==TranslationTiming.Stage.AI_REQUEST && record.duration(stage)>=0) {
                        if(record.hasNetworkDetails()) {
                            for(TranslationTiming.NetworkPhase phase:TranslationTiming.NetworkPhase.values())
                                text.append("\n  · ").append(phase.title).append("：").append(duration(record.duration(phase)));
                        } else text.append("\n  · 此记录未采集网络细分");
                    }
                }
                detail.setText(text); detail.setTextIsSelectable(true); detail.setPadding(0,pad/2,0,pad/2);
                detail.setVisibility(i==0 ? View.VISIBLE : View.GONE); card.addView(detail);
                heading.setOnClickListener(v -> detail.setVisibility(detail.getVisibility()==View.VISIBLE ? View.GONE : View.VISIBLE));
                records.addView(card); Ui.gap(records,10);
            }
        };
        refresh.run();
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("翻译耗时 · 最近 30 次").setView(scroll)
                .setPositiveButton("关闭",null).setNeutralButton("清空记录",null).show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> { store.clear(); refresh.run(); });
    }
}

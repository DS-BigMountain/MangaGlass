package com.mangaglass.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.widget.*;
import java.time.LocalDate;

final class TokenUsageDialog {
    private TokenUsageDialog() {}
    static void show(Activity activity) {
        TokenUsageStore store=TokenUsageStore.get(activity);
        LinearLayout page=Ui.column(activity); int pad=Ui.dp(activity,20); page.setPadding(pad,pad,pad,pad);
        ScrollView scroll=new ScrollView(activity); scroll.addView(page);
        LocalDate[] selected={LocalDate.now()};
        Button date=Ui.button(activity,"",false); page.addView(date);
        LinearLayout navigation=new LinearLayout(activity);
        Button previous=Ui.button(activity,"前一天",false),next=Ui.button(activity,"后一天",false);
        navigation.addView(previous,new LinearLayout.LayoutParams(0,-2,1)); navigation.addView(next,new LinearLayout.LayoutParams(0,-2,1)); page.addView(navigation);
        TextView data=Ui.text(activity,"",16,Ui.INK,false); data.setTextIsSelectable(true); Ui.gap(page,16); page.addView(data);
        Ui.gap(page,18); page.addView(Ui.text(activity,"按请求开始时的本地日期统计，仅包含本应用的截图翻译与 API 翻译测试。获取模型列表、切换原图预览不计入。\n\n数字来自接口返回的 usage；未返回或失败的请求单独标明，不估算费用。总量按接口报告显示。\n\n记录保存在本机，启用此版本前的用量无法补查。",12,Ui.MUTED,false));
        Runnable refresh=() -> {
            date.setText(selected[0]+"  ·  选择日期"); next.setEnabled(selected[0].isBefore(LocalDate.now()));
            TokenUsageStore.Day day=store.query(selected[0]);
            data.setText("请求次数："+day.requests+"\n\n输入 Token："+day.display(day.input,day.inputKnown)+"\n\n输出 Token："+day.display(day.output,day.outputKnown)+"\n\n总 Token："+day.display(day.total,day.totalKnown));
        };
        previous.setOnClickListener(v -> { selected[0]=selected[0].minusDays(1); refresh.run(); });
        next.setOnClickListener(v -> { selected[0]=selected[0].plusDays(1); refresh.run(); });
        date.setOnClickListener(v -> {
            DatePickerDialog picker=new DatePickerDialog(activity,(view,year,month,day) -> { selected[0]=LocalDate.of(year,month+1,day); refresh.run(); },selected[0].getYear(),selected[0].getMonthValue()-1,selected[0].getDayOfMonth());
            picker.getDatePicker().setMaxDate(System.currentTimeMillis()); picker.show();
        });
        refresh.run(); new AlertDialog.Builder(activity).setTitle("Token 用量").setView(scroll).setPositiveButton("关闭",null).show();
    }
}

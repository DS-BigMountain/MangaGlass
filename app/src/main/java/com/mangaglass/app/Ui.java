package com.mangaglass.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.WHITE, INK = 0xff242529, GREEN = 0xff343539, MUTED = 0xff777a80, SURFACE = 0xfff1f2f4, LINE = 0xffe7e8eb;
    static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static GradientDrawable shape(int color, float radius, Context context) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(context, radius)); return d;
    }
    static TextView text(Context c, String value, int size, int color, boolean bold) {
        TextView v = new TextView(c); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setLineSpacing(dp(c, 3), 1); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    static LinearLayout card(Context c, int color) {
        LinearLayout v = column(c); v.setPadding(dp(c,20),dp(c,20),dp(c,20),dp(c,20)); v.setBackground(shape(color,22,c)); return v;
    }
    static void gap(LinearLayout parent, int dp) { View v = new View(parent.getContext()); parent.addView(v,new LinearLayout.LayoutParams(1,dp(parent.getContext(),dp))); }
    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c); b.setText(label); b.setAllCaps(false); b.setTextSize(15); b.setTextColor(primary ? Color.WHITE : GREEN);
        b.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x18000000),shape(primary ? GREEN : SURFACE,9,c),null));
        b.setStateListAnimator(null); b.setMinHeight(dp(c,48)); b.setPadding(dp(c,12),0,dp(c,12),0); return b;
    }
    static void divider(LinearLayout parent) {
        View line=new View(parent.getContext()); line.setBackgroundColor(LINE); parent.addView(line,new LinearLayout.LayoutParams(-1,dp(parent.getContext(),1)));
    }
    static LinearLayout row(Context c,String title,String subtitle,String icon,Runnable action) {
        LinearLayout row=new LinearLayout(c); row.setGravity(android.view.Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(c,64));
        row.setPadding(0,dp(c,9),0,dp(c,9));
        android.widget.ImageView glyph=new android.widget.ImageView(c); glyph.setImageDrawable(new UiIcon(icon)); glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams imageParams=new LinearLayout.LayoutParams(dp(c,23),dp(c,23)); imageParams.rightMargin=dp(c,16); row.addView(glyph,imageParams);
        LinearLayout labels=column(c); labels.addView(text(c,title,16,INK,false));
        TextView value=text(c,subtitle,12,MUTED,false); value.setTag("subtitle"); labels.addView(value);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=text(c,"›",25,MUTED,false); arrow.setPadding(dp(c,10),0,dp(c,2),0); row.addView(arrow);
        row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x10000000),shape(Color.WHITE,0,c),null));
        row.setOnClickListener(v -> action.run()); row.setFocusable(true); return row;
    }
}

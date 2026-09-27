package com.flixtown.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(8,9,13), CARD = Color.rgb(24,28,37), RED = Color.rgb(215,37,54);
    static int dp(Context c, int value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(1); return v; }
    static LinearLayout row(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(0); return v; }
    static TextView text(Context c, String text, int sp) { TextView v = new TextView(c); v.setText(text); v.setTextSize(sp); v.setTextColor(Color.WHITE); return v; }
    static GradientDrawable rounded(int color, int radius, Context c) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radius)); return d; }
    static Button button(Context c, String label) {
        Button b = new Button(c); b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(17);
        b.setAllCaps(false); b.setBackground(rounded(CARD, 8, c));
        b.setOnFocusChangeListener((v,focused) -> {
            v.setBackground(rounded(focused ? RED : CARD, 8, c));
            v.animate().scaleX(focused ? 1.07f : 1f).scaleY(focused ? 1.07f : 1f).setDuration(110).start();
        }); return b;
    }
    static TextView heading(Context c, String text, int size) { TextView v = text(c,text,size); v.setTypeface(null,Typeface.BOLD); return v; }
    static void pad(View v, Context c, int l,int t,int r,int b) { v.setPadding(dp(c,l),dp(c,t),dp(c,r),dp(c,b)); }
    static void center(View v) { if (v instanceof TextView) ((TextView)v).setGravity(Gravity.CENTER); }
}

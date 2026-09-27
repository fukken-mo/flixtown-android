package com.flixtown.tv;

import android.content.Context;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    interface Choice { void select(int index); }
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
            v.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f).setDuration(100).start();
        }); return b;
    }
    static void options(Activity activity,String title,String[] labels,Choice action) {
        Dialog dialog=new Dialog(activity);
        LinearLayout body=column(activity);pad(body,activity,28,25,28,26);
        body.setBackground(rounded(0xFF191B23,19,activity));
        TextView heading=heading(activity,title,24);body.addView(heading);
        TextView hint=text(activity,"Select with your remote",14);hint.setTextColor(0xFFA9ABB5);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.bottomMargin=dp(activity,16);body.addView(hint,hp);
        for(int i=0;i<labels.length;i++){
            final int index=i;
            TextView option=text(activity,labels[i],17);option.setGravity(Gravity.CENTER_VERTICAL);
            option.setFocusable(true);pad(option,activity,18,0,12,0);
            option.setBackground(rounded(0xFF282A34,9,activity));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(activity,52));p.bottomMargin=dp(activity,6);body.addView(option,p);
            option.setOnFocusChangeListener((v,focused)->v.setBackground(rounded(focused?RED:0xFF282A34,9,activity)));
            option.setOnClickListener(v->{dialog.dismiss();action.select(index);});
            if(i==0)option.post(option::requestFocus);
        }
        dialog.setContentView(body);dialog.show();
        Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(Math.min(dp(activity,490),activity.getResources().getDisplayMetrics().widthPixels-dp(activity,80)),WindowManager.LayoutParams.WRAP_CONTENT);}
    }
    static TextView heading(Context c, String text, int size) { TextView v = text(c,text,size); v.setTypeface(null,Typeface.BOLD); return v; }
    static void pad(View v, Context c, int l,int t,int r,int b) { v.setPadding(dp(c,l),dp(c,t),dp(c,r),dp(c,b)); }
    static void center(View v) { if (v instanceof TextView) ((TextView)v).setGravity(Gravity.CENTER); }
}

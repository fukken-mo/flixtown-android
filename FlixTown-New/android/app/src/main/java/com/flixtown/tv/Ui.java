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
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

final class Ui {
    interface Choice { void select(int index); }
    static final int BG = Color.rgb(8,9,13), CARD = Color.rgb(24,28,37), RED = Color.rgb(215,37,54);
    static int dp(Context c, int value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(1); return v; }
    static LinearLayout row(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(0); return v; }
    static TextView text(Context c, String text, int sp) { TextView v = new TextView(c); v.setText(text); v.setTextSize(sp); v.setTextColor(Color.WHITE); return v; }
    static GradientDrawable rounded(int color, int radius, Context c) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radius)); return d; }
    static GradientDrawable glass(Context c,int radius) {
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xB23A3944,0xA61C1D27,0xB00F1018});
        d.setCornerRadius(dp(c,radius));d.setStroke(dp(c,1),0x66C6BBC5);return d;
    }
    static GradientDrawable panel(Context c,int radius) {
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xF12C2932,0xF0181922,0xF20C0E14});
        d.setCornerRadius(dp(c,radius));d.setStroke(dp(c,1),0x888B727B);return d;
    }
    static GradientDrawable focusSurface(Context c,boolean focused){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            focused?new int[]{0xFF8B263B,0xFF4A1D2B,0xFF25151E}:new int[]{0xF23A3440,0xF221202A,0xF013151D});
        d.setCornerRadius(dp(c,13));d.setStroke(dp(c,focused?2:1),focused?0xFFFF8295:0xFF655863);return d;
    }
    static GradientDrawable posterBorder(Context c,boolean focused){
        GradientDrawable d=new GradientDrawable();d.setColor(0xFF10131A);d.setCornerRadius(dp(c,9));
        d.setStroke(dp(c,1),focused?0xFFFF899A:0xFF343842);return d;
    }
    static GradientDrawable posterGlow(Context c){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0x99FF426B,0xDDE52F55,0x99A51E50});
        d.setCornerRadius(dp(c,17));return d;
    }
    static GradientDrawable navBackground(Context c,boolean focused,boolean selected){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            focused?new int[]{0xDB692335,0xDC281B28}:selected?new int[]{0xAA391C29,0xB2181B23}:new int[]{0x00131920,0x00131920});
        d.setCornerRadius(dp(c,12));
        if(focused)d.setStroke(dp(c,1),0xFFE64D68);
        return d;
    }
    static Button button(Context c, String label) {
        Button b = new Button(c); b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(17);
        b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setPadding(dp(c,20),0,dp(c,20),0);b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);b.setBackground(focusSurface(c,false));
        b.setOnFocusChangeListener((v,focused) -> {
            v.setBackground(focusSurface(c,focused));
            v.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f).setDuration(100).start();
        }); return b;
    }
    static void options(Activity activity,String title,String[] labels,Choice action) {
        Dialog dialog=new Dialog(activity);
        LinearLayout body=column(activity);pad(body,activity,24,22,24,20);
        body.setBackground(panel(activity,20));
        View accent=new View(activity);accent.setBackground(rounded(RED,3,activity));
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(dp(activity,36),dp(activity,3));ap.bottomMargin=dp(activity,13);body.addView(accent,ap);
        TextView heading=heading(activity,title,22);body.addView(heading);
        TextView hint=text(activity,"Select with your remote",14);hint.setTextColor(0xFFA9ABB5);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=dp(activity,4);hp.bottomMargin=dp(activity,16);body.addView(hint,hp);
        for(int i=0;i<labels.length;i++){
            final int index=i;
            TextView option=text(activity,labels[i],16);option.setGravity(Gravity.CENTER_VERTICAL);
            option.setFocusable(true);pad(option,activity,18,0,12,0);
            option.setBackground(focusSurface(activity,false));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(activity,45));p.bottomMargin=dp(activity,7);body.addView(option,p);
            option.setOnFocusChangeListener((v,focused)->{v.setBackground(focusSurface(activity,focused));v.animate().scaleX(focused?1.025f:1f).scaleY(focused?1.025f:1f).setDuration(110).start();});
            option.setOnClickListener(v->{dialog.dismiss();action.select(index);});
            if(i==0)option.post(option::requestFocus);
        }
        dialog.setContentView(body);dialog.show();
        Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(Math.min(dp(activity,375),activity.getResources().getDisplayMetrics().widthPixels-dp(activity,100)),WindowManager.LayoutParams.WRAP_CONTENT);}
    }
    static void picker(Activity activity,String title,String[] labels,int selected,Choice action){
        Dialog dialog=new Dialog(activity);
        LinearLayout body=column(activity);pad(body,activity,22,18,22,16);body.setBackground(panel(activity,18));
        TextView heading=heading(activity,title,23);body.addView(heading);
        TextView hint=text(activity,"Select with your remote",13);hint.setTextColor(0xFFB6B4BD);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=dp(activity,5);hp.bottomMargin=dp(activity,15);body.addView(hint,hp);
        RecyclerView list=new RecyclerView(activity);LinearLayoutManager manager=new LinearLayoutManager(activity);list.setLayoutManager(manager);
        list.setItemAnimator(null);list.setVerticalScrollBarEnabled(false);
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>(){
            @Override public int getItemCount(){return labels.length;}
            @Override public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent,int type){
                TextView item=text(activity,"",16);item.setGravity(Gravity.CENTER_VERTICAL);item.setFocusable(true);
                item.setSingleLine(true);item.setEllipsize(android.text.TextUtils.TruncateAt.END);
                pad(item,activity,16,0,16,0);
                RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(-1,dp(activity,48));params.bottomMargin=dp(activity,5);item.setLayoutParams(params);
                return new RecyclerView.ViewHolder(item){};
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder,int position){
                TextView item=(TextView)holder.itemView;item.setText(labels[position]);
                item.setBackground(navBackground(activity,false,position==selected));
                item.setOnFocusChangeListener((v,focused)->v.setBackground(navBackground(activity,focused,position==selected)));
                item.setOnClickListener(v->{dialog.dismiss();action.select(position);});
            }
        });
        body.addView(list,new LinearLayout.LayoutParams(-1,0,1));dialog.setContentView(body);dialog.show();
        Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(Math.min(dp(activity,465),activity.getResources().getDisplayMetrics().widthPixels-dp(activity,110)),
                Math.min(dp(activity,440),activity.getResources().getDisplayMetrics().heightPixels-dp(activity,110)));}
        manager.scrollToPositionWithOffset(Math.max(0,selected),dp(activity,105));
        list.post(()->{RecyclerView.ViewHolder holder=list.findViewHolderForAdapterPosition(selected);
            if(holder!=null)holder.itemView.requestFocus();else list.requestFocus();});
    }
    static TextView heading(Context c, String text, int size) { TextView v = text(c,text,size); v.setTypeface(null,Typeface.BOLD); return v; }
    static void pad(View v, Context c, int l,int t,int r,int b) { v.setPadding(dp(c,l),dp(c,t),dp(c,r),dp(c,b)); }
    static void center(View v) { if (v instanceof TextView) ((TextView)v).setGravity(Gravity.CENTER); }
}

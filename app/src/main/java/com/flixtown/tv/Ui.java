package com.flixtown.tv;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Flix Town look and shared TV widgets.
 *
 * Colours are sampled from the Flix Town logo (res/drawable-nodpi/flix_logo.png): the lit face of
 * the red marquee letters is #AB3A39 and the gold rim is #B99254. Brighter tones keep the same hue
 * so focus stays readable on a dark TV. Surfaces are pre-drawn gradients with a hairline edge, never
 * live blur, so they cost nothing on Chromecast-class hardware.
 */
final class Ui {
    interface Choice { void select(int index); }

    static final int BG=0xFF08080B;
    static final int ACCENT=0xFFAB3A39;        // logo letter face
    static final int ACCENT_FOCUS=0xFFB53231;  // filled focus state
    static final int GLOW=0xFFCE4B4A;          // poster glow / focus ring
    static final int GOLD=0xFFB99254;          // logo rim, used sparingly (ratings)
    static final int TEXT=0xFFF4F1EE,TEXT_2=0xFFBDB7B2,TEXT_3=0xFF8C8782;
    static final int HAIRLINE=0x2EFFFFFF;
    /** Kept for older call sites. */
    static final int RED=ACCENT,CARD=0xFF15161C;

    static int dp(Context c, int value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    static int safeX(Context c){return Math.max(dp(c,24),Math.round(c.getResources().getDisplayMetrics().widthPixels*.05f));}
    static int safeY(Context c){return Math.max(dp(c,16),Math.round(c.getResources().getDisplayMetrics().heightPixels*.05f));}
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    static LinearLayout row(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    static TextView text(Context c, String text, int sp) { TextView v = new TextView(c); v.setText(text); v.setTextSize(sp); v.setTextColor(TEXT); return v; }
    static TextView heading(Context c, String text, int size) { TextView v = text(c,text,size); v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return v; }
    static void pad(View v, Context c, int l,int t,int r,int b) { v.setPadding(dp(c,l),dp(c,t),dp(c,r),dp(c,b)); }
    static void center(View v) { if (v instanceof TextView) ((TextView)v).setGravity(Gravity.CENTER); }
    static GradientDrawable rounded(int color, int radius, Context c) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radius)); return d; }

    /** Dark smoked-glass panel: vertical gradient plus a 1dp light edge. */
    static GradientDrawable glass(Context c,int radius) {
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xF0201E24,0xF2141317,0xF40E0D11});
        d.setCornerRadius(dp(c,radius));d.setStroke(dp(c,1),HAIRLINE);return d;
    }
    static GradientDrawable panel(Context c,int radius){return glass(c,radius);}
    /** Pill background for buttons and list rows. */
    static GradientDrawable pill(Context c,boolean focused,boolean selected,int radius){
        GradientDrawable d=new GradientDrawable();d.setCornerRadius(dp(c,radius));
        if(focused){d.setColor(ACCENT_FOCUS);d.setStroke(dp(c,1),0x66FFFFFF);}
        else if(selected){d.setColor(0x33AB3A39);d.setStroke(dp(c,1),0x55CE4B4A);}
        else{d.setColor(0x1AFFFFFF);d.setStroke(dp(c,1),HAIRLINE);}
        return d;
    }
    static GradientDrawable focusSurface(Context c,boolean focused){return pill(c,focused,false,10);}

    /** Standard TV button. Callers set the height (48–52dp); width may be WRAP_CONTENT. */
    static Button button(Context c, String label) {
        Button b = new Button(c); b.setText(label); b.setTextColor(TEXT); b.setTextSize(17);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setStateListAnimator(null);b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c,24),0,dp(c,24),0);b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);b.setFocusable(true);b.setFocusableInTouchMode(true);b.setClickable(true);
        GradientDrawable normal=pill(c,false,false,24),focused=pill(c,true,false,24);
        b.setBackground(normal);
        b.setOnFocusChangeListener((v,f)->{v.setBackground(f?focused:normal);
            v.animate().scaleX(f?1.04f:1f).scaleY(f?1.04f:1f).setDuration(120).start();});
        return b;
    }

    /* ---------------- Dialogs ---------------- */

    private static Dialog newDialog(Activity activity){
        Dialog dialog=new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return dialog;
    }
    private static void showWindow(Activity activity,Dialog dialog,int widthDp,boolean fixedHeight,int maxHeightDp){
        dialog.show();
        Window window=dialog.getWindow();if(window==null)return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.72f);
        int screenW=activity.getResources().getDisplayMetrics().widthPixels,screenH=activity.getResources().getDisplayMetrics().heightPixels;
        int width=Math.min(dp(activity,widthDp),screenW-2*safeX(activity));
        int height=fixedHeight?Math.min(dp(activity,maxHeightDp),screenH-2*safeY(activity)):WindowManager.LayoutParams.WRAP_CONTENT;
        window.setLayout(width,height);
    }
    private static LinearLayout sheet(Activity activity,String title,String message){
        LinearLayout body=column(activity);pad(body,activity,32,26,32,26);body.setBackground(glass(activity,18));
        View accent=new View(activity);accent.setBackground(rounded(ACCENT,2,activity));
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(dp(activity,32),dp(activity,3));ap.bottomMargin=dp(activity,14);body.addView(accent,ap);
        TextView heading=heading(activity,title,23);heading.setMaxLines(2);heading.setEllipsize(TextUtils.TruncateAt.END);body.addView(heading);
        if(message!=null && !message.isEmpty()){
            TextView m=text(activity,message,16);m.setTextColor(TEXT_2);m.setLineSpacing(0,1.15f);m.setMaxLines(5);m.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.topMargin=dp(activity,8);body.addView(m,mp);
        }
        return body;
    }

    /**
     * Compact confirmation or choice. Two or three short choices sit side by side (Exit, Resume);
     * longer lists stack. {@code focusIndex} is focused when the dialog opens.
     * {@code onCancel} runs for Back (may be null).
     */
    static Dialog dialog(Activity activity,String title,String message,String[] labels,int focusIndex,Choice action,Runnable onCancel){
        Dialog dialog=newDialog(activity);
        LinearLayout body=sheet(activity,title,message);
        int longest=0;for(String l:labels)longest=Math.max(longest,l.length());
        boolean horizontal=labels.length<=3 && longest<=20;
        LinearLayout buttons=horizontal?row(activity):column(activity);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=dp(activity,22);body.addView(buttons,bp);
        Button focus=null;final boolean[] chosen={false};
        for(int i=0;i<labels.length;i++){
            final int index=i;
            Button b=button(activity,labels[i]);b.setTextSize(17);
            LinearLayout.LayoutParams p=horizontal?new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(activity,48))
                :new LinearLayout.LayoutParams(-1,dp(activity,48));
            if(horizontal){if(i>0)p.leftMargin=dp(activity,12);b.setMinWidth(dp(activity,120));}
            else if(i>0)p.topMargin=dp(activity,8);
            buttons.addView(b,p);
            b.setOnClickListener(v->{chosen[0]=true;dialog.dismiss();action.select(index);});
            if(i==Math.max(0,Math.min(focusIndex,labels.length-1)))focus=b;
        }
        dialog.setContentView(body);
        dialog.setOnCancelListener(d->{if(!chosen[0] && onCancel!=null)onCancel.run();});
        showWindow(activity,dialog,horizontal?520:470,false,0);
        if(focus!=null){Button f=focus;f.post(f::requestFocus);}
        return dialog;
    }
    static Dialog options(Activity activity,String title,String[] labels,Choice action){
        return dialog(activity,title,null,labels,0,action,null);
    }
    static Dialog message(Activity activity,String title,String message){
        return dialog(activity,title,message,new String[]{"OK"},0,i->{},null);
    }

    /** Scrollable single-choice list (sort, categories, seasons, audio, subtitles). */
    static Dialog picker(Activity activity,String title,String[] labels,int selected,Choice action){
        Dialog dialog=newDialog(activity);
        LinearLayout body=sheet(activity,title,null);pad(body,activity,26,22,26,20);
        RecyclerView list=new RecyclerView(activity);list.setClipToPadding(false);list.setClipChildren(false);
        list.setPadding(0,dp(activity,6),0,dp(activity,4));
        LinearLayoutManager manager=new LinearLayoutManager(activity);list.setLayoutManager(manager);
        list.setItemAnimator(null);list.setVerticalScrollBarEnabled(false);
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>(){
            @Override public int getItemCount(){return labels.length;}
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent,int type){
                TextView item=text(activity,"",17);item.setGravity(Gravity.CENTER_VERTICAL);item.setFocusable(true);item.setFocusableInTouchMode(true);item.setClickable(true);
                item.setSingleLine(true);item.setEllipsize(TextUtils.TruncateAt.END);
                pad(item,activity,18,0,18,0);
                RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(-1,dp(activity,46));params.bottomMargin=dp(activity,6);item.setLayoutParams(params);
                return new RecyclerView.ViewHolder(item){};
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder,int position){
                TextView item=(TextView)holder.itemView;boolean isSelected=position==selected;
                item.setText(isSelected?"✓  "+labels[position]:labels[position]);
                item.setTextColor(isSelected?TEXT:TEXT_2);
                item.setBackground(pill(activity,item.isFocused(),isSelected,10));
                item.setOnFocusChangeListener((v,focused)->{v.setBackground(pill(activity,focused,isSelected,10));
                    ((TextView)v).setTextColor(focused||isSelected?TEXT:TEXT_2);});
                item.setOnClickListener(v->{dialog.dismiss();action.select(position);});
            }
        });
        int rows=Math.min(labels.length,7);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(activity,rows*52+10));lp.topMargin=dp(activity,12);
        body.addView(list,lp);dialog.setContentView(body);
        showWindow(activity,dialog,470,false,0);
        manager.scrollToPositionWithOffset(Math.max(0,selected),dp(activity,rows>3?104:0));
        // Focus the current choice once it is laid out, so one OK press confirms the highlighted row.
        afterLayout(list,()->{RecyclerView.ViewHolder holder=list.findViewHolderForAdapterPosition(Math.max(0,selected));
            if(holder!=null)holder.itemView.requestFocus();else list.requestFocus();});
        return dialog;
    }

    /** Runs once after the view's next layout pass (for focusing grid items that are not laid out yet). */
    static void afterLayout(View view,Runnable action){
        view.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener(){
            @Override public void onGlobalLayout(){view.getViewTreeObserver().removeOnGlobalLayoutListener(this);action.run();}
        });
        view.requestLayout();
    }
    static boolean isSelect(int key){return key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER;}
    static int alpha(int color,float a){return (Math.round(a*255)<<24)|(color&0x00FFFFFF);}
    static int textOn(){return Color.WHITE;}
}

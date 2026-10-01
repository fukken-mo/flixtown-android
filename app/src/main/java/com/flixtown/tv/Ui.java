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
    /* Buttons: dark charcoal surfaces, a light hairline, modest corners. Red marks focus; a solid red
       fill is only for the one primary action on a screen. */
    static final int SURFACE=0xFF1D1C21,SURFACE_EDGE=0x33FFFFFF,FOCUS_FILL=0xFF3B1C1E,PRIMARY_FOCUS=0xFFC4423F;
    static final int BUTTON_RADIUS=10,HALO=3;
    static final int SECONDARY=0,PRIMARY=1,SELECTED=2;
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
    /** Background for list rows and pickers (no halo): charcoal, red-tinted with a red edge when focused. */
    static GradientDrawable pill(Context c,boolean focused,boolean selected,int radius){
        GradientDrawable d=new GradientDrawable();d.setCornerRadius(dp(c,Math.min(radius,12)));
        if(focused){d.setColor(FOCUS_FILL);d.setStroke(dp(c,2),GLOW);}
        else if(selected){d.setColor(0xFF2A1B1D);d.setStroke(dp(c,1),0x99CE4B4A);}
        else{d.setColor(SURFACE);d.setStroke(dp(c,1),SURFACE_EDGE);}
        return d;
    }
    static GradientDrawable focusSurface(Context c,boolean focused){return pill(c,focused,false,10);}

    /**
     * Button background as one state list (no swapping on focus): the surface sits {@link #HALO}dp
     * inside the view, and when focused a faint red ring fills that margin as a restrained glow.
     * Plain shapes only (no blur or shadow layers), so it is cheap on TV sticks.
     */
    static android.graphics.drawable.Drawable buttonBackground(Context c,int kind){
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},haloed(c,kind,true));
        states.addState(new int[]{},haloed(c,kind,false));
        states.setExitFadeDuration(90);
        return states;
    }
    private static android.graphics.drawable.Drawable haloed(Context c,int kind,boolean focused){
        GradientDrawable surface=new GradientDrawable();surface.setCornerRadius(dp(c,BUTTON_RADIUS));
        if(kind==PRIMARY){surface.setColor(focused?PRIMARY_FOCUS:ACCENT);surface.setStroke(dp(c,focused?2:1),focused?0xE6FFFFFF:0x33FFFFFF);}
        else if(focused){surface.setColor(FOCUS_FILL);surface.setStroke(dp(c,2),GLOW);}
        else if(kind==SELECTED){surface.setColor(0xFF2A1B1D);surface.setStroke(dp(c,1),0x99CE4B4A);}
        else{surface.setColor(SURFACE);surface.setStroke(dp(c,1),SURFACE_EDGE);}
        GradientDrawable halo=new GradientDrawable();halo.setCornerRadius(dp(c,BUTTON_RADIUS+HALO));
        halo.setColor(focused?0x3DCE4B4A:0x00000000);
        android.graphics.drawable.LayerDrawable layers=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{halo,surface});
        int h=dp(c,HALO);layers.setLayerInset(1,h,h,h,h);
        return layers;
    }
    static android.content.res.ColorStateList buttonText(int kind){
        return new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused},{}},
            new int[]{Color.WHITE,kind==PRIMARY?Color.WHITE:0xFFE4DFDA});
    }
    /** Small, quick scale on focus; layout size never changes, so nothing reflows. */
    static void focusScale(View v,boolean focused){v.animate().scaleX(focused?1.04f:1f).scaleY(focused?1.04f:1f).setDuration(120).start();}
    /**
     * Applies the Flix Town button look to any view (Button, or a row holding an icon and label).
     * Keeps a focus listener the caller adds later working by chaining through {@link #focusScale}.
     */
    static void styleButton(View v,int kind){
        Context c=v.getContext();
        int l=v.getPaddingLeft(),t=v.getPaddingTop(),r=v.getPaddingRight(),b=v.getPaddingBottom();
        v.setBackground(buttonBackground(c,kind));v.setPadding(l,t,r,b);
        if(v instanceof TextView)((TextView)v).setTextColor(buttonText(kind));
        v.setOnFocusChangeListener((x,f)->focusScale(x,f));
    }

    /** Standard TV button (secondary). Callers set the height (46–52dp; {@link #HALO} of it is focus margin). */
    static Button button(Context c, String label) {
        Button b = new Button(c); b.setText(label); b.setTextSize(17);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setStateListAnimator(null);b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c,24),0,dp(c,24),0);b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);b.setFocusable(true);b.setFocusableInTouchMode(true);b.setClickable(true);
        styleButton(b,SECONDARY);
        return b;
    }
    /** The screen's main action: solid logo red. */
    static Button primaryButton(Context c,String label){Button b=button(c,label);styleButton(b,PRIMARY);return b;}
    /** Dismissive choices (Cancel, Stay, Later…) are never shown as the primary action. */
    static boolean dismissive(String label){
        String l=label.trim().toLowerCase(java.util.Locale.ROOT);
        return l.equals("cancel")||l.equals("stay")||l.equals("close")||l.equals("later")||l.equals("not now")||l.equals("no")
            ||l.equals("ok")||l.equals("done")||l.equals("hide")||l.equals("continue");
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
        // Actions are sized to their labels (a stacked list takes the width of its longest label).
        LinearLayout buttons=horizontal?row(activity):column(activity);buttons.setClipChildren(false);buttons.setClipToPadding(false);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.topMargin=dp(activity,20);body.addView(buttons,bp);
        Button focus=null;final boolean[] chosen={false};
        int primary=Math.max(0,Math.min(focusIndex,labels.length-1));
        for(int i=0;i<labels.length;i++){
            final int index=i;
            Button b=i==primary && !dismissive(labels[i])?primaryButton(activity,labels[i]):button(activity,labels[i]);b.setTextSize(17);
            LinearLayout.LayoutParams p=horizontal?new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(activity,50))
                :new LinearLayout.LayoutParams(-1,dp(activity,50));
            b.setMinWidth(dp(activity,120));
            if(horizontal){if(i>0)p.leftMargin=dp(activity,10);}
            else if(i>0)p.topMargin=dp(activity,6);
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

    /**
     * Branded notice card for app updates: the Flix Town mark, a short title, one line of copy,
     * an optional muted line (version, what's new) and compact pill buttons sized to their text.
     * {@code cancelable=false} keeps it on screen until a button is chosen (required updates).
     */
    static Dialog notice(Activity activity,String title,String message,String muted,String[] labels,int focusIndex,
                         boolean cancelable,Choice action,Runnable onCancel){
        Dialog dialog=newDialog(activity);
        LinearLayout body=column(activity);pad(body,activity,30,24,30,24);
        // Solid (not see-through) so the hero title behind never shows through the card.
        GradientDrawable surface=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xFF211F25,0xFF17161A,0xFF111014});
        surface.setCornerRadius(dp(activity,18));surface.setStroke(dp(activity,1),HAIRLINE);body.setBackground(surface);
        LinearLayout top=row(activity);top.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView mark=new android.widget.ImageView(activity);mark.setImageResource(R.drawable.flix_logo);
        mark.setScaleType(android.widget.ImageView.ScaleType.FIT_START);mark.setAdjustViewBounds(true);
        top.addView(mark,new LinearLayout.LayoutParams(dp(activity,62),dp(activity,40)));
        View accent=new View(activity);accent.setBackground(rounded(ACCENT,2,activity));
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(dp(activity,28),dp(activity,3));ap.leftMargin=dp(activity,14);top.addView(accent,ap);
        body.addView(top);
        TextView heading=heading(activity,title,22);heading.setMaxLines(2);heading.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=dp(activity,16);body.addView(heading,hp);
        TextView m=text(activity,message,16);m.setTextColor(TEXT_2);m.setLineSpacing(0,1.18f);m.setMaxLines(4);m.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.topMargin=dp(activity,8);body.addView(m,mp);
        if(muted!=null && !muted.isEmpty()){
            TextView q=text(activity,muted,13);q.setTextColor(TEXT_3);q.setMaxLines(3);q.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(-1,-2);qp.topMargin=dp(activity,10);body.addView(q,qp);
        }
        LinearLayout buttons=row(activity);buttons.setClipChildren(false);buttons.setClipToPadding(false);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.topMargin=dp(activity,22);body.addView(buttons,bp);
        Button focus=null;final boolean[] chosen={false};
        for(int i=0;i<labels.length;i++){final int index=i;
            Button b=i==Math.max(0,Math.min(focusIndex,labels.length-1)) && !dismissive(labels[i])?primaryButton(activity,labels[i]):button(activity,labels[i]);
            b.setTextSize(16);b.setMinWidth(dp(activity,124));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(activity,50));
            if(i>0)p.leftMargin=dp(activity,10);buttons.addView(b,p);
            b.setOnClickListener(v->{chosen[0]=true;dialog.dismiss();action.select(index);});
            if(i==Math.max(0,Math.min(focusIndex,labels.length-1)))focus=b;
        }
        dialog.setContentView(body);dialog.setCancelable(cancelable);dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d->{if(!chosen[0] && onCancel!=null)onCancel.run();});
        showWindow(activity,dialog,500,false,0);
        if(focus!=null){Button f=focus;f.post(f::requestFocus);}
        return dialog;
    }

    /** A dialog whose message and bar can change while it is open (update download). */
    static final class Progress {
        final Dialog dialog;private final TextView message;private final View bar;private final int track;
        private Progress(Dialog dialog,TextView message,View bar,int track){this.dialog=dialog;this.message=message;this.bar=bar;this.track=track;}
        void set(String text,int percent){message.setText(text);
            ViewGroup.LayoutParams p=bar.getLayoutParams();p.width=Math.max(0,Math.min(100,percent))*track/100;bar.setLayoutParams(p);}
        boolean isShowing(){return dialog.isShowing();}
        void dismiss(){if(dialog.isShowing())dialog.dismiss();}
    }
    /** Title, live message, progress bar and one button (for example "Hide"). */
    static Progress progress(Activity activity,String title,String button,Runnable onButton){
        Dialog dialog=newDialog(activity);
        LinearLayout body=sheet(activity,title,null);
        TextView message=text(activity,"",16);message.setTextColor(TEXT_2);
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.topMargin=dp(activity,8);body.addView(message,mp);
        int track=dp(activity,406);
        android.widget.FrameLayout bars=new android.widget.FrameLayout(activity);bars.setBackground(rounded(0x26FFFFFF,2,activity));
        View bar=new View(activity);bar.setBackground(rounded(GLOW,2,activity));bars.addView(bar,new android.widget.FrameLayout.LayoutParams(0,-1));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(track,dp(activity,4));bp.topMargin=dp(activity,16);body.addView(bars,bp);
        Button b=button(activity,button);b.setTextSize(17);b.setMinWidth(dp(activity,120));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(activity,50));lp.topMargin=dp(activity,22);
        body.addView(b,lp);b.setOnClickListener(v->{dialog.dismiss();if(onButton!=null)onButton.run();});
        dialog.setContentView(body);showWindow(activity,dialog,470,false,0);b.post(b::requestFocus);
        return new Progress(dialog,message,bar,track);
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

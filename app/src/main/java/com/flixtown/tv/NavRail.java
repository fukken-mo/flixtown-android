package com.flixtown.tv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Side navigation. One glass panel is drawn at full width but clipped to its 96dp icon column
 * while collapsed; opening animates only the clip (no relayout), fades the labels in and dims the
 * page behind it. Collapsed, it cannot take focus, so the D-pad never wanders into it.
 */
final class NavRail {
    interface Host { void onSelect(String tab); void onClose(); }
    static final int COLLAPSED_DP=96,EXPANDED_DP=264;
    private final Activity a;private final FrameLayout root;private final LinearLayout panel;private final View edge,dim;
    private final Host host;private final String[] tabs;
    private final List<View> entries=new ArrayList<>(),labels=new ArrayList<>(),markers=new ArrayList<>();
    private final List<NavIcon> icons=new ArrayList<>();
    private final Rect clip=new Rect();private ValueAnimator anim;private boolean open;private String selected="";

    NavRail(Activity a,FrameLayout root,View dim,String[] tabs,String[] names,int[] iconTypes,Host host){
        this.a=a;this.root=root;this.dim=dim;this.tabs=tabs;this.host=host;
        panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);panel.setBackgroundResource(R.drawable.nav_panel);
        panel.setPadding(0,Ui.safeY(a),0,Ui.safeY(a));
        root.addView(panel,new FrameLayout.LayoutParams(Ui.dp(a,EXPANDED_DP),-1));
        edge=new View(a);edge.setBackgroundColor(0x1FFFFFFF);root.addView(edge,new FrameLayout.LayoutParams(Ui.dp(a,1),-1));

        ImageView logo=new ImageView(a);logo.setImageResource(R.drawable.flix_logo);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(Ui.dp(a,70),Ui.dp(a,48));lp.leftMargin=Ui.dp(a,13);lp.bottomMargin=Ui.dp(a,28);
        panel.addView(logo,lp);
        for(int i=0;i<tabs.length;i++){final String tab=tabs[i];final int index=i;
            LinearLayout entry=new LinearLayout(a);entry.setOrientation(LinearLayout.HORIZONTAL);entry.setGravity(Gravity.CENTER_VERTICAL);
            entry.setFocusable(true);entry.setFocusableInTouchMode(true);entry.setClickable(true);entry.setContentDescription(names[i]);
            entry.setBackgroundResource(R.drawable.nav_item);
            LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(Ui.dp(a,EXPANDED_DP-20),Ui.dp(a,46));ep.leftMargin=Ui.dp(a,10);ep.bottomMargin=Ui.dp(a,6);
            panel.addView(entry,ep);
            View marker=new View(a);marker.setBackground(Ui.rounded(Ui.GLOW,2,a));
            LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(Ui.dp(a,3),Ui.dp(a,18));mp.leftMargin=Ui.dp(a,4);entry.addView(marker,mp);
            NavIcon icon=new NavIcon(a,iconTypes[i]);
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(Ui.dp(a,24),Ui.dp(a,24));ip.leftMargin=Ui.dp(a,19);entry.addView(icon,ip); // icon centred at 48dp
            TextView label=Ui.text(a,names[i],17);label.setSingleLine(true);label.setAlpha(0f);
            LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-2,-2);np.leftMargin=Ui.dp(a,22);entry.addView(label,np);
            entry.setOnFocusChangeListener((v,f)->label.setTextColor(f||tab.equals(selected)?Ui.TEXT:Ui.TEXT_2));
            entry.setOnClickListener(v->host.onSelect(tab));
            entry.setOnKeyListener((v,key,event)->{
                if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
                if(key==KeyEvent.KEYCODE_DPAD_RIGHT){host.onClose();return true;}
                // The list is a closed loop at neither end: focus stays inside the menu.
                if(key==KeyEvent.KEYCODE_DPAD_UP && index==0)return true;
                if(key==KeyEvent.KEYCODE_DPAD_DOWN && index==tabs.length-1)return true;
                return false;
            });
            entries.add(entry);labels.add(label);markers.add(marker);icons.add(icon);
        }
        panel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        setClip(Ui.dp(a,COLLAPSED_DP));
    }
    boolean isOpen(){return open;}
    void setSelected(String tab){selected=tab;
        for(int i=0;i<tabs.length;i++){boolean on=tabs[i].equals(tab);
            markers.get(i).setVisibility(on?View.VISIBLE:View.INVISIBLE);icons.get(i).setActive(on);
            ((TextView)labels.get(i)).setTextColor(on||entries.get(i).isFocused()?Ui.TEXT:Ui.TEXT_2);}}
    private void setClip(int width){clip.set(0,0,width,root.getResources().getDisplayMetrics().heightPixels);panel.setClipBounds(clip);edge.setTranslationX(width-1);}
    private void animateTo(boolean expand){
        if(anim!=null)anim.cancel();
        int from=clip.right,to=Ui.dp(a,expand?EXPANDED_DP:COLLAPSED_DP);
        anim=ValueAnimator.ofInt(from,to);anim.setDuration(expand?200:160);anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(v->setClip((Integer)v.getAnimatedValue()));anim.start();
        for(View l:labels)l.animate().alpha(expand?1f:0f).setStartDelay(expand?60:0).setDuration(expand?160:90).start();
    }
    /** Opens with focus on {@code tab}'s entry. */
    void open(String tab){
        if(open)return;open=true;
        panel.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        dim.setVisibility(View.VISIBLE);dim.setAlpha(0f);dim.animate().alpha(1f).setDuration(180).start();
        animateTo(true);
        for(int i=0;i<tabs.length;i++)if(tabs[i].equals(tab)){entries.get(i).requestFocus();break;}
    }
    void close(){
        if(!open)return;open=false;
        panel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        dim.animate().alpha(0f).setDuration(140).withEndAction(()->{if(!open)dim.setVisibility(View.GONE);}).start();
        animateTo(false);
    }
}

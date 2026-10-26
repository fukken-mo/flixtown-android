package com.flixtown.tv;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Full-screen ambient artwork behind Home.
 *
 * Two image layers crossfade, and only after the next image has decoded, so the page never
 * flashes to black. A request for the artwork already on screen is ignored, and a late image for
 * a title that is no longer wanted is dropped. Backdrops are decoded at the screen's width (up to
 * 1920 px) and shown sharp; the readability dim is a separate layer in Home's layout. When a title
 * has no suitable landscape art (none, a poster, or a small thumbnail), the artwork fades out and
 * Home's normal branded background shows instead of a stretched, blurred picture.
 */
final class BackdropView extends FrameLayout {
    private static final int FADE_MS=420;
    private final ImageView[] layers=new ImageView[2];
    private int front;
    private String current;
    private String label="";

    BackdropView(Context context){
        super(context);
        for(int i=0;i<2;i++){
            ImageView v=new ImageView(context);v.setScaleType(ImageView.ScaleType.CENTER_CROP);v.setAlpha(0f);
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(v,new LayoutParams(-1,-1));layers[i]=v;
        }
    }
    private int screenWidth(){int w=getWidth();return w>0?w:getResources().getDisplayMetrics().widthPixels;}

    /** Shows the landscape artwork {@code url} for the title {@code key}; null shows the branded background. */
    void show(String key,String url){
        label=key==null?"":key;
        if(url==null || url.isEmpty()){none("no landscape art");return;}
        int screen=screenWidth();
        String sized=FocusArt.sized(url,screen),id="s:"+sized;
        if(id.equals(current))return;
        current=id;
        ImageView next=layers[1-front],shown=layers[front];
        next.animate().cancel();next.setAlpha(0f);next.setScaleX(1.04f);next.setScaleY(1.04f);
        String forKey=label;
        Images.load(next,sized,FocusArt.decodeWidth(screen),()->{
            if(!id.equals(current))return;                                   // a newer title was asked for meanwhile
            Drawable d=next.getDrawable();int w=d==null?0:d.getIntrinsicWidth(),h=d==null?0:d.getIntrinsicHeight();
            if(!FocusArt.suitable(w,h,screen)){next.setImageDrawable(null);none("unsuitable "+w+"x"+h);return;}
            if(BuildConfig.DEMO)android.util.Log.i("FlixTownQA","home_art key="+forKey+" url="+sized+" bitmap="+w+"x"+h+" screen="+screen);
            front=1-front;
            next.bringToFront();
            // A slow settle from a slightly larger scale gives the art gentle depth as it arrives.
            next.animate().setStartDelay(0).alpha(1f).scaleX(1f).scaleY(1f).setDuration(FADE_MS).start();
            shown.animate().alpha(0f).setStartDelay(FADE_MS/2).setDuration(FADE_MS).start();
        },()->{if(id.equals(current))none("load failed");});
    }
    /** Fades the artwork out: Home's branded background shows. */
    private void none(String why){
        if(BuildConfig.DEMO)android.util.Log.i("FlixTownQA","home_art key="+label+" branded ("+why+")");
        if("none".equals(current) && why.equals("no landscape art"))return;
        current="none";
        for(ImageView v:layers){v.animate().cancel();v.animate().alpha(0f).setStartDelay(0).setDuration(FADE_MS).start();}
    }
    void clear(){current=null;for(ImageView v:layers){v.animate().cancel();v.setAlpha(0f);v.setImageDrawable(null);v.setTag(null);}}
}

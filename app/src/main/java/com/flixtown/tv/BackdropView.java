package com.flixtown.tv;

import android.content.Context;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Full-screen ambient artwork behind Home.
 *
 * Two image layers crossfade, and only after the next image has decoded, so the page never
 * flashes to black. A request for the artwork already on screen is ignored. Titles without real
 * landscape art get their poster decoded at a tiny size; the GPU upscales it into a soft colour
 * wash, which gives an "ambient light" look without any blur filter.
 */
final class BackdropView extends FrameLayout {
    private static final int SHARP_WIDTH=960,WASH_WIDTH=40,FADE_MS=420;
    private final ImageView[] layers=new ImageView[2];
    private int front;
    private String current;

    BackdropView(Context context){
        super(context);
        for(int i=0;i<2;i++){
            ImageView v=new ImageView(context);v.setScaleType(ImageView.ScaleType.CENTER_CROP);v.setAlpha(0f);
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(v,new LayoutParams(-1,-1));layers[i]=v;
        }
    }
    /** Shows {@code url}; {@code wash} decodes it tiny for the soft colour look. */
    void show(String url,boolean wash){
        String id=(wash?"w:":"s:")+url;
        if(url==null || url.isEmpty() || id.equals(current))return;
        current=id;
        ImageView next=layers[1-front],shown=layers[front];
        next.animate().cancel();next.setAlpha(0f);
        next.setScaleX(wash?1.15f:1.04f);next.setScaleY(wash?1.15f:1.04f);
        Images.load(next,url,wash?WASH_WIDTH:SHARP_WIDTH,()->{
            if(!id.equals(current))return;
            front=1-front;
            next.bringToFront();
            // A slow settle from a slightly larger scale gives the art gentle depth as it arrives.
            next.animate().setStartDelay(0).alpha(1f).scaleX(1f).scaleY(1f).setDuration(FADE_MS).start();
            shown.animate().alpha(0f).setStartDelay(FADE_MS/2).setDuration(FADE_MS).start();
        });
    }
    void clear(){current=null;for(ImageView v:layers){v.animate().cancel();v.setAlpha(0f);v.setImageDrawable(null);v.setTag(null);}}
}

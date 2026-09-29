package com.flixtown.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Thin playback progress line. It grows slightly and shows a knob only when focused, so the
 * resting overlay stays minimal. The owner handles Left/Right seeking.
 */
final class ProgressLine extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private final float thin,thick,knob;
    private long duration,position,buffered;
    ProgressLine(Context c){
        super(c);float d=c.getResources().getDisplayMetrics().density;
        thin=3*d;thick=5*d;knob=7*d;setFocusable(true);setFocusableInTouchMode(true);
        setOnFocusChangeListener((v,f)->invalidate());
    }
    void set(long duration,long position,long buffered){
        if(this.duration==duration && this.position==position && this.buffered==buffered)return;
        this.duration=duration;this.position=position;this.buffered=buffered;invalidate();
    }
    @Override protected void onDraw(Canvas canvas){
        float h=isFocused()?thick:thin,cy=getHeight()/2f,left=knob,right=getWidth()-knob,w=right-left;
        float played=duration>0?Math.max(0,Math.min(1,position/(float)duration)):0;
        float loaded=duration>0?Math.max(played,Math.min(1,buffered/(float)duration)):0;
        rect.set(left,cy-h/2,right,cy+h/2);paint.setColor(0x3DFFFFFF);canvas.drawRoundRect(rect,h,h,paint);
        rect.set(left,cy-h/2,left+w*loaded,cy+h/2);paint.setColor(0x52FFFFFF);canvas.drawRoundRect(rect,h,h,paint);
        rect.set(left,cy-h/2,left+w*played,cy+h/2);paint.setColor(Ui.GLOW);canvas.drawRoundRect(rect,h,h,paint);
        if(isFocused()){paint.setColor(0xFFFFFFFF);canvas.drawCircle(left+w*played,cy,knob,paint);}
    }
}

package com.flixtown.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small vector glyphs for the player controls, drawn on a 24-unit grid. */
final class PlayerIcon extends View {
    static final int PLAY=0,PAUSE=1,AUDIO=2,SUBTITLES=3;
    private final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final RectF rect=new RectF();
    private int type;
    PlayerIcon(Context context,int type){super(context);this.type=type;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void setType(int value){if(type!=value){type=value;invalidate();}}
    @Override protected void onDraw(Canvas canvas){
        float s=Math.min(getWidth(),getHeight())/24f;
        canvas.save();canvas.translate((getWidth()-24*s)/2f,(getHeight()-24*s)/2f);canvas.scale(s,s);
        pen.setColor(0xFFFFFFFF);path.reset();
        switch(type){
            case PLAY:
                pen.setStyle(Paint.Style.FILL);
                path.moveTo(7,4.5f);path.lineTo(19.5f,12);path.lineTo(7,19.5f);path.close();canvas.drawPath(path,pen);break;
            case PAUSE:
                pen.setStyle(Paint.Style.FILL);
                rect.set(6,5,10,19);canvas.drawRoundRect(rect,1,1,pen);rect.set(14,5,18,19);canvas.drawRoundRect(rect,1,1,pen);break;
            case AUDIO:
                pen.setStyle(Paint.Style.FILL);
                path.moveTo(3,9.5f);path.lineTo(7,9.5f);path.lineTo(12,5);path.lineTo(12,19);path.lineTo(7,14.5f);path.lineTo(3,14.5f);path.close();
                canvas.drawPath(path,pen);
                pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(1.8f);pen.setStrokeCap(Paint.Cap.ROUND);
                rect.set(10,8,18,16);canvas.drawArc(rect,-50,100,false,pen);
                rect.set(9,4.5f,21.5f,19.5f);canvas.drawArc(rect,-50,100,false,pen);break;
            default:
                pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(1.8f);pen.setStrokeCap(Paint.Cap.ROUND);
                rect.set(2.5f,5,21.5f,19);canvas.drawRoundRect(rect,2.5f,2.5f,pen);
                canvas.drawLine(6,11,11,11,pen);canvas.drawLine(13,11,18,11,pen);
                canvas.drawLine(6,15,15,15,pen);break;
        }
        canvas.restore();
    }
}

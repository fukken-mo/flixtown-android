package com.flixtown.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Lightweight vector navigation marks drawn at TV resolution. */
final class NavIcon extends View {
    private final int type;
    private final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean active;
    NavIcon(Context context,int type){super(context);this.type=type;setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
    void setActive(boolean value){active=value;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        canvas.save();canvas.scale(getWidth()/32f,getHeight()/32f);
        pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(2.5f);pen.setStrokeCap(Paint.Cap.ROUND);
        pen.setStrokeJoin(Paint.Join.ROUND);pen.setColor(active?0xFFFFFFFF:0xFFCBC8D1);
        Path p=new Path();
        switch(type){
            case 0:
                p.moveTo(4,15);p.lineTo(16,5);p.lineTo(28,15);p.moveTo(7,13);p.lineTo(7,27);p.lineTo(25,27);p.lineTo(25,13);p.moveTo(13,27);p.lineTo(13,19);p.lineTo(19,19);p.lineTo(19,27);canvas.drawPath(p,pen);break;
            case 1:
                canvas.drawCircle(14,14,8,pen);canvas.drawLine(20,20,28,28,pen);break;
            case 2:
                canvas.drawRoundRect(4,7,28,25,3,3,pen);canvas.drawLine(9,7,9,25,pen);canvas.drawLine(23,7,23,25,pen);
                pen.setStyle(Paint.Style.FILL);p.moveTo(14,12);p.lineTo(20,16);p.lineTo(14,20);p.close();canvas.drawPath(p,pen);break;
            case 3:
                canvas.drawRoundRect(6,5,26,12,2,2,pen);canvas.drawRoundRect(6,20,26,27,2,2,pen);
                canvas.drawLine(10,12,10,20,pen);canvas.drawLine(22,12,22,20,pen);break;
            case 4:
                for(int i=0;i<10;i++){double a=-Math.PI/2+i*Math.PI/5;float radius=(i%2==0)?12:5.5f;float x=16+(float)Math.cos(a)*radius,y=16+(float)Math.sin(a)*radius;
                    if(i==0)p.moveTo(x,y);else p.lineTo(x,y);}p.close();canvas.drawPath(p,pen);break;
            default:
                canvas.drawCircle(16,16,8,pen);
                for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(16+(float)Math.cos(a)*10,16+(float)Math.sin(a)*10,16+(float)Math.cos(a)*13,16+(float)Math.sin(a)*13,pen);}
                canvas.drawCircle(16,16,2,pen);break;
        }
        canvas.restore();
    }
}

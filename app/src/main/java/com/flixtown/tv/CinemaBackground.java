package com.flixtown.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/** Lightweight, local artwork for login. No network images or animation. */
final class CinemaBackground extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    CinemaBackground(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas canvas){
        float w=getWidth(),h=getHeight();
        paint.setShader(new LinearGradient(0,0,w,h,
            new int[]{0xFF200D17,0xFF0D0E14,0xFF090A10},null,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,paint);paint.setShader(null);
        paint.setShader(new LinearGradient(w*.5f,0,w*.93f,h*.9f,
            0x463A1724,0x00100911,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,paint);paint.setShader(null);
        float unit=getResources().getDisplayMetrics().density;
        float strip=34*unit,gap=45*unit;
        paint.setColor(0x18272A33);
        for(float x=w*.07f;x<w;x+=gap)canvas.drawRoundRect(x,h*.04f,x+strip,h*.94f,5*unit,5*unit,paint);
        paint.setColor(0x24111117);
        for(float x=w*.07f;x<w;x+=gap){
            for(float y=h*.07f;y<h*.94f;y+=24*unit){
                canvas.drawRoundRect(x+3*unit,y,x+9*unit,y+9*unit,2*unit,2*unit,paint);
                canvas.drawRoundRect(x+strip-9*unit,y,x+strip-3*unit,y+9*unit,2*unit,2*unit,paint);
            }
        }
        paint.setColor(0x4408090D);canvas.drawRect(0,0,w,h,paint);
    }
}

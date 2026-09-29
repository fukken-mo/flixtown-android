package com.flixtown.tv;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Soft red glow drawn in the padding around a focused poster. It is a handful of concentric
 * rounded-rect strokes with falling alpha: no blur filter, no offscreen layer, and nothing is drawn
 * while the poster is not focused. The glow stays inside the card's own bounds, so the grid's
 * padding (not clipping) decides how close it can come to the screen edge.
 */
final class GlowDrawable extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private final float spread,radius,step;
    private final int color;
    private boolean on;

    GlowDrawable(float spreadPx,float radiusPx,int color){
        this.spread=spreadPx;this.radius=radiusPx;this.color=color&0x00FFFFFF;
        this.step=Math.max(1f,spreadPx/7f);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(step+0.5f);
    }
    void setOn(boolean value){if(on!=value){on=value;invalidateSelf();}}

    @Override public void draw(Canvas canvas){
        if(!on)return;
        android.graphics.Rect b=getBounds();
        for(float d=step/2f;d<spread;d+=step){
            float t=1f-d/spread;                      // 1 at the poster edge, 0 at the outside
            int a=Math.round(235*(float)Math.pow(t,1.6));
            if(a<=0)continue;
            paint.setColor((a<<24)|color);
            rect.set(b.left+spread-d,b.top+spread-d,b.right-spread+d,b.bottom-spread+d);
            canvas.drawRoundRect(rect,radius+d,radius+d,paint);
        }
    }
    @Override public void setAlpha(int alpha){}
    @Override public void setColorFilter(ColorFilter filter){}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}

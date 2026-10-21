package com.flixtown.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Offline, read-only guide. The remote is drawn as vectors so it stays sharp on every TV. */
public class RemoteGuideActivity extends Activity {
    private LinearLayout instructions;
    private Button browsing,watching;
    private RemotePicture picture;
    private boolean watchingPage;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Ui.BG);setContentView(root);
        View glow=new View(this);glow.setBackgroundResource(R.drawable.renew_glow);root.addView(glow,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout page=Ui.column(this);page.setClipChildren(false);page.setClipToPadding(false);
        page.setPadding(Ui.safeX(this),Ui.safeY(this),Ui.safeX(this),Ui.safeY(this));root.addView(page,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=Ui.row(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setClipChildren(false);
        page.addView(header,new LinearLayout.LayoutParams(-1,-2));
        TextView title=Ui.heading(this,"Remote Guide",28);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=Ui.button(this,"Close");header.addView(close,new LinearLayout.LayoutParams(-2,Ui.dp(this,46)));close.setOnClickListener(v->finish());
        LinearLayout tabs=Ui.row(this);tabs.setClipChildren(false);tabs.setClipToPadding(false);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,Ui.dp(this,44));tp.topMargin=Ui.dp(this,12);page.addView(tabs,tp);
        browsing=Ui.button(this,"Browsing");watching=Ui.button(this,"Watching");
        browsing.setTextSize(16);watching.setTextSize(16);
        tabs.addView(browsing,new LinearLayout.LayoutParams(Ui.dp(this,150),-1));
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(Ui.dp(this,150),-1);wp.leftMargin=Ui.dp(this,12);tabs.addView(watching,wp);
        browsing.setOnClickListener(v->show(false));watching.setOnClickListener(v->show(true));
        LinearLayout body=Ui.row(this);body.setClipChildren(false);body.setClipToPadding(false);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,0,1);bp.topMargin=Ui.dp(this,14);page.addView(body,bp);
        picture=new RemotePicture(this);body.addView(picture,new LinearLayout.LayoutParams(Ui.dp(this,210),-1));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,-1,1);sp.leftMargin=Ui.dp(this,22);body.addView(scroll,sp);
        instructions=Ui.column(this);instructions.setPadding(Ui.dp(this,5),Ui.dp(this,5),Ui.dp(this,5),Ui.dp(this,5));instructions.setClipChildren(false);
        scroll.addView(instructions,new ScrollView.LayoutParams(-1,-2));
        TextView note=Ui.text(this,"Your remote may look different. These controls work with a standard TV remote.",13);note.setTextColor(Ui.TEXT_3);
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=Ui.dp(this,10);page.addView(note,np);
        show(state!=null&&state.getBoolean("watching"));browsing.post(()->(watchingPage?watching:browsing).requestFocus());
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putBoolean("watching",watchingPage);}
    private void show(boolean playback){
        watchingPage=playback;picture.playback=playback;picture.invalidate();
        browsing.setTextColor(playback?Ui.TEXT_2:Ui.TEXT);watching.setTextColor(playback?Ui.TEXT:Ui.TEXT_2);
        browsing.setSelected(!playback);watching.setSelected(playback);
        instructions.removeAllViews();
        if(!playback){
            item("Arrows","Move through menus, titles and episodes.");
            item("OK","Open a title or choose the highlighted button.");
            item("Hold OK","Continue Watching: Resume, Start over or Remove. Episode card or a movie’s Watch button: watched / unwatched.");
            item("Back","Close a menu or return to the previous screen.");
        }else{
            item("Up","Show the player controls. From the button row, press Up again for Options.");
            item("Left / Right","Seek 10 seconds when controls are hidden or the progress bar is selected. On buttons, move between them.");
            item("OK","Play or pause when controls are hidden. Otherwise, choose the highlighted button.");
            item("Options","Choose episodes, subtitle size and appearance, playback speed, or watched status.");
            item("Back","Hide the controls first. Press Back again to leave playback.");
        }
    }
    private void item(String key,String description){
        LinearLayout row=Ui.row(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setFocusable(true);row.setFocusableInTouchMode(true);
        row.setContentDescription(key+". "+description);Ui.pad(row,this,12,8,12,8);row.setMinimumHeight(Ui.dp(this,53));
        row.setBackground(Ui.pill(this,false,false,10));row.setOnFocusChangeListener((v,f)->v.setBackground(Ui.pill(this,f,false,10)));
        TextView label=Ui.heading(this,key,15);label.setTextColor(Ui.TEXT);row.addView(label,new LinearLayout.LayoutParams(Ui.dp(this,98),-2));
        TextView text=Ui.text(this,description,15);text.setTextColor(Ui.TEXT_2);text.setLineSpacing(0,1.06f);
        row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=Ui.dp(this,6);instructions.addView(row,rp);
    }
    @Override protected void onResume(){super.onResume();ScreenAwake.on(this);}
    @Override protected void onPause(){ScreenAwake.off(this);super.onPause();}

    private static final class RemotePicture extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);boolean playback;
        RemotePicture(Context context){super(context);setContentDescription("TV remote diagram: arrows around OK, Back, and Play / Pause");setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);}
        private void fill(Canvas c,int color){paint.setStyle(Paint.Style.FILL);paint.setColor(color);}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float scale=Math.min(getWidth()/200f,getHeight()/350f);if(scale<=0)return;
            c.save();c.translate((getWidth()-200*scale)/2f,(getHeight()-350*scale)/2f);c.scale(scale,scale);
            fill(c,0xFF221F27);c.drawRoundRect(new RectF(30,3,170,346),40,40,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);paint.setColor(0xFF514850);c.drawRoundRect(new RectF(30,3,170,346),40,40,paint);
            fill(c,0xFF38323C);c.drawCircle(100,119,53,paint);
            arrow(c,100,79,0);arrow(c,140,119,90);arrow(c,100,159,180);arrow(c,60,119,270);
            fill(c,0xFF6B2D32);c.drawCircle(100,119,25,paint);text(c,"OK",100,125,16,Ui.TEXT);
            fill(c,0xFF38323C);c.drawCircle(69,220,23,paint);c.drawCircle(131,220,23,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.4f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(Ui.TEXT);
            Path back=new Path();back.moveTo(74,229);back.lineTo(78,229);back.quadTo(85,229,85,221);back.quadTo(85,214,77,214);back.lineTo(58,214);back.lineTo(65,207);back.moveTo(58,214);back.lineTo(65,221);c.drawPath(back,paint);
            fill(c,Ui.TEXT);Path triangle=new Path();triangle.moveTo(121,212);triangle.lineTo(121,228);triangle.lineTo(131,220);triangle.close();c.drawPath(triangle,paint);
            c.drawRect(135,212,138,228,paint);c.drawRect(141,212,144,228,paint);
            text(c,"BACK",69,259,10,Ui.TEXT_2);text(c,"PLAY / PAUSE",131,259,8,Ui.TEXT_2);
            text(c,"FLIX TOWN",100,316,11,Ui.TEXT_3);c.restore();
        }
        private void arrow(Canvas c,float x,float y,float angle){c.save();c.translate(x,y);c.rotate(angle);
            fill(c,playback&&angle!=180?0xFFE47575:Ui.TEXT_2);Path p=new Path();p.moveTo(0,-6);p.lineTo(6,4);p.lineTo(-6,4);p.close();c.drawPath(p,paint);c.restore();}
        private void text(Canvas c,String value,float x,float y,float size,int color){fill(c,color);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(size);paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));c.drawText(value,x,y,paint);}
    }
}

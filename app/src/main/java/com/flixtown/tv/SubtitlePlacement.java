package com.flixtown.tv;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.media3.common.text.Cue;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import java.util.ArrayList;
import java.util.List;

/**
 * Where subtitles are drawn.
 *
 * Media3's PlayerView draws subtitles inside the video frame, so on a letterboxed movie ordinary
 * dialogue sat inside the picture, far above the bottom of the screen. Here:
 *  - ordinary cues (no position of their own: SRT, plain WebVTT) are drawn on a layer the size of
 *    the screen, bottom-centre, like a TV player;
 *  - cues the subtitle file places itself (ASS/SSA \pos and alignment, positioned WebVTT, CEA-608,
 *    image subtitles) keep the video picture as their reference, as their author intended: their
 *    layer is kept the exact size and position of Media3's video frame.
 * Both layers use the same insets (see {@link #insets}). While the player controls are up, the
 * bottom inset grows so the text sits just above the control bar; when they hide it goes back to
 * the normal lower position. Only the padding changes, in the same layout pass, so the text jumps
 * instantly: no translation or animation (moving subtitles that way once clipped them on TVs).
 * PlayerView's own subtitle layer is hidden so nothing is drawn twice.
 *
 * Both layers sit above the controls overlay (below the cards), so the controls' gradient never
 * dims subtitles.
 */
final class SubtitlePlacement {
    /** Inset of the subtitle box: 5 % at the sides and top (title-safe), 3.5 % at the bottom (HD action-safe, SMPTE ST 2046-1). */
    static final float SIDE=0.05f,TOP=0.05f,BOTTOM=0.035f;
    /** Space kept between the subtitles and the top of the control bar while the controls are up. */
    static final float GAP=0.02f;
    /** Share of the layer height inside the normal insets (text size reference, see PlayerActivity). */
    static final float TEXT_HEIGHT=1f-TOP-BOTTOM;
    final SubtitleView screen,frame;
    private final View controls,bar;
    private int screenLift,frameLift;

    SubtitlePlacement(Context c,ViewGroup playerRoot,PlayerView video,View controlsOverlay,View controlBar){
        controls=controlsOverlay;bar=controlBar;
        SubtitleView builtIn=video.getSubtitleView();if(builtIn!=null)builtIn.setVisibility(View.GONE);
        screen=create(c);frame=create(c);
        screen.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->applyInsets(screen,r-l,b-t,screenLift));
        frame.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->applyInsets(frame,r-l,b-t,frameLift));
        // Above the video and the controls overlay, below the cards (Continue watching?, Up next, errors).
        int above=controlsOverlay==null?-1:playerRoot.indexOfChild(controlsOverlay);
        int at=above<0?Math.min(1,playerRoot.getChildCount()):above+1;
        playerRoot.addView(screen,at,new FrameLayout.LayoutParams(-1,-1));
        playerRoot.addView(frame,at,new FrameLayout.LayoutParams(-1,-1));
        View content=video.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        // QA evidence for the reason above: where Media3 put its own subtitle layer.
        if(BuildConfig.DEMO && builtIn!=null)android.util.Log.i("FlixTownQA","subtitle_builtin_parent="+(builtIn.getParent()==content?"video_frame":"player_view"));
        // The positioned layer follows the video frame (letterboxed or pillarboxed picture).
        if(content!=null)content.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->matchFrame(playerRoot,content));
        // Any layout of the control bar (first showing, the seek preview line appearing) re-checks the lift.
        playerRoot.getViewTreeObserver().addOnGlobalLayoutListener(this::controlsChanged);
    }
    private void matchFrame(View root,View content){
        int[] a=new int[2],b=new int[2];root.getLocationInWindow(a);content.getLocationInWindow(b);
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)frame.getLayoutParams();
        int w=content.getWidth(),h=content.getHeight(),x=b[0]-a[0],y=b[1]-a[1];
        if(p.width==w && p.height==h && p.leftMargin==x && p.topMargin==y && p.gravity==(android.view.Gravity.TOP|android.view.Gravity.START))return;
        p.width=w;p.height=h;p.leftMargin=x;p.topMargin=y;p.gravity=android.view.Gravity.TOP|android.view.Gravity.START;
        frame.post(()->frame.setLayoutParams(p));   // not inside the layout pass that reported the change
    }
    /** Call right after the controls show or hide; also runs after every layout pass. */
    void controlsChanged(){
        boolean up=controls!=null && bar!=null && controls.getVisibility()==View.VISIBLE && bar.getHeight()>0;
        int s=up?liftFor(screen,bar):0,f=up?liftFor(frame,bar):0;
        if(s==screenLift && f==frameLift)return;
        screenLift=s;frameLift=f;
        applyInsets(screen,screen.getWidth(),screen.getHeight(),s);applyInsets(frame,frame.getWidth(),frame.getHeight(),f);
        if(BuildConfig.DEMO){int[] b=new int[2];bar.getLocationOnScreen(b);
            android.util.Log.i("FlixTownQA","subtitle_lift controls="+up+" screen="+s+" frame="+f+" bar_top="+b[1]);}
    }
    /** Bottom inset that puts a layer's text above the control bar (0 when the bar is not over the layer). */
    private static int liftFor(View layer,View bar){
        int[] a=new int[2],b=new int[2];layer.getLocationOnScreen(a);bar.getLocationOnScreen(b);
        return lift(layer.getHeight(),b[1]-a[1]);
    }
    /** For a layer h pixels tall whose control bar starts barTop pixels from the layer's top. */
    static int lift(int h,int barTop){
        if(h<=0 || barTop>=h)return 0;
        if(barTop<h*0.4f)return 0;   // never squeeze the text into less than ~40 % of the layer (a bar this tall is unexpected)
        return h-barTop+Math.round(h*GAP);
    }
    private static SubtitleView create(Context c){
        SubtitleView v=new SubtitleView(c);v.setBottomPaddingFraction(0f);   // the inset below is the only bottom offset
        v.setFocusable(false);v.setClickable(false);
        return v;
    }
    private static void applyInsets(SubtitleView v,int w,int h,int lift){
        if(w<=0||h<=0||v.getChildCount()==0)return;
        View layer=v.getChildAt(0);int[] in=insets(w,h,lift);   // padding on the drawing layer; the text size ignores it (see TEXT_HEIGHT)
        if(layer.getPaddingLeft()!=in[0]||layer.getPaddingTop()!=in[1]||layer.getPaddingRight()!=in[2]||layer.getPaddingBottom()!=in[3])
            layer.setPadding(in[0],in[1],in[2],in[3]);
    }
    /** {left, top, right, bottom} in pixels for a subtitle layer of w × h. */
    static int[] insets(int w,int h){return insets(w,h,0);}
    /** The same, with the bottom inset raised to {@code lift} while the controls are up. */
    static int[] insets(int w,int h,int lift){
        int side=Math.round(w*SIDE);return new int[]{side,Math.round(h*TOP),side,Math.max(Math.round(h*BOTTOM),lift)};
    }
    /** True when the cue carries its own placement (then the video frame is its reference). */
    static boolean positioned(float line,float position,boolean bitmap,boolean vertical){
        return bitmap || vertical || line!=Cue.DIMEN_UNSET || position!=Cue.DIMEN_UNSET;
    }
    void show(List<Cue> cues){
        List<Cue> plain=new ArrayList<>(),placed=new ArrayList<>();
        if(cues!=null)for(Cue c:cues)(positioned(c.line,c.position,c.bitmap!=null,c.verticalType!=Cue.TYPE_UNSET)?placed:plain).add(c);
        screen.setCues(plain);frame.setCues(placed);
    }
    void clear(){screen.setCues(null);frame.setCues(null);}
    /** The viewer's size and appearance choices apply to both layers. */
    interface Style{void apply(SubtitleView v);}
    void style(Style s){s.apply(screen);s.apply(frame);}
}

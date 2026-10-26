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
 *    image subtitles) keep the video frame as their reference, as their author intended.
 * Both layers use the same insets (see {@link #insets}); neither moves when the controls show or
 * hide (no translation or animation: that once clipped subtitles on TVs). PlayerView's own
 * subtitle layer is hidden so nothing is drawn twice.
 *
 * The screen layer sits above the controls overlay (below the cards), so the controls never hide
 * or dim ordinary subtitles; while the controls are up, a line of text may overlap the progress
 * line for those few seconds instead.
 */
final class SubtitlePlacement {
    /** Inset of the subtitle box: 5 % at the sides and top (title-safe), 3.5 % at the bottom (HD action-safe, SMPTE ST 2046-1). */
    static final float SIDE=0.05f,TOP=0.05f,BOTTOM=0.035f;
    final SubtitleView screen,frame;

    SubtitlePlacement(Context c,ViewGroup playerRoot,PlayerView video,View controlsOverlay){
        SubtitleView builtIn=video.getSubtitleView();if(builtIn!=null)builtIn.setVisibility(View.GONE);
        screen=create(c);frame=create(c);
        // Above the video and the controls overlay, below the cards (Continue watching?, Up next, errors).
        int above=controlsOverlay==null?-1:playerRoot.indexOfChild(controlsOverlay);
        playerRoot.addView(screen,above<0?Math.min(1,playerRoot.getChildCount()):above+1,new FrameLayout.LayoutParams(-1,-1));
        ViewGroup content=video.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        // QA evidence for the reason above: where Media3 put its own subtitle layer.
        if(BuildConfig.DEMO && builtIn!=null)android.util.Log.i("FlixTownQA","subtitle_builtin_parent="+(builtIn.getParent()==content?"video_frame":"player_view"));
        if(content!=null)content.addView(frame,new FrameLayout.LayoutParams(-1,-1));
        else playerRoot.addView(frame,Math.min(2,playerRoot.getChildCount()),new FrameLayout.LayoutParams(-1,-1));
    }
    private static SubtitleView create(Context c){
        SubtitleView v=new SubtitleView(c);v.setBottomPaddingFraction(0f);   // the inset below is the only bottom offset
        v.setFocusable(false);v.setClickable(false);
        v.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->applyInsets((SubtitleView)view,r-l,b-t));
        return v;
    }
    private static void applyInsets(SubtitleView v,int w,int h){
        if(w<=0||h<=0||v.getChildCount()==0)return;
        View layer=v.getChildAt(0);int[] in=insets(w,h);   // padding on the drawing layer keeps the text size as before
        if(layer.getPaddingLeft()!=in[0]||layer.getPaddingTop()!=in[1]||layer.getPaddingRight()!=in[2]||layer.getPaddingBottom()!=in[3])
            layer.setPadding(in[0],in[1],in[2],in[3]);
    }
    /** {left, top, right, bottom} in pixels for a subtitle layer of w × h. */
    static int[] insets(int w,int h){
        int side=Math.round(w*SIDE);return new int[]{side,Math.round(h*TOP),side,Math.round(h*BOTTOM)};
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

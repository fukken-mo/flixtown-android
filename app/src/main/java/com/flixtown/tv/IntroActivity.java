package com.flixtown.tv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import org.json.JSONObject;

/**
 * Startup presentation, once per fresh launch of a signed-in TV: the seasonal splash, then the
 * optional panel intro video.
 *
 * Seasonal splash ({@link SeasonalSplash}): the picture for today's event if it is on the TV (each
 * approved picture already contains the Flix Town logo, so nothing is drawn over it), otherwise the
 * normal Flix Town background with the logo. It shows for the panel's display time (default 1.5 s,
 * 0.8–3 s) while the catalog refresh already runs in the background; OK, Enter or Back skip it.
 * When the panel intro video is enabled the splash is only its short loading frame (it is not
 * stretched), so there are never two intros in a row.
 *
 * It waits briefly for the panel config (fetched by {@link StartupRefresh}, which keeps running
 * independently) and otherwise uses the last known setting. The intro plays only when the panel
 * enables it with a valid https URL. OK, Back or Skip continue at once; a load that takes too
 * long, an error, or the end of the video continue automatically.
 */
public class IntroActivity extends Activity implements StartupRefresh.Listener {
    private static final long CONFIG_WAIT_MS=1500,LOAD_TIMEOUT_MS=8000;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean decided,finished,skipped;
    private long shownAt;private int displayMs;private ImageView seasonal;
    private ExoPlayer player;
    private FrameLayout root;
    private ValueAnimator sweep;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);Logs.quietMedia();
        root=new FrameLayout(this);root.setBackgroundColor(Ui.BG);setContentView(root);
        shownAt=android.os.SystemClock.uptimeMillis();
        showSplash();
        StartupRefresh.listen(this);
        handler.postDelayed(()->decide(null),CONFIG_WAIT_MS);
    }
    /**
     * The normal Flix Town background and logo appear at once; when today's seasonal picture is on
     * the TV it is decoded off the main thread and fades in over them (a full-screen picture that
     * already carries the logo). A picture that cannot be decoded leaves the normal background.
     */
    private void showSplash(){
        SeasonalSplash.Choice choice;
        try{choice=SeasonalSplash.choose(this);}catch(Throwable t){choice=new SeasonalSplash.Choice("","",null,SeasonalSchedule.DEFAULT_DISPLAY_MS);}
        displayMs=choice.displayMs;
        View glow=new View(this);glow.setBackgroundResource(R.drawable.startup_glow);root.addView(glow,new FrameLayout.LayoutParams(-1,-1));
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.flix_logo);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(logo,new FrameLayout.LayoutParams(Ui.dp(this,360),Ui.dp(this,248),Gravity.CENTER));
        if(BuildConfig.DEMO)android.util.Log.i("FlixTownQA","splash event="+(choice.eventId.isEmpty()?"default":choice.eventId)+" image="+(choice.image!=null)
            +" file="+(choice.image==null?"":choice.image.getName())+" display_ms="+displayMs);
        if(choice.image==null)return;
        seasonal=new ImageView(this);seasonal.setScaleType(ImageView.ScaleType.CENTER_CROP);seasonal.setAlpha(0f);
        root.addView(seasonal,new FrameLayout.LayoutParams(-1,-1));
        java.io.File file=choice.image;String event=choice.eventId;
        int w=getResources().getDisplayMetrics().widthPixels,h=getResources().getDisplayMetrics().heightPixels;
        Api.IO.execute(()->{android.graphics.Bitmap bmp=SeasonalSplash.decode(this,file,w,h);
            runOnUiThread(()->{
                if(finished||isDestroyed()||bmp==null){if(BuildConfig.DEMO && bmp==null)android.util.Log.i("FlixTownQA","splash image_failed event="+event);return;}
                seasonal.setImageBitmap(bmp);
                boolean animate=animationsOn();
                // The picture only fades in; it stays completely still (no zoom) for its display time.
                seasonal.animate().alpha(1f).setDuration(animate?250:0).withEndAction(()->logo.setVisibility(View.GONE)).start();
                if(BuildConfig.DEMO)android.util.Log.i("FlixTownQA","splash image_shown event="+event);
            });});
    }
    /** Android's "Remove animations" / animator scale 0: no fade. */
    private boolean animationsOn(){
        try{return android.provider.Settings.Global.getFloat(getContentResolver(),android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f;}
        catch(Exception e){return true;}
    }
    @Override public void onConfig(JSONObject config){decide(config);}
    @Override public void onResult(StartupRefresh.Result result){}

    private void decide(JSONObject config){
        if(decided||finished)return;decided=true;handler.removeCallbacksAndMessages(null);
        String url=config!=null?(config.optBoolean("intro_enabled")?config.optString("intro_url",""):"")
            :Api.prefs(this).getString("intro_url","");
        if(url==null || !url.startsWith("https://") || android.net.Uri.parse(url).getHost()==null){
            // No intro video: the splash stays for its display time (counted from launch), then Home.
            long left=skipped?0:displayMs-(android.os.SystemClock.uptimeMillis()-shownAt);
            if(left<=0)openHome();else handler.postDelayed(this::openHome,left);
            return;
        }
        play(url);
    }
    /** OK / Enter / Back during the splash: continue now (the intro video, if any, keeps its Skip button). */
    private void skipSplash(){
        if(finished)return;skipped=true;
        if(!decided)decide(null);else if(player==null)openHome();
    }
    private void play(String url){
        if(sweep!=null)sweep.cancel();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PlayerView view=new PlayerView(this);view.setUseController(false);view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setShutterBackgroundColor(Ui.BG);view.setBackgroundColor(Ui.BG);view.setFocusable(false);
        root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(6000).setReadTimeoutMs(8000);
        player=new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this,http))).build();
        view.setPlayer(player);
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(state==Player.STATE_READY)handler.removeCallbacks(loadTimeout);
                if(state==Player.STATE_ENDED)openHome();
            }
            @Override public void onPlayerError(PlaybackException error){android.util.Log.w("FlixTownIntro","Intro failed: "+error.getErrorCodeName()+" "+Logs.safe(error));openHome();}
        });
        player.setMediaItem(MediaItem.fromUri(url));player.prepare();player.play();
        handler.postDelayed(loadTimeout,LOAD_TIMEOUT_MS);
        Button skip=Ui.button(this,"Skip intro  ›");skip.setTextSize(16);
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,Ui.dp(this,50),Gravity.END|Gravity.BOTTOM);
        bp.setMargins(0,0,Ui.safeX(this),Ui.safeY(this));root.addView(skip,bp);
        skip.setOnClickListener(v->openHome());skip.requestFocus();
    }
    private final Runnable loadTimeout=this::openHome;

    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getAction()==KeyEvent.ACTION_DOWN && event.getKeyCode()==KeyEvent.KEYCODE_BACK){openHome();return true;}
        if(player==null && event.getAction()==KeyEvent.ACTION_DOWN && (Ui.isSelect(event.getKeyCode())||event.getKeyCode()==KeyEvent.KEYCODE_ENTER)){skipSplash();return true;}
        if(event.getKeyCode()==KeyEvent.KEYCODE_BACK)return true;
        return super.dispatchKeyEvent(event);
    }
    private void openHome(){if(finished)return;finished=true;handler.removeCallbacksAndMessages(null);
        if(BuildConfig.DEMO)android.util.Log.i("FlixTownQA","splash end shown_ms="+(android.os.SystemClock.uptimeMillis()-shownAt)
            +(seasonal!=null?" scale="+seasonal.getScaleX()+"x"+seasonal.getScaleY():""));
        Intent intent=new Intent(this,HomeActivity.class);intent.putExtra(HomeActivity.EXTRA_FRESH,true);
        startActivity(intent);overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);finish();}
    @Override protected void onStop(){super.onStop();if(player!=null && !finished)player.pause();}
    @Override protected void onRestart(){super.onRestart();if(player!=null && !finished)player.play();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);StartupRefresh.unlisten(this);if(sweep!=null)sweep.cancel();
        if(player!=null){player.release();player=null;}super.onDestroy();}
}

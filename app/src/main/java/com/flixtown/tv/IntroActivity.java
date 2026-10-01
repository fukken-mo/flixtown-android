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
 * Optional panel intro, shown once per fresh launch.
 *
 * It waits briefly for the panel config (fetched by {@link StartupRefresh}, which keeps running
 * independently) and otherwise uses the last known setting. The intro plays only when the panel
 * enables it with a valid https URL. OK, Back or Skip continue at once; a load that takes too
 * long, an error, or the end of the video continue automatically.
 */
public class IntroActivity extends Activity implements StartupRefresh.Listener {
    private static final long CONFIG_WAIT_MS=1500,LOAD_TIMEOUT_MS=8000;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean decided,finished;
    private ExoPlayer player;
    private FrameLayout root;
    private ValueAnimator sweep;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        root=new FrameLayout(this);root.setBackgroundColor(Ui.BG);setContentView(root);
        showBrand();
        StartupRefresh.listen(this);
        handler.postDelayed(()->decide(null),CONFIG_WAIT_MS);
    }
    /** Same branded look as Home's loading screen, so the hand-off is seamless. */
    private void showBrand(){
        View glow=new View(this);glow.setBackgroundResource(R.drawable.startup_glow);root.addView(glow,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout box=Ui.column(this);box.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(box,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.flix_logo);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(logo,new LinearLayout.LayoutParams(Ui.dp(this,300),Ui.dp(this,207)));
        FrameLayout track=new FrameLayout(this);track.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(Ui.dp(this,220),Ui.dp(this,3));tp.topMargin=Ui.dp(this,26);box.addView(track,tp);
        View bar=new View(this);bar.setBackgroundColor(Ui.GLOW);track.addView(bar,new FrameLayout.LayoutParams(Ui.dp(this,72),-1));
        sweep=ObjectAnimator.ofFloat(bar,View.TRANSLATION_X,-Ui.dp(this,72),Ui.dp(this,220));
        sweep.setDuration(1100);sweep.setRepeatCount(ValueAnimator.INFINITE);sweep.start();
        TextView status=Ui.text(this,"Checking for new movies and shows…",17);status.setTextColor(Ui.TEXT_2);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,-2);sp.topMargin=Ui.dp(this,18);box.addView(status,sp);
    }
    @Override public void onConfig(JSONObject config){decide(config);}
    @Override public void onResult(StartupRefresh.Result result){}

    private void decide(JSONObject config){
        if(decided||finished)return;decided=true;handler.removeCallbacksAndMessages(null);
        String url=config!=null?(config.optBoolean("intro_enabled")?config.optString("intro_url",""):"")
            :Api.prefs(this).getString("intro_url","");
        if(url==null || !url.startsWith("https://") || android.net.Uri.parse(url).getHost()==null){openHome();return;}
        play(url);
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
            @Override public void onPlayerError(PlaybackException error){android.util.Log.w("FlixTownIntro","Intro failed",error);openHome();}
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
        if(event.getKeyCode()==KeyEvent.KEYCODE_BACK)return true;
        return super.dispatchKeyEvent(event);
    }
    private void openHome(){if(finished)return;finished=true;handler.removeCallbacksAndMessages(null);
        Intent intent=new Intent(this,HomeActivity.class);intent.putExtra(HomeActivity.EXTRA_FRESH,true);
        startActivity(intent);overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);finish();}
    @Override protected void onStop(){super.onStop();if(player!=null && !finished)player.pause();}
    @Override protected void onRestart(){super.onRestart();if(player!=null && !finished)player.play();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);StartupRefresh.unlisten(this);if(sweep!=null)sweep.cancel();
        if(player!=null){player.release();player=null;}super.onDestroy();}
}

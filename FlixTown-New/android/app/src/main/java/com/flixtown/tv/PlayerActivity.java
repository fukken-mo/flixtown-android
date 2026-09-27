package com.flixtown.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.ui.PlayerView;

public class PlayerActivity extends Activity {
    private ExoPlayer player;private PlayerView playerView;private String url;private long position;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        url=getIntent().getStringExtra("url");if(url==null||url.isEmpty()){finish();return;}
        playerView=new PlayerView(this);playerView.setUseController(true);playerView.setControllerShowTimeoutMs(5000);
        FrameLayout root=new FrameLayout(this);root.addView(playerView,new FrameLayout.LayoutParams(-1,-1));setContentView(root);
    }
    @Override protected void onStart(){super.onStart();if(url==null)return;
        DefaultRenderersFactory renderers=new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        player=new ExoPlayer.Builder(this,renderers).build();playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(url));player.prepare();if(position>0)player.seekTo(position);player.play();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if(player!=null && e.getAction()==KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible()) {
            if(e.getKeyCode()==KeyEvent.KEYCODE_DPAD_LEFT) {player.seekTo(Math.max(0,player.getCurrentPosition()-10000));return true;}
            if(e.getKeyCode()==KeyEvent.KEYCODE_DPAD_RIGHT) {player.seekTo(player.getCurrentPosition()+10000);return true;}
        }
        return super.dispatchKeyEvent(e);
    }
    @Override protected void onStop(){if(player!=null){position=player.getCurrentPosition();playerView.setPlayer(null);player.release();player=null;}super.onStop();}
}

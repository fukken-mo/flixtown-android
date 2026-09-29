package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.VideoView;

public class IntroActivity extends Activity {
    private boolean finished;
    private final Handler handler=new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String url=Api.prefs(this).getString("intro_url","");
        if(!url.startsWith("https://")){openHome();return;}
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Ui.BG);setContentView(root);
        VideoView video=new VideoView(this);root.addView(video,new FrameLayout.LayoutParams(-1,-1));
        Button skip=Ui.button(this,"Skip");FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(Ui.dp(this,110),Ui.dp(this,52),Gravity.RIGHT|Gravity.BOTTOM);bp.setMargins(0,0,Ui.safeX(this),Ui.safeY(this));root.addView(skip,bp);
        skip.setOnClickListener(v->openHome());skip.requestFocus();
        video.setOnCompletionListener(mp->openHome());video.setOnErrorListener((mp,what,extra)->{openHome();return true;});
        video.setOnPreparedListener(mp->video.start());video.setVideoURI(Uri.parse(url));
        handler.postDelayed(this::openHome,25000);
    }
    private void openHome(){if(finished)return;finished=true;handler.removeCallbacksAndMessages(null);startActivity(new Intent(this,HomeActivity.class));finish();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
}

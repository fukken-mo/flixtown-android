package com.flixtown.tv;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView video;
    private FrameLayout root;
    private LinearLayout controls;
    private TextView clock, playButton, status;
    private SeekBar timeline;
    private String url, nextUrl;
    private String kind, contentId, episodeId, episodeExt, nextEpisodeId, nextEpisodeExt;
    private long position;
    private boolean nextCanceled, promptShown, scrubbing, ended;
    private Dialog dialog;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        url=getIntent().getStringExtra("url");
        nextUrl=getIntent().getStringExtra("next_url");
        kind=getIntent().getStringExtra("content_kind");contentId=getIntent().getStringExtra("content_id");
        episodeId=getIntent().getStringExtra("episode_id");episodeExt=getIntent().getStringExtra("episode_ext");
        nextEpisodeId=getIntent().getStringExtra("next_episode_id");nextEpisodeExt=getIntent().getStringExtra("next_episode_ext");
        if(kind!=null && contentId!=null && !contentId.isEmpty())position=Api.prefs(this).getLong("resume_position_"+kind+":"+contentId,0);
        if(url==null || url.isEmpty()){finish();return;}
        root=new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFocusable(true);
        video=new PlayerView(this);
        video.setUseController(false);
        video.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        video.setFocusable(false);
        root.addView(video,new FrameLayout.LayoutParams(-1,-1));
        buildControls();
        setContentView(root);
        root.requestFocus();
    }

    private void buildControls() {
        controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        Ui.pad(controls,this,72,65,72,30);
        GradientDrawable shade=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x00000000,0x9E050609,0xF208090D});
        controls.setBackground(shade);
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        root.addView(controls,cp);

        TextView label=Ui.heading(this,getIntent().getStringExtra("title")==null?"FLIX TOWN":getIntent().getStringExtra("title"),24);
        controls.addView(label);

        LinearLayout timeRow=Ui.row(this);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tr=new LinearLayout.LayoutParams(-1,-2);
        tr.topMargin=Ui.dp(this,12);
        controls.addView(timeRow,tr);
        timeline=new SeekBar(this);
        timeline.setMax(1000);
        timeline.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.RED));
        timeline.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        timeRow.addView(timeline,new LinearLayout.LayoutParams(0,Ui.dp(this,38),1));
        clock=Ui.text(this,"00:00 / 00:00",14);
        Ui.pad(clock,this,18,0,0,0);
        timeRow.addView(clock);
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){scrubbing=true;handler.removeCallbacks(hideControls);}
            public void onProgressChanged(SeekBar b,int progress,boolean fromUser){
                if(fromUser && player!=null && player.getDuration()>0)clock.setText(time(player.getDuration()*progress/1000)+" / "+time(player.getDuration()));
            }
            public void onStopTrackingTouch(SeekBar b){
                if(player!=null && player.getDuration()>0)player.seekTo(player.getDuration()*b.getProgress()/1000);
                scrubbing=false;scheduleHide();
            }
        });
        timeline.setOnKeyListener((v,key,event)->{
            if(event.getAction()==KeyEvent.ACTION_DOWN && (key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT)){
                seek(key==KeyEvent.KEYCODE_DPAD_LEFT?-10000:10000);return true;
            }
            return false;
        });

        LinearLayout actions=Ui.row(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);
        ap.topMargin=Ui.dp(this,7);
        controls.addView(actions,ap);
        addAction(actions,"↶ 10s",()->seek(-10000));
        playButton=addAction(actions,"Pause",()->{if(player==null)return;if(player.isPlaying())player.pause();else player.play();updatePlay();scheduleHide();});
        addAction(actions,"10s ↷",()->seek(10000));
        addAction(actions,"Subtitles",()->showTracks(C.TRACK_TYPE_TEXT));
        addAction(actions,"Audio",()->showTracks(C.TRACK_TYPE_AUDIO));
        status=Ui.text(this,"",13);status.setTextColor(0xFFD6D0D1);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=Ui.dp(this,10);
        controls.addView(status,sp);
        controls.setVisibility(View.GONE);
    }

    private TextView addAction(LinearLayout parent,String label,Runnable click) {
        TextView button=Ui.text(this,label,16);
        button.setTypeface(null,Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setBackground(Ui.rounded(0xCC242630,11,this));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,Ui.dp(this,49),1);
        bp.setMargins(Ui.dp(this,5),0,Ui.dp(this,5),0);
        parent.addView(button,bp);
        button.setOnClickListener(v->{click.run();showControls();});
        button.setOnFocusChangeListener((v,focused)->{
            button.setBackground(Ui.rounded(focused?Ui.RED:0xCC242630,11,this));
            v.animate().scaleX(focused?1.06f:1f).scaleY(focused?1.06f:1f).setDuration(100).start();
            if(focused)showControls();
        });
        return button;
    }
    private void showControls(){controls.setVisibility(View.VISIBLE);scheduleHide();}
    private void scheduleHide(){handler.removeCallbacks(hideControls);if(player!=null && player.isPlaying() && dialog==null && !scrubbing)handler.postDelayed(hideControls,6000);}
    private final Runnable hideControls=()->{
        if(controls.hasFocus())root.requestFocus();
        controls.setVisibility(View.GONE);
    };
    private void seek(long delta){
        if(player==null)return;
        long current=player.getCurrentPosition();
        long duration=player.getDuration();
        long target=Math.max(0,current+delta);
        if(duration>0 && duration!=C.TIME_UNSET)target=Math.min(target,duration);
        player.seekTo(target);
        status.setText(delta>0?"+10 seconds":"−10 seconds");
        showControls();
    }
    private void updatePlay(){if(playButton!=null && player!=null)playButton.setText(player.isPlaying()?"Pause":"Play");}
    private static String time(long ms){
        if(ms<0 || ms==C.TIME_UNSET)return "--:--";
        long seconds=ms/1000;
        return seconds>=3600?String.format(Locale.US,"%d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60)
            :String.format(Locale.US,"%02d:%02d",seconds/60,seconds%60);
    }

    @Override protected void onStart(){
        super.onStart();
        if(url==null)return;
        player=new ExoPlayer.Builder(this,new DefaultRenderersFactory(this).setEnableDecoderFallback(true)).build();
        video.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        if(position>0)player.seekTo(position);
        player.play();
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(state==Player.STATE_ENDED){ended=true;clearProgress();if(!nextCanceled && nextUrl!=null && !nextUrl.isEmpty())playNext();}
            }
            @Override public void onIsPlayingChanged(boolean playing){updatePlay();if(playing)scheduleHide();else showControls();}
        });
        handler.post(progress);
        showControls();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(player!=null && event.getAction()==KeyEvent.ACTION_DOWN && dialog==null){
            int key=event.getKeyCode();
            boolean focusOnControl=controls!=null && controls.hasFocus();
            if(key==KeyEvent.KEYCODE_DPAD_LEFT && !focusOnControl){seek(-10000);return true;}
            if(key==KeyEvent.KEYCODE_DPAD_RIGHT && !focusOnControl){seek(10000);return true;}
            if(key==KeyEvent.KEYCODE_DPAD_UP && !focusOnControl){showControls();playButton.requestFocus();return true;}
            if((key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER) && !focusOnControl){showControls();playButton.requestFocus();return true;}
        }
        return super.dispatchKeyEvent(event);
    }

    private void showTracks(int type){
        if(player==null)return;
        List<Tracks.Group> groups=new ArrayList<>();
        List<Integer> indexes=new ArrayList<>();
        List<String> labels=new ArrayList<>();
        if(type==C.TRACK_TYPE_TEXT){labels.add("Off");groups.add(null);indexes.add(-1);}
        for(Tracks.Group group:player.getCurrentTracks().getGroups()){
            if(group.getType()!=type)continue;
            for(int i=0;i<group.length;i++){
                if(!group.isTrackSupported(i))continue;
                Format f=group.getTrackFormat(i);
                String language=f.language==null?"Unknown language":new Locale(f.language).getDisplayLanguage(Locale.US);
                labels.add((f.label!=null&&!f.label.isEmpty()?f.label:language)+(group.isTrackSelected(i)?" ✓":""));
                groups.add(group);indexes.add(i);
            }
        }
        if(labels.isEmpty()){status.setText(type==C.TRACK_TYPE_TEXT?"No subtitles in this video":"No other audio tracks");showControls();return;}
        showDialog(type==C.TRACK_TYPE_TEXT?"Subtitles":"Audio tracks",labels,index->{
            if(player==null)return;
            TrackSelectionParameters.Builder builder=player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type);
            if(groups.get(index)==null){builder.setTrackTypeDisabled(type,true);}
            else {
                builder.setTrackTypeDisabled(type,false);
                builder.addOverride(new TrackSelectionOverride(groups.get(index).getMediaTrackGroup(),indexes.get(index)));
            }
            player.setTrackSelectionParameters(builder.build());
            status.setText(labels.get(index).replace(" ✓","")+" selected");
            showControls();
        });
    }

    private interface Choice{void select(int index);}
    private void showDialog(String title,List<String> options,Choice choice){
        handler.removeCallbacks(hideControls);
        dialog=new Dialog(this);
        LinearLayout box=Ui.column(this);
        Ui.pad(box,this,27,25,27,26);
        box.setBackground(Ui.rounded(0xFF191B23,18,this));
        TextView heading=Ui.heading(this,title,23);box.addView(heading);
        TextView hint=Ui.text(this,title.equals("Up next")?"Next episode starts automatically":"Choose with your remote",14);
        if(title.equals("Up next"))nextCountdown=hint;
        hint.setTextColor(0xFFACACB6);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.bottomMargin=Ui.dp(this,14);box.addView(hint,hp);
        int count=Math.min(options.size(),12);
        for(int i=0;i<count;i++){
            final int selected=i;
            TextView option=Ui.text(this,options.get(i),17);
            Ui.pad(option,this,18,12,18,12);
            option.setFocusable(true);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,Ui.dp(this,51));
            p.bottomMargin=Ui.dp(this,4);box.addView(option,p);
            option.setBackground(Ui.rounded(0xFF282A34,9,this));
            option.setOnFocusChangeListener((v,focused)->v.setBackground(Ui.rounded(focused?Ui.RED:0xFF282A34,9,this)));
            option.setOnClickListener(v->{Dialog active=dialog;choice.select(selected);if(active!=null && active.isShowing())active.dismiss();});
            if(i==0)option.post(option::requestFocus);
        }
        dialog.setContentView(box);
        Window w=dialog.getWindow();
        if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout(Ui.dp(this,510),WindowManager.LayoutParams.WRAP_CONTENT);}
        dialog.setOnDismissListener(d->{dialog=null;nextCountdown=null;scheduleHide();});
        dialog.show();
        Window shown=dialog.getWindow();
        if(shown!=null)shown.setLayout(Math.min(Ui.dp(this,510),getResources().getDisplayMetrics().widthPixels-Ui.dp(this,80)),WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private final Runnable progress=new Runnable(){@Override public void run(){
        if(player==null)return;
        long duration=player.getDuration(),current=player.getCurrentPosition();
        if(player.isPlaying() && current>=15000 && current/5000!=lastSavedAt/5000){saveProgress(current,duration);lastSavedAt=current;}
        if(duration>0 && duration!=C.TIME_UNSET){
            if(!scrubbing){timeline.setProgress((int)Math.min(1000,current*1000/duration));clock.setText(time(current)+" / "+time(duration));}
            long remaining=duration-current;
            if(nextUrl!=null && !nextUrl.isEmpty() && !nextCanceled && remaining>0){
                if(remaining<=25000 && !promptShown){promptShown=true;showNext();}
                if(dialog!=null && dialog.isShowing() && remaining<=25000 && nextCountdown!=null)
                    nextCountdown.setText("Next episode in "+Math.max(1,(remaining+999)/1000)+" seconds");
            }
        }
        handler.postDelayed(this,1000);
    }};
    private TextView nextCountdown;
    private long lastSavedAt;
    private void saveProgress(long current,long duration){
        if(!ended && kind!=null && contentId!=null && !contentId.isEmpty())
            Catalog.saveProgress(this,kind,contentId,episodeId,episodeExt,nextEpisodeId,nextEpisodeExt,current,duration);
    }
    private void clearProgress(){if(kind!=null && contentId!=null && !contentId.isEmpty())Catalog.clearProgress(this,kind,contentId);}
    private void showNext(){
        List<String> choices=new ArrayList<>();choices.add("Play next now");choices.add("Cancel autoplay");
        showDialog("Up next",choices,index->{if(index==0)playNext();else nextCanceled=true;});
    }
    private void playNext(){
        if(nextUrl==null || nextUrl.isEmpty() || player==null)return;
        if(dialog!=null){dialog.dismiss();dialog=null;}
        url=nextUrl;nextUrl="";position=0;promptShown=false;ended=false;lastSavedAt=0;
        episodeId=nextEpisodeId;episodeExt=nextEpisodeExt;nextEpisodeId="";nextEpisodeExt="";
        player.setMediaItem(MediaItem.fromUri(url));player.prepare();player.play();
    }
    @Override protected void onStop(){
        handler.removeCallbacksAndMessages(null);
        if(dialog!=null){dialog.dismiss();dialog=null;}
        if(player!=null){position=player.getCurrentPosition();if(!ended)saveProgress(position,player.getDuration());video.setPlayer(null);player.release();player=null;}
        super.onStop();
    }
}

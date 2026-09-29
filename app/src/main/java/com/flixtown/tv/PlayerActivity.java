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
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView video;
    private FrameLayout root;
    private LinearLayout controls;
    private TextView clock, playButton, status, seekPreview;
    private SeekBar timeline;
    private String url, nextUrl;
    private String kind, contentId, episodeId, episodeExt, nextEpisodeId, nextEpisodeExt;
    private long position;
    private boolean nextCanceled, promptShown, scrubbing, ended;
    private long previewPosition=-1;
    private int seekDirection, seekRepeats;
    private Dialog dialog;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        url=getIntent().getStringExtra("url");
        nextUrl=getIntent().getStringExtra("next_url");
        kind=getIntent().getStringExtra("content_kind");contentId=getIntent().getStringExtra("content_id");
        episodeId=getIntent().getStringExtra("episode_id");episodeExt=getIntent().getStringExtra("episode_ext");
        nextEpisodeId=getIntent().getStringExtra("next_episode_id");nextEpisodeExt=getIntent().getStringExtra("next_episode_ext");
        if(kind!=null && contentId!=null && !contentId.isEmpty() && !getIntent().getBooleanExtra("start_over",false)){
            String key=kind+":"+contentId;
            position=Api.prefs(this).getLong("resume_position_"+key,0);
            // Series progress is stored per show; only reuse it for the episode it was saved for.
            if("series".equals(kind) && (episodeId==null || !episodeId.equals(Api.prefs(this).getString("resume_episode_"+key,""))))position=0;
        }
        if(url==null || url.isEmpty()){finish();return;}
        setContentView(R.layout.activity_player);
        root=findViewById(R.id.player_root);
        video=findViewById(R.id.player_view);
        video.setUseController(false);
        video.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        buildControls();
        root.requestFocus();
    }

    private void buildControls() {
        controls=findViewById(R.id.player_overlay);
        TextView label=findViewById(R.id.player_title);
        label.setText(getIntent().getStringExtra("title")==null?"FLIX TOWN":getIntent().getStringExtra("title"));
        seekPreview=findViewById(R.id.seek_preview);
        timeline=findViewById(R.id.player_timeline);
        clock=findViewById(R.id.player_clock);
        status=findViewById(R.id.player_status);
        timeline.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFE7151E));
        timeline.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFE7151E));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){scrubbing=true;handler.removeCallbacks(hideControls);}
            public void onProgressChanged(SeekBar b,int progress,boolean fromUser){
                if(fromUser && player!=null && player.getDuration()>0)
                    clock.setText(time(player.getDuration()*progress/1000)+" / "+time(player.getDuration()));
            }
            public void onStopTrackingTouch(SeekBar b){
                if(player!=null && player.getDuration()>0)player.seekTo(player.getDuration()*b.getProgress()/1000);
                scrubbing=false;scheduleHide();
            }
        });
        timeline.setOnKeyListener((v,key,event)->{
            if(event.getAction()==KeyEvent.ACTION_DOWN && (key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT)){
                previewSeek(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1,event.getRepeatCount());return true;
            }
            return false;
        });
        LinearLayout actions=findViewById(R.id.player_actions);
        addAction(actions,"−10s",()->seek(-10000));
        playButton=addAction(actions,"Pause",()->{if(player==null)return;togglePlay();updatePlay();scheduleHide();});
        addAction(actions,"+10s",()->seek(10000));
        addAction(actions,"Subtitles",()->showTracks(C.TRACK_TYPE_TEXT));
        addAction(actions,"Audio",()->showTracks(C.TRACK_TYPE_AUDIO));
        addAction(actions,"Speed",this::showSpeed);
    }

    private TextView addAction(LinearLayout parent,String label,Runnable click) {
        TextView button=Ui.text(this,label,16);
        button.setTypeface(null,Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);button.setFocusableInTouchMode(true);button.setClickable(true);
        button.setBackground(Ui.focusSurface(this,false));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,Ui.dp(this,49),1);
        bp.setMargins(Ui.dp(this,5),0,Ui.dp(this,5),0);
        parent.addView(button,bp);
        button.setOnClickListener(v->{click.run();showControls();});
        button.setOnFocusChangeListener((v,focused)->{
            button.setBackground(Ui.focusSurface(this,focused));
            v.animate().scaleX(focused?1.06f:1f).scaleY(focused?1.06f:1f).setDuration(100).start();
            if(focused)showControls();
        });
        return button;
    }
    private void showControls(){controls.setVisibility(View.VISIBLE);scheduleHide();}
    private void scheduleHide(){handler.removeCallbacks(hideControls);if(player!=null && player.isPlaying() && dialog==null && !scrubbing)handler.postDelayed(hideControls,3000);}
    private final Runnable hideControls=()->{
        if(previewPosition>=0 || scrubbing || dialog!=null)return;
        root.requestFocus();controls.setVisibility(View.GONE);
    };
    private void previewSeek(int direction,int repeats){
        if(player==null)return;
        long duration=player.getDuration();
        if(previewPosition<0)previewPosition=player.getCurrentPosition();
        seekRepeats=direction==seekDirection?Math.max(seekRepeats,repeats):0;
        seekDirection=direction;
        long step=seekRepeats>=12?60000:seekRepeats>=5?30000:10000;
        previewPosition=Math.max(0,previewPosition+direction*step);
        if(duration>0 && duration!=C.TIME_UNSET)previewPosition=Math.min(duration,previewPosition);
        seekPreview.setText("Seek to " + time(previewPosition) + "   •   OK to play   •   Back to cancel");
        showControls();handler.removeCallbacks(commitPreview);handler.postDelayed(commitPreview,2400);
    }
    private final Runnable commitPreview=()->{
        if(previewPosition<0 || player==null)return;
        player.seekTo(previewPosition);previewPosition=-1;seekPreview.setText("");scheduleHide();
    };
    private void showSpeed(){
        if(player==null)return;
        float[] speeds={0.75f,1f,1.25f,1.5f,2f};
        List<String> choices=new ArrayList<>();
        float current=player.getPlaybackParameters().speed;int selected=1;
        for(int i=0;i<speeds.length;i++){float speed=speeds[i];
            // "%.2g" rendered 1.25 as "1.3×"; print the exact value instead.
            String label=speed==1f?"Normal (1×)":new java.math.BigDecimal(Float.toString(speed)).stripTrailingZeros().toPlainString()+"×";
            if(Math.abs(speed-current)<0.01f){label+=" ✓";selected=i;}
            choices.add(label);}
        showDialog("Playback speed",choices,selected,index->{player.setPlaybackSpeed(speeds[index]);status.setText(choices.get(index).replace(" ✓","")+" speed");});
    }
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
    private void togglePlay(){
        if(player==null)return;
        if(player.getPlaybackState()==Player.STATE_IDLE){retry();return;}
        if(player.isPlaying())player.pause();else player.play();
    }
    private void retry(){
        if(player==null)return;
        status.setText("Retrying…");
        player.prepare();player.play();
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
        // Xtream servers commonly redirect streams to another host, sometimes from http to https.
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000);
        player=new ExoPlayer.Builder(this,new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this,http))).build();
        video.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        if(position>0)player.seekTo(position);
        player.play();
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(state==Player.STATE_READY)status.setText("");
                if(state==Player.STATE_ENDED){ended=true;clearProgress();if(Api.prefs(PlayerActivity.this).getBoolean("autoplay_next",true) && !nextCanceled && nextUrl!=null && !nextUrl.isEmpty())playNext();}
            }
            @Override public void onIsPlayingChanged(boolean playing){
                // Stop Google TV's ambient mode from starting during a movie.
                video.setKeepScreenOn(playing);
                updatePlay();if(playing)scheduleHide();else showControls();}
            @Override public void onPlayerError(PlaybackException error){
                android.util.Log.w("FlixTownPlayer","Playback failed",error);
                status.setText("This stream could not be played ("+error.getErrorCodeName()+"). Press OK to try again.");
                updatePlay();showControls();playButton.requestFocus();
            }
        });
        handler.post(progress);
        showControls();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(player!=null && event.getAction()==KeyEvent.ACTION_DOWN && dialog==null){
            int key=event.getKeyCode();
            if(previewPosition>=0){
                if(key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER){handler.removeCallbacks(commitPreview);commitPreview.run();return true;}
                if(key==KeyEvent.KEYCODE_BACK){handler.removeCallbacks(commitPreview);previewPosition=-1;seekPreview.setText("");scheduleHide();return true;}
            }
            if(key==KeyEvent.KEYCODE_BACK && event.getRepeatCount()==0 && controls.getVisibility()==View.VISIBLE && player.isPlaying()){
                handler.removeCallbacks(hideControls);hideControls.run();return true;
            }
            if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){togglePlay();showControls();return true;}
            if(key==KeyEvent.KEYCODE_MEDIA_PLAY){player.play();showControls();return true;}
            if(key==KeyEvent.KEYCODE_MEDIA_PAUSE){player.pause();showControls();return true;}
            if(key==KeyEvent.KEYCODE_MEDIA_REWIND){previewSeek(-1,event.getRepeatCount());return true;}
            if(key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD){previewSeek(1,event.getRepeatCount());return true;}
            boolean focusOnControl=controls!=null && controls.hasFocus();
            if(key==KeyEvent.KEYCODE_DPAD_LEFT && !focusOnControl){previewSeek(-1,event.getRepeatCount());return true;}
            if(key==KeyEvent.KEYCODE_DPAD_RIGHT && !focusOnControl){previewSeek(1,event.getRepeatCount());return true;}
            if((key==KeyEvent.KEYCODE_DPAD_UP || key==KeyEvent.KEYCODE_DPAD_DOWN) && !focusOnControl){showControls();playButton.requestFocus();return true;}
            if((key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER) && !focusOnControl){
                togglePlay();
                showControls();return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void showTracks(int type){
        if(player==null)return;
        List<Tracks.Group> groups=new ArrayList<>();
        List<Integer> indexes=new ArrayList<>();
        List<String> labels=new ArrayList<>();
        int selected=0;boolean anySelected=false;
        if(type==C.TRACK_TYPE_TEXT){labels.add("Off");groups.add(null);indexes.add(-1);}
        int number=0;
        for(Tracks.Group group:player.getCurrentTracks().getGroups()){
            if(group.getType()!=type)continue;
            for(int i=0;i<group.length;i++){
                number++;
                Format f=group.getTrackFormat(i);
                String label=trackLabel(f,type,number);
                if(!group.isTrackSupported(i))label+=" — not supported on this TV";
                else if(group.isTrackSelected(i)){label+=" ✓";if(!anySelected)selected=labels.size();anySelected=true;}
                labels.add(label);groups.add(group);indexes.add(i);
            }
        }
        if(type==C.TRACK_TYPE_TEXT && !anySelected)labels.set(0,"Off ✓");
        if(labels.isEmpty() || (type==C.TRACK_TYPE_TEXT && labels.size()==1)){
            status.setText(type==C.TRACK_TYPE_TEXT?"No subtitles in this video":"No audio tracks reported by this stream");showControls();return;}
        showDialog(type==C.TRACK_TYPE_TEXT?"Subtitles":"Audio",labels,selected,index->{
            if(player==null)return;
            Tracks.Group group=groups.get(index);
            if(group!=null && !group.isTrackSupported(indexes.get(index))){
                status.setText("That audio format can't be decoded on this device");showControls();return;}
            TrackSelectionParameters.Builder builder=player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type);
            if(group==null){builder.setTrackTypeDisabled(type,true);}
            else {
                builder.setTrackTypeDisabled(type,false);
                builder.addOverride(new TrackSelectionOverride(group.getMediaTrackGroup(),indexes.get(index)));
            }
            player.setTrackSelectionParameters(builder.build());
            status.setText(labels.get(index).replace(" ✓","")+" selected");
            showControls();
        });
    }
    private static String trackLabel(Format f,int type,int number){
        String language=f.language==null||f.language.isEmpty()||"und".equals(f.language)?"":new Locale(f.language).getDisplayLanguage(Locale.US);
        String name=f.label!=null&&!f.label.isEmpty()?f.label:!language.isEmpty()?language:(type==C.TRACK_TYPE_TEXT?"Subtitle ":"Track ")+number;
        StringBuilder out=new StringBuilder(name);
        if(type==C.TRACK_TYPE_AUDIO){
            int ch=f.channelCount;
            if(ch==1)out.append(" · Mono");else if(ch==2)out.append(" · Stereo");else if(ch==6)out.append(" · 5.1");else if(ch==8)out.append(" · 7.1");else if(ch>0)out.append(" · ").append(ch).append(" ch");
            String codec=codecName(f.sampleMimeType);if(!codec.isEmpty())out.append(" · ").append(codec);
        }else if(type==C.TRACK_TYPE_TEXT && (f.selectionFlags&C.SELECTION_FLAG_FORCED)!=0)out.append(" (forced)");
        return out.toString();
    }
    private static String codecName(String mime){
        if(mime==null)return "";
        switch(mime){
            case "audio/mp4a-latm":return "AAC";
            case "audio/ac3":return "Dolby Digital";
            case "audio/eac3":case "audio/eac3-joc":return "Dolby Digital Plus";
            case "audio/true-hd":return "Dolby TrueHD";
            case "audio/vnd.dts":case "audio/vnd.dts.hd":return "DTS";
            case "audio/mpeg":return "MP3";
            case "audio/opus":return "Opus";
            default:return "";
        }
    }

    private interface Choice{void select(int index);}
    private void showDialog(String title,List<String> options,Choice choice){showDialog(title,options,0,choice);}
    private void showDialog(String title,List<String> options,int focusIndex,Choice choice){
        handler.removeCallbacks(hideControls);
        dialog=new Dialog(this);
        LinearLayout box=Ui.column(this);
        Ui.pad(box,this,32,29,32,27);
        box.setBackground(Ui.panel(this,19));
        View accent=new View(this);accent.setBackground(Ui.rounded(Ui.RED,3,this));
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(Ui.dp(this,45),Ui.dp(this,3));ap.bottomMargin=Ui.dp(this,18);box.addView(accent,ap);
        TextView heading=Ui.heading(this,title,23);box.addView(heading);
        TextView hint=Ui.text(this,title.equals("Up next")?"Next episode starts automatically":"Choose with your remote",14);
        if(title.equals("Up next"))nextCountdown=hint;
        hint.setTextColor(0xFFACACB6);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=Ui.dp(this,5);hp.bottomMargin=Ui.dp(this,19);box.addView(hint,hp);
        int count=options.size();
        android.widget.ScrollView scroller=new android.widget.ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);scroller.setClipToPadding(false);box.addView(scroller,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout optionList=Ui.column(this);scroller.addView(optionList);
        for(int i=0;i<count;i++){
            final int selected=i;
            TextView option=Ui.text(this,options.get(i),17);
            Ui.pad(option,this,18,12,18,12);
            option.setFocusable(true);option.setFocusableInTouchMode(true);option.setClickable(true);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,Ui.dp(this,51));
            p.bottomMargin=Ui.dp(this,7);optionList.addView(option,p);
            option.setBackground(Ui.focusSurface(this,false));
            option.setOnFocusChangeListener((v,focused)->v.setBackground(Ui.focusSurface(this,focused)));
            option.setOnClickListener(v->{Dialog active=dialog;choice.select(selected);if(active!=null && active.isShowing())active.dismiss();});
            if(i==Math.max(0,Math.min(focusIndex,count-1)))option.post(option::requestFocus);
        }
        dialog.setContentView(box);
        Window w=dialog.getWindow();
        if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout(Ui.dp(this,510),WindowManager.LayoutParams.WRAP_CONTENT);}
        dialog.setOnDismissListener(d->{dialog=null;nextCountdown=null;scheduleHide();});
        dialog.show();
        Window shown=dialog.getWindow();
        if(shown!=null)shown.setLayout(Math.min(Ui.dp(this,510),getResources().getDisplayMetrics().widthPixels-Ui.dp(this,80)),Math.min(Ui.dp(this,570),getResources().getDisplayMetrics().heightPixels-Ui.dp(this,60)));
    }

    private final Runnable progress=new Runnable(){@Override public void run(){
        if(player==null)return;
        long duration=player.getDuration(),current=player.getCurrentPosition();
        if(player.isPlaying() && current>=15000 && current/5000!=lastSavedAt/5000){saveProgress(current,duration);lastSavedAt=current;}
        if(duration>0 && duration!=C.TIME_UNSET){
            if(!scrubbing){timeline.setProgress((int)Math.min(1000,(previewPosition>=0?previewPosition:current)*1000/duration));clock.setText(time(previewPosition>=0?previewPosition:current)+" / "+time(duration));}
            long remaining=duration-current;
            if(Api.prefs(PlayerActivity.this).getBoolean("autoplay_next",true) && nextUrl!=null && !nextUrl.isEmpty() && !nextCanceled && remaining>0){
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
    /** A finished movie leaves Continue Watching; a finished episode queues the next one there. */
    private void clearProgress(){if(kind!=null && contentId!=null && !contentId.isEmpty())Catalog.markFinished(this,kind,contentId,nextEpisodeId,nextEpisodeExt);}
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

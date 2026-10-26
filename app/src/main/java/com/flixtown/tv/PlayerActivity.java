package com.flixtown.tv;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Media3 player with a minimal TV overlay.
 *
 * Hidden overlay: the video is unobstructed; OK plays/pauses, Left/Right seek 10 seconds,
 * Up/Down show the controls, Back leaves playback. Visible overlay: a title at the top, a small
 * row (play/pause, audio, subtitles) and a thin progress line with elapsed and remaining time.
 * It hides after about 3 seconds without a key press; Back hides it first.
 * Partially watched titles open with a compact Continue / Start over choice.
 */
public class PlayerActivity extends Activity {
    private static final long HIDE_MS=3000,SEEK_STEP_MS=10000,SEEK_COMMIT_MS=450,REPEAT_GAP_MS=150;
    private ExoPlayer player;
    private PlayerView video;
    private FrameLayout root;
    private View controls,card;
    private TextView elapsed,remaining,status,audioLabel,subtitleLabel,cardTitle,cardMessage;
    private LinearLayout cardActions;
    private ProgressLine progressLine;
    private LinearLayout playButton;private PlayerIcon playIcon;
    private String url,nextUrl,title,subtitle,nextSubtitle;
    private String kind,contentId,episodeId,episodeExt,nextEpisodeId,nextEpisodeExt;
    private long position;
    private boolean nextCanceled,promptShown,ended,resumeAsked,cardForNext,cardForError;
    private long pendingSeek=-1,lastRepeatAt;
    private Dialog dialog;
    private SubtitlePlacement subtitles;
    private long lastSavedAt,holdStartedAt;
    private View reportButton,previousButton,nextButton,optionsButton;
    private EpisodeNavigation episodeNavigation;private boolean nextAfterMenu;
    private org.json.JSONObject episodeCatalog;private boolean loadingEpisodes;private int episodeLoadGeneration;
    private boolean reporting,reportWasPlaying,reportFromError,stopping,reportEnded;
    private String lastPlayerError="";private boolean lastErrorNetwork;
    private ExoPlayer reportPlayer;private Boolean reportRestartReady;

    /** Episodes after the next one (from Details), so autoplay keeps going through the series. */
    static final int MAX_QUEUE=200;
    private String[] laterIds,laterExts,laterLabels;private int laterIndex;private boolean laterTruncated;
    /** True when an empty "next" really is the end of the show (Details sent the complete list of later episodes). */
    private boolean endKnown(){return episodeNavigation!=null?episodeNavigation.completeAfter:laterIds!=null && !laterTruncated;}
    private final Handler handler=new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);Logs.quietMedia();
        url=getIntent().getStringExtra("url");
        nextUrl=getIntent().getStringExtra("next_url");
        title=getIntent().getStringExtra("title");subtitle=getIntent().getStringExtra("subtitle");nextSubtitle=getIntent().getStringExtra("next_subtitle");
        kind=getIntent().getStringExtra("content_kind");contentId=getIntent().getStringExtra("content_id");
        episodeId=getIntent().getStringExtra("episode_id");episodeExt=getIntent().getStringExtra("episode_ext");
        nextEpisodeId=getIntent().getStringExtra("next_episode_id");nextEpisodeExt=getIntent().getStringExtra("next_episode_ext");
        laterIds=getIntent().getStringArrayExtra("later_episode_ids");laterExts=getIntent().getStringArrayExtra("later_episode_exts");
        laterLabels=getIntent().getStringArrayExtra("later_episode_labels");laterTruncated=getIntent().getBooleanExtra("later_truncated",false);
        if("series".equals(kind) && episodeId!=null && !episodeId.isEmpty()){
            if(state!=null && state.containsKey("navigation_ids")){
                episodeNavigation=new EpisodeNavigation(state.getStringArray("navigation_ids"),state.getStringArray("navigation_exts"),
                    state.getStringArray("navigation_labels"),state.getInt("navigation_index"),state.getBoolean("navigation_complete"));
                EpisodeNavigation.Episode current=episodeNavigation.current();episodeId=current.id;episodeExt=current.extension;subtitle=current.label;
                url=state.getString("current_url",url);nextCanceled=state.getBoolean("next_canceled");
            }else episodeNavigation=EpisodeNavigation.from(getIntent().getStringArrayExtra("previous_episode_ids"),
                getIntent().getStringArrayExtra("previous_episode_exts"),getIntent().getStringArrayExtra("previous_episode_labels"),
                episodeId,episodeExt,subtitle,nextEpisodeId,nextEpisodeExt,nextSubtitle,laterIds,laterExts,laterLabels,laterTruncated);
            syncEpisodeNeighbors();
        }
        if(kind!=null && contentId!=null && !contentId.isEmpty() && !getIntent().getBooleanExtra("start_over",false)){
            String key=kind+":"+contentId;
            position=Api.prefs(this).getLong("resume_position_"+key,0);
            // Series progress is stored per show; only reuse it for the episode it was saved for.
            if("series".equals(kind) && (episodeId==null || !episodeId.equals(Api.prefs(this).getString("resume_episode_"+key,""))))position=0;
        }
        if(position<15000)position=0;
        resumeAsked=position==0 || getIntent().getBooleanExtra("resume_now",false)
            || (state!=null && state.getBoolean("resume_asked",false));
        if(state!=null)position=state.getLong("position",position);
        if(url==null || url.isEmpty()){finish();return;}
        setContentView(R.layout.activity_player);
        root=findViewById(R.id.player_root);
        video=findViewById(R.id.player_view);
        video.setUseController(false);
        video.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        video.setShutterBackgroundColor(0xFF000000);
        subtitles=new SubtitlePlacement(this,root,video,findViewById(R.id.player_overlay),findViewById(R.id.player_bottom));applySubtitleAppearance();
        buildControls();
        root.requestFocus();
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);
        out.putBoolean("resume_asked",resumeAsked);out.putLong("position",player!=null?player.getCurrentPosition():position);
        if(episodeNavigation!=null){out.putStringArray("navigation_ids",episodeNavigation.ids());out.putStringArray("navigation_exts",episodeNavigation.extensions());
            out.putStringArray("navigation_labels",episodeNavigation.labels());out.putInt("navigation_index",episodeNavigation.index());
            out.putBoolean("navigation_complete",episodeNavigation.completeAfter);out.putString("current_url",url);out.putBoolean("next_canceled",nextCanceled);}}

    /* ---------------- Controls ---------------- */

    private void buildControls() {
        controls=findViewById(R.id.player_overlay);card=findViewById(R.id.player_card);
        cardTitle=findViewById(R.id.player_card_title);cardMessage=findViewById(R.id.player_card_message);cardActions=findViewById(R.id.player_card_actions);
        ((TextView)findViewById(R.id.player_title)).setText(title==null?"Flix Town":title);
        showSubtitle();
        ((TextView)findViewById(R.id.player_title)).setPadding(0,0,Ui.dp(this,160),0);
        elapsed=findViewById(R.id.player_elapsed);remaining=findViewById(R.id.player_remaining);status=findViewById(R.id.player_status);
        progressLine=new ProgressLine(this);
        ((FrameLayout)findViewById(R.id.player_progress_slot)).addView(progressLine,new FrameLayout.LayoutParams(-1,-1));
        progressLine.setOnKeyListener((v,key,event)->{
            if(event.getAction()==KeyEvent.ACTION_DOWN && (key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT)){
                seekBy(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1,event);return true;}
            if(event.getAction()==KeyEvent.ACTION_DOWN && Ui.isSelect(key)){togglePlay();return true;}
            return false;
        });
        Button options=Ui.button(this,"Options");optionsButton=options;
        FrameLayout.LayoutParams op=new FrameLayout.LayoutParams(-2,Ui.dp(this,46),Gravity.TOP|Gravity.END);
        op.topMargin=Ui.dp(this,30);op.rightMargin=Ui.safeX(this);((FrameLayout)controls).addView(options,op);
        options.setOnClickListener(v->showOptions());
        LinearLayout actions=findViewById(R.id.player_actions);
        playButton=controlButton(actions,PlayerIcon.PAUSE,null,this::togglePlay);
        playIcon=(PlayerIcon)playButton.getChildAt(0);
        LinearLayout audio=controlButton(actions,PlayerIcon.AUDIO,"Audio",()->showTracks(C.TRACK_TYPE_AUDIO));audioButton=audio;
        audioLabel=(TextView)audio.getChildAt(1);
        if(episodeNavigation!=null){audioLabel.setMaxWidth(Ui.dp(this,90));audioLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);}
        LinearLayout subs=controlButton(actions,PlayerIcon.SUBTITLES,"Subtitles",()->showTracks(C.TRACK_TYPE_TEXT));subsButton=subs;
        subtitleLabel=(TextView)subs.getChildAt(1);
        if(episodeNavigation!=null){subtitleLabel.setMaxWidth(Ui.dp(this,100));subtitleLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);}
        if(reportable()){
            reportButton=controlButton(actions,PlayerIcon.REPORT,"Report",this::showReport);
            reportButton.setContentDescription("Report a problem");
        }
        if(episodeNavigation!=null){
            View spacer=new View(this);
            actions.addView(spacer,new LinearLayout.LayoutParams(0,1,1f));
            LinearLayout episodes=actions;
            previousButton=controlButton(episodes,PlayerIcon.PREVIOUS,"Previous episode",()->navigateEpisode(-1,true));
            previousButton.setContentDescription("Previous episode");
            nextButton=controlButton(episodes,PlayerIcon.NEXT,"Next episode",()->navigateEpisode(1,true));
            nextButton.setContentDescription("Next episode");
            updateEpisodeButtons();
        }
    }
    private void showSubtitle(){TextView sub=findViewById(R.id.player_subtitle);
        sub.setVisibility(subtitle==null||subtitle.isEmpty()?View.GONE:View.VISIBLE);sub.setText(subtitle);}
    /** Small refined control: a round icon button, or an icon + label pill. */
    private LinearLayout controlButton(LinearLayout parent,int icon,String label,Runnable click){
        LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.HORIZONTAL);b.setGravity(Gravity.CENTER);
        b.setFocusable(true);b.setFocusableInTouchMode(true);b.setClickable(true);
        boolean round=label==null;
        boolean compact=episodeNavigation!=null;
        PlayerIcon glyph=new PlayerIcon(this,icon);
        b.addView(glyph,new LinearLayout.LayoutParams(Ui.dp(this,round?22:20),Ui.dp(this,round?22:20)));
        if(!round){TextView t=Ui.text(this,label,compact?14:15);t.setTextColor(Ui.TEXT);t.setSingleLine(true);
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.leftMargin=Ui.dp(this,compact?6:8);b.addView(t,tp);
            b.setPadding(Ui.dp(this,compact?12:18),0,Ui.dp(this,compact?12:20),0);}
        Ui.styleButton(b,Ui.SECONDARY);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(round?Ui.dp(this,50):ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,50));
        if(parent.getChildCount()>0)p.leftMargin=Ui.dp(this,8);
        parent.addView(b,p);
        b.setOnClickListener(v->{click.run();showControls(null);});
        return b;
    }
    private boolean controlsVisible(){return controls.getVisibility()==View.VISIBLE;}
    /** Shows the overlay and focuses {@code target} (or keeps the current control focused). */
    private void showControls(View target){
        if(card.getVisibility()==View.VISIBLE)return;
        controls.setVisibility(View.VISIBLE);if(subtitles!=null)subtitles.controlsChanged();
        if(target!=null)target.requestFocus();
        else if(!controls.hasFocus())playButton.requestFocus();
        updateProgressUi();scheduleHide();
    }
    private void scheduleHide(){handler.removeCallbacks(hideControls);if(dialog==null)handler.postDelayed(hideControls,HIDE_MS);}
    private final Runnable hideControls=()->{
        if(dialog!=null || pendingSeek>=0){scheduleHide();return;}
        controls.setVisibility(View.GONE);status.setVisibility(View.GONE);if(subtitles!=null)subtitles.controlsChanged();root.requestFocus();
    };
    private void hideNow(){handler.removeCallbacks(hideControls);hideControls.run();}
    private void flash(String text){status.setText(text);status.setVisibility(View.VISIBLE);}

    /* ---------------- Playback actions ---------------- */

    private void togglePlay(){
        if(player==null)return;
        if(player.getPlaybackState()==Player.STATE_IDLE){retry();return;}
        if(player.isPlaying())player.pause();else player.play();
    }
    private void retry(){if(player==null)return;hideCard();player.prepare();player.play();}
    /**
     * Left/Right (and rewind/fast-forward): 10 seconds per press; holding speeds up (see {@link SeekStep}).
     * Presses are combined into one pending target, shown as a time and a white marker on the progress
     * line, and applied once the remote goes quiet. Play/pause is kept; Back cancels a pending target.
     */
    private void seekBy(int direction,KeyEvent event){
        if(player==null)return;
        if(!player.isCurrentMediaItemSeekable()){
            flash("This video can't be skipped forward or back.");if(!controlsVisible())showControls(progressLine);else scheduleHide();qaState();return;}
        long now=SystemClock.uptimeMillis();
        if(event.getRepeatCount()==0)holdStartedAt=now;
        else if(now-lastRepeatAt<REPEAT_GAP_MS)return;
        lastRepeatAt=now;
        long base=pendingSeek>=0?pendingSeek:player.getCurrentPosition();
        long duration=player.getDuration();if(duration==C.TIME_UNSET)duration=0;
        long step=SeekStep.step(event.getRepeatCount()==0?0:now-holdStartedAt);
        pendingSeek=SeekStep.clamp(base+direction*step,duration);
        handler.removeCallbacks(commitSeek);handler.postDelayed(commitSeek,SEEK_COMMIT_MS);
        if(!controlsVisible())showControls(progressLine);else scheduleHide();
        showSeekPreview();updateProgressUi();qaState();
    }
    /** The pending target in the status line ("→ 1:02:30  (+12:00)") and as a marker; playback time stays as is. */
    private void showSeekPreview(){
        if(player==null||pendingSeek<0)return;
        flash("→ "+SeekStep.time(pendingSeek)+"   ("+SeekStep.offset(player.getCurrentPosition(),pendingSeek)+")");
        progressLine.setPending(pendingSeek);
    }
    private void clearPendingSeek(){
        handler.removeCallbacks(commitSeek);pendingSeek=-1;
        if(progressLine!=null)progressLine.setPending(-1);
        if(status!=null)status.setVisibility(View.GONE);
    }
    private final Runnable commitSeek=()->{
        if(pendingSeek<0 || player==null)return;
        long target=pendingSeek;clearPendingSeek();
        player.seekTo(target);updateProgressUi();scheduleHide();qaState();
    };
    /** Player options › Go to time. */
    private void goToTime(){
        if(player==null)return;
        long duration=player.getDuration();
        if(!player.isCurrentMediaItemSeekable()||duration<=0||duration==C.TIME_UNSET){flash("Go to time isn't available for this video.");showControls(optionsButton);return;}
        clearPendingSeek();handler.removeCallbacks(hideControls);
        Dialog opened=GoToTime.show(this,player.getCurrentPosition(),duration,ms->{
            dialog=null;if(player==null)return;player.seekTo(ms);updateProgressUi();showControls(progressLine);});
        dialog=opened;
        opened.setOnDismissListener(d->{if(dialog==opened){dialog=null;continueAfterMenu();showControls(optionsButton);}});
    }
    private void updatePlayIcon(){if(playIcon!=null && player!=null)playIcon.setType(player.isPlaying()||(player.getPlayWhenReady()&&player.getPlaybackState()==Player.STATE_BUFFERING)?PlayerIcon.PAUSE:PlayerIcon.PLAY);}
    private static String time(long ms){
        if(ms<0 || ms==C.TIME_UNSET)return "--:--";
        long seconds=ms/1000;
        return seconds>=3600?String.format(Locale.US,"%d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60)
            :String.format(Locale.US,"%d:%02d",seconds/60,seconds%60);
    }
    private void updateProgressUi(){
        if(player==null)return;
        long duration=player.getDuration(),current=pendingSeek>=0?pendingSeek:player.getCurrentPosition();
        boolean known=duration>0 && duration!=C.TIME_UNSET;
        elapsed.setText(time(current));
        remaining.setText(known?"-"+time(Math.max(0,duration-current)):"");
        progressLine.set(known?duration:0,current,player.getBufferedPosition());
    }

    /* ---------------- Lifecycle ---------------- */

    @Override protected void onStart(){
        super.onStart();
        if(url==null)return;
        stopping=false;
        // Xtream servers commonly redirect streams to another host, sometimes from http to https.
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000);
        player=new ExoPlayer.Builder(this,new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this,http))).build();
        video.setPlayer(player);
        applyPreferences(this,player);
        player.setPlaybackSpeed(ViewerOptions.speed(Api.prefs(this).getFloat(ViewerOptions.SPEED,1f)));
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(state==Player.STATE_ENDED){if(reporting){reportEnded=true;return;}ended=true;finishProgress();
                    if(Api.prefs(PlayerActivity.this).getBoolean("autoplay_next",true) && !nextCanceled && nextUrl!=null && !nextUrl.isEmpty())playNext();
                    else finish();}
                updatePlayIcon();
            }
            @Override public void onIsPlayingChanged(boolean playing){
                // Stop Google TV's ambient mode from starting during a movie.
                video.setKeepScreenOn(playing);updatePlayIcon();
            }
            @Override public void onPlayWhenReadyChanged(boolean playWhenReady,int reason){
                if(reporting&&playWhenReady&&player!=null){player.pause();return;}updatePlayIcon();}
            @Override public void onTracksChanged(Tracks tracks){updateTrackLabels();}
            @Override public void onCues(androidx.media3.common.text.CueGroup cueGroup){if(subtitles!=null)subtitles.show(cueGroup.cues);}
            @Override public void onPlayerError(PlaybackException error){
                android.util.Log.w("FlixTownPlayer","Playback failed: "+error.getErrorCodeName()+" "+Logs.safe(error));
                hideNow();
                // Connection problems (including a stream that timed out) are not a format problem.
                boolean network=(error.errorCode>=PlaybackException.ERROR_CODE_IO_UNSPECIFIED && error.errorCode<PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
                    || error.errorCode==PlaybackException.ERROR_CODE_TIMEOUT;
                lastPlayerError=error.getErrorCodeName();lastErrorNetwork=network;
                if(!reporting)showPlaybackError();
            }
        });
        player.setMediaItem(MediaItem.fromUri(url));
        if(position>0)player.seekTo(position);
        player.setPlayWhenReady(reportRestartReady==null?resumeAsked:reportRestartReady);reportRestartReady=null;
        player.prepare();
        if(!resumeAsked)askResume();
        handler.post(progress);
    }
    /** Compact Continue / Start over, shown before a partially watched title starts. */
    private void askResume(){
        showCard("Continue watching?",subtitle!=null&&!subtitle.isEmpty()?subtitle:title,
            new String[]{"Continue from "+time(position),"Start over"},0,i->{
                resumeAsked=true;
                if(i==1){position=0;player.seekTo(0);}
                player.play();
            });
    }
    private interface CardChoice{void select(int index);}
    private void showCard(String heading,String message,String[] labels,int focusIndex,CardChoice choice){
        // One card at a time: whatever card was up (for example Up next with its countdown) is replaced
        // completely, so its countdown, Back behaviour and buttons can never mix with the new card.
        cardForNext=false;cardForError=false;
        handler.removeCallbacks(hideControls);controls.setVisibility(View.GONE);if(subtitles!=null)subtitles.controlsChanged();
        cardTitle.setText(heading);cardMessage.setText(message==null?"":message);cardMessage.setVisibility(message==null||message.isEmpty()?View.GONE:View.VISIBLE);
        cardActions.removeAllViews();
        for(int i=0;i<labels.length;i++){final int index=i;
            Button b=i==focusIndex && !Ui.dismissive(labels[i])?Ui.primaryButton(this,labels[i]):Ui.button(this,labels[i]);b.setTextSize(16);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,48));
            if(i>0)p.leftMargin=Ui.dp(this,10);cardActions.addView(b,p);
            b.setOnClickListener(v->{hideCard();choice.select(index);});
        }
        card.setVisibility(View.VISIBLE);
        cardActions.getChildAt(Math.max(0,Math.min(focusIndex,labels.length-1))).requestFocus();
    }
    private void hideCard(){card.setVisibility(View.GONE);cardForNext=false;cardForError=false;root.requestFocus();}

    /* ---------------- Customer playback reports ---------------- */

    private boolean reportable(){
        return ("movie".equals(kind)||"series".equals(kind)) && PlaybackReport.id(contentId)>0
            && (!"series".equals(kind)||PlaybackReport.id(episodeId)>0) && !ReportsApi.url(this).isEmpty();
    }
    private void showPlaybackError(){
        String message=lastErrorNetwork?"The server didn't send the video. Check the connection and try again.":"This video's format isn't supported on this TV.";
        if(reportable())showCard("This title couldn't be played",message,new String[]{"Try again","Report a problem","Back"},0,i->{
            if(i==0)retry();else if(i==1){cardForError=true;showReport();}else finish();});
        else showCard("This title couldn't be played",message,new String[]{"Try again","Back"},0,i->{if(i==0)retry();else finish();});
        cardForError=true;   // after showCard, which starts every card with a clean state
    }
    private void showReport(){
        if(player==null||dialog!=null||reporting||!reportable())return;
        // Capture exactly this episode, before autoplay can change any IDs or track information.
        if(pendingSeek>=0){long target=pendingSeek;clearPendingSeek();player.seekTo(target);}
        reportFromError=cardForError;reportWasPlaying=player.getPlayWhenReady()&&!reportFromError;
        reportPlayer=player;reportEnded=false;reporting=true;player.pause();handler.removeCallbacks(hideControls);
        Format vf=player.getVideoFormat(),af=player.getAudioFormat();
        PlaybackReport snapshot=new PlaybackReport(kind,contentId,episodeId,player.getCurrentPosition(),android.os.Build.VERSION.SDK_INT,
            android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL,reportFormat(vf),reportFormat(af),lastPlayerError);
        showReportIssues(snapshot);
    }
    private static String reportFormat(Format f){
        if(f==null)return "";
        return (f.sampleMimeType==null?"":f.sampleMimeType)+(f.width>0?" "+f.width+"x"+f.height:"")+(f.channelCount>0?" "+f.channelCount+"ch":"");
    }
    private void showReportIssues(PlaybackReport snapshot){
        Dialog picker=Ui.picker(this,"Report a problem",PlaybackReport.LABELS,-1,issue->confirmReport(snapshot,issue));
        dialog=picker;
        picker.setOnDismissListener(d->handler.post(()->{if(dialog==picker){dialog=null;finishReporting();}}));
    }
    private void confirmReport(PlaybackReport snapshot,int issue){
        dialog=null;
        Dialog confirmation=Ui.dialog(this,"Send report?",PlaybackReport.LABELS[issue]+"\nTitle, playback time and basic device details will be included.",
            new String[]{"Send report","Cancel"},0,i->{dialog=null;if(i==0)sendReport(snapshot,issue);else finishReporting();},null);
        dialog=confirmation;
        confirmation.setOnDismissListener(d->handler.post(()->{if(dialog==confirmation){dialog=null;finishReporting();}}));
    }
    private void sendReport(PlaybackReport snapshot,int issue){
        String endpoint=ReportsApi.url(this);String[] account=AccountStore.read(this);
        final org.json.JSONObject payload;
        try{payload=snapshot.payload(issue);}catch(Exception e){finishReporting();return;}
        // No automatic retry and no title-specific report data saved to disk. A manual retry uses the same UUID.
        Dialog waiting=Ui.message(this,"Sending report…","Please wait.");dialog=waiting;
        waiting.setOnDismissListener(d->handler.post(()->{if(dialog==waiting){dialog=null;finishReporting();}}));
        Api.IO.execute(()->{
            ReportsApi.Reply result=ReportsApi.submit(endpoint,account,payload);
            runOnUiThread(()->{
                if(stopping||isFinishing()||isDestroyed()||!reporting||dialog!=waiting)return;
                dialog=null;waiting.dismiss();
                Dialog answer=Ui.dialog(this,result.ok?"Thank you":"Report not sent",result.message,
                    result.ok?new String[]{"Back to video"}:new String[]{"Try again","Back to video"},0,
                    i->{dialog=null;if(!result.ok&&i==0)sendReport(snapshot,issue);else finishReporting();},null);
                dialog=answer;
                answer.setOnDismissListener(d->handler.post(()->{if(dialog==answer){dialog=null;finishReporting();}}));
            });
        });
    }
    private void finishReporting(){
        if(!reporting)return;
        reporting=false;
        if(stopping||isFinishing()||player==null||player!=reportPlayer)return;
        if(reportFromError||player.getPlayerError()!=null){showPlaybackError();return;}
        if(reportEnded){
            ended=true;finishProgress();
            if(Api.prefs(this).getBoolean("autoplay_next",true)&&!nextCanceled&&nextUrl!=null&&!nextUrl.isEmpty())playNext();else finish();
            return;
        }
        if(reportWasPlaying)player.play();
        showControls(reportButton!=null?reportButton:playButton);
    }

    /* ---------------- Keys ---------------- */

    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int key=event.getKeyCode();
        if(player==null || dialog!=null)return super.dispatchKeyEvent(event);
        if(key==KeyEvent.KEYCODE_BACK){
            if(event.getAction()!=KeyEvent.ACTION_DOWN || event.getRepeatCount()>0)return true;
            if(loadingEpisodes){episodeLoadGeneration++;loadingEpisodes=false;}
            if(card.getVisibility()==View.VISIBLE){
                if(cardForNext){nextCanceled=true;hideCard();return true;}
                finish();return true;           // Back on Continue/Start over or an error leaves playback
            }
            if(pendingSeek>=0){clearPendingSeek();updateProgressUi();return true;}   // Back first cancels a pending seek
            if(controlsVisible()){hideNow();return true;}
            finish();return true;
        }
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return super.dispatchKeyEvent(event);
        if(card.getVisibility()==View.VISIBLE)return super.dispatchKeyEvent(event);
        switch(key){
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:togglePlay();showControls(null);return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:player.play();showControls(null);return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:player.pause();showControls(null);return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if(episodeNavigation!=null){if(event.getRepeatCount()==0)navigateEpisode(key==KeyEvent.KEYCODE_MEDIA_NEXT?1:-1,true);return true;}
                return super.dispatchKeyEvent(event);
            case KeyEvent.KEYCODE_MEDIA_REWIND:seekBy(-1,event);return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:seekBy(1,event);return true;
        }
        if(!controlsVisible()){
            if(Ui.isSelect(key)){togglePlay();if(!player.getPlayWhenReady())showControls(null);return true;}
            if(key==KeyEvent.KEYCODE_DPAD_LEFT){seekBy(-1,event);return true;}
            if(key==KeyEvent.KEYCODE_DPAD_RIGHT){seekBy(1,event);return true;}
            if(key==KeyEvent.KEYCODE_DPAD_UP || key==KeyEvent.KEYCODE_DPAD_DOWN){showControls(playButton);return true;}
            return super.dispatchKeyEvent(event);
        }
        // Overlay visible: every key keeps it up; D-pad moves between the controls and the progress line.
        scheduleHide();
        View focus=getCurrentFocus();
        boolean onRow=focus!=null && focus.getParent()==playButton.getParent();
        if(key==KeyEvent.KEYCODE_DPAD_UP && onRow){optionsButton.requestFocus();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_DOWN && focus==optionsButton){playButton.requestFocus();return true;}
        if(focus==optionsButton && (key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT || key==KeyEvent.KEYCODE_DPAD_UP))return true;
        if(key==KeyEvent.KEYCODE_DPAD_DOWN && onRow){progressLine.requestFocus();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_UP && focus==progressLine){playButton.requestFocus();return true;}
        if((key==KeyEvent.KEYCODE_DPAD_UP && onRow) || (key==KeyEvent.KEYCODE_DPAD_DOWN && focus==progressLine))return true;
        if(focus==root){playButton.requestFocus();return true;}
        return super.dispatchKeyEvent(event);
    }

    /** Secondary features live off the main row, with one predictable D-pad route. */
    private void choose(String title,String[] labels,int selected,Ui.Choice action){
        handler.removeCallbacks(hideControls);
        Dialog opened=Ui.picker(this,title,labels,selected,i->{dialog=null;action.select(i);if(dialog==null){continueAfterMenu();showControls(optionsButton);}});dialog=opened;
        opened.setOnDismissListener(d->{if(dialog==opened){dialog=null;continueAfterMenu();showControls(optionsButton);}});
    }
    private String watchedKey(){return ViewerOptions.watchedKey(kind,contentId,episodeId);}
    private void showOptions(){
        if(player==null||reporting||dialog!=null)return;
        List<String> names=new ArrayList<>();
        if(episodeNavigation!=null)names.add("Episodes");
        names.add("Go to time");names.add("Playback speed");names.add("Subtitle size");names.add("Subtitle appearance");
        boolean canMark=("movie".equals(kind)||episodeNavigation!=null)&&contentId!=null&&!contentId.isEmpty();
        if(canMark)names.add(Api.prefs(this).getBoolean(watchedKey(),false)?"Mark as unwatched":"Mark as watched");
        choose("Player options",names.toArray(new String[0]),0,i->{String name=names.get(i);
            if(name.equals("Episodes"))showEpisodes();
            else if(name.equals("Go to time"))handler.post(this::goToTime);
            else if(name.equals("Playback speed"))choose("Playback speed",new String[]{"0.75×","1× (Normal)","1.25×","1.5×"},ViewerOptions.index(player.getPlaybackParameters().speed,ViewerOptions.SPEEDS),n->{
                if(player==null)return;float speed=ViewerOptions.SPEEDS[n];player.setPlaybackSpeed(speed);Api.prefs(this).edit().putFloat(ViewerOptions.SPEED,speed).apply();});
            else if(name.equals("Subtitle size"))choose("Subtitle size",new String[]{"Small","Normal","Large","Extra large"},ViewerOptions.index(Api.prefs(this).getFloat(ViewerOptions.SIZE,1f),ViewerOptions.SIZES),n->{Api.prefs(this).edit().putFloat(ViewerOptions.SIZE,ViewerOptions.SIZES[n]).apply();applySubtitleAppearance();});
            else if(name.equals("Subtitle appearance"))choose("Subtitle appearance",new String[]{"Original track style","White with outline","White on black","Yellow with outline"},Math.max(0,Math.min(3,Api.prefs(this).getInt(ViewerOptions.STYLE,0))),n->{Api.prefs(this).edit().putInt(ViewerOptions.STYLE,n).apply();applySubtitleAppearance();});
            else {boolean watched=!Api.prefs(this).getBoolean(watchedKey(),false);Api.prefs(this).edit().putBoolean(watchedKey(),watched).apply();flash(watched?"Marked as watched":"Marked as unwatched");}
        });
    }
    private void showEpisodes(){
        if(episodeNavigation==null||loadingEpisodes)return;
        if(episodeCatalog!=null){showEpisodeSeasons();return;}
        loadingEpisodes=true;final int loadToken=++episodeLoadGeneration;flash("Loading episodes…");
        Api.IO.execute(()->{org.json.JSONObject data=null;
            try{data=Api.get(Api.xtream(this,"get_series_info","&series_id="+Api.enc(contentId))).optJSONObject("episodes");}catch(Exception ignored){}
            final org.json.JSONObject loaded=data;
            runOnUiThread(()->{if(loadToken!=episodeLoadGeneration)return;loadingEpisodes=false;if(stopping||isFinishing()||isDestroyed())return;
                if(reporting||dialog!=null){episodeCatalog=loaded;return;}
                if(loaded==null){flash("Unable to load all episodes. Showing the loaded episodes.");showLoadedEpisodes();return;}
                episodeCatalog=loaded;showEpisodeSeasons();});});
    }
    private void showLoadedEpisodes(){
        String[] ids=episodeNavigation.ids(),labels=episodeNavigation.labels();
        for(int i=0;i<labels.length;i++){if(labels[i].isEmpty())labels[i]="Episode "+(i+1);
            if(Api.prefs(this).getBoolean(ViewerOptions.watchedKey("series",contentId,ids[i]),false))labels[i]+=" · Watched";}
        choose("Loaded episodes",labels,episodeNavigation.index(),n->navigateToEpisode(n,true,n<episodeNavigation.index()?-1:1));
    }
    private void showEpisodeSeasons(){
        List<Integer> seasons=new ArrayList<>();
        for(java.util.Iterator<String> it=episodeCatalog.keys();it.hasNext();){String key=it.next();try{int n=Integer.parseInt(key);
            org.json.JSONArray a=episodeCatalog.optJSONArray(key);if(n>0&&a!=null&&a.length()>0)seasons.add(n);}catch(NumberFormatException ignored){}}
        java.util.Collections.sort(seasons);if(seasons.isEmpty()){showLoadedEpisodes();return;}
        String[] names=new String[seasons.size()];int selected=0;
        for(int i=0;i<names.length;i++){names[i]="Season "+seasons.get(i);org.json.JSONArray a=episodeCatalog.optJSONArray(""+seasons.get(i));
            for(int j=0;j<a.length();j++){org.json.JSONObject ep=a.optJSONObject(j);if(ep!=null&&episodeId.equals(ep.optString("id")))selected=i;}}
        choose("Select season",names,selected,n->showCatalogSeason(seasons,seasons.get(n)));
    }
    private void showCatalogSeason(List<Integer> seasons,int season){
        List<EpisodeNavigation.Episode> all=new ArrayList<>();List<Integer> targets=new ArrayList<>();List<String> names=new ArrayList<>();int selected=0;
        for(int sn:seasons){org.json.JSONArray a=episodeCatalog.optJSONArray(""+sn);for(int j=0;j<a.length();j++){
            org.json.JSONObject ep=a.optJSONObject(j);if(ep==null||ep.optString("id").isEmpty())continue;
            String id=ep.optString("id"),ext=ep.optString("container_extension","");org.json.JSONObject info=ep.optJSONObject("info");if(ext.isEmpty())ext=info==null?"mp4":info.optString("container_extension","mp4");String label="S"+sn+" · E"+ep.optInt("episode_num",j+1)+"  "+ep.optString("title","");
            if(sn==season){if(id.equals(episodeId))selected=names.size();targets.add(all.size());
                names.add(label+(Api.prefs(this).getBoolean(ViewerOptions.watchedKey("series",contentId,id),false)?" · Watched":""));}
            all.add(new EpisodeNavigation.Episode(id,ext,label));}}
        if(names.isEmpty()){flash("No playable episodes in this season");return;}
        choose("Season "+season,names.toArray(new String[0]),selected,n->{int target=targets.get(n);EpisodeNavigation.Episode ep=all.get(target);
            if(ep.id.equals(episodeId))return;
            int first=Math.max(0,target-MAX_QUEUE),last=Math.min(all.size(),target+MAX_QUEUE+1),count=last-first;
            String[] ids=new String[count],exts=new String[count],labels=new String[count];
            for(int i=0;i<count;i++){EpisodeNavigation.Episode e=all.get(first+i);ids[i]=e.id;exts[i]=e.extension;labels[i]=e.label;}
            EpisodeNavigation fresh=new EpisodeNavigation(ids,exts,labels,target-first,last==all.size());
            switchEpisode(ep,true,1,()->episodeNavigation=fresh);
        });
    }
    private void applySubtitleAppearance(){if(subtitles!=null)subtitles.style(this::applySubtitleAppearance);}
    private void applySubtitleAppearance(androidx.media3.ui.SubtitleView subs){
        float size=ViewerOptions.size(Api.prefs(this).getFloat(ViewerOptions.SIZE,1f));
        // Sized from the whole layer height, so raising the bottom inset above the controls never shrinks the text;
        // the factor keeps the size it had when it was measured inside the normal insets.
        subs.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION*size*SubtitlePlacement.TEXT_HEIGHT,true);
        subs.setApplyEmbeddedFontSizes(size==1f);
        int style=Api.prefs(this).getInt(ViewerOptions.STYLE,0);subs.setApplyEmbeddedStyles(style==0);
        if(style==0){subs.setStyle(androidx.media3.ui.CaptionStyleCompat.DEFAULT);return;}
        subs.setStyle(new androidx.media3.ui.CaptionStyleCompat(style==3?0xFFFFFF00:0xFFFFFFFF,style==2?0xCC000000:0x00000000,0x00000000,
            style==2?androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE:androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,0xFF000000,null));
    }

    /* ---------------- Audio and subtitles ---------------- */

    /**
     * Settings › Preferred audio language, Subtitles and Subtitle language. They only set
     * preferences: a stream without that language plays its default track, and choices made in
     * the player's Audio and Subtitles menus still override them for the current video.
     */
    static void applyPreferences(android.content.Context c,ExoPlayer player){
        android.content.SharedPreferences p=Api.prefs(c);
        TrackSelectionParameters.Builder b=player.getTrackSelectionParameters().buildUpon();
        String audio=p.getString(SettingsPage.AUDIO_LANGUAGE,"");
        b.setPreferredAudioLanguage(audio.isEmpty()?null:audio);
        if(p.getBoolean(SettingsPage.SUBTITLES_ON,false)){
            String text=p.getString(SettingsPage.SUBTITLE_LANGUAGE,"");
            if(text.isEmpty())text=Locale.getDefault().getLanguage();
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setPreferredTextLanguage(text)
                .setSelectUndeterminedTextLanguage(true).setIgnoredTextSelectionFlags(0);
        }else{
            // Off: no subtitles unless the stream marks them forced (for example, foreign-language lines).
            b.setPreferredTextLanguage(null).setSelectUndeterminedTextLanguage(false)
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT);
        }
        player.setTrackSelectionParameters(b.build());
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
                else if(group.isTrackSelected(i) && !anySelected){selected=labels.size();anySelected=true;}
                labels.add(label);groups.add(group);indexes.add(i);
            }
        }
        if(BuildConfig.DEMO)android.util.Log.i("FlixTownQA","tracks type="+type+" count="+labels.size()+" "+labels);
        if(labels.isEmpty() || (type==C.TRACK_TYPE_TEXT && labels.size()==1)){
            flash(type==C.TRACK_TYPE_TEXT?"This video has no subtitles":"This stream has only one audio track");scheduleHide();return;}
        handler.removeCallbacks(hideControls);
        dialog=Ui.picker(this,type==C.TRACK_TYPE_TEXT?"Subtitles":"Audio",labels.toArray(new String[0]),selected,index->{
            if(player==null)return;
            Tracks.Group group=groups.get(index);
            if(group!=null && !group.isTrackSupported(indexes.get(index))){
                flash("That audio format can't be played on this TV");return;}
            TrackSelectionParameters.Builder builder=player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type);
            if(group==null){builder.setTrackTypeDisabled(type,true);}
            else {
                builder.setTrackTypeDisabled(type,false);
                builder.addOverride(new TrackSelectionOverride(group.getMediaTrackGroup(),indexes.get(index)));
            }
            if(type==C.TRACK_TYPE_TEXT){
                android.content.SharedPreferences.Editor pref=Api.prefs(this).edit().putBoolean(SettingsPage.SUBTITLES_ON,group!=null);
                if(group!=null){String language=group.getTrackFormat(indexes.get(index)).language;
                    if(language!=null&&!language.isEmpty()&&!"und".equals(language)){pref.putString(SettingsPage.SUBTITLE_LANGUAGE,language);builder.setPreferredTextLanguage(language);}}
                pref.apply();
            }
            player.setTrackSelectionParameters(builder.build());
        });
        dialog.setOnDismissListener(d->{dialog=null;continueAfterMenu();scheduleHide();});
    }
    /** Shows the current choice next to the Audio and Subtitles buttons. */
    private void updateTrackLabels(){
        if(player==null)return;
        String audio=null,text=null;
        for(Tracks.Group group:player.getCurrentTracks().getGroups())for(int i=0;i<group.length;i++){
            if(!group.isTrackSelected(i))continue;
            Format f=group.getTrackFormat(i);
            if(group.getType()==C.TRACK_TYPE_AUDIO && audio==null)audio=shortLanguage(f);
            if(group.getType()==C.TRACK_TYPE_TEXT && text==null)text=shortLanguage(f);
        }
        audioLabel.setText(audio==null?"Audio":"Audio · "+audio);
        subtitleLabel.setText("Subtitles · "+(text==null?"Off":text));
    }
    private static String shortLanguage(Format f){
        if(f.language!=null && !f.language.isEmpty() && !"und".equals(f.language)){
            String name=new Locale(f.language).getDisplayLanguage(Locale.US);return name.isEmpty()?f.language:name;}
        return "On";
    }
    private static String trackLabel(Format f,int type,int number){
        String language=f.language==null||f.language.isEmpty()||"und".equals(f.language)?"":new Locale(f.language).getDisplayLanguage(Locale.US);
        // Prefer the language; add the stream's own label only when it says something more (e.g. "Commentary").
        boolean usefulLabel=f.label!=null && !f.label.isEmpty() && !f.label.matches("(?i)(stream|track|audio|sub(title)?s?)[ _-]?\\d*") && !f.label.equalsIgnoreCase(language);
        String name=!language.isEmpty()?(usefulLabel?language+" ("+f.label+")":language):usefulLabel?f.label:(type==C.TRACK_TYPE_TEXT?"Subtitle ":"Track ")+number;
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

    /* ---------------- Progress and next episode ---------------- */

    private final Runnable progress=new Runnable(){@Override public void run(){
        if(player==null)return;
        qaState();
        long duration=player.getDuration(),current=player.getCurrentPosition();
        if(player.isPlaying() && current>=15000 && current/5000!=lastSavedAt/5000){saveProgress(current,duration);lastSavedAt=current;}
        if(controlsVisible())updateProgressUi();
        if(duration>0 && duration!=C.TIME_UNSET){
            long left=duration-current;
            if(Api.prefs(PlayerActivity.this).getBoolean("autoplay_next",true) && nextUrl!=null && !nextUrl.isEmpty() && !nextCanceled && left>0){
                if(left<=25000 && !promptShown && !reporting && dialog==null && player.isPlaying() && card.getVisibility()!=View.VISIBLE){promptShown=true;showNext();}
                // The countdown belongs to the Up next card only; any other card (an error) stops it.
                if(cardForNext && card.getVisibility()==View.VISIBLE){
                    long s=Math.max(1,(left+999)/1000);
                    cardMessage.setText("Starts in "+s+(s==1?" second":" seconds")+(nextSubtitle!=null&&!nextSubtitle.isEmpty()?"  ·  "+nextSubtitle:""));
                    cardMessage.setVisibility(View.VISIBLE);}   // the card opens with an empty (hidden) message line
            }
        }
        handler.postDelayed(this,500);
    }};
    /** QA build only: the player's visible state for the emulator test (UI dumps fail while video plays). */
    private String qaLast="";private View audioButton,subsButton;
    private void qaState(){
        if(!BuildConfig.DEMO || player==null)return;
        View f=getCurrentFocus();
        String focus=f==null?"none":f==optionsButton?"options":f==playButton?"play":f==audioButton?"audio":f==subsButton?"subs":f==reportButton?"report":f==previousButton?"previous":f==nextButton?"next":f==progressLine?"progress":"other";
        String s="controls="+controlsVisible()+" pending="+(pendingSeek<0?-1:pendingSeek/1000)+" status=["+(status!=null&&status.getVisibility()==View.VISIBLE?status.getText():"")+"]"
            +" dialog="+(dialog!=null)+" playing="+player.isPlaying()+" reporting="+reporting+" focus="+focus+" elapsed="+player.getCurrentPosition()/1000
            +" speed="+player.getPlaybackParameters().speed+" subtitle_size="+Api.prefs(this).getFloat(ViewerOptions.SIZE,1f)+" subtitle_style="+Api.prefs(this).getInt(ViewerOptions.STYLE,0)
            +" audio=["+(audioLabel==null?"":audioLabel.getText())+"] subs=["+(subtitleLabel==null?"":subtitleLabel.getText())+"]"
            +" controls_fit="+(nextButton==null || nextButton.getRight()<=((View)nextButton.getParent()).getWidth())
            +" episode_controls="+(episodeNavigation!=null)+" episode="+(episodeId==null?"":episodeId)+" previous="+(previousButton!=null&&previousButton.isEnabled())+" next="+(nextButton!=null&&nextButton.isEnabled())
            +" card=["+(card.getVisibility()==View.VISIBLE?cardTitle.getText():"")+"] cardmsg=["+(card.getVisibility()==View.VISIBLE && cardMessage.getVisibility()==View.VISIBLE?cardMessage.getText():"")+"] subtitle="+(subtitle==null?"":subtitle);
        if(!s.equals(qaLast)){qaLast=s;android.util.Log.i("FlixTownQA","player "+s);}
    }
    private void saveProgress(long current,long duration){
        if(!ended && kind!=null && contentId!=null && !contentId.isEmpty()){
            Catalog.saveProgress(this,kind,contentId,episodeId,episodeExt,nextEpisodeId,nextEpisodeExt,current,duration,endKnown());
            // "S2 · E4  Title" for the Continue Watching card on Home.
            if("series".equals(kind) && subtitle!=null && current>=15000)Api.prefs(this).edit().putString("resume_label_"+kind+":"+contentId,subtitle).apply();
        }
    }
    /** A finished movie leaves Continue Watching; a finished episode queues the next one there. */
    private void finishProgress(){if(kind!=null && contentId!=null && !contentId.isEmpty()){
        Catalog.markFinished(this,kind,contentId,episodeId,episodeExt,nextEpisodeId,nextEpisodeExt,endKnown());
        if("series".equals(kind) && nextSubtitle!=null && !nextSubtitle.isEmpty())Api.prefs(this).edit().putString("resume_label_"+kind+":"+contentId,nextSubtitle).apply();}}
    private void showNext(){
        hideNow();
        // A "Play now" that arrives just after the episode ended (autoplay already moved on) must not skip one more.
        String shownFor=episodeId;
        showCard("Up next","",new String[]{"Play now","Cancel"},0,i->{
            if(!java.util.Objects.equals(shownFor,episodeId))return;
            if(i==0)playNext();else nextCanceled=true;});
        cardForNext=true;
    }
    private void continueAfterMenu(){
        if(nextAfterMenu&&dialog==null&&player!=null&&!stopping){nextAfterMenu=false;playNext();}
    }
    private void playNext(){
        if(dialog!=null){nextAfterMenu=true;return;}
        if(episodeNavigation!=null){navigateEpisode(1,false);return;}
        if(nextUrl==null || nextUrl.isEmpty() || player==null)return;
        if(card.getVisibility()==View.VISIBLE)hideCard();
        // A seek still waiting to be applied belongs to the episode that just ended, not to the next one.
        clearPendingSeek();
        url=nextUrl;nextUrl="";lastPlayerError="";position=0;promptShown=false;ended=false;lastSavedAt=0;resumeAsked=true;
        episodeId=nextEpisodeId;episodeExt=nextEpisodeExt;nextEpisodeId="";nextEpisodeExt="";
        subtitle=nextSubtitle;nextSubtitle="";showSubtitle();
        queueFollowingEpisode();
        player.setMediaItem(MediaItem.fromUri(url));player.prepare();player.play();
    }
    private void syncEpisodeNeighbors(){
        EpisodeNavigation.Episode next=episodeNavigation.adjacent(1);
        nextEpisodeId=next==null?"":next.id;nextEpisodeExt=next==null?"mp4":next.extension;nextSubtitle=next==null?"":next.label;
        try{nextUrl=next==null?"":Api.stream(this,"series",next.id,next.extension);}catch(Exception ignored){nextUrl="";}
    }
    private void updateEpisodeButtons(){
        if(episodeNavigation==null)return;
        View[] buttons={previousButton,nextButton};int[] directions={-1,1};
        for(int i=0;i<buttons.length;i++)if(buttons[i]!=null){boolean enabled=episodeNavigation.adjacent(directions[i])!=null;
            buttons[i].setEnabled(enabled);buttons[i].setFocusable(enabled);buttons[i].setAlpha(enabled?1f:.35f);}
    }
    /** Manual skips do not mark an unfinished episode complete; autoplay still does so at EOF. */
    private void navigateEpisode(int direction,boolean manual){navigateToEpisode(episodeNavigation==null?-1:episodeNavigation.index()+direction,manual,direction);}
    private void navigateToEpisode(int targetIndex,boolean manual,int direction){
        if(player==null || episodeNavigation==null || reporting || dialog!=null)return;
        if(targetIndex<0||targetIndex>=episodeNavigation.ids().length||targetIndex==episodeNavigation.index())return;
        EpisodeNavigation.Episode target=new EpisodeNavigation.Episode(episodeNavigation.ids()[targetIndex],episodeNavigation.extensions()[targetIndex],episodeNavigation.labels()[targetIndex]);
        switchEpisode(target,manual,direction,()->episodeNavigation.select(targetIndex));
    }
    private void switchEpisode(EpisodeNavigation.Episode target,boolean manual,int direction,Runnable select){
        if(player==null||reporting||dialog!=null)return;
        String targetUrl;try{targetUrl=Api.stream(this,"series",target.id,target.extension);}catch(Exception ignored){flash("Unable to open episode. Please try again.");return;}
        boolean keepPlaying=!manual || player.getPlayWhenReady();
        if(manual && !ended && resumeAsked)saveProgress(player.getCurrentPosition(),player.getDuration());
        if(card.getVisibility()==View.VISIBLE)hideCard();
        clearPendingSeek();
        player.pause();nextAfterMenu=false;select.run();
        url=targetUrl;episodeId=target.id;episodeExt=target.extension;subtitle=target.label;
        position=0;lastSavedAt=0;lastPlayerError="";promptShown=false;nextCanceled=false;ended=false;resumeAsked=true;
        syncEpisodeNeighbors();showSubtitle();updateEpisodeButtons();status.setVisibility(View.GONE);
        // Persist the selected episode immediately, even if Back is pressed before the 15-second save threshold.
        Catalog.selectEpisode(this,contentId,episodeId,episodeExt,nextEpisodeId,nextEpisodeExt,subtitle);
        player.setMediaItem(MediaItem.fromUri(url));player.prepare();player.setPlayWhenReady(keepPlaying);
        if(manual){View focus=direction<0?previousButton:nextButton;showControls(focus!=null&&focus.isEnabled()?focus:playButton);}
    }
    /** After moving on to the next episode: the one after it becomes "next" (Up next card, autoplay, Continue Watching). */
    private void queueFollowingEpisode(){
        if(laterIds==null || laterExts==null || laterIndex>=laterIds.length || laterIndex>=laterExts.length)return;
        String id=laterIds[laterIndex],ext=laterExts[laterIndex]==null||laterExts[laterIndex].isEmpty()?"mp4":laterExts[laterIndex];
        String label=laterLabels!=null && laterIndex<laterLabels.length && laterLabels[laterIndex]!=null?laterLabels[laterIndex]:"";
        laterIndex++;
        if(id==null || id.isEmpty())return;
        try{nextUrl=Api.stream(this,"series",id,ext);}catch(Exception e){nextUrl="";return;}   // signed out meanwhile
        nextEpisodeId=id;nextEpisodeExt=ext;nextSubtitle=label;
    }
    @Override protected void onResume(){super.onResume();ScreenAwake.on(this);}
    @Override protected void onPause(){ScreenAwake.off(this);super.onPause();}
    @Override protected void onStop(){
        if(reporting)reportRestartReady=reportWasPlaying&&!reportFromError;
        stopping=true;reporting=false;episodeLoadGeneration++;loadingEpisodes=false;
        handler.removeCallbacksAndMessages(null);
        if(dialog!=null){dialog.dismiss();dialog=null;}
        if(player!=null){position=player.getCurrentPosition();if(!ended && resumeAsked)saveProgress(position,player.getDuration());
            video.setPlayer(null);player.release();player=null;}
        if(subtitles!=null)subtitles.clear();
        super.onStop();
    }
}

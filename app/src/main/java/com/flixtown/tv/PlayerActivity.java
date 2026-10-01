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
    private long lastSavedAt;
    private final Handler handler=new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        url=getIntent().getStringExtra("url");
        nextUrl=getIntent().getStringExtra("next_url");
        title=getIntent().getStringExtra("title");subtitle=getIntent().getStringExtra("subtitle");nextSubtitle=getIntent().getStringExtra("next_subtitle");
        kind=getIntent().getStringExtra("content_kind");contentId=getIntent().getStringExtra("content_id");
        episodeId=getIntent().getStringExtra("episode_id");episodeExt=getIntent().getStringExtra("episode_ext");
        nextEpisodeId=getIntent().getStringExtra("next_episode_id");nextEpisodeExt=getIntent().getStringExtra("next_episode_ext");
        if(kind!=null && contentId!=null && !contentId.isEmpty() && !getIntent().getBooleanExtra("start_over",false)){
            String key=kind+":"+contentId;
            position=Api.prefs(this).getLong("resume_position_"+key,0);
            // Series progress is stored per show; only reuse it for the episode it was saved for.
            if("series".equals(kind) && (episodeId==null || !episodeId.equals(Api.prefs(this).getString("resume_episode_"+key,""))))position=0;
        }
        if(position<15000)position=0;
        resumeAsked=position==0 || (state!=null && state.getBoolean("resume_asked",false));
        if(state!=null)position=state.getLong("position",position);
        if(url==null || url.isEmpty()){finish();return;}
        setContentView(R.layout.activity_player);
        root=findViewById(R.id.player_root);
        video=findViewById(R.id.player_view);
        video.setUseController(false);
        video.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        video.setShutterBackgroundColor(0xFF000000);
        buildControls();
        root.requestFocus();
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);
        out.putBoolean("resume_asked",resumeAsked);out.putLong("position",player!=null?player.getCurrentPosition():position);}

    /* ---------------- Controls ---------------- */

    private void buildControls() {
        controls=findViewById(R.id.player_overlay);card=findViewById(R.id.player_card);
        cardTitle=findViewById(R.id.player_card_title);cardMessage=findViewById(R.id.player_card_message);cardActions=findViewById(R.id.player_card_actions);
        ((TextView)findViewById(R.id.player_title)).setText(title==null?"Flix Town":title);
        showSubtitle();
        elapsed=findViewById(R.id.player_elapsed);remaining=findViewById(R.id.player_remaining);status=findViewById(R.id.player_status);
        progressLine=new ProgressLine(this);
        ((FrameLayout)findViewById(R.id.player_progress_slot)).addView(progressLine,new FrameLayout.LayoutParams(-1,-1));
        progressLine.setOnKeyListener((v,key,event)->{
            if(event.getAction()==KeyEvent.ACTION_DOWN && (key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT)){
                seekBy(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1,event);return true;}
            if(event.getAction()==KeyEvent.ACTION_DOWN && Ui.isSelect(key)){togglePlay();return true;}
            return false;
        });
        LinearLayout actions=findViewById(R.id.player_actions);
        playButton=controlButton(actions,PlayerIcon.PAUSE,null,this::togglePlay);
        playIcon=(PlayerIcon)playButton.getChildAt(0);
        LinearLayout audio=controlButton(actions,PlayerIcon.AUDIO,"Audio",()->showTracks(C.TRACK_TYPE_AUDIO));
        audioLabel=(TextView)audio.getChildAt(1);
        LinearLayout subs=controlButton(actions,PlayerIcon.SUBTITLES,"Subtitles",()->showTracks(C.TRACK_TYPE_TEXT));
        subtitleLabel=(TextView)subs.getChildAt(1);
    }
    private void showSubtitle(){TextView sub=findViewById(R.id.player_subtitle);
        sub.setVisibility(subtitle==null||subtitle.isEmpty()?View.GONE:View.VISIBLE);sub.setText(subtitle);}
    /** Small refined control: a round icon button, or an icon + label pill. */
    private LinearLayout controlButton(LinearLayout parent,int icon,String label,Runnable click){
        LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.HORIZONTAL);b.setGravity(Gravity.CENTER);
        b.setFocusable(true);b.setFocusableInTouchMode(true);b.setClickable(true);
        boolean round=label==null;
        PlayerIcon glyph=new PlayerIcon(this,icon);
        b.addView(glyph,new LinearLayout.LayoutParams(Ui.dp(this,round?22:20),Ui.dp(this,round?22:20)));
        if(!round){TextView t=Ui.text(this,label,15);t.setTextColor(Ui.TEXT);t.setSingleLine(true);
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.leftMargin=Ui.dp(this,8);b.addView(t,tp);
            b.setPadding(Ui.dp(this,18),0,Ui.dp(this,20),0);}
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
        controls.setVisibility(View.VISIBLE);liftSubtitles(true);
        if(target!=null)target.requestFocus();
        else if(!controls.hasFocus())playButton.requestFocus();
        updateProgressUi();scheduleHide();
    }
    private void scheduleHide(){handler.removeCallbacks(hideControls);if(dialog==null)handler.postDelayed(hideControls,HIDE_MS);}
    private final Runnable hideControls=()->{
        if(dialog!=null || pendingSeek>=0){scheduleHide();return;}
        controls.setVisibility(View.GONE);status.setVisibility(View.GONE);liftSubtitles(false);root.requestFocus();
    };
    /** Keeps subtitles above the controls while they are shown, and at the normal height otherwise. */
    private void liftSubtitles(boolean up){
        // Moving the whole layer also works for cues that carry their own position (common in WebVTT/ASS).
        androidx.media3.ui.SubtitleView subs=video.getSubtitleView();
        if(subs!=null)subs.animate().translationY(up?-Ui.dp(this,118):0).setDuration(150).start();
    }
    private void hideNow(){handler.removeCallbacks(hideControls);hideControls.run();}
    private void flash(String text){status.setText(text);status.setVisibility(View.VISIBLE);}

    /* ---------------- Playback actions ---------------- */

    private void togglePlay(){
        if(player==null)return;
        if(player.getPlaybackState()==Player.STATE_IDLE){retry();return;}
        if(player.isPlaying())player.pause();else player.play();
    }
    private void retry(){if(player==null)return;hideCard();player.prepare();player.play();}
    /** Left/Right: 10 seconds per press. Presses are combined and applied once the remote goes quiet. */
    private void seekBy(int direction,KeyEvent event){
        if(player==null)return;
        long now=SystemClock.uptimeMillis();
        if(event.getRepeatCount()>0 && now-lastRepeatAt<REPEAT_GAP_MS)return;
        lastRepeatAt=now;
        long base=pendingSeek>=0?pendingSeek:player.getCurrentPosition();
        long duration=player.getDuration();
        long target=Math.max(0,base+direction*SEEK_STEP_MS);
        if(duration>0 && duration!=C.TIME_UNSET)target=Math.min(target,Math.max(0,duration-1000));
        pendingSeek=target;
        handler.removeCallbacks(commitSeek);handler.postDelayed(commitSeek,SEEK_COMMIT_MS);
        if(!controlsVisible())showControls(progressLine);else scheduleHide();
        updateProgressUi();
    }
    private final Runnable commitSeek=()->{
        if(pendingSeek<0 || player==null)return;
        player.seekTo(pendingSeek);pendingSeek=-1;updateProgressUi();scheduleHide();
    };
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
        // Xtream servers commonly redirect streams to another host, sometimes from http to https.
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000);
        player=new ExoPlayer.Builder(this,new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this,http))).build();
        video.setPlayer(player);
        applyPreferences(this,player);
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(state==Player.STATE_ENDED){ended=true;finishProgress();
                    if(Api.prefs(PlayerActivity.this).getBoolean("autoplay_next",true) && !nextCanceled && nextUrl!=null && !nextUrl.isEmpty())playNext();
                    else finish();}
                updatePlayIcon();
            }
            @Override public void onIsPlayingChanged(boolean playing){
                // Stop Google TV's ambient mode from starting during a movie.
                video.setKeepScreenOn(playing);updatePlayIcon();
            }
            @Override public void onPlayWhenReadyChanged(boolean playWhenReady,int reason){updatePlayIcon();}
            @Override public void onTracksChanged(Tracks tracks){updateTrackLabels();}
            @Override public void onPlayerError(PlaybackException error){
                android.util.Log.w("FlixTownPlayer","Playback failed",error);
                hideNow();cardForError=true;
                boolean network=error.errorCode>=PlaybackException.ERROR_CODE_IO_UNSPECIFIED && error.errorCode<PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED;
                showCard("This title couldn't be played",network?"The server didn't send the video. Check the connection and try again."
                        :"This video's format isn't supported on this TV.",
                    new String[]{"Try again","Back"},0,i->{if(i==0)retry();else finish();});
            }
        });
        player.setMediaItem(MediaItem.fromUri(url));
        if(position>0)player.seekTo(position);
        player.setPlayWhenReady(resumeAsked);
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
        handler.removeCallbacks(hideControls);controls.setVisibility(View.GONE);
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

    /* ---------------- Keys ---------------- */

    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int key=event.getKeyCode();
        if(player==null || dialog!=null)return super.dispatchKeyEvent(event);
        if(key==KeyEvent.KEYCODE_BACK){
            if(event.getAction()!=KeyEvent.ACTION_DOWN || event.getRepeatCount()>0)return true;
            if(card.getVisibility()==View.VISIBLE){
                if(cardForNext){nextCanceled=true;hideCard();return true;}
                finish();return true;           // Back on Continue/Start over or an error leaves playback
            }
            if(pendingSeek>=0){handler.removeCallbacks(commitSeek);pendingSeek=-1;updateProgressUi();}
            if(controlsVisible()){hideNow();return true;}
            finish();return true;
        }
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return super.dispatchKeyEvent(event);
        if(card.getVisibility()==View.VISIBLE)return super.dispatchKeyEvent(event);
        switch(key){
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:togglePlay();showControls(null);return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:player.play();showControls(null);return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:player.pause();showControls(null);return true;
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
        if(key==KeyEvent.KEYCODE_DPAD_DOWN && onRow){progressLine.requestFocus();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_UP && focus==progressLine){playButton.requestFocus();return true;}
        if((key==KeyEvent.KEYCODE_DPAD_UP && onRow) || (key==KeyEvent.KEYCODE_DPAD_DOWN && focus==progressLine))return true;
        if(focus==root){playButton.requestFocus();return true;}
        return super.dispatchKeyEvent(event);
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
            player.setTrackSelectionParameters(builder.build());
        });
        dialog.setOnDismissListener(d->{dialog=null;scheduleHide();});
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
        long duration=player.getDuration(),current=player.getCurrentPosition();
        if(player.isPlaying() && current>=15000 && current/5000!=lastSavedAt/5000){saveProgress(current,duration);lastSavedAt=current;}
        if(controlsVisible())updateProgressUi();
        if(duration>0 && duration!=C.TIME_UNSET){
            long left=duration-current;
            if(Api.prefs(PlayerActivity.this).getBoolean("autoplay_next",true) && nextUrl!=null && !nextUrl.isEmpty() && !nextCanceled && left>0){
                if(left<=25000 && !promptShown && player.isPlaying()){promptShown=true;showNext();}
                if(cardForNext && card.getVisibility()==View.VISIBLE)
                    cardMessage.setText("Starts in "+Math.max(1,(left+999)/1000)+" seconds"+(nextSubtitle!=null&&!nextSubtitle.isEmpty()?"  ·  "+nextSubtitle:""));
            }
        }
        handler.postDelayed(this,500);
    }};
    private void saveProgress(long current,long duration){
        if(!ended && kind!=null && contentId!=null && !contentId.isEmpty()){
            Catalog.saveProgress(this,kind,contentId,episodeId,episodeExt,nextEpisodeId,nextEpisodeExt,current,duration);
            // "S2 · E4  Title" for the Continue Watching card on Home.
            if("series".equals(kind) && subtitle!=null && current>=15000)Api.prefs(this).edit().putString("resume_label_"+kind+":"+contentId,subtitle).apply();
        }
    }
    /** A finished movie leaves Continue Watching; a finished episode queues the next one there. */
    private void finishProgress(){if(kind!=null && contentId!=null && !contentId.isEmpty()){
        Catalog.markFinished(this,kind,contentId,nextEpisodeId,nextEpisodeExt);
        if("series".equals(kind) && nextSubtitle!=null && !nextSubtitle.isEmpty())Api.prefs(this).edit().putString("resume_label_"+kind+":"+contentId,nextSubtitle).apply();}}
    private void showNext(){
        hideNow();
        showCard("Up next","",new String[]{"Play now","Cancel"},0,i->{if(i==0)playNext();else nextCanceled=true;});
        cardForNext=true;
    }
    private void playNext(){
        if(nextUrl==null || nextUrl.isEmpty() || player==null)return;
        if(card.getVisibility()==View.VISIBLE)hideCard();
        url=nextUrl;nextUrl="";position=0;promptShown=false;ended=false;lastSavedAt=0;resumeAsked=true;
        episodeId=nextEpisodeId;episodeExt=nextEpisodeExt;nextEpisodeId="";nextEpisodeExt="";
        subtitle=nextSubtitle;nextSubtitle="";showSubtitle();
        player.setMediaItem(MediaItem.fromUri(url));player.prepare();player.play();
    }
    @Override protected void onStop(){
        handler.removeCallbacksAndMessages(null);
        if(dialog!=null){dialog.dismiss();dialog=null;}
        if(player!=null){position=player.getCurrentPosition();if(!ended && resumeAsked)saveProgress(position,player.getDuration());
            video.setPlayer(null);player.release();player=null;}
        super.onStop();
    }
}

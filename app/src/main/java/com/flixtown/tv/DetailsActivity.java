package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

public class DetailsActivity extends Activity {
    private String id,kind,title,extension;
    private LinearLayout episodeArea,castArea,seasonContent; private TextView summary; private Button trailerButton,watch;
    private boolean enrichedCast;
    private java.util.List<JSONObject> orderedEpisodes;private int seasonIndex;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        id=getIntent().getStringExtra("id");kind=getIntent().getStringExtra("kind");title=getIntent().getStringExtra("title");extension=getIntent().getStringExtra("extension");
        if(id==null||kind==null){finish();return;} Images.init(this);draw();details();cast();
    }
    @Override protected void onRestart(){super.onRestart();if(watch!=null)watch.setText(watchLabel());}
    private String resumeKey(){return kind+":"+id;}
    private String watchLabel(){
        if("series".equals(kind))return Api.prefs(this).getString("resume_episode_"+resumeKey(),"").isEmpty()?"Watch Now":"Continue";
        return Api.prefs(this).getLong("resume_position_"+resumeKey(),0)>=15000?"Resume":"Watch Now";
    }
    private void draw() {
        FrameLayout shell=new FrameLayout(this);shell.setBackgroundColor(Ui.BG);setContentView(shell);
        ImageView background=new ImageView(this);background.setScaleType(ImageView.ScaleType.CENTER_CROP);background.setAlpha(.25f);
        shell.addView(background,new FrameLayout.LayoutParams(-1,-1));Images.load(background,getIntent().getStringExtra("backdrop"),1000);
        // One vertical page: hero, then episodes, then cast. Earlier the episode list was squeezed into
        // the space left under the cast (about 100dp on a 540dp-tall TV), so the 143dp cards were clipped.
        ScrollView page=new ScrollView(this);page.setVerticalScrollBarEnabled(false);page.setFillViewport(true);
        page.setSmoothScrollingEnabled(true);page.setClipChildren(false);
        shell.addView(page,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout content=Ui.column(this);content.setClipChildren(false);content.setClipToPadding(false);
        content.setPadding(Ui.dp(this,80),Ui.safeY(this),Ui.dp(this,80),Ui.safeY(this));page.addView(content,new FrameLayout.LayoutParams(-1,-2));
        LinearLayout hero=Ui.row(this);content.addView(hero);
        ImageView poster=new ImageView(this);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(poster,new LinearLayout.LayoutParams(Ui.dp(this,128),Ui.dp(this,180)));
        Images.load(poster,getIntent().getStringExtra("poster"),300);
        LinearLayout text=Ui.column(this);Ui.pad(text,this,35,5,0,0);hero.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        TextView titleView=Ui.heading(this,title,27);titleView.setMaxLines(2);titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);text.addView(titleView);
        summary=Ui.text(this,"Loading details…",16);summary.setMaxLines(2);summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Ui.pad(summary,this,0,10,0,14);text.addView(summary);
        LinearLayout actions=Ui.row(this);actions.setGravity(Gravity.CENTER_VERTICAL);text.addView(actions);
        watch=Ui.button(this,watchLabel());actions.addView(watch,new LinearLayout.LayoutParams(Ui.dp(this,150),Ui.dp(this,52)));watch.setOnClickListener(v->{
            if("movie".equals(kind))play(id,extension);
            else if(!Api.prefs(this).getString("resume_episode_series:"+id,"").isEmpty()) resumeSeries();
            else if(seasonContent!=null && seasonContent.getChildCount()>0) {
                android.view.View first=seasonContent.getChildAt(0);
                if(first instanceof HorizontalScrollView) {
                    android.view.View row=((HorizontalScrollView)first).getChildAt(0);
                    if(row instanceof LinearLayout && ((LinearLayout)row).getChildCount()>0)((LinearLayout)row).getChildAt(0).requestFocus();
                }
            }
        });
        trailerButton=Ui.button(this,"Trailer");LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(Ui.dp(this,130),Ui.dp(this,52));tp.leftMargin=Ui.dp(this,12);actions.addView(trailerButton,tp);
        trailerButton.setEnabled(false);trailerButton.setAlpha(.45f);
        trailerButton.setOnClickListener(v->{String url=trailerButton.getTag() instanceof String?(String)trailerButton.getTag():"";
            if(url.startsWith("http") && !url.contains("youtube.com/") && !url.contains("youtu.be/"))playUrl(url,false);
            else if(!url.isEmpty())startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url.startsWith("http")?url:"https://www.youtube.com/watch?v="+url)));
        });
        Button favorite=Ui.button(this,isFavorite()?"Remove Favorite":"Add Favorite");
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(Ui.dp(this,185),Ui.dp(this,52));fp.leftMargin=Ui.dp(this,12);actions.addView(favorite,fp);
        favorite.setOnClickListener(v->{toggleFavorite();favorite.setText(isFavorite()?"Remove Favorite":"Add Favorite");});
        castArea=Ui.column(this);
        if("series".equals(kind)) {
            TextView heading=Ui.heading(this,"Episodes",21);Ui.pad(heading,this,0,16,0,6);content.addView(heading);
            episodeArea=Ui.column(this);episodeArea.setClipChildren(false);episodeArea.setClipToPadding(false);
            Ui.pad(episodeArea,this,4,3,4,6);content.addView(episodeArea);
            TextView loading=Ui.text(this,"Loading episodes…",15);loading.setTextColor(0xFFA9ABB1);episodeArea.addView(loading);
        }
        content.addView(castArea);
        watch.requestFocus();
    }
    private void details() {
        Api.IO.execute(()->{
            try {
                JSONObject data=Api.get(Api.xtream(this,"movie".equals(kind)?"get_vod_info":"get_series_info","&"+("movie".equals(kind)?"vod_id=":"series_id=")+Api.enc(id)));
                runOnUiThread(()->renderDetails(data));
            } catch(Exception e){runOnUiThread(()->summary.setText("Details unavailable. You can still play this title."));}
        });
    }
    private void renderDetails(JSONObject data) {
        JSONObject info=data.optJSONObject("info");
        if(info==null)info=new JSONObject();
        String trailer=info.optString("youtube_trailer","");
        if(!trailer.isEmpty())enableTrailer(trailer);
        String plot=info.optString("plot",info.optString("description",""));
        summary.setText(plot.length()>300?plot.substring(0,300)+"…":plot);
        if(!enrichedCast && castArea.getChildCount()==0){
            String actorNames=info.optString("cast",info.optString("actors",""));
            if(!actorNames.isEmpty()){
                JSONArray fallback=new JSONArray();
                for(String actorName:actorNames.split(","))if(!actorName.trim().isEmpty()){
                    JSONObject actor=new JSONObject();try{actor.put("name",actorName.trim());}catch(Exception ignored){}fallback.put(actor);
                }
                JSONObject payload=new JSONObject();try{payload.put("cast",fallback);}catch(Exception ignored){}showCast(payload);
            }
        }
        if("movie".equals(kind)) {
            JSONObject movie=data.optJSONObject("movie_data");
            if(movie!=null)extension=movie.optString("container_extension",extension);
        } else {
            JSONObject episodes=data.optJSONObject("episodes");
            if(episodes!=null){
                java.util.List<String> seasons=new java.util.ArrayList<>();
                for(java.util.Iterator<String> keys=episodes.keys();keys.hasNext();) {
                    String value=keys.next();if(!"0".equals(value))seasons.add(value);
                }
                seasons.sort((a,b)->Integer.compare(parseSeason(a),parseSeason(b)));
                java.util.List<JSONObject> ordered=new java.util.ArrayList<>();
                for(String season:seasons){JSONArray list=episodes.optJSONArray(season);if(list!=null)for(int i=0;i<list.length();i++){
                    JSONObject episode=list.optJSONObject(i);if(episode!=null)ordered.add(episode);
                }}
                orderedEpisodes=ordered;
                episodeArea.removeAllViews();
                seasonContent=Ui.column(this);seasonContent.setClipChildren(false);episodeArea.addView(seasonContent);
                if(seasons.isEmpty()){TextView none=Ui.text(this,"No episodes available yet",15);none.setTextColor(0xFFA9ABB1);episodeArea.addView(none,0);}
                else{
                    // Open on the season that holds the episode the viewer is watching.
                    String saved=Api.prefs(this).getString("resume_episode_"+resumeKey(),"");seasonIndex=0;
                    if(!saved.isEmpty())for(int i=0;i<seasons.size();i++){JSONArray list=episodes.optJSONArray(seasons.get(i));
                        if(list!=null)for(int j=0;j<list.length();j++){JSONObject ep=list.optJSONObject(j);if(ep!=null && saved.equals(ep.optString("id")))seasonIndex=i;}}
                    String[] names=new String[seasons.size()];for(int i=0;i<seasons.size();i++)names[i]="Season "+seasons.get(i);
                    Button select=Ui.button(this,names[seasonIndex]+"  ›");select.setTextSize(15);
                    LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,43));sp.leftMargin=Ui.dp(this,6);sp.bottomMargin=Ui.dp(this,4);
                    episodeArea.addView(select,0,sp);
                    select.setOnClickListener(v->Ui.picker(this,"Select season",names,seasonIndex,index->{
                        seasonIndex=index;select.setText(names[index]+"  ›");showSeason(episodes,seasons.get(index),ordered);
                    }));
                    showSeason(episodes,seasons.get(seasonIndex),ordered);
                }
            }
        }
    }
    private static int parseSeason(String value){try{return Integer.parseInt(value);}catch(Exception e){return Integer.MAX_VALUE;}}
    private void showSeason(JSONObject seasons,String season,java.util.List<JSONObject> ordered){
        JSONArray list=seasons.optJSONArray(season);seasonContent.removeAllViews();if(list==null)return;
        HorizontalScrollView scroller=new HorizontalScrollView(this);scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);scroller.setClipToPadding(false);scroller.setPadding(Ui.dp(this,10),Ui.dp(this,8),Ui.dp(this,10),Ui.dp(this,9));
        seasonContent.addView(scroller,new LinearLayout.LayoutParams(-1,Ui.dp(this,143)));
        LinearLayout row=Ui.row(this);row.setClipChildren(false);row.setClipToPadding(false);scroller.addView(row);
        String saved=Api.prefs(this).getString("resume_episode_"+resumeKey(),"");View resume=null;
        for(int i=0;i<list.length();i++){
            JSONObject ep=list.optJSONObject(i);if(ep==null)continue;
            String episodeId=ep.optString("id");String ext=extensionOf(ep,"mp4");
            int index=ordered.indexOf(ep);JSONObject next=index>=0&&index+1<ordered.size()?ordered.get(index+1):null;
            String nextId=next==null?"":next.optString("id");String nextExt=next==null?"mp4":extensionOf(next,"mp4");
            FrameLayout card=episodeCard(ep,ep.optInt("episode_num",i+1));
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(Ui.dp(this,206),Ui.dp(this,114));bp.setMargins(Ui.dp(this,8),0,Ui.dp(this,15),0);row.addView(card,bp);
            card.setOnClickListener(v->playEpisode(episodeId,ext,nextId,nextExt));
            if(episodeId.equals(saved))resume=card;
        }
        // Bring the episode being watched into view (without taking focus away from Watch).
        if(resume!=null){View target=resume;scroller.post(()->scroller.scrollTo(Math.max(0,target.getLeft()-Ui.dp(this,8)),0));}
    }
    private void enableTrailer(String url){trailerButton.setTag(url);trailerButton.setEnabled(true);trailerButton.setAlpha(1f);}
    /** Plays the episode saved for this show, using the loaded episode list to find the next one when possible. */
    private void resumeSeries(){
        String key=resumeKey();
        String episode=Api.prefs(this).getString("resume_episode_"+key,"");
        String ext=Api.prefs(this).getString("resume_extension_"+key,"mp4");
        String nextId=Api.prefs(this).getString("resume_next_"+key,""),nextExt=Api.prefs(this).getString("resume_next_ext_"+key,"mp4");
        if(orderedEpisodes!=null)for(int i=0;i<orderedEpisodes.size();i++){JSONObject ep=orderedEpisodes.get(i);
            if(!episode.equals(ep.optString("id")))continue;
            ext=extensionOf(ep,ext);
            JSONObject next=i+1<orderedEpisodes.size()?orderedEpisodes.get(i+1):null;
            nextId=next==null?"":next.optString("id");nextExt=next==null?"mp4":extensionOf(next,"mp4");break;}
        playEpisode(episode,ext,nextId,nextExt);
    }
    private static String extensionOf(JSONObject ep,String fallback){JSONObject info=ep.optJSONObject("info");
        String ext=ep.optString("container_extension","");if(ext.isEmpty() && info!=null)ext=info.optString("container_extension","");
        return ext.isEmpty()?fallback:ext;}
    private FrameLayout episodeCard(JSONObject ep,int number){
        FrameLayout frame=new FrameLayout(this);frame.setFocusable(true);frame.setFocusableInTouchMode(true);frame.setClickable(true);frame.setBackground(Ui.glass(this,10));
        ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setAlpha(.7f);
        frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        JSONObject info=ep.optJSONObject("info");String art=info==null?"":info.optString("movie_image","");
        Images.load(image,art,350);
        TextView index=Ui.heading(this,String.format(java.util.Locale.US,"%02d",number),38);
        Ui.pad(index,this,12,5,0,0);frame.addView(index);
        TextView label=Ui.text(this,ep.optString("title","Episode "+number),13);label.setMaxLines(2);
        label.setBackgroundColor(0xB9000000);Ui.pad(label,this,8,6,8,6);
        frame.addView(label,new FrameLayout.LayoutParams(-1,Ui.dp(this,47),Gravity.BOTTOM));
        frame.setOnFocusChangeListener((v,focused)->{
            v.setBackground(focused?Ui.rounded(Ui.RED,6,this):Ui.glass(this,10));
            v.setPadding(Ui.dp(this,focused?4:0),Ui.dp(this,focused?4:0),Ui.dp(this,focused?4:0),Ui.dp(this,focused?4:0));
            v.animate().scaleX(focused?1.05f:1f).scaleY(focused?1.05f:1f).setDuration(110).start();
        });
        return frame;
    }
    private void cast(){Api.IO.execute(()->{try{
        int year=getIntent().getIntExtra("year",0);
        String endpoint=BuildConfig.PANEL_URL+"tmdb.php?kind="+Api.enc(kind)+"&title="+Api.enc(title)
            +(year>1900?"&year="+year:"");
        JSONObject tmdb=Api.get(endpoint);
        runOnUiThread(()->showCast(tmdb));
    }catch(Exception e){android.util.Log.w("FlixTownCast","Cast lookup failed",e);}});}
    private void showCast(JSONObject data){
        String trailer=data.optString("trailer","");if(!trailer.isEmpty() && (trailerButton.getTag()==null || "".equals(trailerButton.getTag())))enableTrailer(trailer);
        JSONArray cast=data.optJSONArray("cast");if(cast==null||cast.length()==0)return;
        boolean hasPortrait=false;
        for(int j=0;j<cast.length();j++){JSONObject person=cast.optJSONObject(j);if(person!=null && person.optString("image","").startsWith("https://")){hasPortrait=true;break;}}
        if(hasPortrait)enrichedCast=true;
        else if(enrichedCast)return;
        castArea.removeAllViews();
        TextView heading=Ui.heading(this,"Cast",20);Ui.pad(heading,this,0,13,0,8);castArea.addView(heading);
        HorizontalScrollView scroller=new HorizontalScrollView(this);scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);scroller.setClipToPadding(false);scroller.setPadding(Ui.dp(this,12),Ui.dp(this,6),Ui.dp(this,12),Ui.dp(this,6));castArea.addView(scroller);
        LinearLayout row=Ui.row(this);scroller.addView(row);
        for(int i=0;i<Math.min(15,cast.length());i++){
            JSONObject actor=cast.optJSONObject(i);if(actor==null)continue;
            LinearLayout card=Ui.column(this);card.setFocusable(true);card.setFocusableInTouchMode(true);card.setClickable(true);card.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(Ui.dp(this,92),Ui.dp(this,110));cp.rightMargin=Ui.dp(this,17);row.addView(card,cp);
            String actorName=actor.optString("name");int actorId=actor.optInt("id",0);
            FrameLayout avatar=new FrameLayout(this);avatar.setBackground(castRing(false));
            avatar.setPadding(Ui.dp(this,3),Ui.dp(this,3),Ui.dp(this,3),Ui.dp(this,3));
            card.addView(avatar,new LinearLayout.LayoutParams(Ui.dp(this,68),Ui.dp(this,68)));
            FrameLayout portrait=new FrameLayout(this);portrait.setBackground(Ui.rounded(0xFF2D2330,54,this));
            portrait.setOutlineProvider(new android.view.ViewOutlineProvider(){@Override public void getOutline(View v,android.graphics.Outline outline){outline.setOval(0,0,v.getWidth(),v.getHeight());}});
            portrait.setClipToOutline(true);avatar.addView(portrait,new FrameLayout.LayoutParams(-1,-1));
            String initial=actorName.isEmpty()?"?":actorName.substring(0,1).toUpperCase(java.util.Locale.ROOT);
            TextView letter=Ui.heading(this,initial,40);letter.setTextColor(0xFFFFD4D9);letter.setGravity(Gravity.CENTER);
            portrait.addView(letter,new FrameLayout.LayoutParams(-1,-1));
            String imageUrl=actor.optString("image","");
            if(imageUrl.startsWith("https://")||imageUrl.startsWith("http://")){
                ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                portrait.addView(image,new FrameLayout.LayoutParams(-1,-1));Images.load(image,imageUrl,200);
            }
            TextView name=Ui.text(this,actorName,14);name.setGravity(Gravity.CENTER);name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,Ui.dp(this,33));np.topMargin=Ui.dp(this,6);card.addView(name,np);
            card.setOnFocusChangeListener((v,f)->{avatar.setBackground(castRing(f));
                name.setTextColor(f?0xFFFFC9D0:0xFFFFFFFF);
                v.animate().scaleX(f?1.04f:1f).scaleY(f?1.04f:1f).setDuration(130).start();});
            card.setOnClickListener(v->{Intent intent=new Intent(this,ActorActivity.class);intent.putExtra("actor_id",actorId);intent.putExtra("actor_name",actorName);startActivity(intent);});
        }
    }
    private android.graphics.drawable.GradientDrawable castRing(boolean focused){
        android.graphics.drawable.GradientDrawable ring=new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);ring.setColor(0xFF211A26);
        ring.setStroke(Ui.dp(this,focused?3:1),focused?0xFFFF7792:0xFF69505D);
        return ring;
    }
    private void promptResume(Runnable resume,Runnable restart){
        long saved=Api.prefs(this).getLong("resume_position_"+kind+":"+id,0);
        if(saved<15000){resume.run();return;}
        long minutes=saved/60000,seconds=(saved/1000)%60;
        String time=String.format(java.util.Locale.US,"%d:%02d",minutes,seconds);
        Ui.options(this,"Keep watching " + title + "?",new String[]{"Continue from " + time,"Start over"},choice->{
            if(choice==0)resume.run();else restart.run();
        });
    }
    private void play(String streamId,String ext){
        String url=Api.stream(this,"movie",streamId,ext==null||ext.isEmpty()?"mp4":ext);
        promptResume(()->playUrl(url,true,false),()->playUrl(url,true,true));
    }
    private void playEpisode(String streamId,String ext,String nextId,String nextExt){
        String savedEpisode=Api.prefs(this).getString("resume_episode_series:"+id,"");
        if(streamId.equals(savedEpisode))promptResume(()->launchEpisode(streamId,ext,nextId,nextExt,false),
            ()->launchEpisode(streamId,ext,nextId,nextExt,true));
        else launchEpisode(streamId,ext,nextId,nextExt,false);
    }
    private void launchEpisode(String streamId,String ext,String nextId,String nextExt,boolean startOver){
        String url=Api.stream(this,"series",streamId,ext==null||ext.isEmpty()?"mp4":ext);
        String next=nextId.isEmpty()?"":Api.stream(this,"series",nextId,nextExt);
        Catalog.remember(this,new Catalog.Item(itemJson(),kind));
        Intent intent=new Intent(this,PlayerActivity.class);intent.putExtra("url",url);intent.putExtra("title",title);intent.putExtra("next_url",next);
        intent.putExtra("content_kind",kind);intent.putExtra("content_id",id);
        intent.putExtra("episode_id",streamId);intent.putExtra("episode_ext",ext);
        intent.putExtra("next_episode_id",nextId);intent.putExtra("next_episode_ext",nextExt);
        intent.putExtra("start_over",startOver);startActivity(intent);
    }
    private void playUrl(String url,boolean track){playUrl(url,track,false);}
    private void playUrl(String url,boolean track,boolean startOver){
        if(track)Catalog.remember(this,new Catalog.Item(itemJson(),kind));
        Intent i=new Intent(this,PlayerActivity.class);i.putExtra("url",url);i.putExtra("title",title);
        if(track){i.putExtra("content_kind",kind);i.putExtra("content_id",id);i.putExtra("start_over",startOver);}startActivity(i);
    }
    private JSONObject itemJson() { JSONObject j=new JSONObject();try {j.put("name",title).put("stream_id",id).put("series_id",id);}catch(Exception ignored){}return j; }
    private boolean isFavorite(){return Api.prefs(this).getString("favorites","").contains("|"+kind+":"+id+"|");}
    private void toggleFavorite(){String key="|"+kind+":"+id+"|";String favorites=Api.prefs(this).getString("favorites","");
        Api.prefs(this).edit().putString("favorites",isFavorite()?favorites.replace(key,""):favorites+key).apply();}
}

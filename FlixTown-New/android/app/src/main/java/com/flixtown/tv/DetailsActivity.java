package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
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
    private LinearLayout episodeArea,castArea; private TextView summary; private Button trailerButton;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        id=getIntent().getStringExtra("id");kind=getIntent().getStringExtra("kind");title=getIntent().getStringExtra("title");extension=getIntent().getStringExtra("extension");
        if(id==null||kind==null){finish();return;} draw();details();cast();
    }
    private void draw() {
        FrameLayout shell=new FrameLayout(this);shell.setBackgroundColor(Ui.BG);setContentView(shell);
        ImageView background=new ImageView(this);background.setScaleType(ImageView.ScaleType.CENTER_CROP);background.setAlpha(.25f);
        shell.addView(background,new FrameLayout.LayoutParams(-1,-1));Images.load(background,getIntent().getStringExtra("backdrop"),1000);
        ScrollView scroll=new ScrollView(this);shell.addView(scroll);
        LinearLayout content=Ui.column(this);Ui.pad(content,this,96,75,96,50);scroll.addView(content);
        LinearLayout hero=Ui.row(this);content.addView(hero);
        ImageView poster=new ImageView(this);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(poster,new LinearLayout.LayoutParams(Ui.dp(this,175),Ui.dp(this,250)));
        Images.load(poster,getIntent().getStringExtra("poster"),300);
        LinearLayout text=Ui.column(this);Ui.pad(text,this,35,5,0,0);hero.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        text.addView(Ui.heading(this,title,30));
        summary=Ui.text(this,"Loading details…",16);Ui.pad(summary,this,0,10,0,10);text.addView(summary);
        LinearLayout actions=Ui.row(this);text.addView(actions);
        Button watch=Ui.button(this,"Watch Now");actions.addView(watch);watch.setOnClickListener(v->{
            if("movie".equals(kind))play(id,extension);
            else if(Api.prefs(this).getLong("resume_position_series:"+id,0)>=15000 &&
                    !Api.prefs(this).getString("resume_episode_series:"+id,"").isEmpty()) {
                String key="series:"+id;
                playEpisode(Api.prefs(this).getString("resume_episode_"+key,""),
                    Api.prefs(this).getString("resume_extension_"+key,"mp4"),
                    Api.prefs(this).getString("resume_next_"+key,""),
                    Api.prefs(this).getString("resume_next_ext_"+key,"mp4"));
            } else if(episodeArea!=null && episodeArea.getChildCount()>1) {
                android.view.View first=episodeArea.getChildAt(1);
                if(first instanceof HorizontalScrollView) {
                    android.view.View row=((HorizontalScrollView)first).getChildAt(0);
                    if(row instanceof LinearLayout && ((LinearLayout)row).getChildCount()>0)((LinearLayout)row).getChildAt(0).requestFocus();
                }
            }
        });
        trailerButton=Ui.button(this,"Trailer");LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.leftMargin=Ui.dp(this,12);actions.addView(trailerButton,tp);
        trailerButton.setEnabled(false);
        trailerButton.setOnClickListener(v->{String url=trailerButton.getTag() instanceof String?(String)trailerButton.getTag():"";
            if(url.startsWith("http") && !url.contains("youtube.com/") && !url.contains("youtu.be/"))playUrl(url,false);
            else if(!url.isEmpty())startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url.startsWith("http")?url:"https://www.youtube.com/watch?v="+url)));
        });
        Button favorite=Ui.button(this,isFavorite()?"Remove Favorite":"Add Favorite");
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-2,-2);fp.leftMargin=Ui.dp(this,12);actions.addView(favorite,fp);
        favorite.setOnClickListener(v->{toggleFavorite();favorite.setText(isFavorite()?"Remove Favorite":"Add Favorite");});
        castArea=Ui.column(this);content.addView(castArea);
        if("series".equals(kind)) {
            TextView heading=Ui.heading(this,"Episodes",23);Ui.pad(heading,this,0,55,0,16);content.addView(heading);
            episodeArea=Ui.column(this);content.addView(episodeArea);
        }
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
        if(!trailer.isEmpty()){trailerButton.setTag(trailer);trailerButton.setEnabled(true);}
        String plot=info.optString("plot",info.optString("description",""));
        summary.setText(plot.length()>300?plot.substring(0,300)+"…":plot);
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
                for(String season:seasons){
                JSONArray list=episodes.optJSONArray(season);if(list==null)continue;
                TextView label=Ui.heading(this,"Season "+season,19);Ui.pad(label,this,0,15,0,8);episodeArea.addView(label);
                HorizontalScrollView scroller=new HorizontalScrollView(this);scroller.setHorizontalScrollBarEnabled(false);
                episodeArea.addView(scroller);LinearLayout row=Ui.row(this);scroller.addView(row);
                for(int i=0;i<Math.min(30,list.length());i++) {
                    JSONObject ep=list.optJSONObject(i);if(ep==null)continue;
                    String episodeId=ep.optString("id");String ext=ep.optJSONObject("info")!=null?ep.optJSONObject("info").optString("container_extension","mp4"):"mp4";
                    int index=ordered.indexOf(ep);JSONObject next=index>=0&&index+1<ordered.size()?ordered.get(index+1):null;
                    String nextId=next==null?"":next.optString("id");String nextExt=next!=null&&next.optJSONObject("info")!=null?next.optJSONObject("info").optString("container_extension","mp4"):"mp4";
                    FrameLayout card=episodeCard(ep,i+1);
                    LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(Ui.dp(this,230),Ui.dp(this,145));bp.setMargins(0,0,Ui.dp(this,12),Ui.dp(this,14));row.addView(card,bp);
                    card.setOnClickListener(v->playEpisode(episodeId,ext,nextId,nextExt));
                }
                }
            }
        }
    }
    private static int parseSeason(String value){try{return Integer.parseInt(value);}catch(Exception e){return Integer.MAX_VALUE;}}
    private FrameLayout episodeCard(JSONObject ep,int number){
        FrameLayout frame=new FrameLayout(this);frame.setFocusable(true);frame.setBackground(Ui.glass(this,10));
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
    }catch(Exception ignored){}});}
    private void showCast(JSONObject data){
        String trailer=data.optString("trailer","");if(!trailer.isEmpty() && (trailerButton.getTag()==null || "".equals(trailerButton.getTag()))){trailerButton.setTag(trailer);trailerButton.setEnabled(true);}
        JSONArray cast=data.optJSONArray("cast");if(cast==null||cast.length()==0)return;
        TextView heading=Ui.heading(this,"Cast",23);Ui.pad(heading,this,0,55,0,16);castArea.addView(heading);
        HorizontalScrollView scroller=new HorizontalScrollView(this);scroller.setHorizontalScrollBarEnabled(false);castArea.addView(scroller);
        LinearLayout row=Ui.row(this);scroller.addView(row);
        for(int i=0;i<Math.min(15,cast.length());i++){
            JSONObject actor=cast.optJSONObject(i);if(actor==null)continue;
            LinearLayout card=Ui.column(this);card.setFocusable(true);card.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(Ui.dp(this,126),Ui.dp(this,175));cp.rightMargin=Ui.dp(this,14);row.addView(card,cp);
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackgroundColor(Ui.CARD);
            image.setOutlineProvider(new android.view.ViewOutlineProvider(){@Override public void getOutline(View v,android.graphics.Outline outline){outline.setOval(0,0,v.getWidth(),v.getHeight());}});
            image.setClipToOutline(true);
            card.addView(image,new LinearLayout.LayoutParams(Ui.dp(this,115),Ui.dp(this,115)));Images.load(image,actor.optString("image"),185);
            String actorName=actor.optString("name");int actorId=actor.optInt("id",0);
            TextView name=Ui.text(this,actorName,14);name.setGravity(Gravity.CENTER);name.setMaxLines(2);card.addView(name);
            card.setOnFocusChangeListener((v,f)->{v.setBackground(f?Ui.rounded(Ui.RED,12,this):Ui.glass(this,12));v.animate().scaleX(f?1.05f:1f).scaleY(f?1.05f:1f).setDuration(110).start();});
            card.setOnClickListener(v->{Intent intent=new Intent(this,ActorActivity.class);intent.putExtra("actor_id",actorId);intent.putExtra("actor_name",actorName);startActivity(intent);});
        }
    }
    private void play(String streamId,String ext) { playUrl(Api.stream(this,"movie".equals(kind)?"movie":"series",streamId,ext==null||ext.isEmpty()?"mp4":ext),true); }
    private void playEpisode(String streamId,String ext,String nextId,String nextExt){
        String url=Api.stream(this,"series",streamId,ext==null||ext.isEmpty()?"mp4":ext);
        String next=nextId.isEmpty()?"":Api.stream(this,"series",nextId,nextExt);
        Catalog.remember(this,new Catalog.Item(itemJson(),kind));
        Intent intent=new Intent(this,PlayerActivity.class);intent.putExtra("url",url);intent.putExtra("title",title);intent.putExtra("next_url",next);
        intent.putExtra("content_kind",kind);intent.putExtra("content_id",id);
        intent.putExtra("episode_id",streamId);intent.putExtra("episode_ext",ext);
        intent.putExtra("next_episode_id",nextId);intent.putExtra("next_episode_ext",nextExt);startActivity(intent);
    }
    private void playUrl(String url,boolean track) { if(track)Catalog.remember(this,new Catalog.Item(itemJson(),kind));Intent i=new Intent(this,PlayerActivity.class);i.putExtra("url",url);i.putExtra("title",title);if(track){i.putExtra("content_kind",kind);i.putExtra("content_id",id);}startActivity(i); }
    private JSONObject itemJson() { JSONObject j=new JSONObject();try {j.put("name",title).put("stream_id",id).put("series_id",id);}catch(Exception ignored){}return j; }
    private boolean isFavorite(){return Api.prefs(this).getString("favorites","").contains("|"+kind+":"+id+"|");}
    private void toggleFavorite(){String key="|"+kind+":"+id+"|";String favorites=Api.prefs(this).getString("favorites","");
        Api.prefs(this).edit().putString("favorites",isFavorite()?favorites.replace(key,""):favorites+key).apply();}
}

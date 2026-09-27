package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
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
    private LinearLayout episodeArea; private TextView summary; private Button trailerButton;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        id=getIntent().getStringExtra("id");kind=getIntent().getStringExtra("kind");title=getIntent().getStringExtra("title");extension=getIntent().getStringExtra("extension");
        if(id==null||kind==null){finish();return;} draw();details();
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
            else if(episodeArea!=null && episodeArea.getChildCount()>1) {
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
            if(url.startsWith("http"))playUrl(url);
            else if(!url.isEmpty())startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.youtube.com/watch?v="+url)));
        });
        Button favorite=Ui.button(this,isFavorite()?"Remove Favorite":"Add Favorite");
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-2,-2);fp.leftMargin=Ui.dp(this,12);actions.addView(favorite,fp);
        favorite.setOnClickListener(v->{toggleFavorite();favorite.setText(isFavorite()?"Remove Favorite":"Add Favorite");});
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
            if(episodes!=null)for(java.util.Iterator<String> seasons=episodes.keys();seasons.hasNext();) {
                String season=seasons.next();if("0".equals(season))continue;
                JSONArray list=episodes.optJSONArray(season);if(list==null)continue;
                TextView label=Ui.heading(this,"Season "+season,19);Ui.pad(label,this,0,15,0,8);episodeArea.addView(label);
                HorizontalScrollView scroller=new HorizontalScrollView(this);scroller.setHorizontalScrollBarEnabled(false);
                episodeArea.addView(scroller);LinearLayout row=Ui.row(this);scroller.addView(row);
                for(int i=0;i<Math.min(30,list.length());i++) {
                    JSONObject ep=list.optJSONObject(i);if(ep==null)continue;
                    String episodeId=ep.optString("id");String ext=ep.optJSONObject("info")!=null?ep.optJSONObject("info").optString("container_extension","mp4"):"mp4";
                    Button button=Ui.button(this,(i+1)+"  "+ep.optString("title","Episode "+(i+1)));
                    LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(Ui.dp(this,185),Ui.dp(this,72));bp.setMargins(0,0,Ui.dp(this,12),Ui.dp(this,14));row.addView(button,bp);
                    button.setOnClickListener(v->play(episodeId,ext));
                }
            }
        }
    }
    private void play(String streamId,String ext) { playUrl(Api.stream(this,"movie".equals(kind)?"movie":"series",streamId,ext==null||ext.isEmpty()?"mp4":ext)); }
    private void playUrl(String url) { Catalog.remember(this,new Catalog.Item(itemJson(),kind));Intent i=new Intent(this,PlayerActivity.class);i.putExtra("url",url);i.putExtra("title",title);startActivity(i); }
    private JSONObject itemJson() { JSONObject j=new JSONObject();try {j.put("name",title).put("stream_id",id).put("series_id",id);}catch(Exception ignored){}return j; }
    private boolean isFavorite(){return Api.prefs(this).getString("favorites","").contains("|"+kind+":"+id+"|");}
    private void toggleFavorite(){String key="|"+kind+":"+id+"|";String favorites=Api.prefs(this).getString("favorites","");
        Api.prefs(this).edit().putString("favorites",isFavorite()?favorites.replace(key,""):favorites+key).apply();}
}

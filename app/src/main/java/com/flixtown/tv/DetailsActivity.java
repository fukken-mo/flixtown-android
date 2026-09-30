package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.HorizontalGridView;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Movie and TV show details.
 *
 * Both start with a full-bleed backdrop behind the title, metadata, synopsis and actions.
 * Movies then show a compact row of round cast portraits and a "More Like This" row of posters
 * that exist in this catalog, all sized to fit one TV screen inside the safe area. TV shows keep
 * the season selector and episode row, followed by the cast. Rows are Leanback grids, so every
 * action, actor and poster is one D-pad press away and each row remembers its position.
 */
public class DetailsActivity extends Activity {
    private static final int SIDE_DP=56,CAST_ROW_DP=92,SECTION_LABEL_DP=30;
    // Fixed heights for the movie layout, so the poster row can be sized once and never overflows.
    private static final int TITLE_DP=46,META_DP=24,SUMMARY_DP=62,ACTIONS_DP=46,CAST_GAP_DP=14,INFO_GAPS_DP=4+8+14;
    private int moreArtWidth,moreArtHeight;
    private String id,kind,title,extension,categoryId;
    private LinearLayout content,episodeArea,seasonContent,castSection,moreSection;
    private HorizontalGridView castRow,moreRow;
    private TextView summary,metaLine;private ImageView backdrop;
    private Button trailerButton,watch,favorite;
    private boolean enrichedCast,movie;
    private final List<JSONObject> castPeople=new ArrayList<>();
    private List<JSONObject> orderedEpisodes;private int seasonIndex;private boolean playedOnOpen;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        id=getIntent().getStringExtra("id");kind=getIntent().getStringExtra("kind");title=getIntent().getStringExtra("title");
        extension=getIntent().getStringExtra("extension");categoryId=getIntent().getStringExtra("category");
        if(id==null||kind==null){finish();return;}
        if(title==null)title="";if(categoryId==null)categoryId="";
        movie="movie".equals(kind);
        Images.init(this);draw();details();cast();if(movie)loadSimilar();
    }
    @Override protected void onRestart(){super.onRestart();if(watch!=null)watch.setText(watchLabel());}
    private String resumeKey(){return kind+":"+id;}
    private String watchLabel(){
        if(!movie)return Api.prefs(this).getString("resume_episode_"+resumeKey(),"").isEmpty()?"▶  Watch Now":"▶  Continue";
        return Api.prefs(this).getLong("resume_position_"+resumeKey(),0)>=15000?"▶  Resume":"▶  Play";
    }

    /* ---------------- Layout ---------------- */

    private void draw() {
        DisplayMetrics m=getResources().getDisplayMetrics();
        int safeY=Ui.safeY(this),screenH=m.heightPixels;
        FrameLayout shell=new FrameLayout(this);shell.setBackgroundColor(Ui.BG);setContentView(shell);
        backdrop=new ImageView(this);backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        shell.addView(backdrop,new FrameLayout.LayoutParams(-1,Math.round(screenH*.70f)));
        Images.load(backdrop,getIntent().getStringExtra("backdrop"),1280);
        View scrim=new View(this);scrim.setBackgroundResource(R.drawable.details_scrim);
        shell.addView(scrim,new FrameLayout.LayoutParams(-1,Math.round(screenH*.72f)));

        content=Ui.column(this);content.setClipChildren(false);content.setClipToPadding(false);
        content.setPadding(Ui.dp(this,SIDE_DP),safeY,Ui.dp(this,48),safeY);
        if(movie){
            // Movies fit on one screen: no scrolling, every row visible at once.
            shell.addView(content,new FrameLayout.LayoutParams(-1,-1));
        }else{
            ScrollView page=new ScrollView(this);page.setVerticalScrollBarEnabled(false);page.setFillViewport(true);
            page.setSmoothScrollingEnabled(true);page.setClipChildren(false);
            shell.addView(page,new FrameLayout.LayoutParams(-1,-1));
            page.addView(content,new FrameLayout.LayoutParams(-1,-2));
        }

        TextView titleView=Ui.heading(this,title,32);titleView.setMaxLines(movie?1:2);titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setShadowLayer(12,0,2,0xAA000000);
        content.addView(titleView,new LinearLayout.LayoutParams(Ui.dp(this,640),movie?Ui.dp(this,TITLE_DP):-2));
        metaLine=Ui.text(this,yearText(),16);metaLine.setTextColor(Ui.TEXT_2);metaLine.setSingleLine(true);metaLine.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(Ui.dp(this,640),movie?Ui.dp(this,META_DP):-2);mp.topMargin=Ui.dp(this,4);content.addView(metaLine,mp);
        summary=Ui.text(this,"Loading details…",15);summary.setTextColor(0xFFD9D4CF);summary.setMaxLines(3);summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setLineSpacing(0,1.12f);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(Ui.dp(this,580),movie?Ui.dp(this,SUMMARY_DP):-2);sp.topMargin=Ui.dp(this,8);content.addView(summary,sp);

        LinearLayout actions=Ui.row(this);actions.setGravity(Gravity.CENTER_VERTICAL);actions.setClipChildren(false);actions.setClipToPadding(false);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-2,-2);ap.topMargin=Ui.dp(this,14);content.addView(actions,ap);
        watch=Ui.button(this,watchLabel());actions.addView(watch,actionParams(false));
        watch.setOnClickListener(v->{
            if(movie)play(id,extension);
            else if(!Api.prefs(this).getString("resume_episode_series:"+id,"").isEmpty()) resumeSeries();
            else focusFirstEpisode();
        });
        trailerButton=Ui.button(this,"Trailer");actions.addView(trailerButton,actionParams(true));
        trailerButton.setEnabled(false);trailerButton.setAlpha(.4f);
        trailerButton.setOnClickListener(v->{String url=trailerButton.getTag() instanceof String?(String)trailerButton.getTag():"";
            if(url.startsWith("http") && !url.contains("youtube.com/") && !url.contains("youtu.be/"))playUrl(url,false);
            else if(!url.isEmpty())startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url.startsWith("http")?url:"https://www.youtube.com/watch?v="+url)));
        });
        favorite=Ui.button(this,favoriteLabel());actions.addView(favorite,actionParams(true));
        favorite.setOnClickListener(v->{toggleFavorite();favorite.setText(favoriteLabel());});

        if(movie){
            castSection=Ui.column(this);castSection.setVisibility(View.INVISIBLE);castSection.setClipChildren(false);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,Ui.dp(this,CAST_ROW_DP));cp.topMargin=Ui.dp(this,CAST_GAP_DP);
            content.addView(castSection,cp);
            castRow=castGrid();castSection.addView(castRow,new LinearLayout.LayoutParams(-1,-1));

            moreSection=Ui.column(this);moreSection.setVisibility(View.GONE);moreSection.setClipChildren(false);
            content.addView(moreSection,new LinearLayout.LayoutParams(-1,-2));
            TextView label=Ui.heading(this,"More Like This",19);label.setGravity(Gravity.BOTTOM);
            label.setPadding(0,0,0,Ui.dp(this,2));
            moreSection.addView(label,new LinearLayout.LayoutParams(-1,Ui.dp(this,SECTION_LABEL_DP)));
            moreRow=new HorizontalGridView(this);configureRow(moreRow,-PosterAdapter.GLOW_DP);
            // Whatever is left of the screen below the fixed blocks (the cast row is always reserved).
            int fixed=Ui.dp(this,TITLE_DP+META_DP+SUMMARY_DP+ACTIONS_DP+INFO_GAPS_DP+CAST_GAP_DP+CAST_ROW_DP+SECTION_LABEL_DP);
            int listHeight=Math.max(Ui.dp(this,120),screenH-2*safeY-fixed);
            moreArtHeight=listHeight-2*Ui.dp(this,PosterAdapter.GLOW_DP)-Ui.dp(this,6);moreArtWidth=moreArtHeight*2/3;
            moreSection.addView(moreRow,new LinearLayout.LayoutParams(-1,listHeight));
        }else{
            TextView heading=Ui.heading(this,"Episodes",21);heading.setPadding(0,Ui.dp(this,20),0,Ui.dp(this,6));content.addView(heading);
            episodeArea=Ui.column(this);episodeArea.setClipChildren(false);episodeArea.setClipToPadding(false);
            content.addView(episodeArea);
            TextView loading=Ui.text(this,"Loading episodes…",15);loading.setTextColor(Ui.TEXT_3);episodeArea.addView(loading);
            castSection=Ui.column(this);castSection.setVisibility(View.GONE);castSection.setClipChildren(false);
            TextView castLabel=Ui.heading(this,"Cast",19);castLabel.setPadding(0,Ui.dp(this,16),0,Ui.dp(this,4));castSection.addView(castLabel);
            castRow=castGrid();castSection.addView(castRow,new LinearLayout.LayoutParams(-1,Ui.dp(this,CAST_ROW_DP)));
            content.addView(castSection,new LinearLayout.LayoutParams(-1,-2));
        }
        watch.requestFocus();
    }
    private LinearLayout.LayoutParams actionParams(boolean gap){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,46));
        if(gap)p.leftMargin=Ui.dp(this,12);return p;}
    private String favoriteLabel(){return isFavorite()?"✓  My List":"+  My List";}
    private String yearText(){int year=getIntent().getIntExtra("year",0);return year>1900?String.valueOf(year):"";}
    /** Rows keep the selected item at the left edge, lined up with the text above. */
    private void configureRow(HorizontalGridView row,int edgeOffsetDp){
        row.setClipChildren(false);row.setClipToPadding(false);row.setItemAnimator(null);row.setHorizontalSpacing(0);
        row.setPadding(0,0,Ui.dp(this,8),0);
        // Poster cards carry a glow margin; shift the row left so the artwork lines up with the title.
        if(edgeOffsetDp<0)row.setTranslationX(Ui.dp(this,edgeOffsetDp));
        row.setWindowAlignment(BaseGridView.WINDOW_ALIGN_BOTH_EDGE);
        row.setWindowAlignmentOffsetPercent(BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);
        row.setWindowAlignmentOffset(row.getPaddingLeft());
        row.setItemAlignmentOffsetPercent(0f);row.setItemAlignmentOffset(0);
    }
    private HorizontalGridView castGrid(){HorizontalGridView g=new HorizontalGridView(this);configureRow(g,0);g.setHorizontalSpacing(Ui.dp(this,6));return g;}

    /* ---------------- Details ---------------- */

    private void details() {
        Api.IO.execute(()->{
            try {
                JSONObject data=Api.get(Api.xtream(this,movie?"get_vod_info":"get_series_info","&"+(movie?"vod_id=":"series_id=")+Api.enc(id)));
                runOnUiThread(()->{if(!isFinishing())renderDetails(data);});
            } catch(Exception e){runOnUiThread(()->summary.setText("Details are unavailable right now. You can still play this title."));}
        });
    }
    private void renderDetails(JSONObject data) {
        JSONObject info=data.optJSONObject("info");
        if(info==null)info=new JSONObject();
        String trailer=info.optString("youtube_trailer","");
        if(!trailer.isEmpty())enableTrailer(trailer);
        String plot=info.optString("plot",info.optString("description","")).trim();
        summary.setText(plot.isEmpty()?"":plot);
        metaLine.setText(metaText(info));
        String art=firstBackdrop(info);
        if(art!=null)Images.load(backdrop,art,1280);
        if(!enrichedCast && castPeople.isEmpty()){
            String actorNames=info.optString("cast",info.optString("actors",""));
            if(!actorNames.isEmpty()){
                JSONArray fallback=new JSONArray();
                for(String actorName:actorNames.split(","))if(!actorName.trim().isEmpty()){
                    JSONObject actor=new JSONObject();try{actor.put("name",actorName.trim());}catch(Exception ignored){}fallback.put(actor);
                }
                JSONObject payload=new JSONObject();try{payload.put("cast",fallback);}catch(Exception ignored){}showCast(payload);
            }
        }
        if(movie) {
            JSONObject movieData=data.optJSONObject("movie_data");
            if(movieData!=null)extension=movieData.optString("container_extension",extension);
        } else renderEpisodes(data.optJSONObject("episodes"));
    }
    /** Year · runtime · genre · ★ rating, from whatever the server provides. */
    private String metaText(JSONObject info){
        List<String> parts=new ArrayList<>();
        String date=info.optString("releasedate",info.optString("releaseDate",""));
        String year=date.length()>=4 && date.substring(0,4).matches("\\d{4}")?date.substring(0,4):yearText();
        if(!year.isEmpty())parts.add(year);
        int seconds=info.optInt("duration_secs",0);
        if(seconds<=0){String d=info.optString("duration","");String[] hms=d.split(":");
            try{if(hms.length==3)seconds=Integer.parseInt(hms[0])*3600+Integer.parseInt(hms[1])*60+Integer.parseInt(hms[2]);}catch(Exception ignored){}}
        if(seconds>=60)parts.add(seconds>=3600?(seconds/3600)+"h "+((seconds/60)%60)+"m":(seconds/60)+"m");
        String genre=info.optString("genre","").trim();
        if(!genre.isEmpty()){String[] g=genre.split("[,/]");StringBuilder out=new StringBuilder();
            for(int i=0;i<Math.min(2,g.length);i++){if(out.length()>0)out.append(", ");out.append(g[i].trim());}parts.add(out.toString());}
        double rating=0;try{rating=Double.parseDouble(info.optString("rating","0"));}catch(Exception ignored){}
        if(rating>0)parts.add(String.format(Locale.US,"★ %.1f",rating));
        return TextUtils.join("   ·   ",parts);
    }
    private static String firstBackdrop(JSONObject info){
        JSONArray list=info.optJSONArray("backdrop_path");
        String url=list!=null&&list.length()>0?list.optString(0,""):info.optString("backdrop_path","");
        return url.startsWith("http")?url:null;
    }

    /* ---------------- More Like This ---------------- */

    private void loadSimilar(){Api.IO.execute(()->{
        List<Catalog.Item> pool=Catalog.Store.loaded()?Catalog.Store.movies:Catalog.parse(Api.cached(this,"movies"),"movie");
        Catalog.Item self=null;for(Catalog.Item item:pool)if(item.id.equals(id)){self=item;break;}
        if(self==null){JSONObject j=new JSONObject();try{j.put("stream_id",id).put("name",title).put("category_id",categoryId);}catch(Exception ignored){}
            self=new Catalog.Item(j,"movie");}
        List<Catalog.Item> similar=Catalog.similar(self,pool,20);
        runOnUiThread(()->{if(isFinishing()||similar.isEmpty())return;
            moreSection.setVisibility(View.VISIBLE);
            moreRow.setAdapter(new PosterAdapter(this,similar,moreArtWidth,moreArtHeight,false,null));
        });
    });}

    /* ---------------- Episodes (TV shows) ---------------- */

    private void renderEpisodes(JSONObject episodes){
        if(episodes==null){episodeArea.removeAllViews();TextView none=Ui.text(this,"No episodes available yet",15);none.setTextColor(Ui.TEXT_3);episodeArea.addView(none);return;}
        List<String> seasons=new ArrayList<>();
        for(java.util.Iterator<String> keys=episodes.keys();keys.hasNext();) {
            String value=keys.next();if(!"0".equals(value))seasons.add(value);
        }
        seasons.sort((a,b)->Integer.compare(parseSeason(a),parseSeason(b)));
        List<JSONObject> ordered=new ArrayList<>();
        for(String season:seasons){JSONArray list=episodes.optJSONArray(season);if(list!=null)for(int i=0;i<list.length();i++){
            JSONObject episode=list.optJSONObject(i);if(episode!=null)ordered.add(episode);
        }}
        orderedEpisodes=ordered;
        episodeArea.removeAllViews();
        seasonContent=Ui.column(this);seasonContent.setClipChildren(false);episodeArea.addView(seasonContent);
        if(seasons.isEmpty()){TextView none=Ui.text(this,"No episodes available yet",15);none.setTextColor(Ui.TEXT_3);episodeArea.addView(none,0);return;}
        // Open on the season that holds the episode the viewer is watching.
        String saved=Api.prefs(this).getString("resume_episode_"+resumeKey(),"");seasonIndex=0;
        if(!saved.isEmpty())for(int i=0;i<seasons.size();i++){JSONArray list=episodes.optJSONArray(seasons.get(i));
            if(list!=null)for(int j=0;j<list.length();j++){JSONObject ep=list.optJSONObject(j);if(ep!=null && saved.equals(ep.optString("id")))seasonIndex=i;}}
        String[] names=new String[seasons.size()];for(int i=0;i<seasons.size();i++)names[i]="Season "+seasons.get(i);
        Button select=Ui.button(this,names[seasonIndex]+"  ›");select.setTextSize(15);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,42));sp.bottomMargin=Ui.dp(this,4);
        episodeArea.addView(select,0,sp);
        select.setOnClickListener(v->Ui.picker(this,"Select season",names,seasonIndex,index->{
            seasonIndex=index;select.setText(names[index]+"  ›");showSeason(episodes,seasons.get(index),ordered);
        }));
        showSeason(episodes,seasons.get(seasonIndex),ordered);
        playOnOpen();
    }
    private void focusFirstEpisode(){
        if(seasonContent!=null && seasonContent.getChildCount()>0 && seasonContent.getChildAt(0) instanceof HorizontalScrollView){
            View row=((HorizontalScrollView)seasonContent.getChildAt(0)).getChildAt(0);
            if(row instanceof LinearLayout && ((LinearLayout)row).getChildCount()>0)((LinearLayout)row).getChildAt(0).requestFocus();
        }
    }
    private static int parseSeason(String value){try{return Integer.parseInt(value);}catch(Exception e){return Integer.MAX_VALUE;}}
    private void showSeason(JSONObject seasons,String season,List<JSONObject> ordered){
        JSONArray list=seasons.optJSONArray(season);seasonContent.removeAllViews();if(list==null)return;
        HorizontalScrollView scroller=new HorizontalScrollView(this);scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);scroller.setClipToPadding(false);scroller.setPadding(0,Ui.dp(this,8),Ui.dp(this,10),Ui.dp(this,9));
        seasonContent.addView(scroller,new LinearLayout.LayoutParams(-1,Ui.dp(this,143)));
        LinearLayout row=Ui.row(this);row.setClipChildren(false);row.setClipToPadding(false);scroller.addView(row);
        String saved=Api.prefs(this).getString("resume_episode_"+resumeKey(),"");View resume=null;
        for(int i=0;i<list.length();i++){
            JSONObject ep=list.optJSONObject(i);if(ep==null)continue;
            String episodeId=ep.optString("id");String ext=extensionOf(ep,"mp4");
            int index=ordered.indexOf(ep);JSONObject next=index>=0&&index+1<ordered.size()?ordered.get(index+1):null;
            String nextId=next==null?"":next.optString("id");String nextExt=next==null?"mp4":extensionOf(next,"mp4");
            FrameLayout card=episodeCard(ep,ep.optInt("episode_num",i+1),episodeId.equals(saved));
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(Ui.dp(this,206),Ui.dp(this,116));bp.setMargins(i==0?0:Ui.dp(this,8),0,Ui.dp(this,12),0);row.addView(card,bp);
            String label=episodeLabel(ep),nextLabel=next==null?"":episodeLabel(next);
            card.setOnClickListener(v->{rememberArt(ep);playEpisode(episodeId,ext,nextId,nextExt,label,nextLabel);});
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
        String label="",nextLabel="";
        if(orderedEpisodes!=null)for(int i=0;i<orderedEpisodes.size();i++){JSONObject ep=orderedEpisodes.get(i);
            if(!episode.equals(ep.optString("id")))continue;
            ext=extensionOf(ep,ext);label=episodeLabel(ep);rememberArt(ep);
            JSONObject next=i+1<orderedEpisodes.size()?orderedEpisodes.get(i+1):null;
            nextId=next==null?"":next.optString("id");nextExt=next==null?"mp4":extensionOf(next,"mp4");
            nextLabel=next==null?"":episodeLabel(next);break;}
        playEpisode(episode,ext,nextId,nextExt,label,nextLabel);
    }
    private static String extensionOf(JSONObject ep,String fallback){JSONObject info=ep.optJSONObject("info");
        String ext=ep.optString("container_extension","");if(ext.isEmpty() && info!=null)ext=info.optString("container_extension","");
        return ext.isEmpty()?fallback:ext;}
    private FrameLayout episodeCard(JSONObject ep,int number,boolean current){
        FrameLayout frame=new FrameLayout(this);frame.setFocusable(true);frame.setFocusableInTouchMode(true);frame.setClickable(true);
        int radius=Ui.dp(this,10);
        frame.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),radius);}});
        frame.setClipToOutline(true);frame.setBackgroundColor(0xFF17171C);
        ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setAlpha(.75f);
        frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        JSONObject info=ep.optJSONObject("info");String art=info==null?"":info.optString("movie_image","");
        Images.load(image,art,420);
        View shade=new View(this);shade.setBackgroundResource(R.drawable.card_shade);frame.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        TextView index=Ui.heading(this,String.format(Locale.US,"%02d",number),30);
        Ui.pad(index,this,12,6,0,0);frame.addView(index);
        if(current){TextView badge=Ui.text(this,"Continue",12);badge.setBackground(Ui.rounded(Ui.ACCENT,4,this));Ui.pad(badge,this,8,2,8,2);
            FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-2,-2,Gravity.END|Gravity.TOP);bp.setMargins(0,Ui.dp(this,10),Ui.dp(this,10),0);frame.addView(badge,bp);}
        TextView label=Ui.text(this,ep.optString("title","Episode "+number),13);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);
        Ui.pad(label,this,12,0,12,8);
        frame.addView(label,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        GradientDrawable ring=new GradientDrawable();ring.setCornerRadius(radius);ring.setStroke(Ui.dp(this,3),0xFFE06A66);ring.setColor(0);
        frame.setOnFocusChangeListener((v,focused)->{
            frame.setForeground(focused?ring:null);image.setAlpha(focused?1f:.75f);
            v.animate().scaleX(focused?1.05f:1f).scaleY(focused?1.05f:1f).setDuration(120).start();
        });
        return frame;
    }

    /* ---------------- Cast ---------------- */

    private void cast(){Api.IO.execute(()->{try{
        int year=getIntent().getIntExtra("year",0);
        String endpoint=BuildConfig.PANEL_URL+"tmdb.php?kind="+Api.enc(kind)+"&title="+Api.enc(title)
            +(year>1900?"&year="+year:"");
        JSONObject tmdb=Api.get(endpoint);
        runOnUiThread(()->{if(!isFinishing())showCast(tmdb);});
    }catch(Exception e){android.util.Log.w("FlixTownCast","Cast lookup failed",e);}});}
    private void showCast(JSONObject data){
        String trailer=data.optString("trailer","");if(!trailer.isEmpty() && (trailerButton.getTag()==null || "".equals(trailerButton.getTag())))enableTrailer(trailer);
        JSONArray cast=data.optJSONArray("cast");if(cast==null||cast.length()==0)return;
        boolean hasPortrait=false;
        for(int j=0;j<cast.length();j++){JSONObject person=cast.optJSONObject(j);if(person!=null && person.optString("image","").startsWith("https://")){hasPortrait=true;break;}}
        if(hasPortrait)enrichedCast=true;
        else if(enrichedCast)return;
        castPeople.clear();
        for(int i=0;i<Math.min(15,cast.length());i++){JSONObject actor=cast.optJSONObject(i);if(actor!=null && !actor.optString("name").isEmpty())castPeople.add(actor);}
        if(castPeople.isEmpty())return;
        boolean hadFocus=castRow.hasFocus();
        castRow.setAdapter(new CastAdapter(new ArrayList<>(castPeople)));
        castSection.setVisibility(View.VISIBLE);
        if(hadFocus)castRow.requestFocus();
    }
    private final class CastAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>{
        private final List<JSONObject> people;
        CastAdapter(List<JSONObject> people){this.people=people;}
        @Override public int getItemCount(){return people.size();}
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent,int type){
            LinearLayout card=Ui.column(DetailsActivity.this);card.setFocusable(true);card.setFocusableInTouchMode(true);card.setClickable(true);
            card.setGravity(Gravity.CENTER_HORIZONTAL);card.setClipChildren(false);
            card.setLayoutParams(new RecyclerView.LayoutParams(Ui.dp(DetailsActivity.this,84),ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout ring=new FrameLayout(DetailsActivity.this);int pad=Ui.dp(DetailsActivity.this,3);ring.setPadding(pad,pad,pad,pad);
            ring.setBackground(castRing(false));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(Ui.dp(DetailsActivity.this,62),Ui.dp(DetailsActivity.this,62));rp.topMargin=Ui.dp(DetailsActivity.this,4);
            card.addView(ring,rp);
            FrameLayout portrait=new FrameLayout(DetailsActivity.this);portrait.setBackgroundColor(0xFF25212A);
            portrait.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setOval(0,0,v.getWidth(),v.getHeight());}});
            portrait.setClipToOutline(true);ring.addView(portrait,new FrameLayout.LayoutParams(-1,-1));
            TextView letter=Ui.heading(DetailsActivity.this,"",24);letter.setTextColor(Ui.TEXT_2);letter.setGravity(Gravity.CENTER);
            portrait.addView(letter,new FrameLayout.LayoutParams(-1,-1));
            ImageView image=new ImageView(DetailsActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            portrait.addView(image,new FrameLayout.LayoutParams(-1,-1));
            TextView name=Ui.text(DetailsActivity.this,"",13);name.setTextColor(Ui.TEXT_2);name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=Ui.dp(DetailsActivity.this,6);card.addView(name,np);
            card.setOnFocusChangeListener((v,f)->{ring.setBackground(castRing(f));name.setTextColor(f?Ui.TEXT:Ui.TEXT_2);
                ring.animate().scaleX(f?1.08f:1f).scaleY(f?1.08f:1f).setDuration(120).start();});
            card.setTag(new Object[]{letter,image,name});
            return new RecyclerView.ViewHolder(card){};
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder,int position){
            JSONObject actor=people.get(position);Object[] parts=(Object[])holder.itemView.getTag();
            String actorName=actor.optString("name");int actorId=actor.optInt("id",0);
            ((TextView)parts[0]).setText(actorName.isEmpty()?"?":actorName.substring(0,1).toUpperCase(Locale.ROOT));
            String imageUrl=actor.optString("image","");
            Images.load((ImageView)parts[1],imageUrl.startsWith("http")?imageUrl:null,200);
            ((TextView)parts[2]).setText(actorName);
            holder.itemView.setContentDescription(actorName);
            holder.itemView.setOnClickListener(v->{Intent intent=new Intent(DetailsActivity.this,ActorActivity.class);
                intent.putExtra("actor_id",actorId);intent.putExtra("actor_name",actorName);startActivity(intent);});
        }
    }
    private GradientDrawable castRing(boolean focused){
        GradientDrawable ring=new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);ring.setColor(focused?0x33CE4B4A:0x00000000);
        ring.setStroke(Ui.dp(this,focused?3:1),focused?0xFFE06A66:0x40FFFFFF);
        return ring;
    }

    /* ---------------- Playback ---------------- */

    // Continue / Start over is asked by the player itself, so every entry point behaves the same.
    private void play(String streamId,String ext){
        String url=Api.stream(this,"movie",streamId,ext==null||ext.isEmpty()?"mp4":ext);
        playUrl(url,true);
    }
    /** Keeps the episode still for the Continue Watching card on Home. */
    private void rememberArt(JSONObject ep){JSONObject info=ep.optJSONObject("info");String art=info==null?"":info.optString("movie_image","");
        if(art.startsWith("http")||art.startsWith("demo:"))Api.prefs(this).edit().putString("resume_art_"+resumeKey(),art).apply();}
    /** Home's "Watch Now" on a TV show: resume the saved episode, otherwise start the first one. */
    private void playOnOpen(){
        if(!getIntent().getBooleanExtra("play_on_open",false) || playedOnOpen || orderedEpisodes==null || orderedEpisodes.isEmpty())return;
        playedOnOpen=true;
        if(!Api.prefs(this).getString("resume_episode_"+resumeKey(),"").isEmpty()){resumeSeries();return;}
        JSONObject first=orderedEpisodes.get(0),next=orderedEpisodes.size()>1?orderedEpisodes.get(1):null;
        rememberArt(first);
        playEpisode(first.optString("id"),extensionOf(first,"mp4"),next==null?"":next.optString("id"),next==null?"mp4":extensionOf(next,"mp4"),
            episodeLabel(first),next==null?"":episodeLabel(next));
    }
    /** "S1 · E3  Title" for the player's title area and the Up next card. */
    private static String episodeLabel(JSONObject ep){
        int season=ep.optInt("season",0),number=ep.optInt("episode_num",0);String name=ep.optString("title","").trim();
        StringBuilder out=new StringBuilder();
        if(season>0)out.append("S").append(season).append(" · ");
        if(number>0)out.append("E").append(number);
        if(!name.isEmpty()){if(out.length()>0)out.append("   ");out.append(name);}
        return out.toString();
    }
    private void playEpisode(String streamId,String ext,String nextId,String nextExt,String label,String nextLabel){
        String url=Api.stream(this,"series",streamId,ext==null||ext.isEmpty()?"mp4":ext);
        String next=nextId.isEmpty()?"":Api.stream(this,"series",nextId,nextExt);
        Catalog.remember(this,new Catalog.Item(itemJson(),kind));
        Intent intent=new Intent(this,PlayerActivity.class);intent.putExtra("url",url);intent.putExtra("title",title);intent.putExtra("next_url",next);
        intent.putExtra("content_kind",kind);intent.putExtra("content_id",id);
        intent.putExtra("episode_id",streamId);intent.putExtra("episode_ext",ext);
        intent.putExtra("next_episode_id",nextId);intent.putExtra("next_episode_ext",nextExt);
        intent.putExtra("subtitle",label);intent.putExtra("next_subtitle",nextLabel);
        startActivity(intent);
    }
    private void playUrl(String url,boolean track){
        if(track)Catalog.remember(this,new Catalog.Item(itemJson(),kind));
        Intent i=new Intent(this,PlayerActivity.class);i.putExtra("url",url);i.putExtra("title",title);
        if(track){i.putExtra("content_kind",kind);i.putExtra("content_id",id);}
        startActivity(i);
    }
    private JSONObject itemJson() { JSONObject j=new JSONObject();try {j.put("name",title).put("stream_id",id).put("series_id",id).put("category_id",categoryId);}catch(Exception ignored){}return j; }
    private boolean isFavorite(){return Api.prefs(this).getString("favorites","").contains("|"+kind+":"+id+"|");}
    private void toggleFavorite(){String key="|"+kind+":"+id+"|";String favorites=Api.prefs(this).getString("favorites","");
        Api.prefs(this).edit().putString("favorites",isFavorite()?favorites.replace(key,""):favorites+key).apply();}
}

package com.flixtown.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.EditText;
import android.text.TextWatcher;
import android.text.Editable;
import android.graphics.drawable.GradientDrawable;
import androidx.leanback.widget.HorizontalGridView;
import java.util.ArrayList;
import java.util.List;

public class HomeActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private List<Catalog.Item> movies=new ArrayList<>(),series=new ArrayList<>();
    private LinearLayout rows,rail; private ScrollView scroll; private ImageView backdrop; private TextView hero,notice,expiry;
    private String tab="Home",lastFocused=""; private int generation,sortMode; private boolean focusQueued,avoidFocusSteal;
    @Override public void onCreate(Bundle b) { super.onCreate(b);if(Api.prefs(this).getBoolean("expired",false)){startActivity(new android.content.Intent(this,RenewalActivity.class));finish();return;}renderShell(); loadCache(); }
    @Override protected void onResume() { super.onResume(); if(rows!=null)refresh(); }
    private void renderShell() {
        LinearLayout shell=Ui.row(this);shell.setBackgroundColor(Ui.BG);setContentView(shell);
        rail=Ui.column(this);rail.setBackgroundColor(0xFF11131B);Ui.pad(rail,this,12,20,12,0);
        shell.addView(rail,new LinearLayout.LayoutParams(Ui.dp(this,182),-1));
        ImageView railLogo=new ImageView(this);
        railLogo.setImageResource(R.drawable.flix_logo);
        railLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoParams=new LinearLayout.LayoutParams(-1,Ui.dp(this,95));
        logoParams.bottomMargin=Ui.dp(this,20);rail.addView(railLogo,logoParams);
        LinearLayout content=Ui.column(this);shell.addView(content,new LinearLayout.LayoutParams(0,-1,1));
        int screenDp=Math.round(getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density);
        int heroHeight=Math.max(175,Math.min(220,Math.round(screenDp*.27f)));
        FrameLayout top=new FrameLayout(this); content.addView(top,new LinearLayout.LayoutParams(-1,Ui.dp(this,heroHeight)));
        backdrop=new ImageView(this);backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);backdrop.setAlpha(.38f);top.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        View scrim=new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xF808090D,0xD408090D,0x8008090D}));
        top.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout info=Ui.column(this);Ui.pad(info,this,35,0,30,21);
        FrameLayout.LayoutParams infoParams=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        top.addView(info,infoParams);
        TextView featured=Ui.text(this,"FEATURED",12);featured.setTextColor(0xFFFF5965);info.addView(featured);
        hero=Ui.heading(this,"Flix Town",30);hero.setMaxLines(2);hero.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(hero);
        expiry=Ui.text(this,"",14);info.addView(expiry);
        notice=Ui.text(this,"",15);notice.setMaxLines(2); LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=Ui.dp(this,7);info.addView(notice,np);
        scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setClipChildren(false);scroll.setClipToPadding(false); content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        rows=Ui.column(this);rows.setClipChildren(false);Ui.pad(rows,this,24,12,24,24);scroll.addView(rows);
        for(String name:new String[]{"Home","Search","Movies","Series","Favorites","Settings"}) {
            TextView button=Ui.text(this,name,17);button.setGravity(Gravity.CENTER_VERTICAL);button.setTypeface(null,android.graphics.Typeface.BOLD);
            Ui.pad(button,this,19,0,0,0);button.setFocusable(true);
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,Ui.dp(this,52));bp.bottomMargin=Ui.dp(this,7);rail.addView(button,bp);
            button.setContentDescription(name);
            button.setBackground(Ui.rounded(name.equals(tab)?0xFF351B29:0xFF11131B,11,this));
            button.setOnFocusChangeListener((v,f)->{
                v.setBackground(Ui.rounded(f?Ui.RED:(name.equals(tab)?0xFF351B29:0xFF11131B),11,this));
                button.setTextColor(f?0xFFFFFFFF:0xFFE1E1E8);
            });
            button.setOnClickListener(v->{tab=name;for(int i=1;i<rail.getChildCount();i++){
                View entry=rail.getChildAt(i);if(entry!=v && !entry.hasFocus())entry.setBackground(Ui.rounded(0xFF11131B,11,this));
            }drawRows();});
        }
    }
    private void loadCache() {
        movies=Catalog.parse(Api.prefs(this).getString("movies","[]"),"movie");
        series=Catalog.parse(Api.prefs(this).getString("series","[]"),"series");drawRows();
    }
    private void refresh() {
        int token=++generation;
        Api.IO.execute(()->{
            try {
                org.json.JSONObject config=Api.get(BuildConfig.PANEL_URL+"config.php");
                Api.prefs(this).edit().putString("intro_url",config.optBoolean("intro_enabled")?config.optString("intro_url",""):"").apply();
                if(config.optBoolean("maintenance")){
                    runOnUiThread(()->{rows.removeAllViews();rail.setVisibility(View.GONE);hero.setText("Flix Town is temporarily unavailable");notice.setText(config.optString("announcement","Please try again later."));});
                    return;
                }
                runOnUiThread(()->rail.setVisibility(View.VISIBLE));
                org.json.JSONObject account=Api.get(Api.accountUrl(this));
                org.json.JSONObject user=account.optJSONObject("user_info");
                if(user!=null){
                    boolean expired=!"Active".equalsIgnoreCase(user.optString("status",""));
                    Api.prefs(this).edit().putBoolean("expired",expired).apply();
                    if(expired){runOnUiThread(()->{if(!isFinishing()){startActivity(new android.content.Intent(this,RenewalActivity.class));finish();}});return;}
                    String date=user.optString("exp_date","");
                    if(date.matches("[0-9]{9,12}")){
                        String expires=new java.text.SimpleDateFormat("MMM d, yyyy",java.util.Locale.US).format(new java.util.Date(Long.parseLong(date)*1000));
                        runOnUiThread(()->expiry.setText("Expires " + expires));
                    }
                }
                String movieJson=Api.request(Api.xtream(this,"get_vod_streams",""),null);
                String seriesJson=Api.request(Api.xtream(this,"get_series",""),null);
                List<Catalog.Item> m=Catalog.parse(movieJson,"movie"),s=Catalog.parse(seriesJson,"series");
                if(m.isEmpty() && s.isEmpty()) throw new IllegalStateException("No movies or series available");
                Api.cache(this,"movies",movieJson);Api.cache(this,"series",seriesJson);
                runOnUiThread(()->{if(token!=generation||isFinishing())return;movies=m;series=s;notice.setText(config.optString("announcement",""));drawRows();});
            } catch(Exception e) { runOnUiThread(()->{if(movies.isEmpty()&&series.isEmpty())notice.setText("Could not load the catalog. Check your connection.");}); }
        });
    }
    private void drawRows() {
        if(rows==null)return;
        android.view.View focused=getCurrentFocus();
        if(focused!=null && focused.getTag() instanceof String)lastFocused=(String)focused.getTag();
        int oldScroll=scroll.getScrollY();
        avoidFocusSteal=focused!=null && isInRail(focused);
        rows.removeAllViews();
        focusQueued=false;
        if(tab.equals("Settings")){settingButtons();return;}
        if(tab.equals("Search")){searchUi();return;}
        if(tab.equals("Favorites")){addRow("Favorites",favorites());return;}
        if(tab.equals("Movies")){sortButton();addRow("Movies",sorted(movies));}
        else if(tab.equals("Series")){sortButton();addRow("Series",sorted(series));}
        else {
            List<Catalog.Item> continued=Catalog.continueWatching(this,movies,series);
            if(!continued.isEmpty())addRow("Continue Watching",continued);
            addRow("Trending Movies",Catalog.topRated(movies,20));
            addRow("Latest Movies",Catalog.recent(movies,25));addRow("Latest Series",Catalog.recent(series,25));
        }
        scroll.post(()->scroll.scrollTo(0,oldScroll));
    }
    private boolean isInRail(View focused){for(View v=focused;v!=null && v.getParent() instanceof View;v=(View)v.getParent())if(v==rail)return true;return false;}
    private List<Catalog.Item> sorted(List<Catalog.Item> items){
        if(sortMode==1)return Catalog.alphabetical(items);
        if(sortMode==2)return Catalog.topRated(items,items.size());
        return Catalog.recent(items,items.size());
    }
    private void sortButton(){
        android.widget.Button sort=Ui.button(this,"Sort: "+new String[]{"Recently Added","Title A-Z","Rating"}[sortMode]);
        rows.addView(sort,new LinearLayout.LayoutParams(Ui.dp(this,220),Ui.dp(this,52)));
        sort.setOnClickListener(v->Ui.options(this,"Sort titles",new String[]{"Recently Added","Title A-Z","Rating"},which->{sortMode=which;drawRows();}));
    }
    private void addRow(String title,List<Catalog.Item> items) {
        if(items.isEmpty())return;
        TextView heading=Ui.heading(this,title,21);Ui.pad(heading,this,4,19,0,12);rows.addView(heading);
        HorizontalGridView grid=new HorizontalGridView(this);grid.setNumRows(1);grid.setClipChildren(false);grid.setClipToPadding(false);
        grid.setItemAnimator(null);grid.setHasFixedSize(true);
        grid.setAdapter(new PosterAdapter(this,items,item->{
            hero.setText(item.title);
            lastFocused=item.kind+":"+item.id;
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(()->Images.load(backdrop,item.backdrop,800),180);
        }));
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,Ui.dp(this,PosterAdapter.rowHeight(this)));
        gp.bottomMargin=Ui.dp(this,15);rows.addView(grid,gp);
        for(int i=0;i<items.size()&&!avoidFocusSteal;i++)if((items.get(i).kind+":"+items.get(i).id).equals(lastFocused)){
            final int position=i;focusQueued=true;grid.post(()->{grid.setSelectedPosition(position);grid.requestFocus();});break;
        }
        if(!focusQueued && !avoidFocusSteal && rows.getChildCount()==2 && (tab.equals("Home")||tab.equals("Movies")||tab.equals("Series")||tab.equals("Favorites"))){
            focusQueued=true;grid.post(()->{grid.setSelectedPosition(0);grid.requestFocus();});
        }
    }
    private void searchUi(){
        EditText query=new EditText(this);query.setSingleLine(true);query.setHint("Search movies and series");query.setTextColor(-1);
        rows.addView(query,new LinearLayout.LayoutParams(-1,Ui.dp(this,54)));
        LinearLayout matches=Ui.column(this);rows.addView(matches);query.requestFocus();
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                matches.removeAllViews();String needle=s.toString().trim().toLowerCase(java.util.Locale.ROOT);if(needle.length()<2)return;
                List<Catalog.Item> found=new ArrayList<>();
                for(Catalog.Item item:movies)if(item.title.toLowerCase(java.util.Locale.ROOT).contains(needle)&&found.size()<40)found.add(item);
                for(Catalog.Item item:series)if(item.title.toLowerCase(java.util.Locale.ROOT).contains(needle)&&found.size()<80)found.add(item);
                if(found.isEmpty())return;
                TextView heading=Ui.heading(HomeActivity.this,"Results",20);matches.addView(heading);
                HorizontalGridView grid=new HorizontalGridView(HomeActivity.this);grid.setNumRows(1);grid.setClipChildren(false);grid.setAdapter(new PosterAdapter(HomeActivity.this,found,item->hero.setText(item.title)));
                matches.addView(grid,new LinearLayout.LayoutParams(-1,Ui.dp(HomeActivity.this,PosterAdapter.rowHeight(HomeActivity.this))));
            }
            public void afterTextChanged(Editable s){}
        });
    }
    private List<Catalog.Item> favorites(){List<Catalog.Item> result=new ArrayList<>();String ids=Api.prefs(this).getString("favorites","");
        for(Catalog.Item item:movies)if(ids.contains("|movie:"+item.id+"|"))result.add(item);
        for(Catalog.Item item:series)if(ids.contains("|series:"+item.id+"|"))result.add(item);
        return result;}
    private void settingButtons() {
        android.widget.Button refresh=Ui.button(this,"Refresh content");rows.addView(refresh);refresh.setOnClickListener(v->refresh());
        android.widget.Button logout=Ui.button(this,"Sign out");rows.addView(logout);
        logout.setOnClickListener(v->{AccountStore.clear(this);startActivity(new android.content.Intent(this,LoginActivity.class));finish();});
    }
    @Override public void onBackPressed() { if(!tab.equals("Home")){tab="Home";drawRows();return;}Ui.options(this,"Exit Flix Town?",new String[]{"Stay","Exit"},which->{if(which==1)finish();}); }
    @Override protected void onDestroy(){generation++;handler.removeCallbacksAndMessages(null);super.onDestroy();}
}

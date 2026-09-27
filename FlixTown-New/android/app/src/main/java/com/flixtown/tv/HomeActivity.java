package com.flixtown.tv;

import android.app.Activity;
import android.app.AlertDialog;
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
import androidx.leanback.widget.HorizontalGridView;
import java.util.ArrayList;
import java.util.List;

public class HomeActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private List<Catalog.Item> movies=new ArrayList<>(),series=new ArrayList<>();
    private LinearLayout rows,rail; private ImageView backdrop; private TextView hero,notice,expiry;
    private String tab="Home",lastFocused=""; private int generation,sortMode; private boolean focusQueued;
    @Override public void onCreate(Bundle b) { super.onCreate(b);if(Api.prefs(this).getBoolean("expired",false)){startActivity(new android.content.Intent(this,RenewalActivity.class));finish();return;}renderShell(); loadCache(); }
    @Override protected void onResume() { super.onResume(); if(rows!=null)refresh(); }
    private void renderShell() {
        LinearLayout shell=Ui.row(this);shell.setBackgroundColor(Ui.BG);setContentView(shell);
        rail=Ui.column(this);rail.setBackgroundColor(0xFF11151D);Ui.pad(rail,this,12,25,12,0);
        shell.addView(rail,new LinearLayout.LayoutParams(Ui.dp(this,76),-1));
        LinearLayout content=Ui.column(this);shell.addView(content,new LinearLayout.LayoutParams(0,-1,1));
        FrameLayout top=new FrameLayout(this); content.addView(top,new LinearLayout.LayoutParams(-1,Ui.dp(this,265)));
        backdrop=new ImageView(this);backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);backdrop.setAlpha(.52f);top.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout info=Ui.column(this);Ui.pad(info,this,35,32,30,12);top.addView(info,new FrameLayout.LayoutParams(-1,-1));
        hero=Ui.heading(this,"Flix Town",27);info.addView(hero);
        expiry=Ui.text(this,"",15);info.addView(expiry);
        notice=Ui.text(this,"",16); LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=Ui.dp(this,12);info.addView(notice,np);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setClipChildren(false);scroll.setClipToPadding(false); content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        rows=Ui.column(this);rows.setClipChildren(false);Ui.pad(rows,this,24,0,18,20);scroll.addView(rows);
        for(String name:new String[]{"Home","Search","Movies","Series","Favorites","Settings"}) {
            TextView button=Ui.text(this,name.equals("Home")?"⌂":name.substring(0,1),21);button.setGravity(Gravity.CENTER);button.setFocusable(true);
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,Ui.dp(this,54));rail.addView(button,bp);
            button.setContentDescription(name);
            button.setOnFocusChangeListener((v,f)->v.setBackground(Ui.rounded(f?Ui.RED:0xFF11151D,7,this)));
            button.setOnClickListener(v->{tab=name;drawRows();});
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
    }
    private List<Catalog.Item> sorted(List<Catalog.Item> items){
        if(sortMode==1)return Catalog.alphabetical(items);
        if(sortMode==2)return Catalog.topRated(items,items.size());
        return Catalog.recent(items,items.size());
    }
    private void sortButton(){
        android.widget.Button sort=Ui.button(this,"Sort: "+new String[]{"Recently Added","Title A-Z","Rating"}[sortMode]);
        rows.addView(sort,new LinearLayout.LayoutParams(Ui.dp(this,220),Ui.dp(this,52)));
        sort.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Sort titles")
            .setItems(new String[]{"Recently Added","Title A-Z","Rating"},(dialog,which)->{sortMode=which;drawRows();}).show());
    }
    private void addRow(String title,List<Catalog.Item> items) {
        if(items.isEmpty())return;
        TextView heading=Ui.heading(this,title,20);Ui.pad(heading,this,4,12,0,0);rows.addView(heading);
        HorizontalGridView grid=new HorizontalGridView(this);grid.setNumRows(1);grid.setClipChildren(false);grid.setClipToPadding(false);
        grid.setItemAnimator(null);grid.setHasFixedSize(true);
        grid.setAdapter(new PosterAdapter(this,items,item->{
            hero.setText(item.title);
            lastFocused=item.kind+":"+item.id;
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(()->Images.load(backdrop,item.backdrop,800),180);
        }));
        rows.addView(grid,new LinearLayout.LayoutParams(-1,Ui.dp(this,260)));
        for(int i=0;i<items.size();i++)if((items.get(i).kind+":"+items.get(i).id).equals(lastFocused)){
            final int position=i;focusQueued=true;grid.post(()->{grid.setSelectedPosition(position);grid.requestFocus();});break;
        }
        if(!focusQueued && rows.getChildCount()==2 && (tab.equals("Home")||tab.equals("Movies")||tab.equals("Series")||tab.equals("Favorites"))){
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
                matches.addView(grid,new LinearLayout.LayoutParams(-1,Ui.dp(HomeActivity.this,260)));
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
    @Override public void onBackPressed() { if(!tab.equals("Home")){tab="Home";drawRows();return;}new AlertDialog.Builder(this).setMessage("Exit Flix Town?").setPositiveButton("Exit",(d,w)->finish()).setNegativeButton("Stay",null).show(); }
    @Override protected void onDestroy(){generation++;handler.removeCallbacksAndMessages(null);super.onDestroy();}
}

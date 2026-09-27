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
    private List<Category> movieCategories=new ArrayList<>(),seriesCategories=new ArrayList<>();
    private String movieCategory="",seriesCategory="";
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
        String[] names={"Home","Search","Movies","Series","Favorites","Settings"};
        int[] icons={android.R.drawable.ic_menu_view,android.R.drawable.ic_menu_search,
            android.R.drawable.ic_media_play,android.R.drawable.ic_menu_slideshow,
            android.R.drawable.btn_star_big_on,android.R.drawable.ic_menu_manage};
        for(int n=0;n<names.length;n++) {
            String name=names[n];
            LinearLayout button=Ui.row(this);button.setGravity(Gravity.CENTER_VERTICAL);button.setFocusable(true);
            Ui.pad(button,this,16,0,9,0);
            ImageView icon=new ImageView(this);icon.setImageResource(icons[n]);icon.setColorFilter(0xFFE6E6EC);
            button.addView(icon,new LinearLayout.LayoutParams(Ui.dp(this,23),Ui.dp(this,23)));
            TextView label=Ui.text(this,name,17);label.setTypeface(null,android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=Ui.dp(this,15);button.addView(label,lp);
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,Ui.dp(this,52));bp.bottomMargin=Ui.dp(this,7);rail.addView(button,bp);
            button.setContentDescription(name);
            button.setBackground(Ui.rounded(name.equals(tab)?0xFF351B29:0xFF11131B,11,this));
            button.setOnFocusChangeListener((v,f)->{
                v.setBackground(Ui.rounded(f?Ui.RED:(name.equals(tab)?0xFF351B29:0xFF11131B),11,this));
                icon.setColorFilter(0xFFFFFFFF);label.setTextColor(f?0xFFFFFFFF:0xFFE1E1E8);
            });
            button.setOnClickListener(v->{tab=name;for(int i=1;i<rail.getChildCount();i++){
                View entry=rail.getChildAt(i);if(entry!=v && !entry.hasFocus())entry.setBackground(Ui.rounded(0xFF11131B,11,this));
            }drawRows();});
        }
    }
    private void loadCache() {
        movies=Catalog.parse(Api.prefs(this).getString("movies","[]"),"movie");
        series=Catalog.parse(Api.prefs(this).getString("series","[]"),"series");drawRows();
        movieCategories=parseCategories(Api.prefs(this).getString("movie_categories","[]"));
        seriesCategories=parseCategories(Api.prefs(this).getString("series_categories","[]"));
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
                String movieCategoryJson="[]",seriesCategoryJson="[]";
                try{movieCategoryJson=Api.request(Api.xtream(this,"get_vod_categories",""),null);}catch(Exception ignored){}
                try{seriesCategoryJson=Api.request(Api.xtream(this,"get_series_categories",""),null);}catch(Exception ignored){}
                List<Catalog.Item> m=Catalog.parse(movieJson,"movie"),s=Catalog.parse(seriesJson,"series");
                if(m.isEmpty() && s.isEmpty()) throw new IllegalStateException("No movies or series available");
                Api.cache(this,"movies",movieJson);Api.cache(this,"series",seriesJson);
                List<Category> mc=parseCategories(movieCategoryJson),sc=parseCategories(seriesCategoryJson);
                Api.cache(this,"movie_categories",movieCategoryJson);Api.cache(this,"series_categories",seriesCategoryJson);
                runOnUiThread(()->{if(token!=generation||isFinishing())return;movies=m;series=s;movieCategories=mc;seriesCategories=sc;notice.setText(config.optString("announcement",""));drawRows();});
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
        if(tab.equals("Movies")){categoryButtons(movieCategories,true);sortButton();addRow("Movies",sorted(filter(movies,movieCategory)));}
        else if(tab.equals("Series")){categoryButtons(seriesCategories,false);sortButton();addRow("Series",sorted(filter(series,seriesCategory)));}
        else {
            List<Catalog.Item> continued=Catalog.continueWatching(this,movies,series);
            if(!continued.isEmpty())addRow("Continue Watching",continued);
            addRow("Trending Movies",Catalog.topRated(movies,20));
            addRow("Latest Movies",Catalog.recent(movies,25));addRow("Latest Series",Catalog.recent(series,25));
        }
        scroll.post(()->scroll.scrollTo(0,oldScroll));
    }
    private boolean isInRail(View focused){for(View v=focused;v!=null && v.getParent() instanceof View;v=(View)v.getParent())if(v==rail)return true;return false;}
    private static final class Category {final String id,name;Category(String id,String name){this.id=id;this.name=name;}}
    private static List<Category> parseCategories(String json){
        List<Category> out=new ArrayList<>();try{org.json.JSONArray data=new org.json.JSONArray(json);
            for(int i=0;i<data.length();i++){org.json.JSONObject c=data.optJSONObject(i);if(c!=null && !c.optString("category_id","").isEmpty())out.add(new Category(c.optString("category_id"),c.optString("category_name","Category")));}
        }catch(Exception ignored){}return out;
    }
    private List<Catalog.Item> filter(List<Catalog.Item> items,String category){if(category.isEmpty())return items;
        List<Catalog.Item> out=new ArrayList<>();for(Catalog.Item item:items)if(category.equals(item.categoryId))out.add(item);return out;}
    private void categoryButtons(List<Category> categories,boolean isMovie){
        TextView title=Ui.heading(this,isMovie?"Movie categories":"Series categories",20);Ui.pad(title,this,3,4,0,10);rows.addView(title);
        android.widget.HorizontalScrollView scroller=new android.widget.HorizontalScrollView(this);scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout chips=Ui.row(this);scroller.addView(chips);rows.addView(scroller);
        List<Category> options=new ArrayList<>();options.add(new Category("","All"));options.addAll(categories);
        String selected=isMovie?movieCategory:seriesCategory;
        for(Category category:options){TextView chip=Ui.text(this,category.name,15);chip.setGravity(Gravity.CENTER);chip.setFocusable(true);
            Ui.pad(chip,this,20,9,20,9);chip.setBackground(Ui.rounded(category.id.equals(selected)?Ui.RED:0xFF252832,10,this));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,Ui.dp(this,43));cp.rightMargin=Ui.dp(this,9);chips.addView(chip,cp);
            chip.setOnFocusChangeListener((v,f)->chip.setBackground(Ui.rounded(f?Ui.RED:(category.id.equals(isMovie?movieCategory:seriesCategory)?Ui.RED:0xFF252832),10,this)));
            chip.setOnClickListener(v->{if(isMovie)movieCategory=category.id;else seriesCategory=category.id;scroll.scrollTo(0,0);drawRows();});}
    }
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

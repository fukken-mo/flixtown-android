package com.flixtown.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.EditText;
import android.text.TextWatcher;
import android.text.Editable;
import android.graphics.drawable.GradientDrawable;
import android.animation.ValueAnimator;
import androidx.leanback.widget.HorizontalGridView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.GridLayoutManager;
import java.util.ArrayList;
import java.util.List;

public class HomeActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private AppUpdates appUpdates;
    private List<Catalog.Item> movies=new ArrayList<>(),series=new ArrayList<>();
    private List<Category> movieCategories=new ArrayList<>(),seriesCategories=new ArrayList<>();
    private String movieCategory="",seriesCategory="";
    private LinearLayout rows,rail,browse,browseHeader; private ScrollView scroll; private FrameLayout top; private ImageView backdrop,ambient; private TextView hero,heroMeta,notice,expiry; private RecyclerView firstGrid,browseGrid; private android.widget.Button browseCategoryButton,browseSortButton;
    private final List<TextView> railLabels=new ArrayList<>(); private ImageView railLogo; private boolean railExpanded=true; private ValueAnimator railAnimator;
    private final android.view.ViewTreeObserver.OnGlobalFocusChangeListener railFocus=(oldFocus,newFocus)->{
        if(newFocus!=null)setRailExpanded(isInRail(newFocus));
    };
    private String tab="Home",lastFocused=""; private int generation,sortMode; private boolean focusQueued,avoidFocusSteal;
    @Override public void onCreate(Bundle b) { super.onCreate(b);if(Api.prefs(this).getBoolean("expired",false)){startActivity(new android.content.Intent(this,RenewalActivity.class));finish();return;}appUpdates=new AppUpdates(this);renderShell(); loadCache();getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(railFocus);rail.post(()->{if(rail.getChildCount()>1)rail.getChildAt(1).requestFocus();}); }
    @Override protected void onResume() { super.onResume(); if(appUpdates!=null)appUpdates.installIfReady();if(rows!=null)refresh(); }
    private void renderShell() {
        FrameLayout scene=new FrameLayout(this);scene.setBackgroundColor(Ui.BG);
        ambient=new ImageView(this);ambient.setScaleType(ImageView.ScaleType.CENTER_CROP);ambient.setAlpha(.045f);
        scene.addView(ambient,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout shell=Ui.row(this);scene.addView(shell,new FrameLayout.LayoutParams(-1,-1));setContentView(scene);
        rail=Ui.column(this);rail.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xF51C1B27,0xF20D1018,0xFB090B10}));Ui.pad(rail,this,14,20,14,0);
        shell.addView(rail,new LinearLayout.LayoutParams(Ui.dp(this,220),-1));
        railLogo=new ImageView(this);
        railLogo.setImageResource(R.drawable.flix_logo);
        railLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoParams=new LinearLayout.LayoutParams(-1,Ui.dp(this,95));
        logoParams.bottomMargin=Ui.dp(this,20);rail.addView(railLogo,logoParams);
        LinearLayout content=Ui.column(this);shell.addView(content,new LinearLayout.LayoutParams(0,-1,1));
        int screenDp=Math.round(getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density);
        int heroHeight=Math.max(175,Math.min(220,Math.round(screenDp*.27f)));
        top=new FrameLayout(this); content.addView(top,new LinearLayout.LayoutParams(-1,Ui.dp(this,heroHeight)));
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
        heroMeta=Ui.text(this,"Movies & Series",15);heroMeta.setTextColor(0xFFD4D0D5);info.addView(heroMeta);
        expiry=Ui.text(this,"",14);info.addView(expiry);
        notice=Ui.text(this,"",15);notice.setMaxLines(2); LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=Ui.dp(this,7);info.addView(notice,np);
        FrameLayout body=new FrameLayout(this);content.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setClipChildren(false);scroll.setClipToPadding(false);body.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        rows=Ui.column(this);rows.setClipChildren(false);Ui.pad(rows,this,30,12,30,24);scroll.addView(rows);
        browse=Ui.column(this);browse.setVisibility(View.GONE);Ui.pad(browse,this,42,28,42,0);body.addView(browse,new FrameLayout.LayoutParams(-1,-1));
        browseHeader=Ui.column(this);browse.addView(browseHeader,new LinearLayout.LayoutParams(-1,-2));
        browseGrid=new RecyclerView(this);browseGrid.setLayoutManager(new GridLayoutManager(this,4));
        browseGrid.setItemAnimator(null);browseGrid.setClipToPadding(false);browseGrid.setClipChildren(false);
        browseGrid.setPadding(0,Ui.dp(this,15),0,Ui.dp(this,34));browseGrid.setHasFixedSize(true);
        browse.addView(browseGrid,new LinearLayout.LayoutParams(-1,0,1));
        String[] names={"Home","Search","Movies","Series","Favorites","Settings"};
        for(int n=0;n<names.length;n++) {
            String name=names[n];
            LinearLayout button=Ui.row(this);button.setGravity(Gravity.CENTER_VERTICAL);button.setFocusable(true);
            Ui.pad(button,this,13,0,8,0);
            NavIcon icon=new NavIcon(this,n);
            button.addView(icon,new LinearLayout.LayoutParams(Ui.dp(this,30),Ui.dp(this,30)));
            TextView label=Ui.text(this,name,17);label.setTypeface(null,android.graphics.Typeface.BOLD);
            railLabels.add(label);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=Ui.dp(this,16);button.addView(label,lp);
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,Ui.dp(this,56));bp.bottomMargin=Ui.dp(this,8);rail.addView(button,bp);
            button.setContentDescription(name);
            button.setBackground(Ui.navBackground(this,false,name.equals(tab)));
            button.setOnFocusChangeListener((v,f)->{
                if(f) setRailExpanded(true);
                v.setBackground(Ui.navBackground(this,f,name.equals(tab)));
                icon.setActive(f || name.equals(tab));label.setTextColor(f?0xFFFFFFFF:0xFFE1E1E8);
            });
            button.setOnClickListener(v->{tab=name;for(int i=1;i<rail.getChildCount();i++){
                View entry=rail.getChildAt(i);entry.setBackground(Ui.navBackground(this,entry==v,name.contentEquals(entry.getContentDescription())));
            }drawRows();});
        }
    }
    private void setRailExpanded(boolean expanded){
        if(railExpanded==expanded || rail==null)return;
        railExpanded=expanded;
        if(railAnimator!=null)railAnimator.cancel();
        int from=rail.getLayoutParams().width,to=Ui.dp(this,expanded?220:84);
        for(TextView label:railLabels)label.setVisibility(expanded?View.VISIBLE:View.GONE);
        railAnimator=ValueAnimator.ofInt(from,to);railAnimator.setDuration(190);
        railAnimator.addUpdateListener(a->{android.view.ViewGroup.LayoutParams lp=rail.getLayoutParams();lp.width=(int)a.getAnimatedValue();rail.setLayoutParams(lp);});
        railAnimator.start();
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
                runOnUiThread(()->{if(!isFinishing() && appUpdates!=null)appUpdates.check(config);});
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
        boolean catalog=tab.equals("Movies")||tab.equals("Series");
        top.setVisibility(tab.equals("Home")?View.VISIBLE:View.GONE);
        browse.setVisibility(catalog?View.VISIBLE:View.GONE);
        scroll.setVisibility(catalog?View.GONE:View.VISIBLE);
        android.view.View focused=getCurrentFocus();
        if(focused!=null && focused.getTag() instanceof String)lastFocused=(String)focused.getTag();
        int oldScroll=scroll.getScrollY();
        avoidFocusSteal=focused!=null && isInRail(focused);
        rows.removeAllViews();
        firstGrid=null;
        focusQueued=false;
        if(catalog){drawBrowse();return;}
        if(tab.equals("Settings")){settingButtons();return;}
        if(tab.equals("Search")){searchUi();return;}
        if(tab.equals("Favorites")){addRow("Favorites",favorites());return;}
        {
            List<Catalog.Item> continued=Catalog.continueWatching(this,movies,series);
            if(!continued.isEmpty())addRow("Continue Watching",continued);
            addRow("Top Rated Movies",Catalog.topRated(movies,20));
            addRow("Latest Movies",Catalog.recent(movies,25));addRow("Latest Series",Catalog.recent(series,25));
        }
        scroll.post(()->scroll.scrollTo(0,oldScroll));
    }
    private boolean isInRail(View focused){for(View v=focused;v!=null && v.getParent() instanceof View;v=(View)v.getParent())if(v==rail)return true;return false;}
    private void focusRail(){
        setRailExpanded(true);
        for(int i=1;i<rail.getChildCount();i++)if(tab.contentEquals(rail.getChildAt(i).getContentDescription())){
            rail.getChildAt(i).requestFocus();return;
        }
        if(rail.getChildCount()>1)rail.getChildAt(1).requestFocus();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            View focused=getCurrentFocus();
            if(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_LEFT && focused!=null && !isInRail(focused)){
                View card=focused;
                while(card.getParent() instanceof View && !(card.getParent() instanceof RecyclerView))card=(View)card.getParent();
                if(card.getParent() instanceof RecyclerView){RecyclerView list=(RecyclerView)card.getParent();int position=list.getChildAdapterPosition(card);
                    if(position==0 || (list.getLayoutManager() instanceof GridLayoutManager && position>=0 && position%4==0)){focusRail();return true;}}
                if(!(card.getParent() instanceof RecyclerView) && focused.focusSearch(View.FOCUS_LEFT)==null){focusRail();return true;}
            }
            if(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_RIGHT && focused!=null && isInRail(focused)){
                if(browse.getVisibility()==View.VISIBLE && browseCategoryButton!=null){browseCategoryButton.requestFocus();return true;}
                if(firstGrid!=null){if(firstGrid instanceof HorizontalGridView)((HorizontalGridView)firstGrid).setSelectedPosition(0);
                    firstGrid.requestFocus();return true;}
            }
        }
        return super.dispatchKeyEvent(event);
    }
    private static final class Category {final String id,name;Category(String id,String name){this.id=id;this.name=name;}}
    private static List<Category> parseCategories(String json){
        List<Category> out=new ArrayList<>();try{org.json.JSONArray data=new org.json.JSONArray(json);
            for(int i=0;i<data.length();i++){org.json.JSONObject c=data.optJSONObject(i);if(c!=null && !c.optString("category_id","").isEmpty())out.add(new Category(c.optString("category_id"),c.optString("category_name","Category")));}
        }catch(Exception ignored){}return out;
    }
    private List<Catalog.Item> filter(List<Catalog.Item> items,String category){if(category.isEmpty())return items;
        List<Catalog.Item> out=new ArrayList<>();for(Catalog.Item item:items)if(category.equals(item.categoryId))out.add(item);return out;}
    private void drawBrowse(){
        boolean isMovie=tab.equals("Movies");
        List<Category> categories=isMovie?movieCategories:seriesCategories;
        String selected=isMovie?movieCategory:seriesCategory;
        String categoryName="All categories";
        for(Category category:categories)if(category.id.equals(selected)){categoryName=category.name;break;}
        browseHeader.removeAllViews();
        TextView title=Ui.heading(this,isMovie?"Movies":"Series",31);
        browseHeader.addView(title);
        LinearLayout filters=Ui.row(this);filters.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams filterParams=new LinearLayout.LayoutParams(-1,Ui.dp(this,66));filterParams.topMargin=Ui.dp(this,15);
        filterParams.bottomMargin=Ui.dp(this,16);browseHeader.addView(filters,filterParams);
        android.widget.Button categoryButton=Ui.button(this,"Categories   ·   "+categoryName+"  ▾");browseCategoryButton=categoryButton;
        categoryButton.setSingleLine(true);categoryButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        filters.addView(categoryButton,new LinearLayout.LayoutParams(Ui.dp(this,305),Ui.dp(this,54)));
        categoryButton.setOnClickListener(v->{List<Category> options=new ArrayList<>();options.add(new Category("","All categories"));options.addAll(categories);
            String[] labels=new String[options.size()];int selectedIndex=0;
            for(int i=0;i<options.size();i++){labels[i]=options.get(i).name;if(options.get(i).id.equals(isMovie?movieCategory:seriesCategory))selectedIndex=i;}
            Ui.picker(this,isMovie?"Movie categories":"Series categories",labels,selectedIndex,index->{
                if(isMovie)movieCategory=options.get(index).id;else seriesCategory=options.get(index).id;
                drawRows();if(browseCategoryButton!=null)browseCategoryButton.requestFocus();
            });
        });
        android.widget.Button sort=Ui.button(this,"Sort by   ·   "+new String[]{"Recently added","Title A–Z","Rating"}[sortMode]+"  ▾");browseSortButton=sort;
        LinearLayout.LayoutParams sortParams=new LinearLayout.LayoutParams(Ui.dp(this,260),Ui.dp(this,54));sortParams.leftMargin=Ui.dp(this,16);
        filters.addView(sort,sortParams);
        sort.setOnClickListener(v->Ui.picker(this,"Sort titles",new String[]{"Recently added","Title A–Z","Rating"},sortMode,index->{sortMode=index;drawRows();if(browseSortButton!=null)browseSortButton.requestFocus();}));
        List<Catalog.Item> filtered=sorted(filter(isMovie?movies:series,selected));
        browseGrid.setAdapter(new PosterAdapter(this,filtered,item->{
            if(item.backdrop!=null && !item.backdrop.isEmpty())Images.load(ambient,item.backdrop,800);
        },true));
        firstGrid=browseGrid;
        browseGrid.scrollToPosition(0);
        if(filtered.isEmpty()){
            TextView empty=Ui.text(this,"No titles in this category yet",18);Ui.pad(empty,this,4,10,0,14);
            browseHeader.addView(empty);
        }
    }
    private List<Catalog.Item> sorted(List<Catalog.Item> items){
        if(sortMode==1)return Catalog.alphabetical(items);
        if(sortMode==2)return Catalog.topRated(items,items.size());
        return Catalog.recent(items,items.size());
    }
    private void addRow(String title,List<Catalog.Item> items) {
        if(items.isEmpty())return;
        TextView heading=Ui.heading(this,title,21);Ui.pad(heading,this,4,19,0,12);rows.addView(heading);
        HorizontalGridView grid=new HorizontalGridView(this);grid.setNumRows(1);grid.setClipChildren(false);grid.setClipToPadding(false);
        if(firstGrid==null)firstGrid=grid;
        grid.setItemAnimator(null);grid.setHasFixedSize(true);
        grid.setAdapter(new PosterAdapter(this,items,item->{
            hero.setText(item.title);
            heroMeta.setText((item.year>1900?item.year+"   ·   ":"")+(item.rating>0?String.format(java.util.Locale.US,"★ %.1f",item.rating):""));
            lastFocused=item.kind+":"+item.id;
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(()->{Images.load(backdrop,item.backdrop,800);Images.load(ambient,item.backdrop,800);},180);
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
    @Override protected void onDestroy(){generation++;handler.removeCallbacksAndMessages(null);if(railAnimator!=null)railAnimator.cancel();if(appUpdates!=null)appUpdates.destroy();getWindow().getDecorView().getViewTreeObserver().removeOnGlobalFocusChangeListener(railFocus);super.onDestroy();}
}

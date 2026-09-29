package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Native TV shell. Focus changes never recreate the catalog or remeasure the poster grid. */
public class HomeActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final RecyclerView.RecycledViewPool rowPool=new RecyclerView.RecycledViewPool();
    private final List<Catalog.Item> movies=new ArrayList<>(),series=new ArrayList<>();
    private final List<Category> movieCategories=new ArrayList<>(),seriesCategories=new ArrayList<>();
    private final List<Row> rows=new ArrayList<>();
    private RecyclerView homeRows,browseGrid; private RowsAdapter rowsAdapter;
    private ImageView backdrop;private TextView title,meta,overview;
    private LinearLayout rail,expandedMenu,filters,actions;private View dim,hero,lastContentFocus;
    private Button play,details;private Catalog.Item featured;
    private String tab="Home",category="";private int sortMode,generation;
    private boolean menuOpen,refreshStarted;
    private AppUpdates updates;
    private static final String[] TABS={"Search","Home","Movies","Series","Favorites","Settings"};
    private static final int[] ICONS={1,0,2,3,4,5};
    static final class Category {final String id,name;Category(String id,String name){this.id=id;this.name=name;}}
    static final class Row {final String name;final List<Catalog.Item> items;
        Row(String name,List<Catalog.Item> items){this.name=name;this.items=items;}}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        if(Api.prefs(this).getBoolean("expired",false)){startActivity(new Intent(this,RenewalActivity.class));finish();return;}
        setContentView(R.layout.activity_main);updates=new AppUpdates(this);
        backdrop=findViewById(R.id.backdrop);title=findViewById(R.id.hero_title);
        meta=findViewById(R.id.hero_meta);overview=findViewById(R.id.hero_overview);
        hero=findViewById(R.id.hero);actions=findViewById(R.id.hero_actions);filters=findViewById(R.id.filters);
        homeRows=findViewById(R.id.home_rows);browseGrid=findViewById(R.id.browse_grid);
        rail=findViewById(R.id.rail);expandedMenu=findViewById(R.id.expanded_menu);dim=findViewById(R.id.dim);
        homeRows.setLayoutManager(new LinearLayoutManager(this));homeRows.setItemAnimator(null);
        homeRows.setHasFixedSize(false);homeRows.setClipToPadding(false);
        browseGrid.setLayoutManager(new GridLayoutManager(this,6));browseGrid.setItemAnimator(null);
        browseGrid.setClipToPadding(false);browseGrid.setPadding(Ui.dp(this,2),Ui.dp(this,10),0,Ui.dp(this,30));
        browseGrid.setAdapter(new PosterAdapter(this,Collections.emptyList(),true,-1,(r,p,k)->false));
        rowsAdapter=new RowsAdapter();homeRows.setAdapter(rowsAdapter);
        buildRail();buildActions();loadCache();render();
        if(!refreshStarted){refreshStarted=true;refresh();}
        handler.post(()->{if(play!=null)play.requestFocus();});
    }
    @Override protected void onResume(){super.onResume();if(updates!=null)updates.installIfReady();}
    private TextView label(String value,int size,int color){TextView t=new TextView(this);t.setText(value);
        t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setSingleLine(true);return t;}
    private GradientDrawable chipBackground(boolean focused){GradientDrawable d=new GradientDrawable();
        d.setColor(focused?0xFFE7151E:0xF0192430);d.setCornerRadius(Ui.dp(this,4));return d;}
    private Button chip(String name,Runnable click){Button b=new Button(this);b.setText(name);b.setAllCaps(false);
        b.setTextSize(16);b.setTextColor(Color.WHITE);b.setFocusable(true);b.setFocusableInTouchMode(true);
        b.setBackground(chipBackground(false));b.setPadding(Ui.dp(this,18),0,Ui.dp(this,18),0);
        b.setOnFocusChangeListener((v,f)->{b.setBackground(chipBackground(f));b.animate().scaleX(f?1.04f:1f).scaleY(f?1.04f:1f).setDuration(110).start();if(f)lastContentFocus=v;});
        b.setOnClickListener(v->click.run());return b;}
    private void buildActions(){actions.removeAllViews();play=chip("▶  Play",()->{
        if(featured==null)return;if(!"movie".equals(featured.kind)){openDetails(featured);return;}
        Intent i=new Intent(this,PlayerActivity.class);i.putExtra("url",Api.stream(this,"movie",featured.id,featured.extension));
        i.putExtra("title",featured.title);i.putExtra("content_kind",featured.kind);i.putExtra("content_id",featured.id);startActivity(i);
    });details=chip("More Info  ›",()->{if(featured!=null)openDetails(featured);});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(Ui.dp(this,122),Ui.dp(this,42));actions.addView(play,p);
        LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(Ui.dp(this,145),Ui.dp(this,42));d.leftMargin=Ui.dp(this,10);actions.addView(details,d);
        play.setOnKeyListener((v,key,event)->event.getAction()==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_DOWN && focusRow(0,0));
        details.setOnKeyListener((v,key,event)->event.getAction()==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_DOWN && focusRow(0,0));
    }
    private void buildRail(){rail.removeAllViews();expandedMenu.removeAllViews();
        ImageView compactLogo=new ImageView(this);compactLogo.setImageResource(R.drawable.flix_logo);
        compactLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(Ui.dp(this,66),Ui.dp(this,62));cl.gravity=Gravity.CENTER_HORIZONTAL;cl.bottomMargin=Ui.dp(this,14);rail.addView(compactLogo,cl);
        ImageView fullLogo=new ImageView(this);fullLogo.setImageResource(R.drawable.flix_logo);
        fullLogo.setScaleType(ImageView.ScaleType.FIT_START);LinearLayout.LayoutParams fl=new LinearLayout.LayoutParams(Ui.dp(this,160),Ui.dp(this,62));fl.leftMargin=Ui.dp(this,20);fl.bottomMargin=Ui.dp(this,14);expandedMenu.addView(fullLogo,fl);
        for(int i=0;i<TABS.length;i++){final String target=TABS[i];final int icon=ICONS[i];
            FrameLayout slot=new FrameLayout(this);slot.setFocusable(true);slot.setFocusableInTouchMode(true);slot.setClickable(true);
            slot.setContentDescription(target);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,42));sp.gravity=Gravity.CENTER_HORIZONTAL;sp.bottomMargin=Ui.dp(this,7);rail.addView(slot,sp);
            NavIcon glyph=new NavIcon(this,icon);FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(Ui.dp(this,24),Ui.dp(this,24),Gravity.CENTER);slot.addView(glyph,gp);
            View marker=new View(this);marker.setBackgroundColor(0xFFE7151E);FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(Ui.dp(this,2),Ui.dp(this,20),Gravity.START|Gravity.CENTER_VERTICAL);slot.addView(marker,mp);
            marker.setVisibility(target.equals(tab)?View.VISIBLE:View.INVISIBLE);
            slot.setOnFocusChangeListener((v,f)->{marker.setVisibility(f||target.equals(tab)?View.VISIBLE:View.INVISIBLE);if(f)openMenu(target);});
            slot.setOnClickListener(v->openMenu(target));
            LinearLayout entry=new LinearLayout(this);entry.setGravity(Gravity.CENTER_VERTICAL);entry.setFocusable(true);entry.setFocusableInTouchMode(true);entry.setClickable(true);entry.setContentDescription(target);
            LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,Ui.dp(this,42));ep.bottomMargin=Ui.dp(this,7);expandedMenu.addView(entry,ep);
            View bar=new View(this);bar.setBackgroundColor(0xFFE7151E);entry.addView(bar,new LinearLayout.LayoutParams(Ui.dp(this,3),Ui.dp(this,22)));
            NavIcon fullIcon=new NavIcon(this,icon);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(Ui.dp(this,24),Ui.dp(this,24));ip.leftMargin=Ui.dp(this,28);entry.addView(fullIcon,ip);
            TextView name=label(target,16,Color.WHITE);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-1);np.leftMargin=Ui.dp(this,18);entry.addView(name,np);
            entry.setBackgroundResource(R.drawable.tv_rail_selector);
            entry.setOnFocusChangeListener((v,f)->bar.setVisibility(f||target.equals(tab)?View.VISIBLE:View.INVISIBLE));
            bar.setVisibility(target.equals(tab)?View.VISIBLE:View.INVISIBLE);
            entry.setOnClickListener(v->{tab=target;category="";sortMode=0;render();closeMenu();});
            entry.setOnKeyListener((v,key,event)->{
                if(event.getAction()==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_RIGHT){closeMenu();return true;}
                return false;
            });
        }
    }
    private void openMenu(String target){if(menuOpen)return;menuOpen=true;
        dim.setVisibility(View.VISIBLE);dim.setAlpha(0f);dim.animate().alpha(1f).setDuration(140).start();
        expandedMenu.setVisibility(View.VISIBLE);expandedMenu.setAlpha(0f);expandedMenu.animate().alpha(1f).setDuration(140).start();
        for(int i=1;i<expandedMenu.getChildCount();i++)if(target.equals(expandedMenu.getChildAt(i).getContentDescription())){
            expandedMenu.getChildAt(i).requestFocus();break;
        }
    }
    private void closeMenu(){if(!menuOpen)return;menuOpen=false;expandedMenu.setVisibility(View.GONE);dim.setVisibility(View.GONE);
        if(lastContentFocus!=null && lastContentFocus.isAttachedToWindow() && lastContentFocus.isShown())lastContentFocus.requestFocus();
        else if(browseGrid.getVisibility()==View.VISIBLE)focusGrid();else if(!focusRow(0,0))play.requestFocus();
    }
    private void loadCache(){movies.clear();movies.addAll(Catalog.parse(Api.prefs(this).getString("movies","[]"),"movie"));
        series.clear();series.addAll(Catalog.parse(Api.prefs(this).getString("series","[]"),"series"));
        movieCategories.clear();movieCategories.addAll(parseCategories(Api.prefs(this).getString("movie_categories","[]")));
        seriesCategories.clear();seriesCategories.addAll(parseCategories(Api.prefs(this).getString("series_categories","[]")));
    }
    private static List<Category> parseCategories(String json){ArrayList<Category> list=new ArrayList<>();try{
        JSONArray array=new JSONArray(json);for(int i=0;i<array.length();i++){
            JSONObject o=array.optJSONObject(i);if(o!=null)list.add(new Category(o.optString("category_id"),o.optString("category_name","Category")));
        }}catch(Exception ignored){}return list;}
    private void refresh(){final int token=++generation;Api.IO.execute(()->{
        try{
            JSONObject config=Api.get(BuildConfig.PANEL_URL+"config.php");runOnUiThread(()->{if(!isFinishing())updates.check(config);});
            Api.prefs(this).edit().putString("intro_url",config.optBoolean("intro_enabled")?config.optString("intro_url",""):"").apply();
            JSONObject account=Api.get(Api.accountUrl(this));JSONObject user=account.optJSONObject("user_info");
            if(user!=null && !"Active".equalsIgnoreCase(user.optString("status",""))){
                Api.prefs(this).edit().putBoolean("expired",true).apply();runOnUiThread(()->{startActivity(new Intent(this,RenewalActivity.class));finish();});return;
            }
            String m=Api.request(Api.xtream(this,"get_vod_streams",""),null);
            String s=Api.request(Api.xtream(this,"get_series",""),null);
            String mc=Api.request(Api.xtream(this,"get_vod_categories",""),null);
            String sc=Api.request(Api.xtream(this,"get_series_categories",""),null);
            if(Catalog.parse(m,"movie").isEmpty() && Catalog.parse(s,"series").isEmpty())return;
            Api.cache(this,"movies",m);Api.cache(this,"series",s);
            Api.cache(this,"movie_categories",mc);Api.cache(this,"series_categories",sc);
            runOnUiThread(()->{if(token!=generation || isFinishing())return;
                boolean hadContent=!movies.isEmpty()||!series.isEmpty();loadCache();
                if(!hadContent || (homeRows.getScrollState()==RecyclerView.SCROLL_STATE_IDLE && browseGrid.getScrollState()==RecyclerView.SCROLL_STATE_IDLE))render();
            });
        }catch(Exception e){android.util.Log.w("FlixTown","Catalog refresh failed; cached titles remain",e);}
    });}
    private void featured(){if(movies.isEmpty()){title.setText("Flix Town");meta.setText("");overview.setText("");return;}
        List<Catalog.Item> popular=Catalog.topRated(movies,Math.min(40,movies.size()));
        if(featured==null)featured=popular.get((int)(Math.random()*popular.size()));
        title.setText(featured.title);meta.setText((featured.year>1900?featured.year+"  •  ":"")+
            (featured.rating>0?String.format(Locale.US,"★ %.1f",featured.rating):"Movie"));
        overview.setText(featured.overview);Images.load(backdrop,featured.backdrop,960);
    }
    private void render(){boolean browse=tab.equals("Movies")||tab.equals("Series")||tab.equals("Favorites");
        boolean home=tab.equals("Home");hero.setVisibility(View.VISIBLE);
        homeRows.setVisibility(home?View.VISIBLE:View.GONE);browseGrid.setVisibility(browse?View.VISIBLE:View.GONE);
        TextView browseTitle=findViewById(R.id.browse_title);browseTitle.setVisibility(browse?View.VISIBLE:View.GONE);
        browseTitle.setText(tab.equals("Series")?"All TV Shows":tab.equals("Favorites")?"My List":"All Movies");
        actions.setVisibility(home?View.VISIBLE:View.GONE);filters.setVisibility(tab.equals("Movies")||tab.equals("Series")?View.VISIBLE:View.GONE);
        if(home){if(actions.getChildCount()!=2 || !(actions.getChildAt(0) instanceof Button))buildActions();featured();rows.clear();
            List<Catalog.Item> continued=Catalog.continueWatching(this,movies,series);if(!continued.isEmpty())rows.add(new Row("Continue Watching",continued));
            if(!movies.isEmpty())rows.add(new Row("Latest Movies",Catalog.recent(movies,30)));
            if(!series.isEmpty())rows.add(new Row("Latest TV Shows",Catalog.recent(series,30)));
            if(!movies.isEmpty())rows.add(new Row("Top Rated Movies",Catalog.topRated(movies,30)));
            rowsAdapter.notifyDataSetChanged();
        }else if(browse){title.setText(tab.equals("Favorites")?"My List":tab.equals("Series")?"TV Shows":"Movies");
            overview.setText("");meta.setText("");featured();
            if(!home){title.setText(tab.equals("Favorites")?"My List":tab.equals("Series")?"TV Shows":"Movies");meta.setText("");overview.setText("");}
            buildFilters();List<Catalog.Item> source=tab.equals("Series")?series:movies;
            if(tab.equals("Favorites")){source=new ArrayList<>();String saved=Api.prefs(this).getString("favorites","");
                for(Catalog.Item item:movies)if(saved.contains("|movie:"+item.id+"|"))source.add(item);
                for(Catalog.Item item:series)if(saved.contains("|series:"+item.id+"|"))source.add(item);
            }
            ArrayList<Catalog.Item> filtered=new ArrayList<>();for(Catalog.Item item:source)if(category.isEmpty()||category.equals(item.categoryId))filtered.add(item);
            if(sortMode==0)filtered=new ArrayList<>(Catalog.recent(filtered,filtered.size()));
            if(sortMode==1)filtered.sort((a,b)->a.title.compareToIgnoreCase(b.title));
            if(sortMode==2)filtered.sort((a,b)->Double.compare(b.rating,a.rating));
            browseGrid.setAdapter(new PosterAdapter(this,filtered,true,-1,(r,p,key)->false));
        }else{title.setText(tab);meta.setText("");overview.setText("");renderUtility();}
        buildRail();
    }
    private void buildFilters(){filters.removeAllViews();if(filters.getVisibility()!=View.VISIBLE)return;
        Button sort=chip("Sort By   ›",()->{
            String[] options={"Recently Added","Title A–Z","Top Rated"};
            Ui.picker(this,"Sort By",options,sortMode,which->{sortMode=which;render();restoreFilterFocus(0);});
        });LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(Ui.dp(this,116),Ui.dp(this,44));filters.addView(sort,a);
        Button cats=chip("Categories   ›",()->{
            List<Category> list=tab.equals("Series")?seriesCategories:movieCategories;
            String[] options=new String[list.size()+1];options[0]="All "+(tab.equals("Series")?"Shows":"Movies");
            for(int i=0;i<list.size();i++)options[i+1]=list.get(i).name;
            int selected=0;for(int i=0;i<list.size();i++)if(category.equals(list.get(i).id))selected=i+1;
            Ui.picker(this,"Categories",options,selected,which->{category=which==0?"":list.get(which-1).id;render();restoreFilterFocus(1);});
        });LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(Ui.dp(this,150),Ui.dp(this,44));b.leftMargin=Ui.dp(this,12);filters.addView(cats,b);
        View.OnKeyListener down=(v,key,event)->{
            if(event.getAction()==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_DOWN){focusGrid();return true;}return false;};
        sort.setOnKeyListener(down);cats.setOnKeyListener(down);
    }
    private void renderUtility(){homeRows.setVisibility(View.VISIBLE);rows.clear();rowsAdapter.notifyDataSetChanged();
        if(tab.equals("Search")){EditText query=new EditText(this);query.setHint("Search movies and TV shows");query.setTextColor(Color.WHITE);
            query.setHintTextColor(0xFF94A3B8);query.setSingleLine(true);query.setInputType(InputType.TYPE_CLASS_TEXT);
            actions.removeAllViews();actions.setVisibility(View.VISIBLE);actions.addView(query,new LinearLayout.LayoutParams(Ui.dp(this,340),Ui.dp(this,48)));
            query.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                public void onTextChanged(CharSequence s,int start,int before,int count){rows.clear();ArrayList<Catalog.Item> results=new ArrayList<>();
                    if(s.length()>1){for(Catalog.Item item:movies)if(item.title.toLowerCase(Locale.US).contains(s.toString().toLowerCase(Locale.US))&&results.size()<40)results.add(item);
                        for(Catalog.Item item:series)if(item.title.toLowerCase(Locale.US).contains(s.toString().toLowerCase(Locale.US))&&results.size()<60)results.add(item);}
                    if(!results.isEmpty())rows.add(new Row("Results",results));rowsAdapter.notifyDataSetChanged();}
                public void afterTextChanged(android.text.Editable e){} });
        }else if(tab.equals("Settings")){actions.removeAllViews();actions.setVisibility(View.VISIBLE);
            Button update=chip("Check for updates",()->refresh());actions.addView(update,new LinearLayout.LayoutParams(Ui.dp(this,200),Ui.dp(this,45)));
        }
    }
    private boolean focusGrid(){browseGrid.scrollToPosition(0);browseGrid.post(()->{
        RecyclerView.ViewHolder holder=browseGrid.findViewHolderForAdapterPosition(0);
        if(holder!=null){holder.itemView.requestFocus();lastContentFocus=holder.itemView;}
    });return true;}
    private void restoreFilterFocus(int index){filters.post(()->{
        if(filters.getVisibility()==View.VISIBLE && filters.getChildCount()>index)
            filters.getChildAt(index).requestFocus();
    });}
    private boolean focusRow(int index,int column){if(index<0||index>=rows.size()||rows.get(index).items.isEmpty())return false;
        homeRows.scrollToPosition(index);homeRows.post(()->{
            RecyclerView.ViewHolder outer=homeRows.findViewHolderForAdapterPosition(index);
            if(!(outer instanceof RowsAdapter.Holder))return;
            RecyclerView list=((RowsAdapter.Holder)outer).list;
            int target=Math.min(Math.max(0,column),Math.max(0,rows.get(index).items.size()-1));
            list.scrollToPosition(target);list.post(()->{RecyclerView.ViewHolder card=list.findViewHolderForAdapterPosition(target);
                if(card!=null){card.itemView.requestFocus();lastContentFocus=card.itemView;}});
        });return true;}
    private void openDetails(Catalog.Item item){Intent i=new Intent(this,DetailsActivity.class);
        i.putExtra("id",item.id);i.putExtra("kind",item.kind);i.putExtra("title",item.title);i.putExtra("poster",item.poster);
        i.putExtra("backdrop",item.backdrop);i.putExtra("year",item.year);i.putExtra("extension",item.extension);startActivity(i);}
    private final class RowsAdapter extends RecyclerView.Adapter<RowsAdapter.Holder>{
        RowsAdapter(){setHasStableIds(true);} @Override public long getItemId(int p){return rows.get(p).name.hashCode();}
        final class Holder extends RecyclerView.ViewHolder{final TextView heading;final RecyclerView list;
            Holder(View view){super(view);heading=view.findViewById(R.id.row_title);list=view.findViewById(R.id.row_list);
                LinearLayoutManager manager=new LinearLayoutManager(HomeActivity.this,RecyclerView.HORIZONTAL,false);
                manager.setInitialPrefetchItemCount(2);list.setLayoutManager(manager);list.setRecycledViewPool(rowPool);
                list.setItemAnimator(null);list.setClipToPadding(false);list.setHasFixedSize(true);}}
        @Override public Holder onCreateViewHolder(ViewGroup parent,int type){return new Holder(getLayoutInflater().inflate(R.layout.item_home_row,parent,false));}
        @Override public void onBindViewHolder(Holder h,int position){Row row=rows.get(position);h.heading.setText(row.name);
            h.list.setAdapter(new PosterAdapter(HomeActivity.this,row.items,false,position,(r,p,key)->{
                if(key==KeyEvent.KEYCODE_DPAD_DOWN)return focusRow(r+1,p);
                if(key==KeyEvent.KEYCODE_DPAD_UP){if(r==0){play.requestFocus();return true;}return focusRow(r-1,p);}
                return false;
            }));}
        @Override public int getItemCount(){return rows.size();}
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(event.getAction()==KeyEvent.ACTION_DOWN){
        if(event.getKeyCode()==KeyEvent.KEYCODE_BACK && menuOpen){closeMenu();return true;}
        if(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_LEFT && !menuOpen){View focus=getCurrentFocus();
            if(focus!=null){View left=focus.focusSearch(View.FOCUS_LEFT);
                if(left==null || left==focus || left==rail || left.getParent()==rail){lastContentFocus=focus;openMenu(tab);return true;}}
        }
    }return super.dispatchKeyEvent(event);}
    @Override protected void onDestroy(){generation++;handler.removeCallbacksAndMessages(null);super.onDestroy();}
}

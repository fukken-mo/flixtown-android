package com.flixtown.tv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Native TV shell.
 *
 * Home rows and the Movies/TV grids are Leanback grids: every D-pad press moves exactly one card,
 * the focused row is scrolled smoothly to a fixed keyline, and each row remembers its selected
 * card. The catalog is refreshed once per fresh launch (see {@link StartupRefresh}); switching tabs
 * or coming back from Details or the player never downloads it again or rebuilds the visible list.
 */
public class HomeActivity extends Activity implements StartupRefresh.Listener {
    static final String EXTRA_FRESH="fresh_launch";
    private static final int BROWSE_COLUMNS=6;
    private static final long CACHED_OVERLAY_MS=2500,SLOW_SERVER_MS=15000;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final RecyclerView.RecycledViewPool rowPool=new RecyclerView.RecycledViewPool();
    private final List<Catalog.Item> movies=new ArrayList<>(),series=new ArrayList<>();
    private final List<Catalog.Category> movieCategories=new ArrayList<>(),seriesCategories=new ArrayList<>();
    private final List<Row> rows=new ArrayList<>();
    private VerticalGridView homeRows,browseGrid; private RowsAdapter rowsAdapter;
    private ImageView backdrop;private TextView title,meta,overview,browseTitle,emptyMessage,startupStatus;
    private LinearLayout rail,expandedMenu,filters,actions,hero,browseHeader,startupActions;private View dim,backdropScrim,startupOverlay,startupSweep;
    private ViewGroup content;private View lastContentFocus;
    private Button play,details;private Catalog.Item featured;
    private String tab="Home",category="";private int sortMode;
    private boolean menuOpen,rendered,pendingRender,cacheLoaded,heroShown=true,overlayUp,destroyed;
    private int restoreGridPosition=-1;
    private int artWidth,artHeight;
    private long overlayShownAt;
    private ValueAnimator sweep;
    private Dialog exitDialog;
    private final List<View> railMarkers=new ArrayList<>(),menuBars=new ArrayList<>();
    private AppUpdates updates;
    private static final String[] TABS={"Search","Home","Movies","Series","Favorites","Settings"};
    private static final int[] ICONS={1,0,2,3,4,5};
    private static final String[] SORTS={"Recently Added","Title A–Z","Top Rated","Release Year"};
    static final class Row {final String name;final List<Catalog.Item> items;int selected;PosterAdapter adapter;
        Row(String name,List<Catalog.Item> items){this.name=name;this.items=new ArrayList<>(items);}}

    @Override public void onCreate(Bundle state){super.onCreate(state);
        if(Api.prefs(this).getBoolean("expired",false)){startActivity(new Intent(this,RenewalActivity.class));finish();return;}
        setContentView(R.layout.activity_main);updates=new AppUpdates(this);Images.init(this);
        backdrop=findViewById(R.id.backdrop);backdropScrim=findViewById(R.id.backdrop_scrim);
        title=findViewById(R.id.hero_title);meta=findViewById(R.id.hero_meta);overview=findViewById(R.id.hero_overview);
        hero=findViewById(R.id.hero);actions=findViewById(R.id.hero_actions);filters=findViewById(R.id.filters);
        browseHeader=findViewById(R.id.browse_header);browseTitle=findViewById(R.id.browse_title);emptyMessage=findViewById(R.id.empty_message);
        homeRows=findViewById(R.id.home_rows);browseGrid=findViewById(R.id.browse_grid);content=findViewById(R.id.content);
        rail=findViewById(R.id.rail);expandedMenu=findViewById(R.id.expanded_menu);dim=findViewById(R.id.dim);
        startupOverlay=findViewById(R.id.startup_overlay);startupStatus=findViewById(R.id.startup_status);
        startupActions=findViewById(R.id.startup_actions);startupSweep=findViewById(R.id.startup_sweep);
        if(state!=null){
            tab=state.getString("tab","Home");category=state.getString("category","");
            sortMode=state.getInt("sort",0);restoreGridPosition=state.getInt("grid_position",-1);
        }
        layoutForScreen();
        homeRows.setItemAnimator(null);rowsAdapter=new RowsAdapter();homeRows.setAdapter(rowsAdapter);
        homeRows.setOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener(){
            @Override public void onChildViewHolderSelected(RecyclerView parent,RecyclerView.ViewHolder child,int position,int sub){updateHero();}
        });
        browseGrid.setItemAnimator(null);
        buildRail();buildActions();render();
        if(tab.equals("Home"))play.requestFocus();
        boolean fresh=state==null && getIntent().getBooleanExtra(EXTRA_FRESH,false);
        if(fresh)showOverlay();
        loadCache();
        // A fresh launch already started the refresh (before the intro); a restored process starts one here.
        if(!StartupRefresh.startedThisProcess())StartupRefresh.start(this,false);
        StartupRefresh.listen(this);
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);
        out.putString("tab",tab);out.putString("category",category);out.putInt("sort",sortMode);
        if(isBrowse())out.putInt("grid_position",browseGrid.getSelectedPosition());
    }

    /** Sizes the hero, keyline and grid from the real screen so nothing lands in the overscan area. */
    private void layoutForScreen(){
        DisplayMetrics m=getResources().getDisplayMetrics();
        int safeY=Ui.safeY(this),rowHeight=Ui.dp(this,36)+PosterAdapter.rowListHeight(this);
        int keyline=m.heightPixels-safeY-rowHeight;
        FrameLayout.LayoutParams hp=(FrameLayout.LayoutParams)hero.getLayoutParams();
        hp.topMargin=safeY;hp.height=Math.max(Ui.dp(this,160),keyline-safeY);hero.setLayoutParams(hp);
        homeRows.setPadding(0,keyline,0,safeY);
        homeRows.setWindowAlignment(BaseGridView.WINDOW_ALIGN_NO_EDGE);
        homeRows.setWindowAlignmentOffsetPercent(BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);
        homeRows.setWindowAlignmentOffset(keyline);
        homeRows.setItemAlignmentOffsetPercent(0f);homeRows.setItemAlignmentOffset(0);

        FrameLayout.LayoutParams bh=(FrameLayout.LayoutParams)browseHeader.getLayoutParams();bh.topMargin=safeY;browseHeader.setLayoutParams(bh);
        FrameLayout.LayoutParams gp=(FrameLayout.LayoutParams)browseGrid.getLayoutParams();gp.topMargin=safeY+bh.height;browseGrid.setLayoutParams(gp);
        // Cards include an 8dp glow margin on each side, so cards sit edge to edge and artwork is 16dp apart.
        int cardSpace=m.widthPixels-Ui.dp(this,96)-browseGrid.getPaddingLeft()-browseGrid.getPaddingRight();
        int cardWidth=cardSpace/BROWSE_COLUMNS,glow=Ui.dp(this,PosterAdapter.GLOW_DP);
        artWidth=cardWidth-2*glow;artHeight=artWidth*3/2;
        browseGrid.setNumColumns(BROWSE_COLUMNS);browseGrid.setHorizontalSpacing(0);browseGrid.setVerticalSpacing(Ui.dp(this,4));
        browseGrid.setPadding(browseGrid.getPaddingLeft(),Ui.dp(this,4),browseGrid.getPaddingRight(),safeY);
    }

    @Override protected void onResume(){super.onResume();if(updates!=null)updates.installIfReady();}
    @Override protected void onRestart(){super.onRestart();updateContinueRow();}

    private TextView label(String value,int size,int color){TextView t=new TextView(this);t.setText(value);
        t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setSingleLine(true);return t;}
    private GradientDrawable chipBackground(boolean focused){return Ui.pill(this,focused,false,22);}
    private Button chip(String name,Runnable click){Button b=new Button(this);b.setText(name);b.setAllCaps(false);
        b.setTextSize(16);b.setTextColor(Ui.TEXT);b.setFocusable(true);b.setFocusableInTouchMode(true);
        b.setSingleLine(true);b.setEllipsize(TextUtils.TruncateAt.END);b.setMinWidth(0);b.setMinimumWidth(0);
        b.setMinHeight(0);b.setMinimumHeight(0);b.setStateListAnimator(null);
        GradientDrawable normal=chipBackground(false),focused=chipBackground(true);
        b.setBackground(normal);b.setPadding(Ui.dp(this,20),0,Ui.dp(this,20),0);
        b.setOnFocusChangeListener((v,f)->{b.setBackground(f?focused:normal);b.animate().scaleX(f?1.04f:1f).scaleY(f?1.04f:1f).setDuration(110).start();
            if(f){lastContentFocus=v;updateHero();}});
        b.setOnClickListener(v->click.run());return b;}
    private void buildActions(){actions.removeAllViews();play=chip("▶  Play",()->{
        if(featured==null)return;if(!"movie".equals(featured.kind)){openDetails(featured);return;}
        Intent i=new Intent(this,PlayerActivity.class);i.putExtra("url",Api.stream(this,"movie",featured.id,featured.extension));
        i.putExtra("title",featured.title);i.putExtra("content_kind",featured.kind);i.putExtra("content_id",featured.id);startActivity(i);
    });details=chip("More Info",()->{if(featured!=null)openDetails(featured);});
        actions.addView(play,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,44)));
        LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,44));d.leftMargin=Ui.dp(this,12);actions.addView(details,d);
    }

    /* ---------------- Startup loading screen ---------------- */

    private void showOverlay(){overlayUp=true;overlayShownAt=SystemClock.uptimeMillis();
        startupOverlay.setVisibility(View.VISIBLE);startupOverlay.setAlpha(1f);
        startupStatus.setText("Checking for new movies and shows…");startupActions.setVisibility(View.GONE);
        startSweep();
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        handler.postDelayed(slowServer,SLOW_SERVER_MS);
    }
    private void startSweep(){
        startupSweep.setVisibility(View.VISIBLE);
        if(sweep!=null)sweep.cancel();
        int track=Ui.dp(this,220),bar=Ui.dp(this,72);
        sweep=ObjectAnimator.ofFloat(startupSweep,View.TRANSLATION_X,-bar,track);
        sweep.setDuration(1100);sweep.setRepeatCount(ValueAnimator.INFINITE);sweep.setInterpolator(new AccelerateDecelerateInterpolator());
        sweep.start();
    }
    /** Hides the loading screen; with cached titles it never stays longer than {@link #CACHED_OVERLAY_MS}. */
    private void hideOverlay(boolean immediately){
        if(!overlayUp)return;
        long elapsed=SystemClock.uptimeMillis()-overlayShownAt;
        if(!immediately && elapsed<600){handler.postDelayed(()->hideOverlay(true),600-elapsed);return;}
        overlayUp=false;handler.removeCallbacks(slowServer);handler.removeCallbacks(cachedTimeout);
        if(sweep!=null)sweep.cancel();
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        startupOverlay.animate().alpha(0f).setDuration(220).withEndAction(()->startupOverlay.setVisibility(View.GONE)).start();
        View focus=getCurrentFocus();
        if(focus==null || !isDescendant(content,focus))focusDefault();
    }
    private final Runnable cachedTimeout=()->{if(overlayUp && (!movies.isEmpty()||!series.isEmpty()))hideOverlay(true);};
    private final Runnable slowServer=new Runnable(){@Override public void run(){
        if(!overlayUp)return;
        if(!movies.isEmpty()||!series.isEmpty()){hideOverlay(true);return;}
        Runnable again=this;
        overlayMessage("The server is taking longer than usual. Keep waiting, or continue and titles will appear when they arrive.",
            new String[]{"Keep waiting","Continue"},i->{
                if(i==0){startupActions.setVisibility(View.GONE);startupStatus.setText("Still checking…");startSweep();handler.postDelayed(again,SLOW_SERVER_MS);}
                else hideOverlay(true);});
    }};
    private void overlayMessage(String text,String[] labels,Ui.Choice choice){
        if(sweep!=null)sweep.cancel();startupSweep.setVisibility(View.INVISIBLE);
        startupStatus.setText(text);startupActions.removeAllViews();startupActions.setVisibility(View.VISIBLE);
        for(int i=0;i<labels.length;i++){final int index=i;
            Button b=Ui.button(this,labels[i]);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,48));
            if(i>0)p.leftMargin=Ui.dp(this,14);b.setMinWidth(Ui.dp(this,150));startupActions.addView(b,p);
            b.setOnClickListener(v->choice.select(index));
        }
        startupActions.getChildAt(0).requestFocus();
    }

    /* ---------------- Startup refresh ---------------- */

    @Override public void onConfig(JSONObject config){
        if(!isFinishing() && !BuildConfig.PREVIEW)updates.check(config);
    }
    @Override public void onResult(StartupRefresh.Result r){
        if(isFinishing()||destroyed)return;
        if(r.state==StartupRefresh.EXPIRED){startActivity(new Intent(this,RenewalActivity.class));finish();return;}
        if(r.state==StartupRefresh.DONE){
            applyCatalog(r.movies,r.series,r.movieCategories,r.seriesCategories);
            if(r.manual)toast("Your catalog is up to date");
            hideOverlay(false);return;
        }
        if(r.manual){toast("Couldn't reach the server. Your saved titles are still here.");return;}
        boolean haveTitles=!movies.isEmpty()||!series.isEmpty();
        if(overlayUp && !haveTitles){
            if(!cacheLoaded)return; // loadCache() re-checks once the saved catalog has been read
            overlayMessage("We couldn't reach Flix Town. Check the TV's internet connection, then try again.",
                new String[]{"Try again","Continue"},i->{
                    if(i==0){startupActions.setVisibility(View.GONE);startupStatus.setText("Checking for new movies and shows…");startSweep();StartupRefresh.start(this,false);}
                    else hideOverlay(true);});
        }else if(overlayUp){hideOverlay(false);toast("Showing saved titles. New titles will appear next time the server is reachable.");}
    }
    private void loadCache(){Api.IO.execute(()->{
        List<Catalog.Item> m=Catalog.parse(Api.cached(this,"movies"),"movie"),s=Catalog.parse(Api.cached(this,"series"),"series");
        List<Catalog.Category> mc=Catalog.parseCategories(Api.cached(this,"movie_categories")),sc=Catalog.parseCategories(Api.cached(this,"series_categories"));
        runOnUiThread(()->{
            if(isFinishing() || destroyed)return;
            cacheLoaded=true;
            // A network result that already arrived is newer than the file cache.
            if(movies.isEmpty() && series.isEmpty())applyCatalog(m,s,mc,sc);
            boolean haveTitles=!movies.isEmpty()||!series.isEmpty();
            if(overlayUp && haveTitles){
                long wait=CACHED_OVERLAY_MS-(SystemClock.uptimeMillis()-overlayShownAt);
                handler.postDelayed(cachedTimeout,Math.max(0,wait));
            }else if(overlayUp && !StartupRefresh.running()){
                // The refresh already failed before the cache was read: show the offline choice now.
                onResult(new StartupRefresh.Result(StartupRefresh.FAILED,false,null,null,null,null,null));
            }
        });
    });}
    private void toast(String text){if(!isFinishing())Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private static long signature(List<Catalog.Item> items){long h=items.size();
        for(Catalog.Item item:items)h=h*31+item.id.hashCode()*17L+item.added;return h;}
    private void applyCatalog(List<Catalog.Item> m,List<Catalog.Item> s,List<Catalog.Category> mc,List<Catalog.Category> sc){
        long before=signature(movies)*7+signature(series);
        movies.clear();movies.addAll(m);series.clear();series.addAll(s);
        movieCategories.clear();movieCategories.addAll(mc);seriesCategories.clear();seriesCategories.addAll(sc);
        Catalog.Store.set(movies,series);
        boolean changed=before!=signature(movies)*7+signature(series);
        if(!changed && rendered && !pendingRender)return;
        if(canRenderNow())render();else pendingRender=true;
    }
    /** Re-render only when it cannot move focus or scroll position out from under the viewer. */
    private boolean canRenderNow(){
        if(!rendered || overlayUp)return true;
        if(menuOpen)return false;
        View focus=getCurrentFocus();
        if(focus==null)return true;
        if(isBrowse())return restoreGridPosition>=0;
        return tab.equals("Home") && actions.hasFocus();
    }
    private void featured(){if(movies.isEmpty()){featured=null;title.setText("Flix Town");meta.setText(cacheLoaded?"Your titles will appear here once they load":"");overview.setText("");return;}
        if(featured==null){List<Catalog.Item> popular=Catalog.topRated(movies,Math.min(40,movies.size()));
            featured=popular.get((int)(Math.random()*popular.size()));}
        title.setText(featured.title);meta.setText((featured.year>1900?featured.year+"   ":"")+
            (featured.rating>0?String.format(Locale.US,"★ %.1f",featured.rating):"Movie"));
        overview.setText(featured.overview);Images.load(backdrop,featured.backdrop,960);
    }

    /* ---------------- Rail and overlay menu ---------------- */

    private void buildRail(){rail.removeAllViews();expandedMenu.removeAllViews();railMarkers.clear();menuBars.clear();
        ImageView compactLogo=new ImageView(this);compactLogo.setImageResource(R.drawable.flix_logo);
        compactLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,50));cl.gravity=Gravity.CENTER_HORIZONTAL;cl.bottomMargin=Ui.dp(this,22);rail.addView(compactLogo,cl);
        ImageView fullLogo=new ImageView(this);fullLogo.setImageResource(R.drawable.flix_logo);
        fullLogo.setScaleType(ImageView.ScaleType.FIT_START);LinearLayout.LayoutParams fl=new LinearLayout.LayoutParams(Ui.dp(this,130),Ui.dp(this,50));fl.leftMargin=Ui.dp(this,24);fl.bottomMargin=Ui.dp(this,22);expandedMenu.addView(fullLogo,fl);
        for(int i=0;i<TABS.length;i++){final String target=TABS[i];final int icon=ICONS[i];final int index=i;
            // Compact rail icons are indicators only. They never take focus, so the D-pad cannot
            // wander into the rail; Left from the content edge opens the overlay menu instead.
            FrameLayout slot=new FrameLayout(this);slot.setFocusable(false);slot.setClickable(true);
            slot.setContentDescription(target);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,42));sp.gravity=Gravity.CENTER_HORIZONTAL;sp.bottomMargin=Ui.dp(this,7);rail.addView(slot,sp);
            NavIcon glyph=new NavIcon(this,icon);FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(Ui.dp(this,24),Ui.dp(this,24),Gravity.CENTER);slot.addView(glyph,gp);
            View marker=new View(this);marker.setBackground(Ui.rounded(Ui.ACCENT,1,this));FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(Ui.dp(this,3),Ui.dp(this,20),Gravity.START|Gravity.CENTER_VERTICAL);slot.addView(marker,mp);
            railMarkers.add(marker);
            slot.setOnClickListener(v->{lastContentFocus=getCurrentFocus();openMenu(target);});
            LinearLayout entry=new LinearLayout(this);entry.setGravity(Gravity.CENTER_VERTICAL);entry.setFocusable(true);entry.setFocusableInTouchMode(true);entry.setClickable(true);entry.setContentDescription(target);
            LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,Ui.dp(this,42));ep.bottomMargin=Ui.dp(this,7);ep.leftMargin=Ui.dp(this,10);ep.rightMargin=Ui.dp(this,12);expandedMenu.addView(entry,ep);
            View bar=new View(this);bar.setBackground(Ui.rounded(Ui.ACCENT,1,this));entry.addView(bar,new LinearLayout.LayoutParams(Ui.dp(this,3),Ui.dp(this,20)));
            menuBars.add(bar);
            NavIcon fullIcon=new NavIcon(this,icon);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(Ui.dp(this,24),Ui.dp(this,24));ip.leftMargin=Ui.dp(this,22);entry.addView(fullIcon,ip);
            TextView name=label(target.equals("Series")?"TV Shows":target.equals("Favorites")?"My List":target,17,Ui.TEXT);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-1);np.leftMargin=Ui.dp(this,16);entry.addView(name,np);
            entry.setBackgroundResource(R.drawable.tv_rail_selector);
            entry.setOnFocusChangeListener((v,f)->bar.setVisibility(f||target.equals(tab)?View.VISIBLE:View.INVISIBLE));
            entry.setOnClickListener(v->selectTab(target));
            entry.setOnKeyListener((v,key,event)->{
                if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
                if(key==KeyEvent.KEYCODE_DPAD_RIGHT){closeMenu();return true;}
                // Keep focus inside the menu at both ends instead of letting it fall behind the dim layer.
                if(key==KeyEvent.KEYCODE_DPAD_UP && index==0)return true;
                if(key==KeyEvent.KEYCODE_DPAD_DOWN && index==TABS.length-1)return true;
                return false;
            });
        }
        updateRail();
    }
    private void updateRail(){for(int i=0;i<TABS.length;i++){boolean selected=TABS[i].equals(tab);
        railMarkers.get(i).setVisibility(selected?View.VISIBLE:View.INVISIBLE);
        View entry=expandedMenu.getChildAt(i+1);
        menuBars.get(i).setVisibility(selected||(entry!=null&&entry.isFocused())?View.VISIBLE:View.INVISIBLE);}}
    private void openMenu(String target){if(menuOpen||overlayUp)return;menuOpen=true;
        dim.setVisibility(View.VISIBLE);dim.setAlpha(0f);dim.animate().alpha(1f).setDuration(140).start();
        expandedMenu.setVisibility(View.VISIBLE);expandedMenu.setAlpha(0f);expandedMenu.animate().alpha(1f).setDuration(140).start();
        for(int i=1;i<expandedMenu.getChildCount();i++)if(target.equals(expandedMenu.getChildAt(i).getContentDescription())){
            expandedMenu.getChildAt(i).requestFocus();break;
        }
        // The page stays exactly where it is under the dim layer, but cannot take focus while the menu is open.
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }
    private void closeMenu(){if(!menuOpen)return;menuOpen=false;
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        expandedMenu.animate().cancel();dim.animate().cancel();
        expandedMenu.setVisibility(View.GONE);dim.setVisibility(View.GONE);
        if(lastContentFocus!=null && lastContentFocus.isAttachedToWindow() && lastContentFocus.isShown() && lastContentFocus.requestFocus())return;
        focusDefault();
    }
    private void selectTab(String target){
        if(target.equals(tab)){closeMenu();return;}
        tab=target;category="";sortMode=0;lastContentFocus=null;restoreGridPosition=-1;render();
        // An empty page (for example My List with no favorites) keeps the menu open so focus is never lost.
        if(hasFocusableContent())closeMenu();else updateRail();
    }
    private boolean isBrowse(){return tab.equals("Movies")||tab.equals("Series")||tab.equals("Favorites");}
    private boolean hasFocusableContent(){
        if(isBrowse())return (browseGrid.getAdapter()!=null && browseGrid.getAdapter().getItemCount()>0)||filters.getChildCount()>0;
        return actions.getChildCount()>0;
    }
    private void focusDefault(){
        if(overlayUp)return;
        if(isBrowse()){focusGrid();return;}
        if(actions.getChildCount()>0)actions.getChildAt(0).requestFocus();
    }
    private void focusGrid(){
        if(browseGrid.getAdapter()==null || browseGrid.getAdapter().getItemCount()==0){
            if(filters.getChildCount()>0)filters.getChildAt(0).requestFocus();return;}
        if(browseGrid.getChildCount()>0 && !browseGrid.isLayoutRequested())browseGrid.requestFocus();
        else Ui.afterLayout(browseGrid,()->{if(!menuOpen && !overlayUp)browseGrid.requestFocus();});
    }

    /* ---------------- Rendering ---------------- */

    private void render(){boolean browse=isBrowse(),home=tab.equals("Home");
        rendered=true;pendingRender=false;
        hero.setVisibility(browse?View.GONE:View.VISIBLE);homeRows.setVisibility(browse?View.GONE:View.VISIBLE);
        backdrop.setVisibility(browse?View.GONE:View.VISIBLE);backdropScrim.setVisibility(browse?View.GONE:View.VISIBLE);
        browseHeader.setVisibility(browse?View.VISIBLE:View.GONE);browseGrid.setVisibility(browse?View.VISIBLE:View.GONE);
        emptyMessage.setVisibility(View.GONE);
        if(home){if(actions.getChildCount()!=2 || actions.getChildAt(0)!=play)buildActions();
            featured();rows.clear();
            List<Catalog.Item> continued=Catalog.continueWatching(this,movies,series);if(!continued.isEmpty())rows.add(new Row("Continue Watching",continued));
            if(!movies.isEmpty())rows.add(new Row("Latest Movies",Catalog.recent(movies,30)));
            if(!series.isEmpty())rows.add(new Row("Latest TV Shows",Catalog.recent(series,30)));
            if(!movies.isEmpty())rows.add(new Row("Top Rated Movies",Catalog.topRated(movies,30)));
            rowsAdapter.notifyDataSetChanged();homeRows.setSelectedPosition(0);
        }else if(browse){
            browseTitle.setText(tab.equals("Favorites")?"My List":tab.equals("Series")?"All TV Shows":"All Movies");
            buildFilters();List<Catalog.Item> source=tab.equals("Series")?series:movies;
            if(tab.equals("Favorites")){source=new ArrayList<>();String saved=Api.prefs(this).getString("favorites","");
                for(Catalog.Item item:movies)if(saved.contains("|movie:"+item.id+"|"))source.add(item);
                for(Catalog.Item item:series)if(saved.contains("|series:"+item.id+"|"))source.add(item);
            }
            ArrayList<Catalog.Item> filtered=new ArrayList<>();for(Catalog.Item item:source)if(category.isEmpty()||category.equals(item.categoryId))filtered.add(item);
            if(sortMode==0)filtered=new ArrayList<>(Catalog.recent(filtered,filtered.size()));
            if(sortMode==1)filtered.sort((a,b)->a.title.compareToIgnoreCase(b.title));
            if(sortMode==2)filtered.sort((a,b)->Double.compare(b.rating,a.rating));
            if(sortMode==3)filtered.sort((a,b)->Integer.compare(b.year,a.year));
            browseGrid.setAdapter(new PosterAdapter(this,filtered,artWidth,artHeight,true,null));
            int start=0;
            // After the activity was recreated, go back to the poster the viewer had selected.
            if(restoreGridPosition>=0 && !filtered.isEmpty()){start=Math.min(restoreGridPosition,filtered.size()-1);restoreGridPosition=-1;
                if(!overlayUp)focusGrid();}
            browseGrid.setSelectedPosition(start);
            if(filtered.isEmpty()){emptyMessage.setVisibility(View.VISIBLE);
                emptyMessage.setText(tab.equals("Favorites")?"Titles you add to My List appear here":movies.isEmpty()&&series.isEmpty()?"Your titles will appear here once they load":"No titles in this category");}
        }else{title.setText(tab);meta.setText("");overview.setText("");renderUtility();}
        applyHero(true);updateRail();
    }
    private void updateHero(){applyHero(false);}
    private void applyHero(boolean force){
        boolean show=!tab.equals("Home") || actions.hasFocus() || homeRows.getSelectedPosition()<=0;
        if(show==heroShown && !force)return;heroShown=show;
        hero.animate().alpha(show?1f:0f).setDuration(180).start();
        backdrop.animate().alpha(show?1f:.35f).setDuration(180).start();
    }
    private String sortLabel(){return "Sort: "+SORTS[sortMode];}
    private String categoryLabel(){if(category.isEmpty())return "Category: All";
        for(Catalog.Category c:tab.equals("Series")?seriesCategories:movieCategories)if(c.id.equals(category))return c.name;
        return "Category";}
    private void buildFilters(){filters.removeAllViews();if(!tab.equals("Movies") && !tab.equals("Series"))return;
        Button sort=chip(sortLabel(),()->
            Ui.picker(this,"Sort by",SORTS,sortMode,which->{sortMode=which;render();restoreFilterFocus(0);}));
        filters.addView(sort,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,42)));
        Button cats=chip(categoryLabel(),()->{
            List<Catalog.Category> list=tab.equals("Series")?seriesCategories:movieCategories;
            String[] options=new String[list.size()+1];options[0]="All "+(tab.equals("Series")?"TV Shows":"Movies");
            for(int i=0;i<list.size();i++)options[i+1]=list.get(i).name;
            int selected=0;for(int i=0;i<list.size();i++)if(category.equals(list.get(i).id))selected=i+1;
            Ui.picker(this,"Categories",options,selected,which->{category=which==0?"":list.get(which-1).id;render();restoreFilterFocus(1);});
        });cats.setMaxWidth(Ui.dp(this,280));
        LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,42));b.leftMargin=Ui.dp(this,12);filters.addView(cats,b);
    }
    private void renderUtility(){rows.clear();rowsAdapter.notifyDataSetChanged();actions.removeAllViews();
        if(tab.equals("Search")){EditText query=new EditText(this);query.setHint("Search movies and TV shows");query.setTextColor(Ui.TEXT);
            query.setHintTextColor(Ui.TEXT_3);query.setSingleLine(true);query.setInputType(InputType.TYPE_CLASS_TEXT);
            query.setBackgroundResource(R.drawable.edit_field);query.setPadding(Ui.dp(this,16),0,Ui.dp(this,16),0);query.setTextSize(17);
            query.setOnFocusChangeListener((v,f)->{if(f)lastContentFocus=v;});
            actions.addView(query,new LinearLayout.LayoutParams(Ui.dp(this,420),Ui.dp(this,50)));
            query.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                public void onTextChanged(CharSequence s,int start,int before,int count){rows.clear();ArrayList<Catalog.Item> results=new ArrayList<>();
                    String needle=s.toString().trim().toLowerCase(Locale.US);
                    if(needle.length()>1){for(Catalog.Item item:movies)if(results.size()<40 && item.title.toLowerCase(Locale.US).contains(needle))results.add(item);
                        for(Catalog.Item item:series)if(results.size()<60 && item.title.toLowerCase(Locale.US).contains(needle))results.add(item);}
                    if(!results.isEmpty())rows.add(new Row("Results",results));rowsAdapter.notifyDataSetChanged();}
                public void afterTextChanged(android.text.Editable e){} });
        }else if(tab.equals("Settings")){
            Button update=chip("Check for new titles",()->{
                if(StartupRefresh.running()){toast("Already checking…");return;}
                Toast.makeText(this,"Checking for new titles…",Toast.LENGTH_SHORT).show();StartupRefresh.start(this,true);});
            actions.addView(update,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,46)));
        }
    }
    private void restoreFilterFocus(int index){filters.post(()->{
        if(filters.getChildCount()>index)filters.getChildAt(index).requestFocus();
    });}

    /** Keeps Continue Watching current after playback without disturbing the row the viewer is in. */
    private void updateContinueRow(){
        if(!tab.equals("Home") || !rendered || menuOpen)return;
        if(pendingRender && canRenderNow()){render();return;}
        List<Catalog.Item> continued=Catalog.continueWatching(this,movies,series);
        int index=-1;for(int i=0;i<rows.size();i++)if(rows.get(i).name.equals("Continue Watching")){index=i;break;}
        boolean focusHere=index>=0 && homeRows.hasFocus() && homeRows.getSelectedPosition()==index;
        if(index>=0){Row row=rows.get(index);
            if(sameTitles(row.items,continued))return;
            if(continued.isEmpty()){if(focusHere)return;rows.remove(index);rowsAdapter.notifyItemRemoved(index);}
            else{row.items.clear();row.items.addAll(continued);row.selected=0;if(row.adapter!=null)row.adapter.notifyDataSetChanged();}
        }else if(!continued.isEmpty()){
            boolean heroFocused=actions.hasFocus();
            rows.add(0,new Row("Continue Watching",continued));rowsAdapter.notifyItemInserted(0);
            if(heroFocused)homeRows.setSelectedPosition(0);
        }
        updateHero();
    }
    private static boolean sameTitles(List<Catalog.Item> a,List<Catalog.Item> b){if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!a.get(i).id.equals(b.get(i).id)||!a.get(i).kind.equals(b.get(i).kind))return false;return true;}
    private void openDetails(Catalog.Item item){Intent i=new Intent(this,DetailsActivity.class);
        i.putExtra("id",item.id);i.putExtra("kind",item.kind);i.putExtra("title",item.title);i.putExtra("poster",item.poster);
        i.putExtra("backdrop",item.backdrop);i.putExtra("year",item.year);i.putExtra("extension",item.extension);
        i.putExtra("category",item.categoryId);startActivity(i);}

    private final class RowsAdapter extends RecyclerView.Adapter<RowsAdapter.Holder>{
        RowsAdapter(){setHasStableIds(true);} @Override public long getItemId(int p){return rows.get(p).name.hashCode();}
        final class Holder extends RecyclerView.ViewHolder{final TextView heading;final HorizontalGridView list;Row row;
            Holder(View view){super(view);heading=view.findViewById(R.id.row_title);list=view.findViewById(R.id.row_list);
                heading.setTextColor(Ui.TEXT);
                list.getLayoutParams().height=PosterAdapter.rowListHeight(HomeActivity.this);
                list.setRecycledViewPool(rowPool);list.setItemAnimator(null);list.setHorizontalSpacing(0);
                // The selected card stays at the left edge of the row; the row slides under it.
                list.setWindowAlignment(BaseGridView.WINDOW_ALIGN_BOTH_EDGE);
                list.setWindowAlignmentOffsetPercent(BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);
                list.setWindowAlignmentOffset(list.getPaddingLeft());
                list.setItemAlignmentOffsetPercent(0f);list.setItemAlignmentOffset(0);
                list.setOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener(){
                    @Override public void onChildViewHolderSelected(RecyclerView parent,RecyclerView.ViewHolder child,int position,int sub){
                        if(row!=null && position>=0)row.selected=position;}
                });
            }}
        @Override public Holder onCreateViewHolder(ViewGroup parent,int type){return new Holder(getLayoutInflater().inflate(R.layout.item_home_row,parent,false));}
        @Override public void onBindViewHolder(Holder h,int position){Row row=rows.get(position);h.heading.setText(row.name);
            int selected=Math.min(row.selected,Math.max(0,row.items.size()-1));
            h.row=null;
            if(row.adapter==null)row.adapter=new PosterAdapter(HomeActivity.this,row.items,item->lastContentFocus=getCurrentFocus());
            if(h.list.getAdapter()!=row.adapter)h.list.setAdapter(row.adapter);
            h.list.setSelectedPosition(selected);h.row=row;row.selected=selected;
        }
        @Override public void onViewRecycled(Holder h){h.row=null;h.list.setAdapter(null);}
        @Override public int getItemCount(){return rows.size();}
    }

    /* ---------------- Keys ---------------- */

    private void confirmExit(){
        if(exitDialog!=null && exitDialog.isShowing())return;
        // "Stay" is focused so an accidental second Back press never closes the app.
        exitDialog=Ui.dialog(this,"Exit Flix Town?",null,new String[]{"Stay","Exit"},0,i->{if(i==1)finish();},null);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int key=event.getKeyCode();
        if(key==KeyEvent.KEYCODE_BACK && event.getAction()==KeyEvent.ACTION_UP)return true; // handled on key down
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            if(overlayUp){
                if(key==KeyEvent.KEYCODE_BACK){if(event.getRepeatCount()==0)confirmExit();return true;}
                if(startupActions.getVisibility()!=View.VISIBLE)return true;
                return super.dispatchKeyEvent(event);
            }
            if(key==KeyEvent.KEYCODE_BACK){
                if(event.getRepeatCount()>0)return true;
                if(menuOpen){closeMenu();return true;}
                if(!tab.equals("Home")){selectTab("Home");focusDefault();return true;}
                if(homeRows.hasFocus() && play!=null && play.isShown()){play.requestFocus();homeRows.setSelectedPositionSmooth(0);updateHero();return true;}
                confirmExit();return true;
            }
            if(key==KeyEvent.KEYCODE_DPAD_LEFT && !menuOpen){View focus=getCurrentFocus();
                boolean editing=focus instanceof EditText && ((EditText)focus).getSelectionStart()>0;
                if(focus!=null && !editing){View left=focus.focusSearch(View.FOCUS_LEFT);
                    boolean firstFilter=filters.getChildCount()>0 && focus==filters.getChildAt(0);
                    if(left==null || left==focus || firstFilter || !isDescendant(content,left)){lastContentFocus=focus;openMenu(tab);return true;}}
                else if(focus==null){openMenu(tab);return true;}
            }
        }
        return super.dispatchKeyEvent(event);
    }
    private static boolean isDescendant(ViewGroup parent,View view){
        for(Object p=view.getParent();p instanceof View;p=((View)p).getParent())if(p==parent)return true;return false;}
    @Override protected void onDestroy(){destroyed=true;StartupRefresh.unlisten(this);handler.removeCallbacksAndMessages(null);
        if(sweep!=null)sweep.cancel();if(exitDialog!=null)exitDialog.dismiss();if(updates!=null)updates.destroy();super.onDestroy();}
}

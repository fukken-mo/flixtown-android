package com.flixtown.tv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Flix Town Home and the browse pages.
 *
 * Home is one vertical Leanback grid. Item 0 is a cinematic hero over full-screen ambient artwork;
 * it rotates through five trending titles every 10 seconds while the viewer is idle on it. The
 * sections below have their own card styles (landscape Continue Watching, ranked Trending,
 * posters with titles). Moving into a section switches Home into "browse mode": the
 * hero fades, a short preview of the focused title appears above the row, and the ambient artwork
 * follows focus after a short pause. The focused row always sits on the same keyline, and every
 * row remembers its position, so the D-pad moves exactly one card per press.
 */
public class HomeActivity extends Activity implements StartupRefresh.Listener, HomeCards.Listener, NavRail.Host, SettingsPage.Host {
    static final String EXTRA_FRESH="fresh_launch";
    private static final int BROWSE_COLUMNS=6;
    private static final long SLOW_SERVER_MS=15000,FOCUS_ART_DELAY_MS=280,HERO_ROTATE_MS=10000,HERO_IDLE_MS=8000;
    private static final Object HERO="hero",UTILITY="utility";
    /** Movie details fetched for the hero (runtime, genre, plot, backdrop), kept for this process. */
    private static final Map<String,JSONObject> HERO_INFO=new HashMap<>();

    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<Integer,RecyclerView.RecycledViewPool> pools=new HashMap<>();
    private final List<Catalog.Item> movies=new ArrayList<>(),series=new ArrayList<>();
    private final List<Catalog.Category> movieCategories=new ArrayList<>(),seriesCategories=new ArrayList<>();
    private final Map<String,String> categoryNames=new HashMap<>();
    private final List<Object> entries=new ArrayList<>();
    private List<Catalog.Item> featured=new ArrayList<>();
    private int heroIndex;
    private VerticalGridView homeRows,browseGrid;private HomeAdapter homeAdapter;
    private BackdropView ambient;private View ambientDim,homeScrim,ambientSlot,bottomFade;
    private TextView previewTitle,previewMeta,previewDesc,accountPill,browseTitle,emptyMessage,startupStatus;
    private View preview,startupOverlay,startupSweep,dim;
    private LinearLayout filters,browseHeader,startupActions;
    private ViewGroup content;private View lastContentFocus;
    private HeroHolder hero;
    private NavRail nav;
    private String tab="Home",category="",searchText="";private int sortMode;
    private boolean rendered,pendingRender,cacheLoaded,overlayUp,destroyed,heroMode=true,resumed;
    private int restoreGridPosition=-1,keyline,heroHeight,artWidth,artHeight;
    private long overlayShownAt,lastKeyAt;
    private ValueAnimator sweep;
    private Dialog exitDialog;
    private AppUpdates updates;
    private Catalog.Item pendingFocusItem;
    private SettingsPage settingsPage;
    private static final String[] TABS={"Search","Home","Movies","Series","Favorites","Settings"};
    private static final String[] TAB_NAMES={"Search","Home","Movies","TV Shows","My List","Settings"};
    private static final int[] ICONS={1,0,2,3,4,5};
    private static final String[] SORTS={"Recently Added","Title A–Z","Top Rated","Release Year"};

    @Override public void onCreate(Bundle state){super.onCreate(state);
        if(Api.prefs(this).getBoolean("expired",false)){startActivity(new Intent(this,RenewalActivity.class));finish();return;}
        setContentView(R.layout.activity_main);updates=new AppUpdates(this);Images.init(this);
        Ratings.init(this);Ratings.listen(ratingsChanged);
        ambientSlot=findViewById(R.id.ambient_slot);ambientDim=findViewById(R.id.ambient_dim);homeScrim=findViewById(R.id.home_scrim);
        ambient=new BackdropView(this);((FrameLayout)ambientSlot).addView(ambient,new FrameLayout.LayoutParams(-1,-1));
        preview=findViewById(R.id.preview);previewTitle=findViewById(R.id.preview_title);previewMeta=findViewById(R.id.preview_meta);previewDesc=findViewById(R.id.preview_desc);
        accountPill=findViewById(R.id.account_pill);bottomFade=findViewById(R.id.bottom_fade);
        filters=findViewById(R.id.filters);browseHeader=findViewById(R.id.browse_header);browseTitle=findViewById(R.id.browse_title);emptyMessage=findViewById(R.id.empty_message);
        homeRows=findViewById(R.id.home_rows);browseGrid=findViewById(R.id.browse_grid);content=findViewById(R.id.content);dim=findViewById(R.id.dim);
        startupOverlay=findViewById(R.id.startup_overlay);startupStatus=findViewById(R.id.startup_status);
        startupActions=findViewById(R.id.startup_actions);startupSweep=findViewById(R.id.startup_sweep);
        nav=new NavRail(this,findViewById(R.id.nav),dim,TABS,TAB_NAMES,ICONS,this);
        if(state!=null){
            tab=state.getString("tab","Home");category=state.getString("category","");
            sortMode=state.getInt("sort",0);restoreGridPosition=state.getInt("grid_position",-1);
        }
        layoutForScreen();
        homeRows.setItemAnimator(null);homeAdapter=new HomeAdapter();homeRows.setAdapter(homeAdapter);
        homeRows.setOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener(){
            @Override public void onChildViewHolderSelected(RecyclerView parent,RecyclerView.ViewHolder child,int position,int sub){onRowSelected(position);}
        });
        browseGrid.setItemAnimator(null);
        render();
        boolean fresh=state==null && getIntent().getBooleanExtra(EXTRA_FRESH,false);
        if(fresh)showOverlay();
        loadCache();
        // A fresh launch already started the refresh (before the intro); a restored process starts one here.
        if(!StartupRefresh.startedThisProcess())StartupRefresh.start(this,false);
        StartupRefresh.listen(this);
        StartupRefresh.watchForeground(getApplication());
        updates.setListener(()->{if(settingsPage!=null)settingsPage.updateRowChanged();});
        updates.checkOnLaunch(state==null);   // a re-created screen (state!=null) is not a new launch
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);
        out.putString("tab",tab);out.putString("category",category);out.putInt("sort",sortMode);
        if(isBrowse())out.putInt("grid_position",browseGrid.getSelectedPosition());
    }

    /** Hero height, row keyline and grid sizes from the real screen, inside the 5% safe area. */
    private void layoutForScreen(){
        DisplayMetrics m=getResources().getDisplayMetrics();
        int safeY=Ui.safeY(this);
        keyline=safeY+Ui.dp(this,170);                 // focused section's top edge in browse mode
        heroHeight=m.heightPixels-Ui.dp(this,84);       // the first section peeks in below the hero
        homeRows.setPadding(0,0,0,safeY);
        homeRows.setWindowAlignment(BaseGridView.WINDOW_ALIGN_LOW_EDGE);
        homeRows.setWindowAlignmentOffsetPercent(BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);
        homeRows.setWindowAlignmentOffset(keyline);
        homeRows.setItemAlignmentOffsetPercent(0f);homeRows.setItemAlignmentOffset(0);
        FrameLayout.LayoutParams pp=(FrameLayout.LayoutParams)preview.getLayoutParams();pp.topMargin=safeY;preview.setLayoutParams(pp);
        FrameLayout.LayoutParams ap=(FrameLayout.LayoutParams)accountPill.getLayoutParams();ap.topMargin=safeY;accountPill.setLayoutParams(ap);

        int headerHeight=Ui.dp(this,52);
        FrameLayout.LayoutParams bh=(FrameLayout.LayoutParams)browseHeader.getLayoutParams();bh.topMargin=0;bh.height=safeY+headerHeight;browseHeader.setLayoutParams(bh);
        browseHeader.setPadding(browseHeader.getPaddingLeft(),safeY,browseHeader.getPaddingRight(),0);
        FrameLayout.LayoutParams gp=(FrameLayout.LayoutParams)browseGrid.getLayoutParams();gp.topMargin=safeY+headerHeight;browseGrid.setLayoutParams(gp);
        int cardSpace=m.widthPixels-Ui.dp(this,NavRail.COLLAPSED_DP)-browseGrid.getPaddingLeft()-browseGrid.getPaddingRight();
        int cardWidth=cardSpace/BROWSE_COLUMNS,glow=Ui.dp(this,PosterAdapter.GLOW_DP);
        artWidth=cardWidth-2*glow;artHeight=artWidth*3/2;
        browseGrid.setNumColumns(BROWSE_COLUMNS);browseGrid.setHorizontalSpacing(0);browseGrid.setVerticalSpacing(Ui.dp(this,4));
        browseGrid.setPadding(browseGrid.getPaddingLeft(),Ui.dp(this,4),browseGrid.getPaddingRight(),safeY);
    }

    @Override protected void onResume(){super.onResume();resumed=true;ScreenAwake.on(this);scheduleRotation();
        if(updates!=null){updates.resume();handler.post(updates::showPendingIfReady);}}
    /** super.onStart() lets the foreground tracker flag a return from the background first. */
    @Override protected void onStart(){super.onStart();if(updates!=null)updates.checkOnReturn();}
    /** Focus comes back when the intro, a dialog or another screen goes away: the moment for a waiting update prompt. */
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);
        if(hasFocus && updates!=null)handler.post(updates::showPendingIfReady);}
    /** An update prompt only appears over Home itself: in front, focused, past the loading screen. */
    boolean readyForUpdatePrompt(){
        return resumed && !isFinishing() && !destroyed && !overlayUp && hasWindowFocus()
            && (exitDialog==null || !exitDialog.isShowing());
    }
    @Override protected void onPause(){resumed=false;ScreenAwake.off(this);handler.removeCallbacks(rotateHero);super.onPause();}
    @Override protected void onRestart(){super.onRestart();refreshAfterReturn();}

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
        overlayUp=false;handler.removeCallbacks(slowServer);
        if(sweep!=null)sweep.cancel();
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        startupOverlay.animate().alpha(0f).setDuration(260).withEndAction(()->{startupOverlay.setVisibility(View.GONE);
            if(updates!=null)updates.showPendingIfReady();}).start();
        if(tab.equals("Home") && hero!=null)bindHero(false);
        View focus=getCurrentFocus();
        if(focus==null || !isDescendant(content,focus))focusDefault();
        scheduleRotation();
    }
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
            Button b=i==0 && !Ui.dismissive(labels[i])?Ui.primaryButton(this,labels[i]):Ui.button(this,labels[i]);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,52));
            if(i>0)p.leftMargin=Ui.dp(this,14);b.setMinWidth(Ui.dp(this,150));startupActions.addView(b,p);
            b.setOnClickListener(v->choice.select(index));
        }
        startupActions.getChildAt(0).requestFocus();
    }

    /* ---------------- Catalog ---------------- */

    /** Updates come from the panel's app-update.php (see AppUpdates), not from config.php. */
    @Override public void onConfig(JSONObject config){}
    @Override public void onResult(StartupRefresh.Result r){
        if(isFinishing()||destroyed)return;
        if(r.state==StartupRefresh.EXPIRED){startActivity(new Intent(this,RenewalActivity.class));finish();return;}
        updateAccountPill();
        if(settingsPage!=null)settingsPage.refreshStatus();
        if(r.state==StartupRefresh.DONE){
            applyCatalog(r.movies,r.series,r.movieCategories,r.seriesCategories);
            SeriesNews.onServerCatalog(this,series,this::homeFactsChanged);
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
        Ratings.ensureLoaded(this);SeriesNews.ensureLoaded(this,s);   // saved ratings and badges are ready for the first render
        runOnUiThread(()->{
            if(isFinishing() || destroyed)return;
            cacheLoaded=true;
            // A network result that already arrived is newer than the file cache.
            if(movies.isEmpty() && series.isEmpty())applyCatalog(m,s,mc,sc);
            boolean haveTitles=!movies.isEmpty()||!series.isEmpty();
            // Saved titles show at once; the refresh that is already running updates them in place.
            if(overlayUp && haveTitles)hideOverlay(false);
            else if(overlayUp && !StartupRefresh.running()){
                // The refresh already failed before the cache was read: show the offline choice now.
                onResult(new StartupRefresh.Result(StartupRefresh.FAILED,false,null,null,null,null,null));
            }else if(!haveTitles && hero!=null)bindHero(false);
        });
    });}
    private void toast(String text){if(!isFinishing())Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private static long signature(List<Catalog.Item> items){long h=items.size();
        for(Catalog.Item item:items)h=h*31+item.id.hashCode()*17L+item.added;return h;}
    private void applyCatalog(List<Catalog.Item> m,List<Catalog.Item> s,List<Catalog.Category> mc,List<Catalog.Category> sc){
        long before=signature(movies)*7+signature(series);
        movies.clear();movies.addAll(m);series.clear();series.addAll(s);
        movieCategories.clear();movieCategories.addAll(mc);seriesCategories.clear();seriesCategories.addAll(sc);
        categoryNames.clear();for(Catalog.Category c:mc)categoryNames.put(c.id,c.name);for(Catalog.Category c:sc)categoryNames.put(c.id,c.name);
        Catalog.Store.set(movies,series);
        boolean changed=before!=signature(movies)*7+signature(series);
        if(changed||!rendered)Trending.refresh(this,movies,series,this::homeFactsChanged);
        if(!changed && rendered && !pendingRender)return;
        if(canRenderNow())renderKeepingFocus();
        else if(isBrowse() && !nav.isOpen())updateBrowseInPlace();
        else pendingRender=true;   // Home sections and Search apply it when the viewer is back at the top
    }
    /** Re-renders, then puts focus back if the rebuild took it away (hero buttons keep it via stable ids). */
    private void renderKeepingFocus(){
        boolean hadFocus=getCurrentFocus()!=null;render();
        if(hadFocus)content.post(()->{View f=getCurrentFocus();if(!overlayUp && !nav.isOpen() && (f==null||!isDescendant(content,f)))focusDefault();});
    }
    /**
     * New catalog data while a poster page is open: the grid keeps the same adapter and the same
     * selected title (found by id, since new titles can shift positions), so neither focus nor the
     * scroll position jumps. Filters keep their focus too.
     */
    private void updateBrowseInPlace(){
        if(tab.equals("Settings")){if(settingsPage!=null)settingsPage.refreshStatus();return;}
        RecyclerView.Adapter<?> current=browseGrid.getAdapter();
        if(!(current instanceof PosterAdapter)){render();return;}
        PosterAdapter grid=(PosterAdapter)current;
        View focus=getCurrentFocus();boolean inGrid=focus!=null && isDescendant(browseGrid,focus);
        int chip=-1;for(int i=0;i<filters.getChildCount();i++)if(filters.getChildAt(i)==focus)chip=i;
        int position=browseGrid.getSelectedPosition();
        String key=position>=0 && position<grid.getItemCount()?grid.itemAt(position).key():null;
        List<Catalog.Item> next=browseItems();
        grid.replace(next);
        int target=key==null?-1:grid.indexOf(key);
        if(target<0)target=Math.max(0,Math.min(position,next.size()-1));
        if(!next.isEmpty())browseGrid.setSelectedPosition(target);
        emptyMessage.setVisibility(next.isEmpty()?View.VISIBLE:View.GONE);
        if(inGrid)focusGrid();else if(chip>=0)restoreFilterFocus(chip);
    }
    /** Re-render only when it cannot move focus or scroll position out from under the viewer. */
    private boolean canRenderNow(){
        if(!rendered || overlayUp)return true;
        if(nav.isOpen())return false;
        View focus=getCurrentFocus();
        if(focus==null)return true;
        if(isBrowse())return restoreGridPosition>=0;
        return tab.equals("Home") && heroMode;
    }

    /**
     * Trending matches or series badges changed. These can add a row or reorder Latest TV Shows, so
     * Home is rebuilt only where that cannot move the viewer (at the hero); otherwise the visible
     * cards just update their words and the rebuild waits until the viewer is back at the top.
     */
    private void homeFactsChanged(){
        if(destroyed || !rendered)return;
        if(!tab.equals("Home")){pendingRender=true;return;}
        if(heroMode && canRenderNow() && !nav.isOpen()){int keep=heroIndex;renderKeepingFocus();heroIndex=keep;if(hero!=null)bindHero(false);}
        else{pendingRender=true;refreshCardText();}
    }
    private final Runnable ratingsChanged=this::refreshCardText;
    /** New ratings or badges: same cards, same focus, only their text lines change. */
    private void refreshCardText(){
        if(destroyed)return;
        for(Object e:entries)if(e instanceof HomeFeed.Section){HomeFeed.Section s=(HomeFeed.Section)e;
            if(s.adapter!=null && s.type!=HomeFeed.CONTINUE)s.adapter.notifyItemRangeChanged(0,s.adapter.getItemCount(),HomeCards.TEXT);}
        if(browseGrid.getAdapter() instanceof PosterAdapter)((PosterAdapter)browseGrid.getAdapter()).refreshText();
        Catalog.Item item=current();
        if(hero!=null && item!=null && tab.equals("Home"))hero.meta.setText(metaLine(item,null));
        if(!heroMode && pendingFocusItem!=null && previewTitle.getText().toString().equals(pendingFocusItem.title))previewMeta.setText(metaLine(pendingFocusItem,null));
    }

    /* ---------------- Navigation ---------------- */

    @Override public void onSelect(String target){selectTab(target);}
    @Override public void onClose(){closeMenu();}
    private void openMenu(){if(nav.isOpen()||overlayUp)return;
        lastContentFocus=getCurrentFocus();
        handler.removeCallbacks(rotateHero);
        nav.open(tab);
        // The page stays exactly where it is under the dim layer, but cannot take focus while the menu is open.
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }
    private void closeMenu(){
        View current=getCurrentFocus();
        // Also recovers the case where the menu is closed but focus was left on one of its entries.
        if(!nav.isOpen() && current!=null && isDescendant(content,current))return;
        // Close first: the focus helpers below deliberately do nothing while the menu counts as open.
        nav.close();
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        boolean restored=lastContentFocus!=null && lastContentFocus.isAttachedToWindow() && lastContentFocus.isShown() && lastContentFocus.requestFocus();
        if(!restored)focusDefault();
        scheduleRotation();
    }
    private void selectTab(String target){
        if(target.equals(tab)){closeMenu();return;}
        tab=target;category="";sortMode=0;lastContentFocus=null;restoreGridPosition=-1;render();
        // An empty page (for example My List with no favorites) keeps the menu open so focus is never lost.
        if(hasFocusableContent())closeMenu();else nav.setSelected(tab);
    }
    /** Pages drawn as a header over the full-page grid: the poster tabs, and Settings as a single column. */
    private boolean isBrowse(){return tab.equals("Movies")||tab.equals("Series")||tab.equals("Favorites")||tab.equals("Settings");}
    private boolean hasFocusableContent(){
        if(isBrowse())return (browseGrid.getAdapter()!=null && browseGrid.getAdapter().getItemCount()>0)||filters.getChildCount()>0;
        return !entries.isEmpty();
    }
    private void focusDefault(){
        if(overlayUp)return;
        if(isBrowse()){focusGrid();return;}
        homeRows.setSelectedPosition(0);focusHomeItem(12);
    }
    private void focusHomeItem(int attempts){
        if(nav.isOpen()||overlayUp||isBrowse())return;
        RecyclerView.ViewHolder h=homeRows.findViewHolderForAdapterPosition(Math.max(0,homeRows.getSelectedPosition()));
        if(h!=null && !homeRows.isLayoutRequested() && h.itemView.requestFocus())return;
        if(attempts>0)homeRows.postOnAnimation(()->focusHomeItem(attempts-1));
        else homeRows.requestFocus();   // the grid hands focus to its selected row once it has laid out
    }
    private void focusGrid(){
        if(browseGrid.getAdapter()==null || browseGrid.getAdapter().getItemCount()==0){
            if(filters.getChildCount()>0)filters.getChildAt(0).requestFocus();return;}
        focusGridCard(12);
    }
    /** Focuses the grid's selected card. Right after a tab switch it may not exist yet, so retry for a few frames. */
    private void focusGridCard(int attempts){
        if(nav.isOpen()||overlayUp||!isBrowse())return;
        RecyclerView.ViewHolder holder=browseGrid.findViewHolderForAdapterPosition(Math.max(0,browseGrid.getSelectedPosition()));
        if(holder!=null && !browseGrid.isLayoutRequested() && holder.itemView.requestFocus())return;
        if(attempts>0)browseGrid.postOnAnimation(()->focusGridCard(attempts-1));
        else browseGrid.requestFocus();   // the grid hands focus to its selected card once it has laid out
    }

    /* ---------------- Rendering ---------------- */

    private void render(){boolean browse=isBrowse(),home=tab.equals("Home");
        rendered=true;pendingRender=false;
        homeRows.setVisibility(browse?View.GONE:View.VISIBLE);
        ambientSlot.setVisibility(browse?View.GONE:View.VISIBLE);ambientDim.setVisibility(browse?View.GONE:View.VISIBLE);homeScrim.setVisibility(browse?View.GONE:View.VISIBLE);
        browseHeader.setVisibility(browse?View.VISIBLE:View.GONE);browseGrid.setVisibility(browse?View.VISIBLE:View.GONE);
        emptyMessage.setVisibility(View.GONE);
        if(browse){handler.removeCallbacks(focusArt);preview.animate().cancel();preview.setAlpha(0f);}   // Home's preview never shows over a grid
        entries.clear();
        if(home){
            HomeFeed.Result feed=HomeFeed.build(this,movies,series);
            featured=feed.featured;heroIndex=featured.isEmpty()?0:heroIndex%featured.size();
            entries.add(HERO);entries.addAll(feed.sections);
            homeAdapter.notifyDataSetChanged();homeRows.setSelectedPosition(0);
            setMode(true,true);
            if(hero!=null)bindHero(false);
        }else if(browse){
            renderBrowse();
            homeAdapter.notifyDataSetChanged();
        }else{
            entries.add(UTILITY);
            if(tab.equals("Search") && searchText.trim().length()>1)entries.add(searchSection(searchText));
            homeAdapter.notifyDataSetChanged();homeRows.setSelectedPosition(0);
            setMode(true,true);ambientDim.animate().alpha(.55f).setDuration(200).start();
        }
        updateAccountPill();
        nav.setSelected(tab);
        scheduleRotation();
    }
    private void renderBrowse(){
        if(tab.equals("Settings")){renderSettings();return;}
        browseGrid.setNumColumns(BROWSE_COLUMNS);
        browseTitle.setText(tab.equals("Favorites")?"My List":tab.equals("Series")?"All TV Shows":"All Movies");
        buildFilters();List<Catalog.Item> filtered=browseItems();
        browseGrid.setAdapter(new PosterAdapter(this,filtered,artWidth,artHeight,true,null));
        int start=0;
        // After the activity was recreated, go back to the poster the viewer had selected.
        if(restoreGridPosition>=0 && !filtered.isEmpty()){start=Math.min(restoreGridPosition,filtered.size()-1);restoreGridPosition=-1;
            if(!overlayUp)focusGrid();}
        browseGrid.setSelectedPosition(start);
        if(filtered.isEmpty()){emptyMessage.setVisibility(View.VISIBLE);
            emptyMessage.setText(tab.equals("Favorites")?"Titles you add to My List appear here":movies.isEmpty()&&series.isEmpty()?"Your titles will appear here once they load":"No titles in this category");}
    }
    /** The current poster page's titles, after My List, category and sort. */
    private List<Catalog.Item> browseItems(){
        List<Catalog.Item> source=tab.equals("Series")?series:movies;
        if(tab.equals("Favorites")){source=new ArrayList<>();String saved=Api.prefs(this).getString("favorites","");
            for(Catalog.Item item:movies)if(saved.contains("|movie:"+item.id+"|"))source.add(item);
            for(Catalog.Item item:series)if(saved.contains("|series:"+item.id+"|"))source.add(item);
        }
        ArrayList<Catalog.Item> filtered=new ArrayList<>();for(Catalog.Item item:source)if(category.isEmpty()||category.equals(item.categoryId))filtered.add(item);
        if(sortMode==0)filtered=new ArrayList<>(Catalog.recent(filtered,filtered.size()));
        if(sortMode==1)java.util.Collections.sort(filtered,(a,b)->a.title.compareToIgnoreCase(b.title));
        if(sortMode==2)filtered=new ArrayList<>(Catalog.topRated(filtered,filtered.size()));   // TMDB ratings known so far
        if(sortMode==3)java.util.Collections.sort(filtered,(a,b)->Integer.compare(b.year,a.year));
        return filtered;
    }
    private void renderSettings(){
        browseTitle.setText("Settings");filters.removeAllViews();
        browseGrid.setNumColumns(1);
        if(settingsPage==null)settingsPage=new SettingsPage(this,this);
        browseGrid.setAdapter(settingsPage);
        int start=0;
        if(restoreGridPosition>=0){start=Math.min(restoreGridPosition,settingsPage.getItemCount()-1);restoreGridPosition=-1;if(!overlayUp)focusGrid();}
        browseGrid.setSelectedPosition(start);
        settingsPage.checkAccount(false);   // current status, expiration and connections from the server
    }

    /* ---------------- Settings actions ---------------- */

    @Override public void refreshCatalog(){
        if(StartupRefresh.running()){toast("Already checking…");return;}
        Toast.makeText(this,"Checking your account and catalog…",Toast.LENGTH_SHORT).show();
        StartupRefresh.start(this,true);
    }
    @Override public void checkForUpdates(){updates.select();}
    @Override public void accountEnded(){
        if(isFinishing()||destroyed)return;
        Api.prefs(this).edit().putBoolean("expired",true).apply();
        startActivity(new Intent(this,RenewalActivity.class));finish();
    }
    @Override public String updateValue(){return updates.rowValue();}
    @Override public String updateDetail(){return updates.rowDetail();}
    @Override public void signOut(){
        Ui.dialog(this,"Sign out of Flix Town?","This TV will forget your account. Your settings stay. To watch again, scan the code or sign in with your remote.",
            new String[]{"Cancel","Sign out"},0,i->{if(i!=1)return;
                SignOut.run(this);HERO_INFO.clear();
                Intent login=new Intent(this,LoginActivity.class);login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(login);finish();},null);
    }
    private HomeFeed.Section searchSection(String query){
        ArrayList<Catalog.Item> results=new ArrayList<>();String needle=query.trim().toLowerCase(Locale.US);
        for(Catalog.Item item:movies)if(results.size()<40 && item.title.toLowerCase(Locale.US).contains(needle))results.add(item);
        for(Catalog.Item item:series)if(results.size()<60 && item.title.toLowerCase(Locale.US).contains(needle))results.add(item);
        return new HomeFeed.Section(HomeFeed.POSTERS,"results",results.isEmpty()?"No matches":"Results",null,results);
    }
    private String sortLabel(){return "Sort: "+SORTS[sortMode];}
    private String categoryLabel(){if(category.isEmpty())return "Category: All";
        for(Catalog.Category c:tab.equals("Series")?seriesCategories:movieCategories)if(c.id.equals(category))return c.name;
        return "Category";}
    private Button chip(String name,Runnable click){Button b=new Button(this);b.setText(name);b.setAllCaps(false);
        b.setTextSize(16);b.setTextColor(Ui.TEXT);b.setFocusable(true);b.setFocusableInTouchMode(true);
        b.setSingleLine(true);b.setEllipsize(TextUtils.TruncateAt.END);b.setMinWidth(0);b.setMinimumWidth(0);
        b.setMinHeight(0);b.setMinimumHeight(0);b.setStateListAnimator(null);
        b.setPadding(Ui.dp(this,22),0,Ui.dp(this,22),0);Ui.styleButton(b,Ui.SECONDARY);
        b.setOnFocusChangeListener((v,f)->{Ui.focusScale(v,f);
            if(f)lastContentFocus=v;});
        b.setOnClickListener(v->click.run());
        // Down from Sort or Categories always lands on the grid's selected poster.
        b.setOnKeyListener((v,key,e)->{
            if(e.getAction()!=KeyEvent.ACTION_DOWN || key!=KeyEvent.KEYCODE_DPAD_DOWN || !isBrowse())return false;
            if(browseGrid.getAdapter()==null || browseGrid.getAdapter().getItemCount()==0)return false;
            focusGrid();return true;});
        return b;}
    private void buildFilters(){filters.removeAllViews();if(!tab.equals("Movies") && !tab.equals("Series"))return;
        Button sort=chip(sortLabel(),()->
            Ui.picker(this,"Sort by",SORTS,sortMode,which->{sortMode=which;render();restoreFilterFocus(0);}));
        filters.addView(sort,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,46)));
        Button cats=chip(categoryLabel(),()->{
            List<Catalog.Category> list=tab.equals("Series")?seriesCategories:movieCategories;
            String[] options=new String[list.size()+1];options[0]="All "+(tab.equals("Series")?"TV Shows":"Movies");
            for(int i=0;i<list.size();i++)options[i+1]=list.get(i).name;
            int selected=0;for(int i=0;i<list.size();i++)if(category.equals(list.get(i).id))selected=i+1;
            Ui.picker(this,"Categories",options,selected,which->{category=which==0?"":list.get(which-1).id;render();restoreFilterFocus(1);});
        });cats.setMaxWidth(Ui.dp(this,280));
        LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,46));b.leftMargin=Ui.dp(this,10);filters.addView(cats,b);
    }
    private void restoreFilterFocus(int index){filters.post(()->{
        if(filters.getChildCount()>index)filters.getChildAt(index).requestFocus();
    });}

    /** After Details or the player: refresh Continue Watching without moving the viewer. */
    private void refreshAfterReturn(){
        if(!tab.equals("Home") || !rendered || nav.isOpen())return;
        if(heroMode && canRenderNow()){int keep=heroIndex;render();heroIndex=keep;if(hero!=null)bindHero(false);return;}
        for(Object e:entries)if(e instanceof HomeFeed.Section && ((HomeFeed.Section)e).type==HomeFeed.CONTINUE && ((HomeFeed.Section)e).adapter!=null)
            ((HomeFeed.Section)e).adapter.notifyDataSetChanged();   // progress bars; same holders, focus kept
        pendingRender=true;                                          // rows added/removed apply back at the hero
    }

    /* ---------------- Home modes: hero vs browsing sections ---------------- */

    private void onRowSelected(int position){
        boolean hasHero=!entries.isEmpty() && entries.get(0)==HERO;
        setMode(position<=0,false);
        fadeRowsAbove(position);
        if(position<=0 && hasHero && pendingRender && tab.equals("Home"))handler.post(()->{if(heroMode && pendingRender){int keep=heroIndex;render();heroIndex=keep;if(hero!=null)bindHero(false);}});
    }
    private void setMode(boolean heroAtTop,boolean force){
        boolean isHome=tab.equals("Home");
        if(heroAtTop==heroMode && !force)return;heroMode=heroAtTop;
        preview.animate().alpha(heroAtTop||!isHome?0f:1f).setDuration(200).start();
        if(isHome)ambientDim.animate().alpha(heroAtTop?0f:.42f).setDuration(260).start();
        accountPill.animate().alpha(heroAtTop?1f:0f).setDuration(200).start();
        if(heroAtTop){handler.removeCallbacks(focusArt);if(hero!=null && isHome)showHeroArt();scheduleRotation();}
        else handler.removeCallbacks(rotateHero);
    }
    /** Sections above the focused one fade out so the preview text above the keyline stays clean. */
    private void fadeRowsAbove(int selected){
        for(int i=0;i<homeRows.getChildCount();i++){View child=homeRows.getChildAt(i);
            int pos=homeRows.getChildAdapterPosition(child);if(pos<0)continue;
            float target=pos<selected?0f:1f;
            if(child.getAlpha()!=target)child.animate().alpha(target).setDuration(180).start();}
    }

    /* ---------------- Cards ---------------- */

    @Override public void onFocus(Catalog.Item item){
        lastContentFocus=getCurrentFocus();
        pendingFocusItem=item;handler.removeCallbacks(focusArt);
        // Wait for the D-pad to settle so fast scrolling never flashes artwork.
        handler.postDelayed(focusArt,FOCUS_ART_DELAY_MS);
    }
    private final Runnable focusArt=()->{
        Catalog.Item item=pendingFocusItem;if(item==null||heroMode||!tab.equals("Home")&&!tab.equals("Search"))return;
        previewTitle.setText(item.title);
        previewMeta.setText(metaLine(item,null));
        String desc=item.overview;JSONObject info=HERO_INFO.get(item.key());
        if(desc.isEmpty() && info!=null)desc=info.optString("plot","");
        previewDesc.setText(desc);previewDesc.setVisibility(desc.isEmpty()?View.GONE:View.VISIBLE);
        showArtFor(item);
    };
    private void showArtFor(Catalog.Item item){
        Catalog.Progress p=Catalog.progress(this,item);
        if(!p.art.isEmpty()){ambient.show(p.art,false);return;}
        String infoArt=heroBackdrop(item);
        if(infoArt!=null)ambient.show(infoArt,false);
        else ambient.show(item.poster,true);
    }
    @Override public void onOpen(Catalog.Item item,HomeFeed.Section section){
        if(section.type==HomeFeed.CONTINUE){watch(item);return;}
        openDetails(item,false);
    }
    /** Movies go straight to the player (it offers Continue / Start over); shows resume the right episode. */
    private void watch(Catalog.Item item){watch(item,null);}
    /** mode: null = as a normal OK press, "resume_now" or "start_over" (chosen in the Continue Watching menu). */
    private void watch(Catalog.Item item,String mode){
        if("movie".equals(item.kind)){
            Catalog.remember(this,item);
            Intent i=new Intent(this,PlayerActivity.class);i.putExtra("url",Api.stream(this,"movie",item.id,item.extension));
            i.putExtra("title",item.title);i.putExtra("content_kind",item.kind);i.putExtra("content_id",item.id);
            if(mode!=null)i.putExtra(mode,true);
            startActivity(i);
        }else openDetails(item,true,mode);
    }
    @Override public void onMenu(Catalog.Item item,HomeFeed.Section section){
        if(section.type!=HomeFeed.CONTINUE || overlayUp)return;
        Catalog.Progress p=Catalog.progress(this,item);
        String detail;
        if(p.upNext)detail=p.label.isEmpty()?"Next episode":"Up next: "+p.label;
        else{String left=p.remainingMinutes()>0?p.remainingMinutes()+" min left":"";
            detail="series".equals(item.kind)?joinDot(p.label,left):left;}
        if(BuildConfig.DEMO)android.util.Log.i("FlixTownQA","continue_menu "+item.key());
        Ui.dialog(this,item.title,detail.isEmpty()?null:detail,
            new String[]{"Resume","Start over","Remove from Continue Watching"},0,which->{
                if(which==0)watch(item,"resume_now");
                else if(which==1)watch(item,"start_over");
                else removeFromContinue(item,section);
            },null);
    }
    private static String joinDot(String a,String b){return a.isEmpty()?b:b.isEmpty()?a:a+"  ·  "+b;}
    /**
     * Forgets the saved position of this movie / show (the series' episode and its queued next one too),
     * then takes the card out of the row at once. Titles, files and My List are not touched.
     * Focus goes to the nearest remaining card, or to the next row when this was the last one.
     */
    private void removeFromContinue(Catalog.Item item,HomeFeed.Section section){
        Catalog.clearProgress(this,item.kind,item.id);           // SharedPreferences apply(): written off the main thread
        int index=section.items.indexOf(item);
        int row=entries.indexOf(section);
        if(BuildConfig.DEMO)android.util.Log.i("FlixTownQA","continue_removed "+item.key());
        if(index<0 || row<0)return;
        section.items.remove(index);
        if(section.items.isEmpty()){
            entries.remove(row);homeAdapter.notifyItemRemoved(row);
            pendingRender=true;                                     // e.g. "Because you watched" follows at the next render
            int target=Math.min(row,entries.size()-1);
            if(target<0)return;
            homeRows.setSelectedPosition(target);
            homeRows.post(()->focusHomeItem(12));
            return;
        }
        if(section.adapter!=null)section.adapter.notifyItemRemoved(index);
        int target=Math.min(index,section.items.size()-1);section.selected=target;
        RecyclerView.ViewHolder h=homeRows.findViewHolderForAdapterPosition(row);
        if(h instanceof SectionHolder){
            HorizontalGridView list=((SectionHolder)h).list;
            list.setSelectedPosition(target);
            list.post(()->{RecyclerView.ViewHolder card=list.findViewHolderForAdapterPosition(target);
                if(card==null || !card.itemView.requestFocus())list.requestFocus();});
        }
    }
    private void openDetails(Catalog.Item item,boolean play){openDetails(item,play,null);}
    private void openDetails(Catalog.Item item,boolean play,String playMode){Intent i=new Intent(this,DetailsActivity.class);
        i.putExtra("id",item.id);i.putExtra("kind",item.kind);i.putExtra("title",item.title);i.putExtra("poster",item.poster);
        String art=heroBackdrop(item);i.putExtra("backdrop",art!=null?art:item.backdrop);i.putExtra("year",item.year);i.putExtra("extension",item.extension);
        i.putExtra("category",item.categoryId);if(play)i.putExtra("play_on_open",true);
        if(playMode!=null)i.putExtra("play_mode",playMode);startActivity(i);}

    /** "2019  ·  ★ 7.6  ·  1h 54m  ·  Drama" with a gold star, from whatever is known. */
    private CharSequence metaLine(Catalog.Item item,JSONObject info){
        if(info==null)info=HERO_INFO.get(item.key());
        List<String> parts=new ArrayList<>();
        if(item.year>1900)parts.add(String.valueOf(item.year));
        String rating=Ratings.label(item);if(!rating.isEmpty())parts.add(rating);
        if("series".equals(item.kind))parts.add("Series");
        else if(info!=null){int secs=info.optInt("duration_secs",0);
            if(secs<=0){String[] hms=info.optString("duration","").split(":");try{if(hms.length==3)secs=Integer.parseInt(hms[0])*3600+Integer.parseInt(hms[1])*60+Integer.parseInt(hms[2]);}catch(Exception ignored){}}
            if(secs>=60)parts.add(secs>=3600?(secs/3600)+"h "+((secs/60)%60)+"m":(secs/60)+"m");}
        String genre=info!=null?info.optString("genre","").trim():"";
        if(genre.isEmpty())genre=item.genre;
        if(genre.isEmpty()){String c=categoryNames.get(item.categoryId);if(c!=null)genre=c;}
        if(!genre.isEmpty())parts.add(genre.split("[,/]")[0].trim());
        SpannableStringBuilder out=new SpannableStringBuilder();
        for(String p:parts){if(out.length()>0)out.append("   ·   ");int start=out.length();out.append(p);
            if(p.startsWith("★"))out.setSpan(new ForegroundColorSpan(Ui.GOLD),start,start+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
        return out;
    }

    /* ---------------- Hero ---------------- */

    private final class HeroHolder extends RecyclerView.ViewHolder{
        final TextView eyebrow,title,meta,desc,primaryLabel;final LinearLayout dots,block;final View primary,info;
        HeroHolder(View v){super(v);
            eyebrow=v.findViewById(R.id.hero_eyebrow);title=v.findViewById(R.id.hero_title);meta=v.findViewById(R.id.hero_meta);
            desc=v.findViewById(R.id.hero_desc);dots=v.findViewById(R.id.hero_dots);block=v.findViewById(R.id.hero_block);
            LinearLayout actions=v.findViewById(R.id.hero_actions);
            primary=heroButton(actions,"Watch Now",true);primaryLabel=(TextView)((LinearLayout)primary).getChildAt(1);
            info=heroButton(actions,"More Info",false);
            primary.setOnClickListener(x->{Catalog.Item item=current();if(item!=null)watch(item);});
            info.setOnClickListener(x->{Catalog.Item item=current();if(item!=null)openDetails(item,false);});
            // The hero is entered from below with FOCUS_UP; always land on the primary action.
            v.setFocusable(true);v.setFocusableInTouchMode(true);
            ((ViewGroup)v).setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            v.setOnFocusChangeListener((x,f)->{if(f)primary.requestFocus();});
        }
    }
    private View heroButton(LinearLayout parent,String label,boolean primary){
        LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.HORIZONTAL);b.setGravity(Gravity.CENTER_VERTICAL);
        b.setFocusable(true);b.setFocusableInTouchMode(true);b.setClickable(true);
        b.setPadding(Ui.dp(this,primary?22:24),0,Ui.dp(this,26),0);
        Ui.styleButton(b,primary?Ui.PRIMARY:Ui.SECONDARY);
        if(primary){PlayerIcon icon=new PlayerIcon(this,PlayerIcon.PLAY);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(Ui.dp(this,18),Ui.dp(this,18));ip.rightMargin=Ui.dp(this,10);b.addView(icon,ip);}
        TextView t=Ui.heading(this,label,17);t.setSingleLine(true);b.addView(t);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,52));
        if(parent.getChildCount()>0)p.leftMargin=Ui.dp(this,10);parent.addView(b,p);
        b.setOnFocusChangeListener((v,f)->{Ui.focusScale(v,f);
            if(f){lastContentFocus=v;lastKeyAt=SystemClock.uptimeMillis();}});
        return b;
    }
    private Catalog.Item current(){return featured.isEmpty()?null:featured.get(heroIndex%featured.size());}
    private void bindHero(boolean animate){
        if(hero==null)return;
        Catalog.Item item=current();
        // Only focusable when there is something to press, so focus can never sit on an empty hero.
        hero.itemView.setFocusable(item!=null);
        if(item==null){
            hero.eyebrow.setText("FLIX TOWN");hero.title.setText(cacheLoaded||!overlayUp?"Your titles are on the way":"");
            hero.meta.setText(cacheLoaded?"They'll appear here as soon as the server is reachable.":"");
            hero.desc.setText(cacheLoaded?"Try Settings › Check for new titles, or reopen Flix Town once the TV is online.":"");
            hero.primary.setVisibility(View.GONE);hero.info.setVisibility(View.GONE);hero.dots.removeAllViews();return;
        }
        hero.primary.setVisibility(View.VISIBLE);hero.info.setVisibility(View.VISIBLE);
        Runnable fill=()->{
            int rank=-1;for(Object e:entries)if(e instanceof HomeFeed.Section && ((HomeFeed.Section)e).type==HomeFeed.TRENDING)rank=((HomeFeed.Section)e).items.indexOf(item);
            String kind="series".equals(item.kind)?"SERIES":"MOVIE";
            // "#n TRENDING" only for real TMDB trending titles; NEW only with proof (see HomeCards.badge).
            String news="series".equals(item.kind)?SeriesNews.badge(item).label():item.isNew()?"NEW RELEASE":null;
            hero.eyebrow.setText(rank>=0?"#"+(rank+1)+" TRENDING  ·  "+kind:news!=null?news+"  ·  "+kind:"FEATURED  ·  "+kind);
            hero.title.setText(item.title);
            hero.meta.setText(metaLine(item,null));
            JSONObject info=HERO_INFO.get(item.key());
            String desc=item.overview.isEmpty()&&info!=null?info.optString("plot",""):item.overview;
            hero.desc.setText(desc);hero.desc.setVisibility(desc.isEmpty()?View.GONE:View.VISIBLE);
            hero.primaryLabel.setText(hasProgress(item)?"Continue":"Watch Now");
            buildDots();
        };
        // Only the words change; buttons and pager stay put, so the hero is never empty mid-rotation
        // and focus on Watch Now / More Info is untouched.
        View[] words={hero.eyebrow,hero.title,hero.meta,hero.desc};
        if(animate){for(View w:words)w.animate().cancel();
            for(int i=0;i<words.length;i++){View w=words[i];boolean last=i==words.length-1;
                w.animate().alpha(0.15f).translationX(-Ui.dp(this,8)).setStartDelay(0).setDuration(150).withEndAction(last?()->{fill.run();
                    for(View x:words){x.setTranslationX(Ui.dp(this,12));x.animate().alpha(1f).translationX(0).setDuration(260).start();}}:null).start();}}
        else{fill.run();for(View w:words){w.animate().cancel();w.setAlpha(1f);w.setTranslationX(0);}}
        if(heroMode && tab.equals("Home"))showHeroArt();
        fetchHeroInfo(item);Ratings.want(item);
        // Warm only the next hero backdrop; nothing else is preloaded.
        if(featured.size()>1){Catalog.Item next=featured.get((heroIndex+1)%featured.size());String art=heroBackdrop(next);
            if(art!=null)Images.prefetch(art,960);}
    }
    private boolean hasProgress(Catalog.Item item){
        if("series".equals(item.kind))return !Api.prefs(this).getString("resume_episode_"+item.key(),"").isEmpty();
        return Api.prefs(this).getLong("resume_position_"+item.key(),0)>=15000;
    }
    private void buildDots(){
        hero.dots.removeAllViews();if(featured.size()<2)return;
        for(int i=0;i<featured.size();i++){boolean on=i==heroIndex%featured.size();View d=new View(this);
            d.setBackground(Ui.rounded(on?Ui.GLOW:0x4DFFFFFF,2,this));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(Ui.dp(this,on?26:12),Ui.dp(this,3));if(i>0)p.leftMargin=Ui.dp(this,6);
            hero.dots.addView(d,p);}
    }
    private String heroBackdrop(Catalog.Item item){
        JSONObject info=HERO_INFO.get(item.key());
        if(info!=null){JSONArray list=info.optJSONArray("backdrop_path");String url=list!=null&&list.length()>0?list.optString(0,""):info.optString("backdrop_path","");
            if(url.startsWith("http")||url.startsWith("demo:"))return url;}
        return item.hasBackdrop()?item.backdrop:null;
    }
    private void showHeroArt(){Catalog.Item item=current();if(item==null)return;
        String art=heroBackdrop(item);if(art!=null)ambient.show(art,false);else ambient.show(item.poster,true);}
    /** Movies' runtime, genre, plot and backdrop come from one details call per hero title, kept for the session. */
    private void fetchHeroInfo(Catalog.Item item){
        if(!"movie".equals(item.kind) || HERO_INFO.containsKey(item.key()))return;
        Api.IO.execute(()->{try{
            JSONObject data=Api.get(Api.xtream(this,"get_vod_info","&vod_id="+Api.enc(item.id)));
            JSONObject info=data.optJSONObject("info");if(info==null)return;
            runOnUiThread(()->{HERO_INFO.put(item.key(),info);
                if(!destroyed && item==current() && hero!=null){hero.meta.setText(metaLine(item,info));
                    if(item.overview.isEmpty()){String plot=info.optString("plot","");hero.desc.setText(plot);hero.desc.setVisibility(plot.isEmpty()?View.GONE:View.VISIBLE);}
                    if(heroMode && tab.equals("Home"))showHeroArt();}});
        }catch(Exception ignored){}});
    }
    private final Runnable rotateHero=new Runnable(){@Override public void run(){
        if(!canRotate())return;
        // Viewer just pressed a key on the hero: wait until they have been idle a moment.
        long idle=SystemClock.uptimeMillis()-lastKeyAt;
        if(idle<HERO_IDLE_MS){handler.postDelayed(this,HERO_IDLE_MS-idle);return;}
        heroIndex=(heroIndex+1)%featured.size();bindHero(true);
        handler.postDelayed(this,HERO_ROTATE_MS);
    }};
    private boolean canRotate(){return resumed && heroMode && tab.equals("Home") && !overlayUp && !nav.isOpen() && featured.size()>1 && hero!=null;}
    private void scheduleRotation(){handler.removeCallbacks(rotateHero);if(canRotate())handler.postDelayed(rotateHero,HERO_ROTATE_MS);}

    /* ---------------- Account ---------------- */

    /** "● Active · Expires Dec 31, 2027" from the real account response; hidden when unknown. */
    private void updateAccountPill(){
        String status=Api.prefs(this).getString("account_status","");String exp=Api.prefs(this).getString("account_exp_date","");
        if(!tab.equals("Home") || status.isEmpty()){accountPill.setVisibility(View.GONE);return;}
        SpannableStringBuilder s=new SpannableStringBuilder("●  ");
        boolean active="Active".equalsIgnoreCase(status);
        s.setSpan(new ForegroundColorSpan(active?0xFF6FCF97:Ui.GLOW),0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.append(active?"ACTIVE":status.toUpperCase(Locale.US));
        try{long secs=Long.parseLong(exp.trim());if(secs>0)s.append("   ·   Expires ").append(DateFormat.getDateInstance(DateFormat.MEDIUM,Locale.getDefault()).format(new Date(secs*1000)));}catch(Exception ignored){}
        accountPill.setText(s);accountPill.setVisibility(View.VISIBLE);accountPill.setAlpha(heroMode?1f:0f);
    }

    /* ---------------- Home adapter ---------------- */

    private static final int TYPE_HERO=100,TYPE_UTILITY=101;
    private final class SectionHolder extends RecyclerView.ViewHolder{final TextView title,tag;final HorizontalGridView list;HomeFeed.Section section;
        SectionHolder(LinearLayout v,TextView title,TextView tag,HorizontalGridView list){super(v);this.title=title;this.tag=tag;this.list=list;}}
    private final class HomeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>{
        HomeAdapter(){setHasStableIds(true);}
        @Override public int getItemCount(){return entries.size();}
        @Override public long getItemId(int p){Object e=entries.get(p);return e==HERO?1:e==UTILITY?2:((HomeFeed.Section)e).id.hashCode();}
        @Override public int getItemViewType(int p){Object e=entries.get(p);return e==HERO?TYPE_HERO:e==UTILITY?TYPE_UTILITY:((HomeFeed.Section)e).type;}
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent,int type){
            if(type==TYPE_HERO){View v=LayoutInflater.from(HomeActivity.this).inflate(R.layout.item_home_hero,parent,false);
                v.getLayoutParams().height=heroHeight;hero=new HeroHolder(v);return hero;}
            if(type==TYPE_UTILITY)return new RecyclerView.ViewHolder(utilityHeader()){};
            LinearLayout row=new LinearLayout(HomeActivity.this);row.setOrientation(LinearLayout.VERTICAL);row.setClipChildren(false);row.setClipToPadding(false);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1,-2));
            LinearLayout header=new LinearLayout(HomeActivity.this);header.setOrientation(LinearLayout.HORIZONTAL);header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(Ui.dp(HomeActivity.this,16),0,0,0);row.addView(header,new LinearLayout.LayoutParams(-1,Ui.dp(HomeActivity.this,34)));
            TextView title=Ui.heading(HomeActivity.this,"",20);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);header.addView(title);
            TextView tag=new TextView(HomeActivity.this);tag.setTextSize(10);tag.setTextColor(0xFFE8736F);tag.setLetterSpacing(0.16f);
            tag.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));tag.setBackground(outline());Ui.pad(tag,HomeActivity.this,7,2,7,2);
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.leftMargin=Ui.dp(HomeActivity.this,12);header.addView(tag,tp);
            HorizontalGridView list=new HorizontalGridView(HomeActivity.this);
            list.setClipChildren(false);list.setClipToPadding(false);list.setItemAnimator(null);list.setHorizontalSpacing(Ui.dp(HomeActivity.this,2));
            int pad=Ui.dp(HomeActivity.this,16-HomeCards.GLOW);
            list.setPadding(pad,Ui.dp(HomeActivity.this,5),Ui.dp(HomeActivity.this,40),Ui.dp(HomeActivity.this,5));
            RecyclerView.RecycledViewPool pool=pools.get(type);if(pool==null){pool=new RecyclerView.RecycledViewPool();pools.put(type,pool);}
            list.setRecycledViewPool(pool);
            // The selected card stays at the left edge; the row slides under it.
            list.setWindowAlignment(BaseGridView.WINDOW_ALIGN_BOTH_EDGE);
            list.setWindowAlignmentOffsetPercent(BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);
            list.setWindowAlignmentOffset(pad);list.setItemAlignmentOffsetPercent(0f);list.setItemAlignmentOffset(0);
            row.addView(list,new LinearLayout.LayoutParams(-1,HomeCards.listHeight(HomeActivity.this,type)+Ui.dp(HomeActivity.this,10)));
            SectionHolder h=new SectionHolder(row,title,tag,list);
            list.setOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener(){
                @Override public void onChildViewHolderSelected(RecyclerView parent,RecyclerView.ViewHolder child,int position,int sub){
                    if(h.section!=null && position>=0)h.section.selected=position;}
            });
            return h;
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder,int position){
            int selected=homeRows.getSelectedPosition();
            holder.itemView.setAlpha(!heroMode && position<selected?0f:1f);
            if(holder instanceof HeroHolder){bindHero(false);return;}
            if(!(holder instanceof SectionHolder)){bindUtility(holder.itemView);return;}
            SectionHolder h=(SectionHolder)holder;HomeFeed.Section s=(HomeFeed.Section)entries.get(position);
            h.title.setText(s.title);h.tag.setVisibility(s.tag==null?View.GONE:View.VISIBLE);if(s.tag!=null)h.tag.setText(s.tag);
            int keep=Math.min(s.selected,Math.max(0,s.items.size()-1));
            h.section=null;
            if(s.adapter==null)s.adapter=HomeCards.adapter(HomeActivity.this,s,HomeActivity.this);
            if(h.list.getAdapter()!=s.adapter)h.list.setAdapter(s.adapter);
            h.list.setSelectedPosition(keep);h.section=s;s.selected=keep;
        }
        @Override public void onViewRecycled(RecyclerView.ViewHolder holder){
            if(holder instanceof SectionHolder){((SectionHolder)holder).section=null;((SectionHolder)holder).list.setAdapter(null);}
        }
    }
    private GradientDrawable outline(){GradientDrawable d=new GradientDrawable();d.setCornerRadius(Ui.dp(this,3));d.setStroke(Ui.dp(this,1),0x99CE4B4A);return d;}

    /* ---------------- Search header ---------------- */

    private View utilityHeader(){
        LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setGravity(Gravity.BOTTOM);
        v.setPadding(Ui.dp(this,16),Ui.safeY(this),Ui.dp(this,48),Ui.dp(this,18));
        v.setLayoutParams(new RecyclerView.LayoutParams(-1,keyline));return v;
    }
    private void bindUtility(View view){
        LinearLayout v=(LinearLayout)view;v.removeAllViews();
        TextView title=Ui.heading(this,"Search",34);v.addView(title);
        TextView hint=Ui.text(this,"Find any movie or TV show in Flix Town. Select the field and press OK to type.",15);hint.setTextColor(Ui.TEXT_2);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-2,-2);hp.topMargin=Ui.dp(this,4);v.addView(hint,hp);
        EditText query=new EditText(this);query.setHint("Movie or show title");query.setTextColor(Ui.TEXT);query.setText(searchText);
        query.setHintTextColor(Ui.TEXT_3);query.setSingleLine(true);query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setBackgroundResource(R.drawable.edit_field);query.setPadding(Ui.dp(this,18),0,Ui.dp(this,18),0);query.setTextSize(18);
        query.setOnFocusChangeListener((x,f)->{if(f)lastContentFocus=x;});
        LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(Ui.dp(this,460),Ui.dp(this,52));qp.topMargin=Ui.dp(this,16);v.addView(query,qp);
        query.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){searchText=s.toString();
                // Only the results row changes; the header (and the field being typed in) is never rebound.
                boolean had=entries.size()>1,has=searchText.trim().length()>1;
                while(entries.size()>1)entries.remove(entries.size()-1);
                if(has)entries.add(searchSection(searchText));
                if(had && has)homeAdapter.notifyItemChanged(1);else if(has)homeAdapter.notifyItemInserted(1);else if(had)homeAdapter.notifyItemRemoved(1);}
            public void afterTextChanged(android.text.Editable e){} });
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
            lastKeyAt=SystemClock.uptimeMillis();
            if(overlayUp){
                if(key==KeyEvent.KEYCODE_BACK){if(event.getRepeatCount()==0)confirmExit();return true;}
                if(startupActions.getVisibility()!=View.VISIBLE)return true;
                return super.dispatchKeyEvent(event);
            }
            if(key==KeyEvent.KEYCODE_BACK){
                if(event.getRepeatCount()>0)return true;
                if(nav.isOpen()){closeMenu();return true;}
                if(!tab.equals("Home")){selectTab("Home");focusDefault();return true;}
                // Browsing sections: Back glides up to the hero first.
                if(!heroMode && homeRows.hasFocus()){homeRows.setSelectedPositionSmooth(0);return true;}
                confirmExit();return true;
            }
            if(key==KeyEvent.KEYCODE_DPAD_LEFT && !nav.isOpen()){View focus=getCurrentFocus();
                boolean editing=focus instanceof EditText && ((EditText)focus).getSelectionStart()>0;
                if(focus!=null && !editing){View left=focus.focusSearch(View.FOCUS_LEFT);
                    boolean firstFilter=filters.getChildCount()>0 && focus==filters.getChildAt(0);
                    if(left==null || left==focus || firstFilter || !isDescendant(content,left)){openMenu();return true;}}
                else if(focus==null){openMenu();return true;}
            }
        }
        return super.dispatchKeyEvent(event);
    }
    private static boolean isDescendant(ViewGroup parent,View view){
        for(Object p=view.getParent();p instanceof View;p=((View)p).getParent())if(p==parent)return true;return false;}
    @Override protected void onDestroy(){destroyed=true;StartupRefresh.unlisten(this);Ratings.unlisten(ratingsChanged);handler.removeCallbacksAndMessages(null);
        if(sweep!=null)sweep.cancel();if(exitDialog!=null)exitDialog.dismiss();if(updates!=null)updates.destroy();super.onDestroy();}
}

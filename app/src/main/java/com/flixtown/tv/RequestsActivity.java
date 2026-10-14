package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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
 * Requests: search movies and series, see whether they are available or already requested, request
 * what is missing (one or more seasons for a series) and follow your own requests.
 *
 * Everything goes through RequestsApi (OnePanel, which talks to Seerr). Nothing here runs unless
 * this screen is open: no startup or Home calls, no polling. If the service cannot be reached the
 * screen says "Requests are temporarily unavailable." and the rest of Flix Town is unaffected.
 */
public class RequestsActivity extends Activity {
    private static final long SEARCH_DELAY_MS=700;
    private static final int MAX_PAGES=10;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private FrameLayout root;private EditText field;private TextView status;private VerticalGridView grid;
    private Button tabSearch,tabMine;private boolean mine;
    private final List<Entry> entries=new ArrayList<>();private Adapter adapter;
    private String query="";private int page,pages,generation;private boolean loading;
    /** Titles on this TV's own catalog, by "movie:tmdb" / "tv:tmdb" (only a hint for Watch). */
    private final Map<String,Catalog.Item> local=new HashMap<>();
    private Details details;

    /** One card: a search result or one of my requests. */
    static final class Entry {
        String type,title,year,poster,state;int id;int[] seasons=new int[0];long requestedAt;
    }

    @Override public void onCreate(Bundle b){super.onCreate(b);
        Images.init(this);
        for(Catalog.Item item:Catalog.Store.movies)if(!item.tmdbId.isEmpty())local.put("movie:"+item.tmdbId,item);
        for(Catalog.Item item:Catalog.Store.series)if(!item.tmdbId.isEmpty())local.put("tv:"+item.tmdbId,item);
        draw();showSearch();
    }

    /* ---------------- layout ---------------- */

    private void draw(){
        root=new FrameLayout(this);root.setBackgroundColor(Ui.BG);setContentView(root);
        View glow=new View(this);glow.setBackgroundResource(R.drawable.renew_glow);root.addView(glow,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout page=Ui.column(this);page.setClipChildren(false);page.setClipToPadding(false);
        page.setPadding(Ui.safeX(this),Ui.safeY(this),Ui.safeX(this),0);
        root.addView(page,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout header=Ui.row(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setClipChildren(false);
        page.addView(header,new LinearLayout.LayoutParams(-1,-2));
        TextView title=Ui.heading(this,"Requests",30);header.addView(title,new LinearLayout.LayoutParams(-2,-2));
        field=new EditText(this);
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(0,Ui.dp(this,46),1);fp.leftMargin=Ui.dp(this,24);fp.rightMargin=Ui.dp(this,8);
        header.addView(field,fp);
        tabSearch=Ui.button(this,"Search");tabMine=Ui.button(this,"My Requests");
        for(Button t:new Button[]{tabSearch,tabMine}){t.setTextSize(16);t.setId(View.generateViewId());
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,46));p.leftMargin=Ui.dp(this,10);header.addView(t,p);}
        tabSearch.setOnClickListener(v->showSearch());tabMine.setOnClickListener(v->showMine());

        field.setHint("Search movies and TV shows");field.setSingleLine(true);field.setTextSize(17);
        field.setTextColor(Ui.TEXT);field.setHintTextColor(Ui.TEXT_3);field.setBackgroundResource(R.drawable.edit_field);
        field.setPadding(Ui.dp(this,18),0,Ui.dp(this,18),0);field.setId(View.generateViewId());
        field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void afterTextChanged(Editable s){focusResults=false;handler.removeCallbacks(debounced);handler.postDelayed(debounced,SEARCH_DELAY_MS);}
        });
        // The keyboard's Search key: search now, close the keyboard and go to the results (now, or when they arrive).
        field.setOnEditorActionListener((v,action,event)->{handler.removeCallbacks(debounced);focusResults=true;startSearch(true);hideKeyboard();
            if(!entries.isEmpty()){focusResults=false;grid.post(this::focusGrid);}return true;});
        field.setOnFocusChangeListener((v,has)->{if(!has)hideKeyboard();});
        // D-pad on the box itself (keyboard closed): Down to the results, Up to the tabs, on every Android version.
        field.setOnKeyListener((v,code,ev)->{
            if(ev.getAction()!=KeyEvent.ACTION_DOWN)return false;
            if(code==KeyEvent.KEYCODE_DPAD_DOWN&&!entries.isEmpty()){hideKeyboard();focusGrid();return true;}
            if(code==KeyEvent.KEYCODE_DPAD_UP){hideKeyboard();tabSearch.requestFocus();return true;}
            return false;});
        field.setNextFocusUpId(tabSearch.getId());

        // Results: the same leanback grid as Movies and TV Shows, four posters across. The status line
        // sits over the grid so it never pushes the cards down.
        // The area clips at its top so rows scrolled up never draw over the header; the grid's own top
        // padding leaves room for the focused card's glow and zoom.
        FrameLayout area=new FrameLayout(this);area.setClipChildren(true);area.setClipToPadding(true);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,0,1);ap.topMargin=Ui.dp(this,4);page.addView(area,ap);
        grid=new VerticalGridView(this);grid.setClipChildren(false);grid.setClipToPadding(false);grid.setItemAnimator(null);
        grid.setNumColumns(COLUMNS);grid.setHorizontalSpacing(0);grid.setVerticalSpacing(Ui.dp(this,4));
        grid.setPadding(0,Ui.dp(this,GRID_TOP_DP),0,Ui.safeY(this));grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        sizeCards();
        adapter=new Adapter();grid.setAdapter(adapter);
        area.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        status=Ui.text(this,"",16);status.setTextColor(Ui.TEXT_2);Ui.pad(status,this,PosterAdapter.GLOW_DP,6,0,0);
        area.addView(status,new FrameLayout.LayoutParams(-1,-2));
        details=new Details();
    }
    private final Runnable debounced=()->startSearch(false);
    static final int COLUMNS=4,GRID_TOP_DP=16;
    static final String SUBMITTED="Request submitted. Track its status in My Requests.";
    /** Text under a poster: title (16sp), year · type (14sp) and the request state (14sp). */
    static final int TEXT_DP=70;
    private int cardWidth,artWidth,artHeight;
    /**
     * Four columns across the page, 2:3 artwork as on Movies. The artwork is also kept short enough
     * that one whole card (glow, poster and its text) fits under the header at 720p and 1080p.
     */
    private void sizeCards(){
        android.util.DisplayMetrics m=getResources().getDisplayMetrics();int glow=Ui.dp(this,PosterAdapter.GLOW_DP);
        cardWidth=(m.widthPixels-2*Ui.safeX(this))/COLUMNS;
        int gridHeight=m.heightPixels-Ui.safeY(this)-Ui.dp(this,46+4)-Ui.dp(this,GRID_TOP_DP)-Ui.safeY(this);
        artWidth=cardWidth-2*glow;artHeight=artWidth*3/2;
        int maxArt=gridHeight-2*glow-Ui.dp(this,TEXT_DP)-Ui.dp(this,8);
        if(artHeight>maxArt){artHeight=maxArt;artWidth=artHeight*2/3;}
    }
    private boolean focusResults;

    private void showSearch(){
        mine=false;styleTabs();field.setVisibility(View.VISIBLE);
        generation++;entries.clear();adapter.notifyDataSetChanged();query="";
        if(RequestsApi.url(this).isEmpty()){status.setText(RequestsApi.UNAVAILABLE);tabSearch.requestFocus();return;}
        status.setText("Type a title to search. Press OK on the search box to open the keyboard.");
        String text=field.getText().toString().trim();
        if(text.length()>=2)startSearch(true);
        field.requestFocus();
    }
    private void showMine(){
        mine=true;styleTabs();field.setVisibility(View.GONE);hideKeyboard();
        generation++;entries.clear();adapter.notifyDataSetChanged();page=0;pages=1;
        if(RequestsApi.url(this).isEmpty()){status.setText(RequestsApi.UNAVAILABLE);tabMine.requestFocus();return;}
        status.setText("Loading your requests…");loadHistory(1);tabMine.requestFocus();
    }
    private void styleTabs(){Ui.styleButton(tabSearch,mine?Ui.SECONDARY:Ui.SELECTED);Ui.styleButton(tabMine,mine?Ui.SELECTED:Ui.SECONDARY);}

    /* ---------------- search and history ---------------- */

    private void startSearch(boolean now){
        String text=field.getText().toString().trim();
        if(mine)return;
        if(text.length()<2){if(text.isEmpty()){generation++;entries.clear();adapter.notifyDataSetChanged();query="";
            status.setText("Type a title to search.");}return;}
        if(text.length()>80)text=text.substring(0,80);
        if(text.equals(query) && !entries.isEmpty())return;
        query=text;page=0;pages=1;generation++;entries.clear();adapter.notifyDataSetChanged();
        status.setText("Searching…");loadSearch(1);
    }
    private void loadSearch(int p){
        if(loading||p>Math.min(pages,MAX_PAGES))return;
        loading=true;int token=generation;String q=query;
        Api.IO.execute(()->{RequestsApi.Reply r=RequestsApi.search(this,q,p);
            runOnUiThread(()->{loading=false;if(token!=generation||isFinishing())return;
                if(!r.ok){if(entries.isEmpty())status.setText(r.message);return;}
                page=p;pages=Math.max(1,r.body.optInt("pages",1));
                JSONArray list=r.body.optJSONArray("results");int before=entries.size();
                for(int i=0;list!=null&&i<list.length();i++){JSONObject o=list.optJSONObject(i);if(o==null)continue;
                    Entry e=new Entry();e.type=o.optString("type");e.id=o.optInt("id");e.title=o.optString("title");
                    e.year=o.optString("year");e.poster=o.optString("poster");e.state=o.optString("state");
                    if(localMatch(e)!=null && "movie".equals(e.type))e.state="local";
                    entries.add(e);}
                adapter.notifyItemRangeInserted(before,entries.size()-before);
                if(BuildConfig.DEMO)Log.i("FlixTownQA","requests search q="+q+" page="+p+" results="+entries.size());
                status.setText(entries.isEmpty()?"No movies or TV shows match “"+q+"”.":"");
                if(focusResults && p==1){focusResults=false;if(!entries.isEmpty())grid.post(this::focusGrid);}
            });});
    }
    private void loadHistory(int p){
        if(loading||p>pages)return;
        loading=true;int token=generation;
        Api.IO.execute(()->{RequestsApi.Reply r=RequestsApi.history(this,p);
            runOnUiThread(()->{loading=false;if(token!=generation||isFinishing())return;
                if(!r.ok){if(entries.isEmpty())status.setText(r.message);return;}
                page=p;pages=r.body.optBoolean("has_more")?p+1:p;
                JSONArray list=r.body.optJSONArray("requests");int before=entries.size();
                for(int i=0;list!=null&&i<list.length();i++){JSONObject o=list.optJSONObject(i);if(o!=null)entries.add(historyEntry(o));}
                if(BuildConfig.DEMO)Log.i("FlixTownQA","requests history loaded page="+p);
                adapter.notifyItemRangeInserted(before,entries.size()-before);
                status.setText(entries.isEmpty()?"You haven't requested anything yet. Use Search to find a movie or show.":"");
                if(before==0 && !entries.isEmpty())grid.post(this::focusGrid);
            });});
    }
    /** One My Requests card. A movie this TV's catalog already has is "In Flix Town", as on search cards and in Details. */
    private Entry historyEntry(JSONObject o){
        Entry e=new Entry();e.type=o.optString("type");e.id=o.optInt("id");e.title=o.optString("title");
        e.year=o.optString("year");e.poster=o.optString("poster");e.state=o.optString("state");
        e.requestedAt=o.optLong("requested_at");JSONArray s=o.optJSONArray("seasons");
        if(s!=null){e.seasons=new int[s.length()];for(int k=0;k<s.length();k++)e.seasons[k]=s.optInt(k);}
        if(localMatch(e)!=null && "movie".equals(e.type))e.state="local";
        return e;
    }
    /**
     * Back on My Requests (from a title, or the TV coming back to the app): ask once for the latest
     * states and update the cards in place, so focus and scroll stay where they were. No polling.
     */
    private void refreshMine(){
        if(!mine||loading||entries.isEmpty()||(details!=null&&details.isOpen()))return;
        int token=generation;
        Api.IO.execute(()->{RequestsApi.Reply r=RequestsApi.history(this,1);
            runOnUiThread(()->{if(token!=generation||isFinishing()||!r.ok)return;
                JSONArray list=r.body.optJSONArray("requests");
                for(int i=0;list!=null&&i<list.length();i++){JSONObject o=list.optJSONObject(i);if(o==null)continue;
                    Entry fresh=historyEntry(o);
                    for(int k=0;k<entries.size();k++){Entry e=entries.get(k);
                        if(e.type.equals(fresh.type)&&e.id==fresh.id&&java.util.Arrays.equals(e.seasons,fresh.seasons)&&!e.state.equals(fresh.state)){
                            e.state=fresh.state;adapter.notifyItemChanged(k);}}}
                if(BuildConfig.DEMO)Log.i("FlixTownQA","requests history refreshed in place");
            });});
    }
    private boolean refreshAfterDetails;
    @Override protected void onRestart(){super.onRestart();
        if(details!=null&&details.isOpen())refreshAfterDetails=true;   // e.g. back from Watch: refresh when the details close
        else refreshMine();}
    /**
     * A TV request's state from the per-season states in Details, by the same rule as the server:
     * In Flix Town only when every requested season is; partly available when some requested
     * seasons are there; then approved, pending, declined. Other seasons of the show never count.
     */
    static String requestState(JSONArray seasons,int[] wanted){
        if(seasons==null||wanted==null||wanted.length==0)return null;
        java.util.Map<Integer,String> by=new java.util.HashMap<>();
        for(int i=0;i<seasons.length();i++){JSONObject s=seasons.optJSONObject(i);if(s!=null)by.put(s.optInt("number"),s.optString("state"));}
        int local=0,have=0,approved=0,pending=0,declined=0;
        for(int n:wanted){String st=by.get(n);if(st==null)st="pending";
            switch(st){case "local":local++;have++;break;case "available":have++;break;case "partial":have++;break;
                case "approved":approved++;break;case "declined":declined++;break;default:pending++;}}
        if(local==wanted.length)return "local";
        if(local>0)return "partial";
        if(have==wanted.length&&local==0){boolean all=true;for(int n:wanted)if(!"available".equals(by.get(n)))all=false;return all?"available":"partial";}
        if(have>0)return "partial";
        if(approved>0)return "approved";
        if(pending>0)return "pending";
        return declined>0?"declined":"pending";
    }
    /** Near the end of the grid: the next page (search) or more history. */
    private void maybeLoadMore(int position){
        if(position<entries.size()-adapterSpan()*2)return;
        if(mine)loadHistory(page+1);else loadSearch(page+1);
    }
    private int adapterSpan(){return COLUMNS;}
    /** Rebinds one card in place, so the card the details came from keeps its view and focus. */
    private void refreshCard(Entry e){int i=entries.indexOf(e);if(i>=0)adapter.notifyItemChanged(i);}
    private void focusGrid(){RecyclerView.ViewHolder h=grid.findViewHolderForAdapterPosition(0);if(h!=null){h.itemView.requestFocus();return;}
        grid.setSelectedPosition(0);grid.post(()->{RecyclerView.ViewHolder f=grid.findViewHolderForAdapterPosition(0);if(f!=null)f.itemView.requestFocus();else grid.requestFocus();});}
    private Catalog.Item localMatch(Entry e){
        Catalog.Item item=local.get(e.type+":"+e.id);
        if(item==null)return null;
        if("movie".equals(e.type)){   // the catalog's TMDB id is a hint: the year must agree too
            int y;try{y=Integer.parseInt(e.year);}catch(Exception x){return null;}
            if(item.year<=1900||Math.abs(item.year-y)>1)return null;
        }
        return item;
    }
    private void hideKeyboard(){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(),0);}

    static String label(String state){
        switch(state){
            case "available":return "Available";
            case "partial":return "Partly available";
            case "approved":return "Approved · processing";
            case "pending":return "Pending approval";
            case "declined":return "Declined";
            case "unavailable":return "Not available";
            case "removed":return "Removed";
            case "local":return "In Flix Town";
            default:return "Requestable";
        }
    }
    static int color(String state){
        switch(state){
            case "available":case "local":return 0xFF6FCF97;
            case "partial":return 0xFF8FD3BE;
            case "approved":return 0xFF6FB5CF;
            case "pending":return Ui.GOLD;
            case "declined":return 0xFFF0A29F;
            case "unavailable":case "removed":return Ui.TEXT_3;
            default:return Ui.TEXT_2;
        }
    }

    /* ---------------- cards ---------------- */

    static final class Card extends RecyclerView.ViewHolder{
        final FrameLayout poster;final ImageView image;final TextView fallback,name,meta,badge;final GlowDrawable glow;final GradientDrawable ring;
        Card(View v,FrameLayout poster,ImageView image,TextView fallback,TextView name,TextView meta,TextView badge,GlowDrawable glow,GradientDrawable ring){
            super(v);this.poster=poster;this.image=image;this.fallback=fallback;this.name=name;this.meta=meta;this.badge=badge;this.glow=glow;this.ring=ring;}
    }
    private final class Adapter extends RecyclerView.Adapter<Card>{
        @Override public int getItemCount(){return entries.size();}
        @Override public Card onCreateViewHolder(ViewGroup parent,int type){
            Activity a=RequestsActivity.this;int glow=Ui.dp(a,PosterAdapter.GLOW_DP),w=artWidth,h=artHeight;
            LinearLayout card=new LinearLayout(a);card.setOrientation(LinearLayout.VERTICAL);card.setClipChildren(false);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setFocusable(true);card.setFocusableInTouchMode(true);card.setClickable(true);
            card.setLayoutParams(new RecyclerView.LayoutParams(cardWidth,h+2*glow+Ui.dp(a,TEXT_DP)));
            FrameLayout poster=new FrameLayout(a);poster.setPadding(glow,glow,glow,glow);
            GlowDrawable glowDrawable=new GlowDrawable(glow,Ui.dp(a,6),Ui.GLOW);poster.setBackground(glowDrawable);
            card.addView(poster,new LinearLayout.LayoutParams(w+2*glow,h+2*glow));
            FrameLayout art=new FrameLayout(a);art.setBackgroundColor(0xFF17171C);int radius=Ui.dp(a,6);
            art.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),radius);}});
            art.setClipToOutline(true);poster.addView(art,new FrameLayout.LayoutParams(-1,-1));
            TextView fallback=Ui.text(a,"",15);fallback.setTextColor(Ui.TEXT_2);fallback.setGravity(Gravity.CENTER);fallback.setMaxLines(4);
            fallback.setEllipsize(TextUtils.TruncateAt.END);Ui.pad(fallback,a,8,8,8,8);art.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
            ImageView image=new ImageView(a);image.setScaleType(ImageView.ScaleType.CENTER_CROP);art.addView(image,new FrameLayout.LayoutParams(-1,-1));
            GradientDrawable ring=new GradientDrawable();ring.setCornerRadius(radius);ring.setStroke(Ui.dp(a,2),0xFFE06A66);ring.setColor(0);
            LinearLayout text=Ui.column(a);text.setPadding(glow,Ui.dp(a,4),glow,0);card.addView(text,new LinearLayout.LayoutParams(-1,Ui.dp(a,TEXT_DP)));
            TextView name=Ui.text(a,"",16);name.setTextColor(Ui.TEXT_2);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);name.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView meta=Ui.text(a,"",14);meta.setTextColor(Ui.TEXT_2);meta.setSingleLine(true);meta.setEllipsize(TextUtils.TruncateAt.END);meta.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView badge=Ui.text(a,"",14);badge.setSingleLine(true);badge.setEllipsize(TextUtils.TruncateAt.END);badge.setGravity(Gravity.CENTER_HORIZONTAL);
            text.addView(name,new LinearLayout.LayoutParams(-1,-2));text.addView(meta,new LinearLayout.LayoutParams(-1,-2));text.addView(badge,new LinearLayout.LayoutParams(-1,-2));
            Card c=new Card(card,poster,image,fallback,name,meta,badge,glowDrawable,ring);
            // Up from the top row goes back to the search box (or the tabs in My Requests), whichever column.
            card.setOnKeyListener((v,code,ev)->{if(ev.getAction()!=KeyEvent.ACTION_DOWN||code!=KeyEvent.KEYCODE_DPAD_UP)return false;
                int p=c.getBindingAdapterPosition();if(p<0||p>=COLUMNS)return false;
                (mine?tabMine:field).requestFocus();return true;});
            card.setOnFocusChangeListener((v,f)->{c.glow.setOn(f);art.setForeground(f?ring:null);name.setTextColor(f?Ui.TEXT:Ui.TEXT_2);
                float s=f?PosterAdapter.FOCUS_SCALE:1f;poster.animate().scaleX(s).scaleY(s).setDuration(120).start();
                if(f){int p=c.getBindingAdapterPosition();if(p>=0)maybeLoadMore(p);}});
            card.setOnClickListener(v->{int p=c.getBindingAdapterPosition();if(p>=0&&p<entries.size())details.open(entries.get(p),v);});
            return c;
        }
        @Override public void onBindViewHolder(Card c,int position){
            Entry e=entries.get(position);
            c.fallback.setText(e.title);c.name.setText(e.title);
            String kind="tv".equals(e.type)?"TV Show":"Movie";
            if(mine){String when=e.requestedAt>0?DateFormat.getDateInstance(DateFormat.MEDIUM,Locale.getDefault()).format(new Date(e.requestedAt*1000)):"";
                c.meta.setText(HomeCards.join(when,seasonsText(e.seasons)));}
            else c.meta.setText(HomeCards.join(e.year,kind));
            c.badge.setText(label(e.state));c.badge.setTextColor(color(e.state));
            c.itemView.setContentDescription(e.title+". "+kind+(e.year.isEmpty()?"":", "+e.year)+". "+label(e.state));
            Images.load(c.image,e.poster,artWidth);
        }
    }
    static String seasonsText(int[] seasons){
        if(seasons==null||seasons.length==0)return "";
        StringBuilder b=new StringBuilder();for(int n:seasons){if(b.length()>0)b.append(", ");b.append("S").append(n);}
        return b.toString();
    }

    /* ---------------- details, seasons and the request ---------------- */

    private final class Details{
        final FrameLayout overlay;final ImageView backdrop,poster;final TextView title,meta,badge,overview,note;
        final LinearLayout actions,seasonList,seasonActions;final ScrollView seasonScroll;
        View from;Entry entry;JSONObject data;final List<Integer> chosen=new ArrayList<>();int token;

        Details(){
            Activity a=RequestsActivity.this;
            overlay=new FrameLayout(a);overlay.setBackgroundColor(0xFA08080B);overlay.setClickable(true);overlay.setVisibility(View.GONE);
            backdrop=new ImageView(a);backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);backdrop.setAlpha(0.32f);
            overlay.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
            View scrim=new View(a);scrim.setBackgroundResource(R.drawable.details_scrim);overlay.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
            LinearLayout row=Ui.row(a);row.setClipChildren(false);row.setPadding(Ui.safeX(a),Ui.safeY(a),Ui.safeX(a),Ui.safeY(a));
            overlay.addView(row,new FrameLayout.LayoutParams(-1,-1));
            poster=new ImageView(a);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);poster.setBackgroundColor(0xFF17171C);
            int radius=Ui.dp(a,8);poster.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),radius);}});
            poster.setClipToOutline(true);
            row.addView(poster,new LinearLayout.LayoutParams(Ui.dp(a,200),Ui.dp(a,300)));
            LinearLayout col=Ui.column(a);col.setClipChildren(false);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-1,1);cp.leftMargin=Ui.dp(a,32);row.addView(col,cp);
            title=Ui.heading(a,"",30);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);col.addView(title);
            meta=Ui.text(a,"",16);meta.setTextColor(Ui.TEXT_2);col.addView(meta);
            badge=Ui.heading(a,"",18);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.topMargin=Ui.dp(a,8);col.addView(badge,bp);
            overview=Ui.text(a,"",16);overview.setLineSpacing(0,1.2f);overview.setMaxLines(7);overview.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,-2);op.topMargin=Ui.dp(a,12);col.addView(overview,op);
            seasonScroll=new ScrollView(a);seasonScroll.setClipChildren(false);seasonScroll.setClipToPadding(false);seasonScroll.setVisibility(View.GONE);
            seasonList=Ui.column(a);seasonList.setClipChildren(false);seasonList.setPadding(Ui.dp(a,4),Ui.dp(a,4),Ui.dp(a,4),Ui.dp(a,4));
            seasonScroll.addView(seasonList);
            LinearLayout.LayoutParams ssp=new LinearLayout.LayoutParams(Ui.dp(a,520),0,1);ssp.topMargin=Ui.dp(a,10);col.addView(seasonScroll,ssp);
            note=Ui.text(a,"",15);note.setTextColor(Ui.TEXT_2);note.setMaxLines(3);
            LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=Ui.dp(a,10);col.addView(note,np);
            actions=Ui.row(a);actions.setClipChildren(false);
            LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.topMargin=Ui.dp(a,12);col.addView(actions,ap);
            seasonActions=Ui.row(a);seasonActions.setClipChildren(false);seasonActions.setVisibility(View.GONE);col.addView(seasonActions,new LinearLayout.LayoutParams(-1,-2));
            root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        }
        boolean isOpen(){return overlay.getVisibility()==View.VISIBLE;}
        void open(Entry e,View from){
            this.from=from;entry=e;data=null;token++;
            title.setText(e.title);meta.setText(HomeCards.join(e.year,"tv".equals(e.type)?"TV Show":"Movie"));
            setBadge(e.state);overview.setText("");note.setText("Loading…");note.setTextColor(Ui.TEXT_2);
            Images.load(poster,e.poster,Ui.dp(RequestsActivity.this,200));backdrop.setImageDrawable(null);
            closeSeasons();setButtons(new String[]{"Back"},new Runnable[]{this::close});
            grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);field.setFocusable(false);
            tabSearch.setFocusable(false);tabMine.setFocusable(false);
            overlay.setVisibility(View.VISIBLE);
            load();
        }
        void load(){
            int t=token;Entry e=entry;
            Api.IO.execute(()->{RequestsApi.Reply r=RequestsApi.details(RequestsActivity.this,e.type,e.id);
                runOnUiThread(()->{if(t!=token||!isOpen())return;
                    if(!r.ok){note.setText(r.message);note.setTextColor(r.unavailable()?Ui.TEXT_2:0xFFF0A29F);return;}
                    data=r.body.optJSONObject("details");if(data==null){note.setText(RequestsApi.UNAVAILABLE);return;}
                    show();});});
        }
        void show(){
            title.setText(data.optString("title",entry.title));
            meta.setText(HomeCards.join(data.optString("year"),"tv".equals(entry.type)?"TV Show":"Movie"));
            overview.setText(data.optString("overview"));
            String bd=data.optString("backdrop");if(!bd.isEmpty())Images.load(backdrop,bd,Ui.dp(RequestsActivity.this,780));
            String state=data.optString("state","requestable");
            Catalog.Item there=localMatch(entry);
            boolean movieHere=there!=null&&"movie".equals(entry.type);
            if(movieHere)state="local";
            if(mine&&"tv".equals(entry.type)){String own=requestState(data.optJSONArray("seasons"),entry.seasons);if(own!=null)state=own;}
            setBadge(state);entry.state=state;refreshCard(entry);
            boolean can=data.optBoolean("can_request")&&!movieHere;
            List<String> labels=new ArrayList<>();List<Runnable> runs=new ArrayList<>();
            if(can){labels.add("tv".equals(entry.type)?"Choose seasons":"Request");runs.add("tv".equals(entry.type)?this::openSeasons:()->submit(null));}
            if(there!=null){labels.add("Watch in Flix Town");runs.add(()->watch(there));}
            labels.add("Back");runs.add(this::close);
            setButtons(labels.toArray(new String[0]),runs.toArray(new Runnable[0]));
            note.setTextColor(Ui.TEXT_2);
            if(movieHere)note.setText("This movie is already in Flix Town.");
            else if(can)note.setText("tv".equals(entry.type)?"Choose the seasons you want. Requests are reviewed before they are added.":"Requests are reviewed before they are added.");
            else note.setText(explain(state));
        }
        String explain(String state){
            switch(state){
                case "available":return "This is already available.";
                case "partial":return "Part of this is already available.";
                case "approved":return "This has been approved and is being prepared.";
                case "pending":return "This has already been requested and is waiting for approval.";
                case "declined":return "This request was declined.";
                case "unavailable":return "This title can't be requested.";
                case "local":return "This is already in Flix Town.";
                default:return "";
            }
        }
        void setBadge(String state){badge.setText("●  "+label(state));badge.setTextColor(color(state));}
        void setButtons(String[] labels,Runnable[] runs){
            actions.removeAllViews();Button first=null;
            for(int i=0;i<labels.length;i++){Runnable run=runs[i];
                Button b=i==0&&!Ui.dismissive(labels[i])?Ui.primaryButton(RequestsActivity.this,labels[i]):Ui.button(RequestsActivity.this,labels[i]);
                b.setTextSize(18);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(RequestsActivity.this,54));
                if(i>0)p.leftMargin=Ui.dp(RequestsActivity.this,12);b.setMinWidth(Ui.dp(RequestsActivity.this,150));
                actions.addView(b,p);b.setOnClickListener(v->run.run());if(first==null)first=b;}
            actions.setVisibility(View.VISIBLE);
            if(first!=null){Button f=first;f.post(f::requestFocus);}
        }
        /* Season picker: real seasons only; seasons that cannot be requested are shown but not focusable. */
        void openSeasons(){
            chosen.clear();seasonList.removeAllViews();
            JSONArray seasons=data.optJSONArray("seasons");View firstFocus=null,lastFocus=null;
            for(int i=0;seasons!=null&&i<seasons.length();i++){JSONObject s=seasons.optJSONObject(i);if(s==null)continue;
                int n=s.optInt("number");String st=s.optString("state");boolean can="requestable".equals(st);
                Button b=Ui.button(RequestsActivity.this,"");b.setTextSize(17);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
                b.setPadding(Ui.dp(RequestsActivity.this,20),0,Ui.dp(RequestsActivity.this,20),0);
                String name=s.optString("name","Season "+n)+"  ·  "+s.optInt("episodes")+" episodes";
                Runnable paint=()->{boolean on=chosen.contains(n);
                    b.setText((can?(on?"✓  ":"○  "):"     ")+name+(can?"":"   —   "+label(st)));
                    Ui.styleButton(b,on?Ui.SELECTED:Ui.SECONDARY);b.setTextColor(can?Ui.TEXT:Ui.TEXT_3);};
                paint.run();b.setEnabled(can);b.setFocusable(can);
                b.setOnClickListener(v->{if(chosen.contains(n))chosen.remove((Integer)n);else chosen.add(n);paint.run();updateSeasonButton();});
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,Ui.dp(RequestsActivity.this,50));p.bottomMargin=Ui.dp(RequestsActivity.this,6);
                b.setId(View.generateViewId());
                seasonList.addView(b,p);if(can&&firstFocus==null)firstFocus=b;if(can)lastFocus=b;}
            overview.setVisibility(View.GONE);actions.setVisibility(View.GONE);seasonScroll.setVisibility(View.VISIBLE);
            seasonActions.removeAllViews();seasonActions.setVisibility(View.VISIBLE);
            Button send=Ui.primaryButton(RequestsActivity.this,"Request");send.setTextSize(18);send.setTag("send");
            Button cancel=Ui.button(RequestsActivity.this,"Cancel");cancel.setTextSize(18);
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(RequestsActivity.this,54));sp.topMargin=Ui.dp(RequestsActivity.this,10);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(RequestsActivity.this,54));cp.topMargin=Ui.dp(RequestsActivity.this,10);cp.leftMargin=Ui.dp(RequestsActivity.this,12);
            send.setMinWidth(Ui.dp(RequestsActivity.this,200));cancel.setMinWidth(Ui.dp(RequestsActivity.this,150));
            seasonActions.addView(send,sp);seasonActions.addView(cancel,cp);
            // Remote paths: Down from the last season that can be requested goes to Request, Up comes back to it.
            send.setId(View.generateViewId());cancel.setId(View.generateViewId());
            if(lastFocus!=null){lastFocus.setNextFocusDownId(send.getId());send.setNextFocusUpId(lastFocus.getId());cancel.setNextFocusUpId(lastFocus.getId());}
            send.setOnClickListener(v->{if(chosen.isEmpty()){note.setText("Choose at least one season.");return;}
                int[] list=new int[chosen.size()];for(int i=0;i<list.length;i++)list[i]=chosen.get(i);java.util.Arrays.sort(list);submit(list);});
            cancel.setOnClickListener(v->{closeSeasons();show();});
            note.setText("Select seasons with OK, then choose Request.");updateSeasonButton();
            if(firstFocus!=null){View f=firstFocus;f.post(f::requestFocus);}else cancel.requestFocus();
        }
        void updateSeasonButton(){
            View send=seasonActions.findViewWithTag("send");
            if(send instanceof Button)((Button)send).setText(chosen.isEmpty()?"Request":"Request "+chosen.size()+(chosen.size()==1?" season":" seasons"));
        }
        boolean seasonsOpen(){return seasonScroll.getVisibility()==View.VISIBLE;}
        void closeSeasons(){seasonScroll.setVisibility(View.GONE);seasonActions.setVisibility(View.GONE);overview.setVisibility(View.VISIBLE);}
        void submit(int[] seasons){
            int t=token;Entry e=entry;
            note.setTextColor(Ui.TEXT_2);note.setText("Sending your request…");
            setButtons(new String[]{"Back"},new Runnable[]{this::close});closeSeasons();
            Api.IO.execute(()->{RequestsApi.Reply r=RequestsApi.request(RequestsActivity.this,e.type,e.id,seasons);
                runOnUiThread(()->{if(t!=token||!isOpen())return;
                    if(BuildConfig.DEMO)Log.i("FlixTownQA","requests submit "+e.type+":"+e.id+" seasons="+seasonsText(seasons)+" ok="+r.ok+" error="+r.error);
                    if(r.ok){JSONObject req=r.body.optJSONObject("request");String st=req==null?"pending":req.optString("state","pending");
                        setBadge(st);e.state=st;refreshCard(e);note.setTextColor(0xFF6FCF97);
                        note.setText(SUBMITTED);
                        return;}
                    note.setTextColor(r.unavailable()?Ui.TEXT_2:0xFFF0A29F);note.setText(r.message);
                    if(r.error.equals("duplicate"))load();   // show what is already there
                });});
        }
        void watch(Catalog.Item item){
            Intent intent=new Intent(RequestsActivity.this,DetailsActivity.class);
            intent.putExtra("id",item.id);intent.putExtra("kind",item.kind);intent.putExtra("title",item.title);
            intent.putExtra("poster",item.poster);intent.putExtra("backdrop",item.backdrop);
            intent.putExtra("extension",item.extension);intent.putExtra("year",item.year);intent.putExtra("category",item.categoryId);
            startActivity(intent);
        }
        void close(){
            token++;overlay.setVisibility(View.GONE);
            grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);field.setFocusable(true);field.setFocusableInTouchMode(true);
            tabSearch.setFocusable(true);tabMine.setFocusable(true);
            if(from!=null&&from.isAttachedToWindow())from.requestFocus();else if(!entries.isEmpty())focusGrid();else(mine?tabMine:field).requestFocus();
            if(refreshAfterDetails){refreshAfterDetails=false;refreshMine();}
        }
        /** Back inside the overlay: seasons -> details -> the card it came from. */
        boolean back(){if(!isOpen())return false;if(seasonsOpen()){closeSeasons();show();}else close();return true;}
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getKeyCode()==KeyEvent.KEYCODE_BACK && details!=null && details.isOpen()){
            if(event.getAction()==KeyEvent.ACTION_UP)details.back();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onDestroy(){generation++;handler.removeCallbacksAndMessages(null);super.onDestroy();}
}

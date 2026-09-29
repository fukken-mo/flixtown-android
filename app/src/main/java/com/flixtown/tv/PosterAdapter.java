package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

/**
 * Poster cards for rows and grids.
 *
 * Every card reserves {@link #GLOW_DP} around the artwork for the focus glow, so the glow never
 * depends on parents not clipping and the grid's own padding keeps it inside the TV safe area.
 * Artwork is 2:3 at a fixed size supplied by the screen; focus only toggles a drawable and runs a
 * short scale animation, so it never remeasures or rebinds the list.
 */
final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> {
    interface Focus { void onFocus(Catalog.Item item); }
    static final int GLOW_DP=8;
    /** Artwork width for horizontal rows, in dp. Height is 1.5× (2:3 artwork). */
    static final int ROW_POSTER_DP=120;
    /** Two lines of 14sp title under grid posters. */
    static final int NAME_DP=42;
    static final float FOCUS_SCALE=1.05f;

    private final Activity activity;
    private final List<Catalog.Item> items;
    private final int artWidth,artHeight,glow;
    private final boolean names;
    private final Focus focus;

    static int rowListHeight(Activity a){return Ui.dp(a,ROW_POSTER_DP*3/2+2*GLOW_DP+12);}
    /** Horizontal padding for a row so the artwork (not the glow margin) lines up with the row title. */
    static int rowEdgePadding(Activity a,int contentEdgeDp){return Ui.dp(a,contentEdgeDp-GLOW_DP);}

    PosterAdapter(Activity activity,List<Catalog.Item> items,Focus focus){
        this(activity,items,Ui.dp(activity,ROW_POSTER_DP),Ui.dp(activity,ROW_POSTER_DP*3/2),false,focus);
    }
    PosterAdapter(Activity activity,List<Catalog.Item> items,int artWidth,int artHeight,boolean names,Focus focus) {
        this.activity=activity;this.items=items;this.artWidth=artWidth;this.artHeight=artHeight;this.names=names;this.focus=focus;
        this.glow=Ui.dp(activity,GLOW_DP);
        setHasStableIds(true);
    }
    @Override public long getItemId(int position) {
        Catalog.Item item=items.get(position);
        long id;try{id=Long.parseLong(item.id);}catch(NumberFormatException e){id=item.id.hashCode()&0xFFFFFFFFL;}
        return "series".equals(item.kind)?-1-id:id;
    }
    static final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout poster;final ImageView image;final TextView fallback,name;final GlowDrawable glow;final GradientDrawable ring;Catalog.Item item;
        Holder(View card,FrameLayout poster,ImageView image,TextView fallback,TextView name,GlowDrawable glow,GradientDrawable ring){
            super(card);this.poster=poster;this.image=image;this.fallback=fallback;this.name=name;this.glow=glow;this.ring=ring;}
    }
    @Override public Holder onCreateViewHolder(ViewGroup parent,int type) {
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);card.setFocusableInTouchMode(true);card.setClickable(true);card.setClipChildren(false);
        card.setLayoutParams(new RecyclerView.LayoutParams(artWidth+2*glow,artHeight+2*glow+(names?Ui.dp(activity,NAME_DP):0)));

        FrameLayout poster=new FrameLayout(activity);poster.setPadding(glow,glow,glow,glow);poster.setClipToPadding(true);
        GlowDrawable glowDrawable=new GlowDrawable(glow,Ui.dp(activity,6),Ui.GLOW);poster.setBackground(glowDrawable);
        card.addView(poster,new LinearLayout.LayoutParams(artWidth+2*glow,artHeight+2*glow));

        FrameLayout art=new FrameLayout(activity);art.setBackgroundColor(0xFF17171C);
        int radius=Ui.dp(activity,6);
        art.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),radius);}});
        art.setClipToOutline(true);
        poster.addView(art,new FrameLayout.LayoutParams(-1,-1));
        // The title sits under the artwork; it is what viewers see when a title has no poster.
        TextView fallback=new TextView(activity);fallback.setTextColor(Ui.TEXT_2);fallback.setTextSize(14);
        fallback.setGravity(Gravity.CENTER);fallback.setMaxLines(4);fallback.setEllipsize(TextUtils.TruncateAt.END);
        int tp=Ui.dp(activity,8);fallback.setPadding(tp,tp,tp,tp);
        art.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
        ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(image,new FrameLayout.LayoutParams(-1,-1));
        GradientDrawable ring=new GradientDrawable();ring.setCornerRadius(radius);ring.setStroke(Ui.dp(activity,2),0xFFE06A66);ring.setColor(0);

        TextView name=null;
        if(names){
            name=new TextView(activity);name.setTextColor(Ui.TEXT_2);name.setTextSize(14);name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);name.setLineSpacing(0,1.05f);name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.setPadding(glow,Ui.dp(activity,2),glow,0);
            card.addView(name,new LinearLayout.LayoutParams(-1,Ui.dp(activity,NAME_DP)));
        }
        Holder holder=new Holder(card,poster,image,fallback,name,glowDrawable,ring);
        card.setOnFocusChangeListener((v,hasFocus)->{
            showFocus(holder,hasFocus,true);
            if(hasFocus && focus!=null && holder.item!=null)focus.onFocus(holder.item);
        });
        card.setOnClickListener(v->{
            Catalog.Item item=holder.item;if(item==null)return;
            Intent intent=new Intent(activity,DetailsActivity.class);
            intent.putExtra("id",item.id);intent.putExtra("kind",item.kind);intent.putExtra("title",item.title);
            intent.putExtra("poster",item.poster);intent.putExtra("backdrop",item.backdrop);
            intent.putExtra("extension",item.extension);intent.putExtra("year",item.year);
            intent.putExtra("category",item.categoryId);
            activity.startActivity(intent);
        });
        return holder;
    }
    private static void showFocus(Holder h,boolean focused,boolean animate){
        h.glow.setOn(focused);
        ((FrameLayout)h.poster.getChildAt(0)).setForeground(focused?h.ring:null);
        if(h.name!=null)h.name.setTextColor(focused?Ui.TEXT:Ui.TEXT_2);
        float s=focused?FOCUS_SCALE:1f;
        if(animate)h.poster.animate().scaleX(s).scaleY(s).setDuration(120).start();
        else{h.poster.animate().cancel();h.poster.setScaleX(s);h.poster.setScaleY(s);}
    }
    @Override public void onBindViewHolder(Holder h,int position) {
        Catalog.Item item=items.get(position);h.item=item;
        h.fallback.setText(item.title);
        if(h.name!=null)h.name.setText(item.title);
        h.itemView.setContentDescription(item.title);
        Images.load(h.image,item.poster,artWidth);
        showFocus(h,h.itemView.isFocused(),false);
    }
    @Override public void onViewRecycled(Holder h){showFocus(h,false,false);}
    @Override public int getItemCount(){return items.size();}
}

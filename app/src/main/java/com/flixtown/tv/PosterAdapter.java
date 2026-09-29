package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

/**
 * Poster cards for rows and grids. Cards have a fixed 2:3 size supplied by the screen, so binding
 * never remeasures. D-pad movement is left to the Leanback grids, which keep one selected card per
 * row and scroll to a fixed keyline instead of jumping.
 */
final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> {
    interface Focus { void onFocus(Catalog.Item item); }
    private final Activity activity;
    private final List<Catalog.Item> items;
    private final int width,height;
    private final Focus focus;

    /** Poster width for horizontal rows, in dp. Height is 1.5× (2:3 artwork). */
    static final int ROW_POSTER_DP=120;
    static int rowListHeight(Activity activity){return Ui.dp(activity,ROW_POSTER_DP*3/2+16);}

    PosterAdapter(Activity activity,List<Catalog.Item> items,Focus focus){
        this(activity,items,Ui.dp(activity,ROW_POSTER_DP),Ui.dp(activity,ROW_POSTER_DP*3/2),focus);
    }
    PosterAdapter(Activity activity,List<Catalog.Item> items,int width,int height,Focus focus) {
        this.activity=activity;this.items=items;this.width=width;this.height=height;this.focus=focus;
        setHasStableIds(true);
    }
    @Override public long getItemId(int position) {
        Catalog.Item item=items.get(position);
        long id;try{id=Long.parseLong(item.id);}catch(NumberFormatException e){id=item.id.hashCode()&0xFFFFFFFFL;}
        return "series".equals(item.kind)?-1-id:id;
    }
    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;final TextView title;final GradientDrawable normal,focused;Catalog.Item item;
        Holder(FrameLayout frame,ImageView image,TextView title,GradientDrawable normal,GradientDrawable focused){
            super(frame);this.image=image;this.title=title;this.normal=normal;this.focused=focused;}
    }
    private GradientDrawable border(boolean focus) {
        GradientDrawable shape=new GradientDrawable();shape.setColor(focus?0xFF352028:0xFF121620);
        shape.setCornerRadius(Ui.dp(activity,5));shape.setStroke(Ui.dp(activity,focus?3:1),focus?0xFFE7151E:0xFF303440);
        return shape;
    }
    @Override public Holder onCreateViewHolder(ViewGroup parent,int type) {
        FrameLayout frame=new FrameLayout(activity);frame.setFocusable(true);frame.setFocusableInTouchMode(true);
        frame.setClickable(true);
        frame.setLayoutParams(new RecyclerView.LayoutParams(width,height));
        int pad=Ui.dp(activity,2);frame.setPadding(pad,pad,pad,pad);
        // Title stays underneath the artwork; it is what viewers see when a title has no poster.
        TextView title=new TextView(activity);title.setTextColor(0xFFC9CBD1);title.setTextSize(14);
        title.setGravity(Gravity.CENTER);title.setMaxLines(4);title.setEllipsize(TextUtils.TruncateAt.END);
        int tp=Ui.dp(activity,8);title.setPadding(tp,tp,tp,tp);
        frame.addView(title,new FrameLayout.LayoutParams(-1,-1));
        ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        Holder holder=new Holder(frame,image,title,border(false),border(true));
        frame.setBackground(holder.normal);
        frame.setOnFocusChangeListener((v,hasFocus)->{
            v.setBackground(hasFocus?holder.focused:holder.normal);
            v.animate().scaleX(hasFocus?1.06f:1f).scaleY(hasFocus?1.06f:1f).setDuration(110).start();
            if(hasFocus && focus!=null && holder.item!=null)focus.onFocus(holder.item);
        });
        frame.setOnClickListener(v->{
            Catalog.Item item=holder.item;if(item==null)return;
            Intent intent=new Intent(activity,DetailsActivity.class);
            intent.putExtra("id",item.id);intent.putExtra("kind",item.kind);intent.putExtra("title",item.title);
            intent.putExtra("poster",item.poster);intent.putExtra("backdrop",item.backdrop);
            intent.putExtra("extension",item.extension);intent.putExtra("year",item.year);
            activity.startActivity(intent);
        });
        return holder;
    }
    @Override public void onBindViewHolder(Holder h,int position) {
        Catalog.Item item=items.get(position);h.item=item;
        h.title.setText(item.title);
        h.itemView.setContentDescription(item.title);
        Images.load(h.image,item.poster,width);
        boolean hasFocus=h.itemView.isFocused();
        h.itemView.setBackground(hasFocus?h.focused:h.normal);
        h.itemView.setScaleX(hasFocus?1.06f:1f);h.itemView.setScaleY(hasFocus?1.06f:1f);
    }
    @Override public void onViewRecycled(Holder h){h.itemView.animate().cancel();h.itemView.setScaleX(1f);h.itemView.setScaleY(1f);}
    @Override public int getItemCount(){return items.size();}
}

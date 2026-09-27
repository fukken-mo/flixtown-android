package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> {
    static int cardWidth(Activity a) {
        int screenDp=Math.round(a.getResources().getDisplayMetrics().widthPixels/a.getResources().getDisplayMetrics().density);
        return Math.max(130,Math.min(206,(screenDp-182-64)/5-18));
    }
    static int rowHeight(Activity a) { return Math.round(cardWidth(a)*1.39f)+58; }
    interface Focus { void onFocus(Catalog.Item item); }
    private final Activity activity; private final List<Catalog.Item> items; private final Focus focus;
    PosterAdapter(Activity a,List<Catalog.Item> items,Focus focus) { this.activity=a;this.items=items;this.focus=focus;setHasStableIds(true); }
    @Override public long getItemId(int position) { Catalog.Item item=items.get(position); return (item.kind+":"+item.id).hashCode(); }
    static final class Holder extends RecyclerView.ViewHolder { final ImageView poster; final TextView title; Holder(View v,ImageView poster,TextView title) { super(v);this.poster=poster;this.title=title; } }
    @Override public Holder onCreateViewHolder(ViewGroup parent,int viewType) {
        FrameLayout frame=new FrameLayout(activity); frame.setFocusable(true);frame.setClipChildren(false);frame.setClipToPadding(false);
        int width=cardWidth(activity);
        RecyclerView.LayoutParams fp=new RecyclerView.LayoutParams(Ui.dp(activity,width),Ui.dp(activity,Math.round(width*1.39f)));
        fp.setMargins(Ui.dp(activity,8),Ui.dp(activity,13),Ui.dp(activity,8),Ui.dp(activity,13)); frame.setLayoutParams(fp);
        ImageView poster=new ImageView(activity); poster.setScaleType(ImageView.ScaleType.CENTER_CROP); poster.setBackgroundColor(Ui.CARD);
        frame.addView(poster,new FrameLayout.LayoutParams(-1,-1));
        TextView title=Ui.text(activity,"",14);title.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);title.setBackgroundColor(0x99000000); Ui.pad(title,activity,4,6,4,6);
        FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,Ui.dp(activity,40),Gravity.BOTTOM);frame.addView(title,tp);
        return new Holder(frame,poster,title);
    }
    @Override public void onBindViewHolder(Holder holder,int position) {
        Catalog.Item item=items.get(position);holder.title.setText(item.title);holder.itemView.setTag(item.kind+":"+item.id);Images.load(holder.poster,item.poster,240);
        if(!holder.itemView.hasFocus()){holder.itemView.setScaleX(1f);holder.itemView.setScaleY(1f);holder.itemView.setElevation(0f);holder.itemView.setPadding(0,0,0,0);holder.poster.clearColorFilter();}
        holder.itemView.setOnFocusChangeListener((v,focused)->{
            if(focused)holder.poster.setColorFilter(0x55D72536,android.graphics.PorterDuff.Mode.SRC_ATOP);
            else holder.poster.clearColorFilter();
            v.setBackground(Ui.rounded(focused?Ui.RED:Color.TRANSPARENT,7,activity));
            v.setPadding(Ui.dp(activity,focused?4:0),Ui.dp(activity,focused?4:0),Ui.dp(activity,focused?4:0),Ui.dp(activity,focused?4:0));
            v.setElevation(Ui.dp(activity,focused?18:0));
            v.animate().scaleX(focused?1.09f:1f).scaleY(focused?1.09f:1f).setDuration(110).start();
            if(focused)focus.onFocus(item);
        });
        holder.itemView.setOnClickListener(v->{
            Intent intent=new Intent(activity,DetailsActivity.class);
            intent.putExtra("id",item.id);intent.putExtra("kind",item.kind);intent.putExtra("title",item.title);
            intent.putExtra("poster",item.poster);intent.putExtra("backdrop",item.backdrop);intent.putExtra("extension",item.extension);
            intent.putExtra("year",item.year);
            activity.startActivity(intent);
        });
    }
    @Override public int getItemCount() { return items.size(); }
}

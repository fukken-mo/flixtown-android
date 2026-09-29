package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> {
    interface Move { boolean move(int row,int position,int direction); }
    interface Focus { void onFocus(Catalog.Item item); }
    private final Activity activity;
    private final List<Catalog.Item> items;
    private final boolean browse;
    private final int row;
    private final Move move;
    private Focus legacyFocus;
    static int rowHeight(Activity activity){return 206;}
    PosterAdapter(Activity activity,List<Catalog.Item> items,Focus focus){
        this(activity,items,false,-1,null);this.legacyFocus=focus;
    }
    PosterAdapter(Activity activity,List<Catalog.Item> items,boolean browse,int row,Move move) {
        this.activity=activity;this.items=items;this.browse=browse;this.row=row;this.move=move;
        setHasStableIds(true);
    }
    @Override public long getItemId(int position) {
        Catalog.Item item=items.get(position);
        return (item.kind+":"+item.id).hashCode();
    }
    static class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        Holder(FrameLayout frame,ImageView image){super(frame);this.image=image;}
    }
    private GradientDrawable border(boolean focus) {
        GradientDrawable shape=new GradientDrawable();shape.setColor(focus?0xFF352028:0xFF121620);
        shape.setCornerRadius(Ui.dp(activity,5));shape.setStroke(Ui.dp(activity,focus?3:1),focus?0xFFE7151E:0xFF303440);
        return shape;
    }
    @Override public Holder onCreateViewHolder(ViewGroup parent,int type) {
        int width=Ui.dp(activity,browse?104:136),height=Ui.dp(activity,browse?140:184);
        FrameLayout frame=new FrameLayout(activity);frame.setFocusable(true);frame.setFocusableInTouchMode(true);
        frame.setClickable(true);frame.setClipChildren(false);frame.setContentDescription("Movie poster");
        RecyclerView.LayoutParams lp=new RecyclerView.LayoutParams(width,height);
        lp.setMargins(Ui.dp(activity,4),Ui.dp(activity,5),Ui.dp(activity,browse?16:20),Ui.dp(activity,browse?13:8));frame.setLayoutParams(lp);
        ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(0xFF121620);frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        frame.setBackground(border(false));frame.setPadding(Ui.dp(activity,2),Ui.dp(activity,2),Ui.dp(activity,2),Ui.dp(activity,2));
        return new Holder(frame,image);
    }
    @Override public void onBindViewHolder(Holder h,int position) {
        Catalog.Item item=items.get(position);
        Images.load(h.image,item.poster,browse?208:272);
        h.itemView.setContentDescription(item.title);
        h.itemView.setBackground(border(h.itemView.isFocused()));
        h.itemView.setOnFocusChangeListener((v,focused)->{
            v.setBackground(border(focused));
            v.animate().scaleX(focused?1.045f:1f).scaleY(focused?1.045f:1f).setDuration(110).start();
            if(focused && legacyFocus!=null)legacyFocus.onFocus(item);
        });
        h.itemView.setOnKeyListener((v,key,event)->{
            if(event.getAction()!=KeyEvent.ACTION_DOWN || move==null)return false;
            if(key==KeyEvent.KEYCODE_DPAD_DOWN || key==KeyEvent.KEYCODE_DPAD_UP)
                return move.move(row,h.getBindingAdapterPosition(),key);
            return false;
        });
        h.itemView.setOnClickListener(v->{
            Intent intent=new Intent(activity,DetailsActivity.class);
            intent.putExtra("id",item.id);intent.putExtra("kind",item.kind);intent.putExtra("title",item.title);
            intent.putExtra("poster",item.poster);intent.putExtra("backdrop",item.backdrop);
            intent.putExtra("extension",item.extension);intent.putExtra("year",item.year);
            activity.startActivity(intent);
        });
    }
    @Override public int getItemCount(){return items.size();}
}

package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> {
    private static final int SIDE_GAP=17, TOP_GAP=13, TITLE_HEIGHT=62;
    static int cardWidth(Activity activity,boolean browse) {
        int width=Math.round(activity.getResources().getDisplayMetrics().widthPixels/activity.getResources().getDisplayMetrics().density);
        int rail=browse?190:84, contentPadding=browse?96:72;
        int safeWidth=Math.round(Ui.safeX(activity)*2/activity.getResources().getDisplayMetrics().density);
        return Math.max(76,Math.min(330,(width-safeWidth-rail-contentPadding-4*SIDE_GAP*2)/4));
    }
    static int cardWidth(Activity activity){return cardWidth(activity,false);}
    static int rowHeight(Activity activity){return Math.round(cardWidth(activity)*1.54f)+TITLE_HEIGHT+TOP_GAP*2+16;}
    interface Focus {void onFocus(Catalog.Item item);}
    private final Activity activity;private final List<Catalog.Item> items;private final Focus focus;private final boolean browse;
    PosterAdapter(Activity activity,List<Catalog.Item> items,Focus focus){this(activity,items,focus,false);}
    PosterAdapter(Activity activity,List<Catalog.Item> items,Focus focus,boolean browse){
        this.activity=activity;this.items=items;this.focus=focus;this.browse=browse;setHasStableIds(true);
    }
    @Override public long getItemId(int position){Catalog.Item item=items.get(position);return (item.kind+":"+item.id).hashCode();}
    static final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout artwork;final ImageView poster;final TextView title;final View glow;
        Holder(View item,FrameLayout artwork,ImageView poster,TextView title,View glow){super(item);this.artwork=artwork;this.poster=poster;this.title=title;this.glow=glow;}
    }
    @Override public Holder onCreateViewHolder(ViewGroup parent,int type){
        int width=cardWidth(activity,browse);
        LinearLayout card=Ui.column(activity);card.setFocusable(true);card.setFocusableInTouchMode(true);card.setClickable(true);card.setGravity(Gravity.TOP);
        card.setClipChildren(false);card.setClipToPadding(false);
        RecyclerView.LayoutParams layout=new RecyclerView.LayoutParams(browse?-1:Ui.dp(activity,width),browse?-2:Ui.dp(activity,Math.round(width*1.54f)+TITLE_HEIGHT));
        layout.setMargins(Ui.dp(activity,SIDE_GAP),Ui.dp(activity,TOP_GAP),Ui.dp(activity,SIDE_GAP),Ui.dp(activity,TOP_GAP));card.setLayoutParams(layout);
        FrameLayout stage=new FrameLayout(activity){@Override protected void onMeasure(int w,int h){
            if(browse)h=View.MeasureSpec.makeMeasureSpec(Math.round(View.MeasureSpec.getSize(w)*1.54f),View.MeasureSpec.EXACTLY);
            super.onMeasure(w,h);
        }};stage.setClipChildren(false);stage.setClipToPadding(false);
        card.addView(stage,new LinearLayout.LayoutParams(-1,browse?-2:Ui.dp(activity,Math.round(width*1.54f))));
        View glow=new View(activity){final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            {setLayerType(View.LAYER_TYPE_SOFTWARE,null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Ui.dp(activity,2));
             paint.setColor(0xFFFF526D);paint.setShadowLayer(Ui.dp(activity,18),0,0,0xF1F51F50);}
            @Override protected void onDraw(Canvas canvas){float edge=Ui.dp(activity,12);
                canvas.drawRoundRect(edge,edge,getWidth()-edge,getHeight()-edge,Ui.dp(activity,10),Ui.dp(activity,10),paint);}
        };glow.setAlpha(0f);
        FrameLayout.LayoutParams glowParams=new FrameLayout.LayoutParams(-1,-1);
        glowParams.setMargins(-Ui.dp(activity,10),-Ui.dp(activity,10),-Ui.dp(activity,10),-Ui.dp(activity,10));stage.addView(glow,glowParams);
        FrameLayout artwork=new FrameLayout(activity);artwork.setBackground(Ui.posterBorder(activity,false));
        artwork.setPadding(Ui.dp(activity,3),Ui.dp(activity,3),Ui.dp(activity,3),Ui.dp(activity,3));
        stage.addView(artwork,new FrameLayout.LayoutParams(-1,-1));
        ImageView poster=new ImageView(activity);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);poster.setBackgroundColor(Ui.CARD);
        artwork.addView(poster,new FrameLayout.LayoutParams(-1,-1));
        TextView title=Ui.text(activity,"",width<150?13:15);title.setTextColor(0xFFE7E4E7);title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);title.setGravity(Gravity.TOP|Gravity.START);
        title.setIncludeFontPadding(false);Ui.pad(title,activity,3,8,4,0);
        card.addView(title,new LinearLayout.LayoutParams(-1,Ui.dp(activity,TITLE_HEIGHT)));
        return new Holder(card,artwork,poster,title,glow);
    }
    @Override public void onBindViewHolder(Holder holder,int position){
        Catalog.Item item=items.get(position);holder.title.setText(item.title);holder.itemView.setTag(item.kind+":"+item.id);
        Images.load(holder.poster,item.poster,browse?280:320);
        holder.itemView.setScaleX(1f);holder.itemView.setScaleY(1f);holder.artwork.setBackground(Ui.posterBorder(activity,holder.itemView.hasFocus()));
        holder.glow.setAlpha(holder.itemView.hasFocus()?1f:0f);
        holder.title.setTextColor(holder.itemView.hasFocus()?Color.WHITE:0xFFE7E4E7);
        holder.itemView.setOnFocusChangeListener((view,focused)->{
            holder.artwork.setBackground(Ui.posterBorder(activity,focused));
            holder.glow.animate().alpha(focused?1f:0f).setDuration(170).start();
            holder.title.setTextColor(focused?Color.WHITE:0xFFE7E4E7);
            view.setElevation(Ui.dp(activity,focused?20:0));
            view.animate().scaleX(focused?1.055f:1f).scaleY(focused?1.055f:1f).setDuration(150).start();
            if(focused)focus.onFocus(item);
        });
        holder.itemView.setOnClickListener(view->{
            Intent intent=new Intent(activity,DetailsActivity.class);
            intent.putExtra("id",item.id);intent.putExtra("kind",item.kind);intent.putExtra("title",item.title);
            intent.putExtra("poster",item.poster);intent.putExtra("backdrop",item.backdrop);intent.putExtra("extension",item.extension);
            intent.putExtra("year",item.year);activity.startActivity(intent);
        });
    }
    @Override public int getItemCount(){return items.size();}
}

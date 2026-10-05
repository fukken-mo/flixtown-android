package com.flixtown.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
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
 * Home card styles. Each section type has its own size so the page has a clear hierarchy:
 * landscape Continue Watching cards, larger ranked Trending posters and standard posters with a
 * title underneath. Focus never changes a card's layout size: it only scales the art,
 * turns on the glow, lifts the dimming and brightens the title, all in about 150 ms.
 */
final class HomeCards {
    interface Listener {
        void onFocus(Catalog.Item item);
        void onOpen(Catalog.Item item,HomeFeed.Section section);
    }
    static final int GLOW=8;
    // Artwork sizes in dp (the card adds the glow margin around the art).
    static final int POSTER_W=124,POSTER_H=186,TREND_W=132,TREND_H=198,TREND_RANK=58,WIDE_W=240,WIDE_H=135,TEXT_H=40;

    /** Height of a section's card list, including room for the focus scale. */
    static int listHeight(Context c,int type){
        switch(type){
            case HomeFeed.CONTINUE:return Ui.dp(c,WIDE_H+2*GLOW+TEXT_H+10);
            case HomeFeed.TRENDING:return Ui.dp(c,TREND_H+2*GLOW+TEXT_H+10);
            default:return Ui.dp(c,POSTER_H+2*GLOW+TEXT_H+10);
        }
    }

    static RecyclerView.Adapter<?> adapter(Activity a,HomeFeed.Section section,Listener listener){
        return new CardAdapter(a,section,listener);
    }

    /* ---------------- Poster, Trending and Continue Watching cards ---------------- */

    static final class Holder extends RecyclerView.ViewHolder{
        final FrameLayout box,art;final ImageView image;final TextView fallback,title,meta,badge;
        final View dim,progressTrack,progressFill;final RankView rank;final GlowDrawable glow;final GradientDrawable ring;
        Catalog.Item item;
        Holder(View card,FrameLayout box,FrameLayout art,ImageView image,TextView fallback,TextView title,TextView meta,TextView badge,
               View dim,View progressTrack,View progressFill,RankView rank,GlowDrawable glow,GradientDrawable ring){
            super(card);this.box=box;this.art=art;this.image=image;this.fallback=fallback;this.title=title;this.meta=meta;this.badge=badge;
            this.dim=dim;this.progressTrack=progressTrack;this.progressFill=progressFill;this.rank=rank;this.glow=glow;this.ring=ring;}
    }

    private static final class CardAdapter extends RecyclerView.Adapter<Holder>{
        private final Activity a;private final HomeFeed.Section section;private final Listener listener;
        private final int artW,artH,rankW,glow;private final float focusScale;
        CardAdapter(Activity a,HomeFeed.Section section,Listener listener){
            this.a=a;this.section=section;this.listener=listener;glow=Ui.dp(a,GLOW);
            switch(section.type){
                case HomeFeed.CONTINUE:artW=Ui.dp(a,WIDE_W);artH=Ui.dp(a,WIDE_H);rankW=0;focusScale=1.06f;break;
                case HomeFeed.TRENDING:artW=Ui.dp(a,TREND_W);artH=Ui.dp(a,TREND_H);rankW=Ui.dp(a,TREND_RANK);focusScale=1.08f;break;
                default:artW=Ui.dp(a,POSTER_W);artH=Ui.dp(a,POSTER_H);rankW=0;focusScale=1.08f;
            }
            setHasStableIds(true);
        }
        @Override public long getItemId(int p){return section.items.get(p).key().hashCode();}
        @Override public int getItemCount(){return section.items.size();}
        @Override public Holder onCreateViewHolder(ViewGroup parent,int type){
            Context c=a;int boxW=artW+2*glow,boxH=artH+2*glow;
            FrameLayout card=new FrameLayout(c);card.setFocusable(true);card.setFocusableInTouchMode(true);card.setClickable(true);
            card.setClipChildren(false);card.setClipToPadding(false);
            card.setLayoutParams(new RecyclerView.LayoutParams(rankW+boxW,boxH+Ui.dp(c,TEXT_H)));

            RankView rank=null;
            if(rankW>0){rank=new RankView(c);card.addView(rank,new FrameLayout.LayoutParams(rankW+glow*2,boxH));}

            FrameLayout box=new FrameLayout(c);box.setPadding(glow,glow,glow,glow);box.setClipToPadding(true);
            GlowDrawable glowDrawable=new GlowDrawable(glow,Ui.dp(c,7),Ui.GLOW);box.setBackground(glowDrawable);
            FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(boxW,boxH);bp.leftMargin=rankW;card.addView(box,bp);

            FrameLayout art=new FrameLayout(c);art.setBackgroundColor(0xFF16161B);
            int radius=Ui.dp(c,7);
            art.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),radius);}});
            art.setClipToOutline(true);box.addView(art,new FrameLayout.LayoutParams(-1,-1));
            TextView fallback=new TextView(c);fallback.setTextColor(Ui.TEXT_2);fallback.setTextSize(14);fallback.setGravity(Gravity.CENTER);
            fallback.setMaxLines(4);fallback.setEllipsize(TextUtils.TruncateAt.END);int fp=Ui.dp(c,10);fallback.setPadding(fp,fp,fp,fp);
            art.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
            ImageView image=new ImageView(c);image.setScaleType(ImageView.ScaleType.CENTER_CROP);art.addView(image,new FrameLayout.LayoutParams(-1,-1));

            View progressTrack=null,progressFill=null;
            if(section.type==HomeFeed.CONTINUE){
                View shade=new View(c);shade.setBackgroundResource(R.drawable.card_shade);art.addView(shade,new FrameLayout.LayoutParams(-1,-1));
                progressTrack=new View(c);progressTrack.setBackground(Ui.rounded(0x4DFFFFFF,2,c));
                FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,Ui.dp(c,4),Gravity.BOTTOM);tp.setMargins(Ui.dp(c,12),0,Ui.dp(c,12),Ui.dp(c,10));
                art.addView(progressTrack,tp);
                progressFill=new View(c);progressFill.setBackground(Ui.rounded(Ui.GLOW,2,c));
                FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(0,Ui.dp(c,4),Gravity.BOTTOM|Gravity.START);pp.setMargins(Ui.dp(c,12),0,0,Ui.dp(c,10));
                art.addView(progressFill,pp);
            }
            View dim=new View(c);dim.setBackgroundColor(0x38000000);art.addView(dim,new FrameLayout.LayoutParams(-1,-1));
            TextView badge=new TextView(c);badge.setTextSize(10);badge.setTextColor(Ui.TEXT);badge.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
            badge.setLetterSpacing(0.12f);badge.setSingleLine(true);badge.setBackground(Ui.rounded(0xE6AB3A39,3,c));badge.setPadding(Ui.dp(c,6),Ui.dp(c,2),Ui.dp(c,6),Ui.dp(c,2));
            FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);gp.setMargins(Ui.dp(c,8),Ui.dp(c,8),0,0);
            art.addView(badge,gp);
            GradientDrawable ring=new GradientDrawable();ring.setCornerRadius(radius);ring.setStroke(Ui.dp(c,2),0xFFE8736F);ring.setColor(0);

            LinearLayout text=new LinearLayout(c);text.setOrientation(LinearLayout.VERTICAL);
            FrameLayout.LayoutParams xp=new FrameLayout.LayoutParams(boxW-2*glow,Ui.dp(c,TEXT_H));xp.leftMargin=rankW+glow;xp.topMargin=boxH;
            card.addView(text,xp);
            TextView title=new TextView(c);title.setTextSize(14);title.setTextColor(Ui.TEXT_2);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));text.addView(title,new LinearLayout.LayoutParams(-1,-2));
            TextView meta=new TextView(c);meta.setTextSize(12);meta.setTextColor(Ui.TEXT_3);meta.setSingleLine(true);meta.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.topMargin=Ui.dp(c,1);text.addView(meta,mp);

            Holder h=new Holder(card,box,art,image,fallback,title,meta,badge,dim,progressTrack,progressFill,rank,glowDrawable,ring);
            card.setOnFocusChangeListener((v,f)->{applyFocus(h,f,true,focusScale);if(f && h.item!=null)listener.onFocus(h.item);});
            card.setOnClickListener(v->{if(h.item!=null)listener.onOpen(h.item,section);});
            return h;
        }
        @Override public void onBindViewHolder(Holder h,int position){
            Catalog.Item item=section.items.get(position);h.item=item;
            h.fallback.setText(item.title);h.title.setText(item.title);h.itemView.setContentDescription(item.title);
            if(h.rank!=null)h.rank.setRank(position+1);
            String badge=null,meta;
            if(section.type==HomeFeed.CONTINUE){
                Catalog.Progress p=Catalog.progress(a,item);
                String art=!p.art.isEmpty()?p.art:item.hasBackdrop()?item.backdrop:item.poster;
                Images.load(h.image,art,artW);
                int fillWidth=Math.round((artW-Ui.dp(a,24))*p.percent()/100f);
                ViewGroup.LayoutParams lp=h.progressFill.getLayoutParams();lp.width=fillWidth;h.progressFill.setLayoutParams(lp);
                h.progressFill.setVisibility(p.upNext?View.GONE:View.VISIBLE);
                if(p.upNext){badge="UP NEXT";meta=p.label.isEmpty()?"Next episode":p.label;}
                else{String left=p.remainingMinutes()>0?p.remainingMinutes()+" min left":"";
                    meta="series".equals(item.kind)?join(p.label,left):join(item.year>1900?String.valueOf(item.year):"",left);}
            }else{
                Images.load(h.image,item.poster,artW);
                badge=badge(item);meta=meta(item);
                Ratings.want(item);
            }
            h.meta.setText(meta);
            h.badge.setVisibility(badge==null?View.GONE:View.VISIBLE);if(badge!=null)h.badge.setText(badge);
            applyFocus(h,h.itemView.isFocused(),false,focusScale);
        }
        /** A rating or badge arrived: update the words only (same card, artwork and focus untouched). */
        @Override public void onBindViewHolder(Holder h,int position,List<Object> payloads){
            if(!payloads.contains(TEXT) || section.type==HomeFeed.CONTINUE){onBindViewHolder(h,position);return;}
            Catalog.Item item=section.items.get(position);
            String badge=badge(item);h.meta.setText(meta(item));
            h.badge.setVisibility(badge==null?View.GONE:View.VISIBLE);if(badge!=null)h.badge.setText(badge);
        }
        @Override public void onViewRecycled(Holder h){applyFocus(h,false,false,focusScale);}
    }
    /** Payload for {@code notifyItemRangeChanged}: only ratings/badges changed. */
    static final Object TEXT="text";
    /** NEW / NEW SERIES only for releases in the last 30 days; uploads never qualify. */
    static String badge(Catalog.Item item){
        if("series".equals(item.kind)){String b=SeriesNews.badge(item).label();if(b!=null)return b;}
        else if(item.isNew())return "NEW";
        return item.isUhd()?"4K":null;
    }
    /** "2019  ·  ★ 8.2  ·  Series": the star is TMDB's rating, left out when there is none. */
    static String meta(Catalog.Item item){
        return join(item.year>1900?String.valueOf(item.year):"",Ratings.label(item),"series".equals(item.kind)?"Series":"");
    }
    static String join(String... parts){StringBuilder out=new StringBuilder();
        for(String p:parts)if(p!=null && !p.isEmpty()){if(out.length()>0)out.append("  ·  ");out.append(p);}return out.toString();}

    /** Scale, glow, brighter art and title. Nothing that changes the card's measured size. */
    static void applyFocus(Holder h,boolean focused,boolean animate,float scale){
        h.glow.setOn(focused);h.art.setForeground(focused?h.ring:null);
        h.title.setTextColor(focused?Ui.TEXT:Ui.TEXT_2);h.meta.setTextColor(focused?Ui.TEXT_2:Ui.TEXT_3);
        float s=focused?scale:1f,d=focused?0f:1f;
        if(animate){
            h.box.animate().scaleX(s).scaleY(s).translationZ(focused?h.box.getResources().getDisplayMetrics().density*10:0).setDuration(150).start();
            h.dim.animate().alpha(d).setDuration(150).start();
            if(h.rank!=null)h.rank.animate().alpha(focused?1f:.7f).setDuration(150).start();
        }else{
            h.box.animate().cancel();h.dim.animate().cancel();
            h.box.setScaleX(s);h.box.setScaleY(s);h.box.setTranslationZ(0);h.dim.setAlpha(d);
            if(h.rank!=null){h.rank.animate().cancel();h.rank.setAlpha(focused?1f:.7f);}
        }
    }

    /** Oversized outlined rank number behind a Trending poster. */
    static final class RankView extends View{
        private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),stroke=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect bounds=new Rect();private String text="1";
        RankView(Context c){super(c);
            Typeface face=Typeface.create("sans-serif-condensed",Typeface.BOLD);
            fill.setTypeface(face);stroke.setTypeface(face);stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(c.getResources().getDisplayMetrics().density*2f);stroke.setColor(0x8CD7D2CC);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);setAlpha(.7f);}
        void setRank(int rank){text=String.valueOf(rank);invalidate();}
        @Override protected void onDraw(Canvas canvas){
            float h=getHeight(),size=h*0.62f;
            fill.setTextSize(size);stroke.setTextSize(size);
            fill.getTextBounds(text,0,text.length(),bounds);
            // Narrow enough for "10" to sit behind the poster without crowding it.
            float maxWidth=getWidth()*1.05f;if(bounds.width()>maxWidth){size*=maxWidth/bounds.width();fill.setTextSize(size);stroke.setTextSize(size);fill.getTextBounds(text,0,text.length(),bounds);}
            float x=getWidth()-bounds.width()-bounds.left-getResources().getDisplayMetrics().density*4,y=h-getResources().getDisplayMetrics().density*6;
            fill.setShader(new LinearGradient(0,y-bounds.height(),0,y,0x33FFFFFF,0x0DFFFFFF,Shader.TileMode.CLAMP));
            canvas.drawText(text,x,y,fill);canvas.drawText(text,x,y,stroke);
        }
    }
}

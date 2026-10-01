package com.flixtown.tv;

import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides what Home shows, using only data the app really has. Sections without content are left
 * out, and nothing is presented as personal unless it comes from the viewer's own history.
 */
final class HomeFeed {
    static final int CONTINUE=1,TRENDING=2,POSTERS=3;
    static final int HERO_COUNT=5;

    static final class Section {
        final int type;final String id,title,tag;
        final List<Catalog.Item> items;
        int selected;RecyclerView.Adapter<?> adapter;
        Section(int type,String id,String title,String tag,List<Catalog.Item> items){
            this.type=type;this.id=id;this.title=title;this.tag=tag;this.items=items;}
    }
    static final class Result {final List<Catalog.Item> featured;final List<Section> sections;
        Result(List<Catalog.Item> f,List<Section> s){featured=f;sections=s;}}

    static Result build(Context c,List<Catalog.Item> movies,List<Catalog.Item> series){
        List<Section> out=new ArrayList<>();
        List<Catalog.Item> continued=Catalog.continueWatching(c,movies,series);
        if(!continued.isEmpty())out.add(new Section(CONTINUE,"continue","Continue Watching",null,continued));

        // This week's TMDB trending titles that are on this server, in TMDB's order (see Trending).
        List<Catalog.Item> trending=Trending.current(movies,series);
        if(trending.size()>=Trending.MIN_ROW)out.add(new Section(TRENDING,"trending","Trending Now",trending.size()==10?"TOP 10":null,trending));

        if(!movies.isEmpty())out.add(new Section(POSTERS,"latest_movies","Latest Movies",null,Catalog.recent(movies,24)));
        if(!series.isEmpty())out.add(new Section(POSTERS,"latest_series","Latest TV Shows",null,SeriesNews.latest(series,24)));

        // Real history only: the most recent movie in Continue Watching, and titles from its category.
        for(Catalog.Item seed:continued){
            if(!"movie".equals(seed.kind) || seed.categoryId.isEmpty())continue;
            Set<String> skip=new HashSet<>();for(Catalog.Item i:continued)skip.add(i.key());
            List<Catalog.Item> similar=new ArrayList<>();
            for(Catalog.Item i:Catalog.similar(seed,movies,24))if(!skip.contains(i.key()) && seed.categoryId.equals(i.categoryId))similar.add(i);
            if(similar.size()>=5)out.add(new Section(POSTERS,"because","Because you watched "+seed.title,null,similar));
            break;
        }

        // Hero: Trending titles, otherwise the newest movies and the series with proven new episodes,
        // preferring titles that have real landscape artwork.
        List<Catalog.Item> featured=new ArrayList<>();
        List<Catalog.Item> pool=new ArrayList<>(trending);
        for(Catalog.Item i:Catalog.recent(movies,20))if(!pool.contains(i))pool.add(i);
        for(Catalog.Item i:SeriesNews.latest(series,10))if(SeriesNews.badge(i).type!=SeriesNews.NONE && !pool.contains(i))pool.add(i);
        for(Catalog.Item i:pool)if(i.hasBackdrop() && featured.size()<HERO_COUNT)featured.add(i);
        for(Catalog.Item i:pool)if(!featured.contains(i) && featured.size()<HERO_COUNT)featured.add(i);
        return new Result(featured,out);
    }
}

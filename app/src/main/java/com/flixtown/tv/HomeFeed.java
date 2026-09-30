package com.flixtown.tv;

import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides what Home shows, using only data the app really has. Sections without content are left
 * out, and nothing is presented as personal unless it comes from the viewer's own history.
 */
final class HomeFeed {
    static final int CONTINUE=1,TRENDING=2,POSTERS=3,CATEGORIES=4;
    static final int HERO_COUNT=5;

    static final class Section {
        final int type;final String id,title,tag;
        final List<Catalog.Item> items;final List<Catalog.Category> categories;final Map<String,Integer> counts;
        int selected;RecyclerView.Adapter<?> adapter;
        Section(int type,String id,String title,String tag,List<Catalog.Item> items,List<Catalog.Category> categories,Map<String,Integer> counts){
            this.type=type;this.id=id;this.title=title;this.tag=tag;this.items=items;this.categories=categories;this.counts=counts;}
    }
    static final class Result {final List<Catalog.Item> featured;final List<Section> sections;
        Result(List<Catalog.Item> f,List<Section> s){featured=f;sections=s;}}

    static Result build(Context c,List<Catalog.Item> movies,List<Catalog.Item> series,List<Catalog.Category> movieCategories){
        List<Section> out=new ArrayList<>();
        List<Catalog.Item> continued=Catalog.continueWatching(c,movies,series);
        if(!continued.isEmpty())out.add(new Section(CONTINUE,"continue","Continue Watching",null,continued,null,null));

        List<Catalog.Item> trending=Catalog.trending(movies,series,10);
        if(trending.size()>=3)out.add(new Section(TRENDING,"trending","Trending Now","TOP 10",trending,null,null));

        if(!movies.isEmpty())out.add(new Section(POSTERS,"latest_movies","Latest Movies",null,Catalog.recent(movies,24),null,null));
        if(!series.isEmpty())out.add(new Section(POSTERS,"latest_series","Latest TV Shows",null,Catalog.recent(series,24),null,null));

        // Real history only: the most recent movie in Continue Watching, and titles from its category.
        for(Catalog.Item seed:continued){
            if(!"movie".equals(seed.kind) || seed.categoryId.isEmpty())continue;
            Set<String> skip=new HashSet<>();for(Catalog.Item i:continued)skip.add(i.key());
            List<Catalog.Item> similar=new ArrayList<>();
            for(Catalog.Item i:Catalog.similar(seed,movies,24))if(!skip.contains(i.key()) && seed.categoryId.equals(i.categoryId))similar.add(i);
            if(similar.size()>=5)out.add(new Section(POSTERS,"because","Because you watched "+seed.title,null,similar,null,null));
            break;
        }

        // Genres: the movie categories that actually hold titles, largest first.
        Map<String,Integer> counts=new HashMap<>();
        for(Catalog.Item m:movies)if(!m.categoryId.isEmpty()){Integer n=counts.get(m.categoryId);counts.put(m.categoryId,n==null?1:n+1);}
        List<Catalog.Category> genres=new ArrayList<>();
        for(Catalog.Category cat:movieCategories)if(counts.containsKey(cat.id))genres.add(cat);
        java.util.Collections.sort(genres,(a,b)->Integer.compare(counts.get(b.id),counts.get(a.id)));
        if(genres.size()>12)genres=new ArrayList<>(genres.subList(0,12));
        if(genres.size()>=3)out.add(new Section(CATEGORIES,"genres","Browse by Genre",null,new ArrayList<>(),genres,counts));

        // Hero: the top of Trending, preferring titles that have real landscape artwork.
        List<Catalog.Item> featured=new ArrayList<>();
        List<Catalog.Item> pool=Catalog.trending(movies,series,30);
        for(Catalog.Item i:pool)if(i.hasBackdrop() && featured.size()<HERO_COUNT)featured.add(i);
        for(Catalog.Item i:pool)if(!featured.contains(i) && featured.size()<HERO_COUNT)featured.add(i);
        return new Result(featured,out);
    }
}

package com.flixtown.tv;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/** TMDB ratings, real Trending matching and proven "new" badges (JVM, no device). */
public class RatingsAndBadgesTest {
    private static final long DAY=86400;
    private static long now(){return System.currentTimeMillis()/1000;}
    private static Catalog.Item movie(String id,String name,int year,String tmdb)throws Exception{
        return new Catalog.Item(new JSONObject().put("stream_id",id).put("name",name).put("year",year==0?"":String.valueOf(year))
            .put("tmdb",tmdb).put("rating","10").put("added",String.valueOf(now()-30*DAY)),"movie");
    }
    private static Catalog.Item series(String id,String name,int year,long lastModified)throws Exception{
        return new Catalog.Item(new JSONObject().put("series_id",id).put("name",name).put("year",year==0?"":String.valueOf(year))
            .put("rating","10").put("last_modified",String.valueOf(lastModified)),"series");
    }

    /* ---------------- Titles ---------------- */

    @Test public void providerDecorationsAreIgnoredLikeThePanel(){
        assertEquals("matrix",TitleMatch.normalize("EN - The Matrix (1999)"));
        assertEquals(1999,TitleMatch.clean("EN - The Matrix (1999)").year);
        assertEquals("dune part two",TitleMatch.normalize("4K-EN - Dune: Part Two"));
        assertEquals("oppenheimer",TitleMatch.normalize("|EN| Oppenheimer [4K]"));
        assertEquals("office",TitleMatch.normalize("US: The Office"));
        assertEquals("csi miami",TitleMatch.normalize("CSI: Miami"));
        assertEquals("blade runner 2049",TitleMatch.normalize("Blade Runner 2049"));
        assertEquals("amelie and co",TitleMatch.normalize("Amélie & Co."));
        assertEquals("schindlers list",TitleMatch.normalize("Schindler's List"));
    }

    /* ---------------- Ratings ---------------- */

    @Test public void providerRatingIsNeverShown()throws Exception{
        Catalog.Item m=movie("31001","Some Film",2020,"");
        assertEquals("the Xtream \"10\" is not a rating","",Ratings.label(m));
        assertEquals("2020",HomeCards.meta(m));
        assertEquals(-1,Ratings.value(m),0);
    }
    @Test public void tmdbRatingShowsOnTenPointScale()throws Exception{
        Catalog.Item m=movie("31002","Rated Film",2020,"");
        Ratings.seed(m,8.237);
        assertEquals("★ 8.2",Ratings.label(m));
        assertEquals("2020  ·  ★ 8.2",HomeCards.meta(m));
        Catalog.Item s=series("31003","Rated Show",2011,0);
        Ratings.seed(s,8.456);
        assertEquals("2011  ·  ★ 8.5  ·  Series",HomeCards.meta(s));
    }
    @Test public void missingRatingIsHiddenNotDefaulted()throws Exception{
        Catalog.Item m=movie("31004","Unrated Film",2024,"");
        Ratings.seed(m,-1);
        assertEquals("",Ratings.label(m));
        assertEquals("",Ratings.format(0));assertEquals("",Ratings.format(11));
        assertEquals("★ 10.0",Ratings.format(10));   // only when TMDB itself says 10 with enough votes
    }
    @Test public void renamedTitleIsLookedUpAgain()throws Exception{
        Ratings.seed(movie("31005","Old Name",2020,""),7.1);
        assertEquals("",Ratings.label(movie("31005","New Name",2020,"")));
    }
    @Test public void catalogFieldsForMatching()throws Exception{
        assertEquals("603",movie("1","The Matrix",1999,"603").tmdbId);
        assertEquals("",movie("2","X",1999,"0").tmdbId);
        assertEquals(2019,movie("3","EN - Parasite (2019)",0,"").year);
        assertFalse("series never use last_modified as 'new'",series("4","Show",2020,now()).isNew());
        assertEquals(now(),series("5","Show",2020,now()).lastModified,2);
    }

    /* ---------------- Trending ---------------- */

    private static Trending.Entry entry(String media,String id,String title,int year,double rating){
        return new Trending.Entry(media,id,title,title,year,rating);}

    @Test public void trendingMatchesByIdThenTitleAndYear()throws Exception{
        Catalog.Item matrix=movie("1","EN - The Matrix",1999,"603"),halloween78=movie("2","Halloween",1978,""),halloween18=movie("3","Halloween",2018,"");
        Catalog.Item got=series("10","Game of Thrones",2011,0);
        List<Trending.Match> m=Trending.match(Arrays.asList(
                entry("movie","999","Not On Server",2026,7),
                entry("movie","424139","Halloween",2018,6.5),
                entry("tv","1399","Game of Thrones",2011,8.5),
                entry("movie","603","The Matrix",1999,8.2),
                entry("movie","2","Halloween",1995,6)),       // no catalog Halloween from 1994–1996
            Arrays.asList(matrix,halloween78,halloween18),Collections.singletonList(got),10);
        assertEquals(3,m.size());
        assertSame(halloween18,m.get(0).item);
        assertSame(got,m.get(1).item);
        assertSame(matrix,m.get(2).item);
    }
    @Test public void trendingNeverGuessesBetweenUndatedNamesakes()throws Exception{
        List<Catalog.Item> movies=Arrays.asList(movie("1","Halloween",0,""),movie("2","Halloween",1978,""));
        assertTrue(Trending.match(Collections.singletonList(entry("movie","9","Halloween",2018,6)),movies,new ArrayList<>(),10).isEmpty());
        // One undated title of that name (plus its 4K copy) is the same film: the regular copy is used.
        Catalog.Item plain=movie("3","Barbie",0,""),uhd=movie("4","4K-EN - Barbie",0,"");
        List<Trending.Match> m=Trending.match(Collections.singletonList(entry("movie","9","Barbie",2023,7)),Arrays.asList(uhd,plain),new ArrayList<>(),10);
        assertSame(plain,m.get(0).item);
    }
    @Test public void trendingIdMustAgreeWithTitleOrYear()throws Exception{
        Catalog.Item wrongId=movie("1","Some Other Film",2001,"603");
        assertTrue(Trending.match(Collections.singletonList(entry("movie","603","The Matrix",1999,8)),Collections.singletonList(wrongId),new ArrayList<>(),10).isEmpty());
    }
    @Test public void trendingUsesEachTitleOnceAndRespectsLimitAndType()throws Exception{
        Catalog.Item a=movie("1","Alpha",2020,""),b=movie("2","Beta",2020,""),c=movie("3","Gamma",2020,"");
        List<Trending.Match> m=Trending.match(Arrays.asList(entry("movie","1","Alpha",2020,1),entry("movie","1","Alpha",2020,1),
                entry("tv","5","Beta",2020,1),entry("movie","2","Beta",2020,1),entry("movie","3","Gamma",2020,1)),
            Arrays.asList(a,b,c),new ArrayList<>(),2);
        assertEquals(2,m.size());assertSame(a,m.get(0).item);assertSame(b,m.get(1).item);
    }
    @Test public void trendingRatingIsSeparateFromRank()throws Exception{
        Catalog.Item a=movie("41001","Alpha",2020,"");
        Trending.Match m=Trending.match(Collections.singletonList(entry("movie","1","Alpha",2020,-1)),Collections.singletonList(a),new ArrayList<>(),10).get(0);
        Ratings.seed(m.item,m.entry.rating);
        assertEquals("rank #1 but no reliable rating","",Ratings.label(a));
    }

    /* ---------------- New series / new episodes ---------------- */

    private static List<SeriesNews.Episode> eps(long... added){
        List<SeriesNews.Episode> l=new ArrayList<>();for(int i=0;i<added.length;i++)l.add(new SeriesNews.Episode("e"+i,added[i]));return l;}
    private static List<SeriesNews.Episode> ids(String... id){
        List<SeriesNews.Episode> l=new ArrayList<>();for(String i:id)l.add(new SeriesNews.Episode(i,0));return l;}

    @Test public void firstImportAloneProvesNothing()throws Exception{
        long t=now();SeriesNews.State s=new SeriesNews.State();
        List<Catalog.Item> all=Arrays.asList(series("1","A",2001,t),series("2","B",2002,t),series("3","C",2003,t));
        s.onCatalog(all,t);
        for(Catalog.Item i:all)assertEquals(SeriesNews.NONE,s.badge(i.id,t).type);
        // A refresh with the same catalog (all last_modified touched again) changes nothing either.
        s.onCatalog(all,t+3600);
        for(Catalog.Item i:all)assertEquals(SeriesNews.NONE,s.badge(i.id,t+3600).type);
    }
    @Test public void olderSeriesWithNewEpisode()throws Exception{
        long t=now();SeriesNews.State s=new SeriesNews.State();
        s.onCatalog(Collections.singletonList(series("1","Old Show",2010,t-DAY)),t);
        s.record("1",t-DAY,eps(t-400*DAY,t-399*DAY,t-2*DAY),t);
        SeriesNews.Badge b=s.badge("1",t);
        assertEquals(SeriesNews.NEW_EPISODES,b.type);assertEquals("NEW EPISODES",b.label());assertEquals(t-2*DAY,b.at);
    }
    @Test public void recentlyAddedSeries()throws Exception{
        long t=now();SeriesNews.State s=new SeriesNews.State();
        s.onCatalog(Collections.singletonList(series("1","New Show",2026,t-3*DAY)),t);
        s.record("1",t-3*DAY,eps(t-3*DAY,t-3*DAY+60,t-3*DAY+120),t);
        assertEquals("NEW SERIES",s.badge("1",t).label());
    }
    @Test public void unchangedSeriesWithTouchedLastModified()throws Exception{
        long t=now();SeriesNews.State s=new SeriesNews.State();
        s.onCatalog(Collections.singletonList(series("1","Old Show",2008,t-3600)),t);
        s.record("1",t-3600,eps(t-500*DAY,t-499*DAY),t);
        assertEquals(SeriesNews.NONE,s.badge("1",t).type);
    }
    @Test public void newEpisodesPreferredWhenBothApply()throws Exception{
        long t=now();SeriesNews.State s=new SeriesNews.State();
        s.onCatalog(Collections.singletonList(series("1","Show",2026,t)),t);
        s.record("1",t,eps(t-10*DAY,t-10*DAY+60,t-DAY),t);   // series added 10 days ago, episode yesterday
        assertEquals(SeriesNews.NEW_EPISODES,s.badge("1",t).type);
    }
    @Test public void reImportedOldSeriesIsNotNew()throws Exception{
        long t=now();SeriesNews.State s=new SeriesNews.State();
        s.onCatalog(Collections.singletonList(series("1","Show",2001,t-20*DAY)),t-20*DAY);   // listed 20 days ago
        s.record("1",t,eps(t-2*DAY,t-2*DAY+60),t);                                          // every episode "added" 2 days ago
        assertEquals(SeriesNews.NONE,s.badge("1",t).type);
    }
    @Test public void episodeIdsWithoutTimes()throws Exception{
        long t=now();SeriesNews.State s=new SeriesNews.State();
        s.onCatalog(Collections.singletonList(series("1","Show",2015,t-5*DAY)),t-DAY);
        s.record("1",t-5*DAY,ids("a","b","c"),t-DAY);
        assertEquals("first check only records",SeriesNews.NONE,s.badge("1",t-DAY).type);
        s.record("1",t-3600,ids("a","b","c","d"),t);
        assertEquals(SeriesNews.NEW_EPISODES,s.badge("1",t).type);
        assertEquals(SeriesNews.NONE,s.badge("1",t+15*DAY).type);   // the badge ends after 14 days
        // Every ID replaced is a re-import, not new episodes.
        SeriesNews.State r=new SeriesNews.State();
        r.onCatalog(Collections.singletonList(series("2","Other",2015,t)),t-DAY);
        r.record("2",t-5*DAY,ids("a","b"),t-DAY);r.record("2",t-3600,ids("x","y"),t);
        assertEquals(SeriesNews.NONE,r.badge("2",t).type);
    }
    @Test public void seriesThatAppearsAfterTheBaseline()throws Exception{
        long t=now();SeriesNews.State s=new SeriesNews.State();
        List<Catalog.Item> first=new ArrayList<>();for(int i=0;i<50;i++)first.add(series(String.valueOf(i),"S"+i,2000,0));
        s.onCatalog(first,t-2*DAY);
        List<Catalog.Item> second=new ArrayList<>(first);second.add(series("99","Brand New",2026,t));
        s.onCatalog(second,t);
        assertEquals("NEW SERIES",s.badge("99",t).label());
        assertEquals(SeriesNews.NONE,s.badge("1",t).type);
        // After a long gap the previous catalog proves nothing about when a show arrived.
        SeriesNews.State gap=new SeriesNews.State();gap.onCatalog(first,t-30*DAY);gap.onCatalog(second,t);
        assertEquals(SeriesNews.NONE,gap.badge("99",t).type);
        // A sudden flood of new IDs (a partial catalog followed by a full one) is not proof either.
        SeriesNews.State partial=new SeriesNews.State();partial.onCatalog(first.subList(0,5),t-DAY);partial.onCatalog(second,t);
        assertEquals(SeriesNews.NONE,partial.badge("99",t).type);
    }
    @Test public void episodeChecksAreBoundedAndSkipUnchanged()throws Exception{
        long t=now();SeriesNews.State s=new SeriesNews.State();
        List<Catalog.Item> all=new ArrayList<>();
        for(int i=0;i<40;i++)all.add(series(String.valueOf(i),"S"+i,2000,t-i*3600));
        all.add(series("old","Old",2000,t-90*DAY));
        s.onCatalog(all,t);
        List<Catalog.Item> todo=s.candidates(all,t,12);
        assertEquals(12,todo.size());assertEquals("0",todo.get(0).id);
        for(Catalog.Item i:todo)s.record(i.id,i.lastModified,eps(t-400*DAY),t);
        List<Catalog.Item> next=s.candidates(all,t,12);
        assertFalse("checked and unchanged: skipped",next.contains(todo.get(0)));
        for(Catalog.Item i:next)assertNotEquals("not touched for 90 days","old",i.id);
    }
    @Test public void stateSurvivesSaving()throws Exception{
        long t=now();SeriesNews.State s=new SeriesNews.State();
        s.onCatalog(Collections.singletonList(series("1","Show",2010,t)),t);
        s.record("1",t,eps(t-400*DAY,t-2*DAY),t);
        SeriesNews.State back=SeriesNews.State.fromJson(new JSONObject(s.toJson().toString()));
        assertEquals(SeriesNews.NEW_EPISODES,back.badge("1",t).type);
        assertEquals(s.baselineAt,back.baselineAt);
    }
    @Test public void latestTvShowsPutsProvenNewsFirst()throws Exception{
        long t=now();
        Catalog.Item touched=series("1","Touched",2000,t),older=series("2","Older With Episode",2000,t-5*DAY),fresh=series("3","Fresh",2026,t-3*DAY);
        List<Catalog.Item> all=Arrays.asList(touched,older,fresh);
        SeriesNews.State s=new SeriesNews.State();s.onCatalog(all,t);
        s.record("2",t-5*DAY,eps(t-300*DAY,t-DAY),t);
        s.record("3",t-3*DAY,eps(t-3*DAY,t-3*DAY+60),t);
        SeriesNews.setForTest(s,all);
        List<Catalog.Item> latest=SeriesNews.latest(all,24);
        assertEquals(Arrays.asList("2","3","1"),Arrays.asList(latest.get(0).id,latest.get(1).id,latest.get(2).id));
        assertEquals("NEW EPISODES",HomeCards.badge(older));
        assertEquals("NEW SERIES",HomeCards.badge(fresh));
        assertNull("touched last_modified alone is no badge",HomeCards.badge(touched));
        SeriesNews.setForTest(new SeriesNews.State(),all);
    }
}

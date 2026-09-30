package com.flixtown.tv;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Pure catalog logic behind the redesigned Home (runs on the JVM, no device needed). */
public class HomeLogicTest {
    private static Catalog.Item movie(String id,String name,double rating,long added,String category,String backdrop)throws Exception{
        JSONObject j=new JSONObject().put("stream_id",id).put("name",name).put("rating",String.valueOf(rating))
            .put("added",String.valueOf(added)).put("category_id",category).put("stream_icon","https://img/"+id+".jpg");
        if(backdrop!=null)j.put("backdrop_path",new JSONArray().put(backdrop));
        return new Catalog.Item(j,"movie");
    }
    private static long now(){return System.currentTimeMillis()/1000;}

    @Test public void trendingPrefersHighlyRatedRecentTitles()throws Exception{
        long t=now();
        Catalog.Item oldGreat=movie("1","Old Great",9.5,t-900*86400L,"1",null);
        Catalog.Item newGood=movie("2","New Good",8.0,t-86400,"1",null);
        Catalog.Item newPoor=movie("3","New Poor",2.0,t-86400,"1",null);
        Catalog.Item oldPoor=movie("4","Old Poor",1.0,t-900*86400L,"1",null);
        List<Catalog.Item> top=Catalog.trending(Arrays.asList(oldGreat,newGood,newPoor,oldPoor),new ArrayList<>(),3);
        assertEquals(3,top.size());
        assertEquals("New Good",top.get(0).title);
        assertFalse("the weakest title is dropped",top.contains(oldPoor));
    }
    @Test public void trendingNeverReturnsMoreThanAsked()throws Exception{
        List<Catalog.Item> many=new ArrayList<>();
        for(int i=0;i<40;i++)many.add(movie(String.valueOf(i),"M"+i,i%10,now()-i*3600,"1",null));
        assertEquals(10,Catalog.trending(many,new ArrayList<>(),10).size());
        assertTrue(Catalog.trending(new ArrayList<>(),new ArrayList<>(),10).isEmpty());
    }
    @Test public void backdropDetectionIgnoresPosterFallback()throws Exception{
        assertFalse(movie("1","No Art",5,now(),"1",null).hasBackdrop());
        assertTrue(movie("2","Art",5,now(),"1","https://img/wide.jpg").hasBackdrop());
    }
    @Test public void newAndUhdBadgesUseRealData()throws Exception{
        assertTrue(movie("1","Fresh",5,now()-3600,"1",null).isNew());
        assertFalse(movie("2","Old",5,now()-30*86400L,"1",null).isNew());
        assertTrue(movie("3","Dune 4K",5,now(),"1",null).isUhd());
        assertFalse(movie("4","Dune",5,now(),"1",null).isUhd());
    }
    @Test public void recentSortsNewestFirst()throws Exception{
        long t=now();
        List<Catalog.Item> r=Catalog.recent(Arrays.asList(movie("1","A",5,t-300,"1",null),movie("2","B",5,t-100,"1",null),movie("3","C",5,t-200,"1",null)),2);
        assertEquals(Arrays.asList("B","C"),Arrays.asList(r.get(0).title,r.get(1).title));
    }
    @Test public void becauseYouWatchedUsesSameCategoryAndSkipsSelf()throws Exception{
        Catalog.Item seed=movie("1","Seed",7,now(),"5",null);
        List<Catalog.Item> pool=Arrays.asList(seed,movie("2","Same",6,now(),"5",null),movie("3","Other",9,now(),"8",null));
        List<Catalog.Item> similar=Catalog.similar(seed,pool,1);
        assertEquals("Same",similar.get(0).title);
        for(Catalog.Item i:Catalog.similar(seed,pool,5))assertNotEquals("1",i.id);
    }
    @Test public void progressPercentAndRemaining(){
        Catalog.Progress p=new Catalog.Progress(30*60000L,120*60000L,"","",false);
        assertEquals(25,p.percent());assertEquals(90,p.remainingMinutes());
        assertEquals(0,new Catalog.Progress(0,0,"","",true).percent());
    }
    @Test public void cardMetaJoinSkipsEmptyParts(){
        assertEquals("2019  ·  ★ 7.6",HomeCards.join("2019","","★ 7.6",null));
        assertEquals("",HomeCards.join("",null));
    }
    @Test public void settingsLanguageNamesAreReadable(){
        java.util.Locale saved=java.util.Locale.getDefault();
        try{java.util.Locale.setDefault(java.util.Locale.US);
            assertEquals("Spanish",SettingsPage.languageName("es"));
            assertEquals("English",SettingsPage.languageName("en"));
            for(String code:SettingsPage.LANGUAGE_CODES)assertNotEquals(code,SettingsPage.languageName(code));
        }finally{java.util.Locale.setDefault(saved);}
    }
}

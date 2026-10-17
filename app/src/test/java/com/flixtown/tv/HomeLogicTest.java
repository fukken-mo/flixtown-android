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

    @Test public void backdropDetectionIgnoresPosterFallback()throws Exception{
        assertFalse(movie("1","No Art",5,now(),"1",null).hasBackdrop());
        assertTrue(movie("2","Art",5,now(),"1","https://img/wide.jpg").hasBackdrop());
    }
    @Test public void newAndUhdBadgesUseRealData()throws Exception{
        assertFalse("upload time alone is not a release date",movie("1","Fresh",5,now()-3600,"1",null).isNew());
        assertFalse(movie("2","Old",5,now()-30*86400L,"1",null).isNew());
        assertTrue(movie("3","Dune 4K",5,now(),"1",null).isUhd());
        assertFalse(movie("4","Dune",5,now(),"1",null).isUhd());
    }
    @Test public void recentSortsNewestFirst()throws Exception{
        long t=now();
        List<Catalog.Item> r=Catalog.recent(Arrays.asList(movie("1","A",5,t-300,"1",null),movie("2","B",5,t-100,"1",null),movie("3","C",5,t-200,"1",null)),2);
        assertEquals(Arrays.asList("B","C"),Arrays.asList(r.get(0).title,r.get(1).title));
    }
    @Test public void seriesRecentKeepsTiesAndMissingDatesInProviderOrder()throws Exception{
        Catalog.Item a=movie("1","First",0,100,"1",null),b=movie("2","Second",0,100,"1",null);
        Catalog.Item c=movie("3","Missing first",0,0,"1",null),d=movie("4","Missing second",0,0,"1",null);
        List<Catalog.Item> input=Arrays.asList(c,a,d,b);
        assertEquals(Arrays.asList(a,b,c,d),Catalog.recentSeries(input));
        assertEquals(Arrays.asList(c,a,d,b),input);
    }
    @Test public void seriesRecentUsesAdditionNotPremiereOrTitle()throws Exception{
        Catalog.Item old=new Catalog.Item(new JSONObject().put("series_id","1").put("name","Z Old show")
            .put("added",200).put("last_modified",50).put("release_date","2010-01-01"),"series");
        Catalog.Item newer=new Catalog.Item(new JSONObject().put("series_id","2").put("name","A New show")
            .put("added",100).put("last_modified",300).put("release_date","2026-01-01"),"series");
        assertEquals(Arrays.asList(old,newer),Catalog.recentSeries(Arrays.asList(newer,old)));
        assertTrue(Catalog.recentSeries(new ArrayList<>()).isEmpty());
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

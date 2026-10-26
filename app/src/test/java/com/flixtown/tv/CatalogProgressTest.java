package com.flixtown.tv;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * Continue Watching and watched status around the end of a title: an error or Back near the end keeps
 * the interrupted title and its position; only a normal end (or Up next's Play now) moves it on.
 */
public class CatalogProgressTest {
    private static final long DURATION=300000;   // a 5-minute episode, as in the emulator test
    private Prefs prefs;private Context c;

    @Before public void setUp(){prefs=new Prefs();c=new FakeContext(prefs);}

    private String episode(){return prefs.getString("resume_episode_series:5002","");}
    private long position(String key){return prefs.getLong("resume_position_"+key,0);}
    private boolean watched(String kind,String id,String ep){return prefs.getBoolean(ViewerOptions.watchedKey(kind,id,ep),false);}
    private boolean listed(String key){return (","+prefs.getString("continue_ids","")+",").contains(","+key+",");}

    // ---- Errors and stops near the end: nothing is marked watched ----

    @Test public void lateEpisodeErrorKeepsTheEpisodeAndPosition(){
        // the player saves every 5 s while playing, then once more when the error card is closed
        Catalog.saveProgress(c,"series","5002","9101","mkv","9102","mkv",285000,DURATION,true);
        Catalog.saveProgress(c,"series","5002","9101","mkv","9102","mkv",289000,DURATION,true);
        assertEquals("9101",episode());
        assertEquals(289000,position("series:5002"));
        assertEquals("9102",prefs.getString("resume_next_series:5002",""));
        assertFalse(watched("series","5002","9101"));
        assertTrue(listed("series:5002"));
    }
    @Test public void errorInTheLastSecondStillKeepsTheEpisode(){
        Catalog.saveProgress(c,"series","5002","9101","mkv","9102","mkv",299000,DURATION,true);
        assertEquals("9101",episode());assertEquals(299000,position("series:5002"));assertFalse(watched("series","5002","9101"));
    }
    @Test public void lateErrorInTheLastEpisodeKeepsIt(){
        Catalog.saveProgress(c,"series","5002","9110","mkv","","mkv",295000,DURATION,true);
        assertEquals("9110",episode());assertEquals(295000,position("series:5002"));
        assertFalse(watched("series","5002","9110"));assertTrue(listed("series:5002"));
    }
    @Test public void lateMovieErrorKeepsTheMovieAndPosition(){
        // past 95 % and inside the last 30 s of a 2-hour movie
        Catalog.saveProgress(c,"movie","1000",null,null,null,null,7180000,7200000,true);
        assertEquals(7180000,position("movie:1000"));
        assertFalse(watched("movie","1000",null));assertTrue(listed("movie:1000"));
    }
    @Test public void savingAfterAnErrorNeverClearsAFinishedFlagOrSetsOne(){
        prefs.edit().putBoolean(ViewerOptions.watchedKey("series","5002","9101"),true).apply();   // Mark as watched earlier
        Catalog.saveProgress(c,"series","5002","9101","mkv","9102","mkv",289000,DURATION,true);
        assertTrue("an explicit Mark as watched stays",watched("series","5002","9101"));
        assertEquals("9101",episode());
    }
    @Test public void midEpisodeErrorStillKeepsThePosition(){
        Catalog.saveProgress(c,"series","5002","9101","mkv","9102","mkv",149000,DURATION,true);
        assertEquals("9101",episode());assertEquals(149000,position("series:5002"));
    }
    @Test public void underFifteenSecondsIsNotSaved(){
        Catalog.saveProgress(c,"series","5002","9101","mkv","9102","mkv",14000,DURATION,true);
        assertFalse(listed("series:5002"));assertEquals("",episode());
    }

    // ---- Normal endings ----

    @Test public void normalEpisodeEndQueuesTheNextEpisode(){
        Catalog.saveProgress(c,"series","5002","9101","mkv","9102","mkv",289000,DURATION,true);
        Catalog.markFinished(c,"series","5002","9101","mkv","9102","mkv",true);
        assertTrue(watched("series","5002","9101"));
        assertEquals("9102",episode());assertEquals(0,position("series:5002"));
        assertTrue("the show stays in Continue Watching on the next episode",listed("series:5002"));
    }
    @Test public void normalEndOfTheLastEpisodeLeavesContinueWatching(){
        Catalog.saveProgress(c,"series","5002","9110","mkv","","mkv",290000,DURATION,true);
        Catalog.markFinished(c,"series","5002","9110","mkv","","mkv",true);
        assertTrue(watched("series","5002","9110"));assertFalse(listed("series:5002"));
    }
    @Test public void normalEndWithAnUnknownEpisodeListKeepsTheShow(){
        Catalog.markFinished(c,"series","5002","9105","mkv","","mkv",false);
        assertTrue(watched("series","5002","9105"));assertTrue(listed("series:5002"));
        assertTrue(prefs.getBoolean(Catalog.DONE+"series:5002",false));
    }
    @Test public void normalMovieEndLeavesContinueWatching(){
        Catalog.saveProgress(c,"movie","1000",null,null,null,null,7180000,7200000,true);
        Catalog.markFinished(c,"movie","1000",null,null,null,null,true);
        assertTrue(watched("movie","1000",null));assertFalse(listed("movie:1000"));assertEquals(0,position("movie:1000"));
    }
    @Test public void resumingAfterALateErrorAndFinishingMovesOn(){
        Catalog.saveProgress(c,"series","5002","9101","mkv","9102","mkv",289000,DURATION,true);   // error
        Catalog.saveProgress(c,"series","5002","9101","mkv","9102","mkv",295000,DURATION,true);   // resumed, playing
        assertEquals("9101",episode());
        Catalog.markFinished(c,"series","5002","9101","mkv","9102","mkv",true);                   // reached the end
        assertEquals("9102",episode());assertTrue(watched("series","5002","9101"));
    }

    // ---- In-memory SharedPreferences ----

    private static final class FakeContext extends ContextWrapper {
        private final SharedPreferences prefs;
        FakeContext(SharedPreferences p){super(null);prefs=p;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
        @Override public Context getApplicationContext(){return this;}
    }
    private static final class Prefs implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>();
        @Override public Map<String,?> getAll(){return new HashMap<>(values);}
        @Override public String getString(String k,String d){Object v=values.get(k);return v instanceof String?(String)v:d;}
        @SuppressWarnings("unchecked") @Override public Set<String> getStringSet(String k,Set<String> d){Object v=values.get(k);return v instanceof Set?(Set<String>)v:d;}
        @Override public int getInt(String k,int d){Object v=values.get(k);return v instanceof Integer?(Integer)v:d;}
        @Override public long getLong(String k,long d){Object v=values.get(k);return v instanceof Long?(Long)v:d;}
        @Override public float getFloat(String k,float d){Object v=values.get(k);return v instanceof Float?(Float)v:d;}
        @Override public boolean getBoolean(String k,boolean d){Object v=values.get(k);return v instanceof Boolean?(Boolean)v:d;}
        @Override public boolean contains(String k){return values.containsKey(k);}
        @Override public Editor edit(){return new Ed();}
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
        private final class Ed implements Editor {
            final Map<String,Object> put=new HashMap<>();final java.util.HashSet<String> removed=new java.util.HashSet<>();boolean clear;
            @Override public Editor putString(String k,String v){put.put(k,v);return this;}
            @Override public Editor putStringSet(String k,Set<String> v){put.put(k,v);return this;}
            @Override public Editor putInt(String k,int v){put.put(k,v);return this;}
            @Override public Editor putLong(String k,long v){put.put(k,v);return this;}
            @Override public Editor putFloat(String k,float v){put.put(k,v);return this;}
            @Override public Editor putBoolean(String k,boolean v){put.put(k,v);return this;}
            @Override public Editor remove(String k){removed.add(k);return this;}
            @Override public Editor clear(){clear=true;return this;}
            @Override public boolean commit(){apply();return true;}
            @Override public void apply(){if(clear)values.clear();for(String k:removed)values.remove(k);
                for(Map.Entry<String,Object> e:put.entrySet()){if(e.getValue()==null)values.remove(e.getKey());else values.put(e.getKey(),e.getValue());}}
        }
    }
}

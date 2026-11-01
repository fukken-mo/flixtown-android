package com.flixtown.tv;
import static org.junit.Assert.*;
import org.junit.Test;
import android.content.SharedPreferences;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
public class ProfilePrefsTest {
 @Test public void newProfileStartsEmptyWithoutTouchingExistingDefault(){Prefs global=new Prefs(),own=new Prefs();global.edit().putLong("resume_position_movie:1",120000).putString("favorites","|movie:1|").putBoolean("watched_series:2:3",true).putString("server","https://fixture.invalid").apply();ProfilePrefs view=new ProfilePrefs(global,own);assertFalse(view.contains("resume_position_movie:1"));assertFalse(view.contains("favorites"));assertFalse(view.getAll().containsKey("watched_series:2:3"));assertEquals("https://fixture.invalid",view.getString("server",""));assertEquals(120000,global.getLong("resume_position_movie:1",0));}
 @Test public void writesAndLateCallbacksStayInTheirOwnProfile(){Prefs global=new Prefs(),a=new Prefs(),b=new Prefs();ProfilePrefs one=new ProfilePrefs(global,a),two=new ProfilePrefs(global,b);one.edit().putLong("resume_position_movie:1",100000).putString("favorites","|movie:1|").putBoolean("watched_series:2:3",true).apply();two.edit().putLong("resume_position_movie:1",20000).apply();one.edit().putLong("resume_position_movie:1",110000).apply();assertEquals(110000,one.getLong("resume_position_movie:1",0));assertEquals(20000,two.getLong("resume_position_movie:1",0));assertFalse(two.getBoolean("watched_series:2:3",false));assertFalse(global.contains("favorites"));}
 @Test public void noticesAndDeviceSettingsRemainAccountOrDeviceWide(){Prefs g=new Prefs(),p=new Prefs();ProfilePrefs view=new ProfilePrefs(g,p);view.edit().putBoolean("notice_seen_report:42",true).putBoolean("autoplay_next",false).putBoolean("watched_movie:1",true).apply();assertTrue(g.getBoolean("notice_seen_report:42",false));assertFalse(g.getBoolean("autoplay_next",true));assertFalse(p.contains("notice_seen_report:42"));assertTrue(p.getBoolean("watched_movie:1",false));}
 @Test public void clearCannotDeleteAnotherProfilesHistory(){Prefs g=new Prefs(),p=new Prefs();g.edit().putLong("resume_position_movie:1",50000).apply();ProfilePrefs view=new ProfilePrefs(g,p);view.edit().putLong("resume_position_movie:2",60000).apply();assertTrue(view.edit().clear().commit());assertEquals(50000,g.getLong("resume_position_movie:1",0));assertFalse(view.contains("resume_position_movie:2"));}
 @Test public void listenersOnlyObserveTheProfilesHistoryAndCommonSettings(){Prefs g=new Prefs(),p=new Prefs();ProfilePrefs view=new ProfilePrefs(g,p);java.util.List<String> seen=new java.util.ArrayList<>();SharedPreferences.OnSharedPreferenceChangeListener listener=(x,k)->seen.add(k);view.registerOnSharedPreferenceChangeListener(listener);g.edit().putLong("resume_position_movie:1",50000).apply();assertTrue(seen.isEmpty());view.edit().putLong("resume_position_movie:2",60000).putBoolean("autoplay_next",true).apply();assertEquals(2,seen.size());assertTrue(seen.contains("resume_position_movie:2"));assertTrue(seen.contains("autoplay_next"));view.unregisterOnSharedPreferenceChangeListener(listener);view.edit().putLong("resume_position_movie:3",70000).apply();assertEquals(2,seen.size());}
    private static final class Prefs implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>(); final java.util.Set<OnSharedPreferenceChangeListener> listeners=new java.util.HashSet<>();
        @Override public Map<String,?> getAll(){return new HashMap<>(values);}
        @Override public String getString(String k,String d){Object v=values.get(k);return v instanceof String?(String)v:d;}
        @SuppressWarnings("unchecked") @Override public Set<String> getStringSet(String k,Set<String> d){Object v=values.get(k);return v instanceof Set?(Set<String>)v:d;}
        @Override public int getInt(String k,int d){Object v=values.get(k);return v instanceof Integer?(Integer)v:d;}
        @Override public long getLong(String k,long d){Object v=values.get(k);return v instanceof Long?(Long)v:d;}
        @Override public float getFloat(String k,float d){Object v=values.get(k);return v instanceof Float?(Float)v:d;}
        @Override public boolean getBoolean(String k,boolean d){Object v=values.get(k);return v instanceof Boolean?(Boolean)v:d;}
        @Override public boolean contains(String k){return values.containsKey(k);}
        @Override public Editor edit(){return new Ed();}
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listeners.add(l);}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listeners.remove(l);}
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
                for(Map.Entry<String,Object> e:put.entrySet()){if(e.getValue()==null)values.remove(e.getKey());else values.put(e.getKey(),e.getValue());}
                java.util.Set<String> changed=new java.util.HashSet<>(put.keySet());changed.addAll(removed);for(String k:changed)for(OnSharedPreferenceChangeListener l:new java.util.HashSet<>(listeners))l.onSharedPreferenceChanged(Prefs.this,k);}
        }
    }
}

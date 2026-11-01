package com.flixtown.tv;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Fixed-profile view: an in-flight callback keeps writing its original profile after a switch. */
final class ProfilePrefs implements SharedPreferences {
    final SharedPreferences global,personal;
    private final Map<OnSharedPreferenceChangeListener,OnSharedPreferenceChangeListener[]> listeners=new HashMap<>();
    ProfilePrefs(SharedPreferences global,SharedPreferences personal){this.global=global;this.personal=personal;}
    static boolean personal(String k){return k!=null&&(k.startsWith("resume_")||k.startsWith("watched_")||k.equals("favorites")||k.equals("continue_ids"));}
    private SharedPreferences source(String k){return personal(k)?personal:global;}
    @Override public Map<String,?> getAll(){Map<String,Object> all=new HashMap<>();for(Map.Entry<String,?> e:global.getAll().entrySet())if(!personal(e.getKey()))all.put(e.getKey(),e.getValue());for(Map.Entry<String,?> e:personal.getAll().entrySet())if(personal(e.getKey()))all.put(e.getKey(),e.getValue());return all;}
    @Override public String getString(String k,String d){return source(k).getString(k,d);}
    @Override public Set<String> getStringSet(String k,Set<String> d){return source(k).getStringSet(k,d);}
    @Override public int getInt(String k,int d){return source(k).getInt(k,d);}
    @Override public long getLong(String k,long d){return source(k).getLong(k,d);}
    @Override public float getFloat(String k,float d){return source(k).getFloat(k,d);}
    @Override public boolean getBoolean(String k,boolean d){return source(k).getBoolean(k,d);}
    @Override public boolean contains(String k){return source(k).contains(k);}
    @Override public Editor edit(){return new Edit();}
    @Override public synchronized void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){
        if(listeners.containsKey(l))return;
        OnSharedPreferenceChangeListener g=(p,k)->{if(k!=null&&!personal(k))l.onSharedPreferenceChanged(this,k);};
        OnSharedPreferenceChangeListener h=(p,k)->{if(k!=null&&personal(k))l.onSharedPreferenceChanged(this,k);};
        listeners.put(l,new OnSharedPreferenceChangeListener[]{g,h});global.registerOnSharedPreferenceChangeListener(g);personal.registerOnSharedPreferenceChangeListener(h);
    }
    @Override public synchronized void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){OnSharedPreferenceChangeListener[] a=listeners.remove(l);if(a!=null){global.unregisterOnSharedPreferenceChangeListener(a[0]);personal.unregisterOnSharedPreferenceChangeListener(a[1]);}}
    private final class Edit implements Editor {
        final Editor g=global.edit(),p=personal.edit();boolean usedG,usedP;
        private Editor target(String k){if(personal(k)){usedP=true;return p;}usedG=true;return g;}
        @Override public Editor putString(String k,String v){target(k).putString(k,v);return this;}
        @Override public Editor putStringSet(String k,Set<String> v){target(k).putStringSet(k,v==null?null:new HashSet<>(v));return this;}
        @Override public Editor putInt(String k,int v){target(k).putInt(k,v);return this;}
        @Override public Editor putLong(String k,long v){target(k).putLong(k,v);return this;}
        @Override public Editor putFloat(String k,float v){target(k).putFloat(k,v);return this;}
        @Override public Editor putBoolean(String k,boolean v){target(k).putBoolean(k,v);return this;}
        @Override public Editor remove(String k){target(k).remove(k);return this;}
        @Override public Editor clear(){for(String k:getAll().keySet())remove(k);return this;}
        @Override public boolean commit(){boolean a=!usedP||p.commit(),b=!usedG||g.commit();return a&&b;}
        @Override public void apply(){if(usedP)p.apply();if(usedG)g.apply();}
    }
}

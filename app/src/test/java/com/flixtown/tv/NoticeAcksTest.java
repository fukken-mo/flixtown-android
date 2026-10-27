package com.flixtown.tv;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * Dismissed Home notices across sign-out, same-account sign-in and account switches, through the real
 * sign-out and sign-in cleanup ({@link SignOut#run}, {@link SignOut#beforeSignIn}).
 */
public class NoticeAcksTest {
    private static final String A="0048213977",B="0012345678",R42="report:42",R43="report:43";
    private FakeContext c;

    @Before public void setUp()throws Exception{c=new FakeContext(Files.createTempDirectory("acks").toFile());}

    private void signIn(String user){SignOut.beforeSignIn(c,user);}
    private void signOut(){SignOut.run(c);}
    private boolean seen(String user,String key){return NoticeAcks.seen(c,SignOut.ownerId(user),key);}
    private void dismiss(String user,String key){NoticeAcks.mark(c,SignOut.ownerId(user),key);}
    private SharedPreferences flix(){return c.getSharedPreferences("flix",0);}

    @Test public void dismissSurvivesSignOutAndSameAccountSignIn(){
        signIn(A);dismiss(A,R42);
        signOut();assertTrue("kept while signed out",seen(A,R42));
        signIn(A);assertTrue("same account signs in again",seen(A,R42));
        signOut();signIn(A);signOut();signIn(A);assertTrue("repeatedly",seen(A,R42));
    }
    @Test public void anotherAccountInheritsNothingAndCannotUndoTheFirstAccountsDismiss(){
        signIn(A);dismiss(A,R42);signOut();
        signIn(B);
        assertFalse("B never inherits A's Dismiss",seen(B,R42));
        dismiss(B,R43);
        signOut();signIn(A);
        assertTrue("A's Dismiss survives B using the TV",seen(A,R42));
        assertFalse("A never inherits B's Dismiss",seen(A,R43));
        signOut();signIn(B);
        assertTrue("B keeps its own",seen(B,R43));assertFalse(seen(B,R42));
    }
    @Test public void laterIsNotStoredAndNewResolutionsAppear(){
        signIn(A);dismiss(A,R42);
        assertFalse("Later stores nothing: still unread",seen(A,"report:44"));
        assertFalse("a new resolution is unread",seen(A,R43));
        assertTrue(seen(A,R42));
    }
    @Test public void earlierVersionsFlagsMoveToTheirOwnerOnly(){
        // As left by 3.8.19 and earlier: one unscoped flag with the recorded owner A.
        signIn(A);flix().edit().putBoolean(NoticeAcks.LEGACY+R42,true).apply();
        assertTrue("read before moving",seen(A,R42));
        assertFalse("never for another account",seen(B,R42));
        signOut();signIn(B);   // the sign-in cleanup used to delete A's flags here
        assertFalse(flix().contains(NoticeAcks.LEGACY+R42));
        assertFalse(seen(B,R42));
        signOut();signIn(A);
        assertTrue("A's earlier Dismiss survives B's sign-in",seen(A,R42));
    }
    @Test public void unattributableEarlierFlagsAreDropped(){
        flix().edit().putBoolean(NoticeAcks.LEGACY+R42,true).apply();   // no owner recorded
        signIn(A);
        assertFalse(seen(A,R42));assertFalse(flix().contains(NoticeAcks.LEGACY+R42));
    }
    @Test public void noAccountNoAcknowledgement(){
        NoticeAcks.mark(c,"",R42);NoticeAcks.mark(c,null,R42);
        assertFalse(NoticeAcks.seen(c,"",R42));assertFalse(NoticeAcks.seen(c,null,R42));
        assertTrue(c.getSharedPreferences(NoticeAcks.FILE,0).getAll().isEmpty());
    }
    @Test public void storageIsBoundedOldestFirst()throws Exception{
        String a=SignOut.ownerId(A);
        for(int i=0;i<NoticeAcks.MAX+5;i++){NoticeAcks.mark(c,a,"report:"+i);if(i==0||i==NoticeAcks.MAX+3)Thread.sleep(2);}
        assertEquals(NoticeAcks.MAX,c.getSharedPreferences(NoticeAcks.FILE,0).getAll().size());
        assertFalse("oldest forgotten",NoticeAcks.seen(c,a,"report:0"));
        assertTrue("newest kept",NoticeAcks.seen(c,a,"report:"+(NoticeAcks.MAX+4)));
    }
    @Test public void entriesNeverContainTheUsername(){
        signIn(A);dismiss(A,R42);
        for(String k:c.getSharedPreferences(NoticeAcks.FILE,0).getAll().keySet())assertFalse(k,k.contains(A));
    }

    // ---- In-memory SharedPreferences, one per file name ----

    private static final class FakeContext extends ContextWrapper {
        private final Map<String,Prefs> files=new HashMap<>();private final File dir;
        FakeContext(File dir){super(null);this.dir=dir;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return files.computeIfAbsent(name,n->new Prefs());}
        @Override public Context getApplicationContext(){return this;}
        @Override public File getFilesDir(){return dir;}
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

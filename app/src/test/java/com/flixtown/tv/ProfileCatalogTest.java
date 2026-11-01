package com.flixtown.tv;
import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONObject;
import java.util.Arrays;

public class ProfileCatalogTest {
    private static final String A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static void rejects(Runnable r){try{r.run();fail("must reject");}catch(IllegalArgumentException expected){}}
    @Test public void initialDefaultDoesNotBecomeKids(){ProfileCatalog c=ProfileCatalog.initial();assertEquals("default",c.selected);assertFalse(c.profiles.get(0).kids);rejects(()->c.edit("default","Kids","avatar_01",true));}
    @Test public void avatarChoiceDoesNotDetermineKids(){ProfileCatalog c=ProfileCatalog.initial().add("Mo","avatar_01",false).add("Kids","avatar_01",true);assertFalse(c.profiles.get(1).kids);assertTrue(c.profiles.get(2).kids);}
    @Test public void stableIdSurvivesRenameAndAvatarChange(){ProfileCatalog c=ProfileCatalog.initial().add("Mo","avatar_02",false);String id=c.profiles.get(1).id;c=c.select(id).edit(id,"Dad","avatar_28",false);assertEquals(id,c.selected);assertEquals(id,c.profiles.get(1).id);assertEquals("Dad",c.profiles.get(1).name);}
    @Test public void sixProfilesMaximum(){ProfileCatalog c=ProfileCatalog.initial();for(int i=0;i<5;i++)c=c.add("Person "+i,"avatar_01",false);ProfileCatalog full=c;assertEquals(6,full.profiles.size());rejects(()->full.add("Extra","avatar_01",false));}
    @Test public void deletionReturnsToDefaultAndNeverReusesId(){ProfileCatalog c=ProfileCatalog.initial().add("Mo","avatar_02",false);String id=c.profiles.get(1).id;c=c.select(id).remove(id);assertEquals("default",c.selected);c=c.add("Mo","avatar_02",false);assertNotEquals(id,c.profiles.get(1).id);rejects(()->ProfileCatalog.initial().remove("default"));}
    @Test public void allPackAvatarsAndOnlyPackAvatars(){for(int i=1;i<=28;i++)assertTrue(ProfileCatalog.validAvatar(String.format(java.util.Locale.ROOT,"avatar_%02d",i)));for(String s:new String[]{null,"avatar_00","avatar_29","../avatar_01","https://image","avatar_1"})assertFalse(ProfileCatalog.validAvatar(s));}
    @Test public void unicodeNamesAndLimits(){assertEquals("Mo",ProfileCatalog.normalizeName(" Mo "));assertEquals("موافـق",ProfileCatalog.normalizeName("موافـق"));assertEquals("Movie 🎬",ProfileCatalog.normalizeName("Movie 🎬"));rejects(()->ProfileCatalog.normalizeName(" "));rejects(()->ProfileCatalog.normalizeName("Bad\nName"));rejects(()->ProfileCatalog.normalizeName("bad\u202ename"));rejects(()->ProfileCatalog.normalizeName("abcdefghijklmnopqrstuvwxyz"));}
    @Test public void roundTripRetainsEverything()throws Exception{ProfileCatalog c=ProfileCatalog.initial().add("Mo","avatar_28",false).add("Kids","avatar_04",true);c=c.select(c.profiles.get(2).id);ProfileCatalog d=ProfileCatalog.decode(c.encode());assertEquals(c.encode(),d.encode());}
    @Test public void scopesSeparateAccountsAndProfiles(){ProfileCatalog c=ProfileCatalog.initial().add("Mo","avatar_02",false);String id=c.profiles.get(1).id;assertNotEquals(ProfileCatalog.historyScope(A,"default"),ProfileCatalog.historyScope(A,id));assertNotEquals(ProfileCatalog.historyScope(A,id),ProfileCatalog.historyScope(B,id));rejects(()->ProfileCatalog.historyScope("username",id));rejects(()->ProfileCatalog.historyScope(A,"../bad"));}
    @Test public void rejectsUnknownSelectionAndDuplicates(){ProfileCatalog c=ProfileCatalog.initial();rejects(()->c.select("missing"));rejects(()->new ProfileCatalog(Arrays.asList(c.profiles.get(0),c.profiles.get(0)),"default"));}
    @Test public void badRemoteCatalogNeverPartiallyAccepted()throws Exception{String raw=ProfileCatalog.initial().encode();for(String bad:new String[]{"{}",raw.replace("\"kids\":false","\"kids\":\"false\""),raw.replace("avatar_01","../../x"),raw.replace("\"version\":1","\"version\":2")}){try{ProfileCatalog.decode(bad);fail("accepted malformed catalog");}catch(Exception expected){}}}
}

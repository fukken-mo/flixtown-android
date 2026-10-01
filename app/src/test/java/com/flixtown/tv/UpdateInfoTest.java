package com.flixtown.tv;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class UpdateInfoTest {
    private static final String PKG="com.myflixtown.tv.native";
    private static final String SHA="ab".repeat(32);

    private static JSONObject release(long code) throws Exception {
        return new JSONObject().put("ok",true).put("package",PKG).put("update_version_code",code)
            .put("update_version_name","3.4.1").put("update_apk_url","https://example.com/flixtown.apk")
            .put("update_sha256",SHA.toUpperCase()).put("update_signer_sha256","cd".repeat(32))
            .put("update_notes","Faster start").put("update_required",false).put("update_size",9_000_000);
    }

    @Test public void newerVersionIsOffered() throws Exception {
        UpdateInfo u=UpdateInfo.parse(release(10007),PKG,10006,false);
        assertNotNull(u);
        assertEquals(10007,u.versionCode);assertEquals("3.4.1",u.versionName);
        assertEquals(SHA,u.sha256);                                   // normalised to lower case
        assertEquals("cd".repeat(32),u.signerSha256);assertEquals("Faster start",u.notes);
        assertEquals("8.6 MB",u.sizeLabel());
    }
    @Test public void sameOrOlderVersionIsNotOffered() throws Exception {
        assertNull(UpdateInfo.parse(release(10006),PKG,10006,false));
        assertNull(UpdateInfo.parse(release(10005),PKG,10006,false));
    }
    @Test public void otherAppsReleaseIsNotOffered() throws Exception {
        assertNull(UpdateInfo.parse(release(20000),PKG+".preview",10006,false));
    }
    @Test public void nothingPublishedIsNotAnUpdate() throws Exception {
        assertNull(UpdateInfo.parse(new JSONObject().put("ok",true).put("package",PKG).put("update_version_code",0),PKG,10006,false));
    }
    @Test public void panelFailureIsAnErrorNotUpToDate() throws Exception {
        try{UpdateInfo.parse(new JSONObject().put("ok",false).put("error","The APK link returned HTTP 404."),PKG,10006,false);fail();}
        catch(UpdateInfo.Problem p){assertEquals("The APK link returned HTTP 404.",p.getMessage());}
        try{UpdateInfo.parse(new JSONObject().put("update_version_code",1),PKG,10006,false);fail();}
        catch(UpdateInfo.Problem p){assertTrue(p.getMessage().length()>0);}
    }
    @Test public void insecureLinkOrMissingChecksumIsRefused() throws Exception {
        JSONObject http=release(10007).put("update_apk_url","http://example.com/flixtown.apk");
        try{UpdateInfo.parse(http,PKG,10006,false);fail();}catch(UpdateInfo.Problem expected){}
        assertNotNull(UpdateInfo.parse(http,PKG,10006,true));        // QA build on the emulator only
        try{UpdateInfo.parse(release(10007).put("update_sha256",""),PKG,10006,false);fail();}catch(UpdateInfo.Problem expected){}
        try{UpdateInfo.parse(release(10007).put("update_sha256","xyz"),PKG,10006,false);fail();}catch(UpdateInfo.Problem expected){}
    }
    @Test public void missingSignerIsAllowedButBlank() throws Exception {
        UpdateInfo u=UpdateInfo.parse(release(10007).put("update_signer_sha256",JSONObject.NULL),PKG,10006,false);
        assertEquals("",u.signerSha256);
    }
    @Test public void cacheBustingKeepsQueryAndFragment(){
        assertEquals("https://a.b/x.apk?_ft=9",UpdateInfo.cacheBusted("https://a.b/x.apk","9"));
        assertEquals("https://a.b/x.apk?k=v&_ft=9",UpdateInfo.cacheBusted("https://a.b/x.apk?k=v","9"));
        assertEquals("https://a.b/x.apk?_ft=9#f",UpdateInfo.cacheBusted("https://a.b/x.apk#f","9"));
    }
    @Test public void hexIsLowerCaseAndPadded(){
        assertEquals("000fff",UpdateInfo.hex(new byte[]{0,15,(byte)255}));
    }
}

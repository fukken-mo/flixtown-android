package com.flixtown.tv;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackReportTest {
    private PlaybackReport movie(){return new PlaybackReport("movie","123",null,12500,31,"Acme TV","video/avc 1920x1080","audio/ac3 6ch","");}
    @Test public void validMediaIdentity(){assertTrue(movie().valid());assertFalse(new PlaybackReport("trailer","1",null,0,31,"","","","").valid());
        assertFalse(new PlaybackReport("series","1",null,0,31,"","","","").valid());
        assertTrue(new PlaybackReport("series","1","23",0,31,"","","","").valid());}
    @Test public void payloadContainsNoCredentialsOrUrl()throws Exception{
        JSONObject o=movie().payload(0);assertEquals(123,o.getInt("media_id"));assertEquals("stutter",o.getString("issue"));
        assertEquals(12500,o.getLong("position_ms"));assertFalse(o.has("url"));assertFalse(o.has("username"));assertFalse(o.has("password"));
        assertFalse(o.has("title"));assertFalse(o.has("session"));assertEquals(14,o.length());assertEquals("",o.getString("audio_diagnostics"));
    }
    @Test public void requestIdIsStableForRetriesAndDifferentForNewReports()throws Exception{
        PlaybackReport r=movie();assertEquals(r.payload(0).getString("request_id"),r.payload(0).getString("request_id"));
        assertNotEquals(r.requestId,movie().requestId);assertTrue(r.requestId.matches("[a-f0-9-]{36}"));}
    @Test public void stripsUrlsAndCredentials(){assertEquals("",PlaybackReport.clean("https://host/user/pass/file",80));
        assertEquals("",PlaybackReport.clean("password=hidden",80));assertEquals("TV",PlaybackReport.clean("T\nV",120));}
    @Test public void positionIsBounded(){assertEquals(0,new PlaybackReport("movie","1",null,-1,31,"","","","").position);
        assertEquals(604800000,new PlaybackReport("movie","1",null,Long.MAX_VALUE,31,"","","","").position);}
    @Test public void endpointRequiresHttpsWithoutCredentials(){assertTrue(ReportsApi.safeUrl("https://example.invalid/api/report.php"));
        assertFalse(ReportsApi.safeUrl("http://example.invalid"));assertFalse(ReportsApi.safeUrl("https://user:pass@example.invalid"));
        assertFalse(ReportsApi.safeUrl("https://example.invalid/?token=x"));assertFalse(ReportsApi.safeUrl("https://example.invalid/#x"));}
    @Test(expected=IllegalArgumentException.class) public void rejectsUnknownIssue()throws Exception{movie().payload(6);}
}

package com.flixtown.tv;
import org.junit.Test;
import org.json.JSONObject;
import java.util.*;
import static org.junit.Assert.*;
public class SyncRecordTest {
 @Test public void whitelist(){
  assertEquals("resume:series:9",SyncRecord.entity("resume_next_ext_series:9"));
  assertEquals("watched:series:9:10",SyncRecord.entity("watched_series:9:10"));
  for(String k:new String[]{"account_data","account_iv","password","server","request_seen_1","notice_seen_1","subtitle_size","resume_art_movie:1"})assertNull(k,SyncRecord.entity(k));
  assertFalse(SyncRecord.valid("resume:movie:https://secret"));assertFalse(SyncRecord.valid("watched:series:9"));
 }
 @Test public void movieAndPrivateData()throws Exception{
  Map<String,Object> p=new HashMap<>();p.put("resume_position_movie:1",123456L);p.put("resume_duration_movie:1",7200000L);p.put("account_data","secret");p.put("resume_art_movie:1","https://secret");
  JSONObject r=SyncRecord.capture(p,"resume:movie:1");assertTrue(r.getBoolean("present"));assertEquals(123456,r.getLong("position"));assertFalse(r.toString().contains("secret"));
 }
 @Test public void queueAtZeroAndCompletionMarker()throws Exception{
  Map<String,Object> p=new HashMap<>();p.put("resume_episode_series:9","10");p.put("resume_done_series:9",true);
  JSONObject r=SyncRecord.capture(p,"resume:series:9");assertTrue(r.getBoolean("present"));assertEquals(0,r.getLong("position"));assertTrue(r.getBoolean("done"));
 }
 @Test public void explicitRemovalIsTombstone()throws Exception{
  JSONObject r=SyncRecord.capture(Collections.emptyMap(),"resume:movie:1");assertFalse(r.getBoolean("present"));assertEquals(1,r.length());
  assertFalse(SyncRecord.capture(Collections.emptyMap(),"watched:movie:1").getBoolean("present"));
 }
 @Test public void favoritesArePerTitle(){
  assertEquals(new HashSet<>(Arrays.asList("movie:1","series:2")),SyncRecord.favorites("|movie:1||series:2|"));
  assertEquals(new HashSet<>(Arrays.asList("movie:1","movie:3")),SyncRecord.favoriteChanges("|movie:1||series:2|","|movie:3||series:2|"));
  assertTrue(SyncRecord.favoriteChanges("|movie:1|","|movie:1|").isEmpty());
 }
 @Test public void noMetadataUrlOrCredentialText()throws Exception{
  Map<String,Object> p=new HashMap<>();p.put("resume_episode_series:1","2");p.put("resume_label_series:1","password=hidden");
  assertEquals("",SyncRecord.capture(p,"resume:series:1").getString("label"));
 }
 @Test public void compareOperationDoesNotLoseNewerLocalEdit(){assertTrue(SyncRecord.matchesOperation("a","a"));assertFalse(SyncRecord.matchesOperation("b","a"));assertFalse(SyncRecord.matchesOperation(null,"a"));}
 @Test public void legacyExtensionsAreSanitized()throws Exception{
  Map<String,Object> p=new HashMap<>();p.put("resume_episode_series:1","2");p.put("resume_extension_series:1","bad/path");p.put("resume_next_series:1","https://bad");
  JSONObject r=SyncRecord.capture(p,"resume:series:1");assertEquals("mp4",r.getString("extension"));assertEquals("",r.getString("next"));
 }
 private static JSONObject resume(String episode)throws Exception{
  return new JSONObject().put("present",true).put("position",20000).put("duration",90000).put("episode",episode).put("next","")
   .put("extension","mkv").put("next_ext","mp4").put("label","S1 · E1").put("done",false);
 }
 @Test public void remoteRecordsAreCheckedBeforeImport()throws Exception{
  assertTrue(SyncRecord.validRemote("resume:series:9",resume("10")));
  assertTrue(SyncRecord.validRemote("resume:movie:1",resume("")));
  assertTrue(SyncRecord.validRemote("resume:movie:1",new JSONObject().put("present",false)));
  assertTrue(SyncRecord.validRemote("watched:series:9:10",new JSONObject().put("present",true)));
  assertFalse("series needs an episode",SyncRecord.validRemote("resume:series:9",resume("")));
  assertFalse("movie has no episode",SyncRecord.validRemote("resume:movie:1",resume("10")));
  assertFalse("episode is numeric",SyncRecord.validRemote("resume:series:9",resume("10/../x")));
  assertFalse("extension ends up in a stream address",SyncRecord.validRemote("resume:series:9",resume("10").put("extension","mp4?x=1")));
  assertFalse(SyncRecord.validRemote("resume:series:9",resume("10").put("next","https://x")));
  assertFalse("label with an address",SyncRecord.validRemote("resume:series:9",resume("10").put("label","see https://x")));
  assertFalse("label too long",SyncRecord.validRemote("resume:series:9",resume("10").put("label",new String(new char[241]).replace('\0','a'))));
  assertFalse("position must be a number",SyncRecord.validRemote("resume:series:9",resume("10").put("position","20000")));
  assertFalse("position bounded",SyncRecord.validRemote("resume:series:9",resume("10").put("position",604800001L)));
  assertFalse("negative position",SyncRecord.validRemote("resume:series:9",resume("10").put("position",-1)));
  assertFalse("missing field",SyncRecord.validRemote("resume:series:9",new JSONObject().put("present",true)));
  assertFalse("present must be a boolean",SyncRecord.validRemote("watched:movie:1",new JSONObject().put("present","true")));
  assertFalse("unknown key",SyncRecord.validRemote("server",new JSONObject().put("present",true)));
  assertFalse(SyncRecord.validRemote("watched:movie:1",null));
 }
}

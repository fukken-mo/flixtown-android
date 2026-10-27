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
}

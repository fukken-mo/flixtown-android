package com.flixtown.tv;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;

public class IntroMarkerTest {
 private JSONObject series()throws Exception{return new JSONObject("{info:{tmdb_id:'118357'},episodes:{'1':[{id:'99',season:1,episode_num:2}]}}");}
 private JSONObject manifest()throws Exception{return new JSONObject("{schema:1,markers:[{tmdb:'118357',season:1,episode:2,start_ms:20000,end_ms:80000,duration_ms:600000}]}");}
 @Test public void explicitIdentityAndBoundaries()throws Exception{
  IntroMarker m=IntroMarker.find(manifest(),series(),"99");assertNotNull(m);
  assertFalse(m.available(19999,600000,true));assertTrue(m.available(20000,600000,true));
  assertTrue(m.available(79999,600000,true));assertFalse(m.available(80000,600000,true));
  assertFalse(m.available(30000,610000,true));assertFalse(m.available(30000,600000,false));
 }
 @Test public void noTitleOrEpisodePositionFallback()throws Exception{
  JSONObject s=series();s.getJSONObject("info").remove("tmdb_id");s.getJSONObject("info").put("name","1883");
  assertNull(IntroMarker.find(manifest(),s,"99"));assertNull(IntroMarker.find(manifest(),series(),"98"));
  s=series();s.getJSONObject("episodes").getJSONArray("1").getJSONObject(0).remove("episode_num");assertNull(IntroMarker.find(manifest(),s,"99"));
 }
 @Test public void wrongShowSeasonAndEpisodeFailClosed()throws Exception{
  for(String key:new String[]{"tmdb","season","episode"}){JSONObject m=manifest();m.getJSONArray("markers").getJSONObject(0).put(key,999);assertNull(IntroMarker.find(m,series(),"99"));}
 }
 @Test public void duplicateIdentityRejected()throws Exception{
  JSONObject m=manifest();m.getJSONArray("markers").put(m.getJSONArray("markers").getJSONObject(0));assertNull(IntroMarker.find(m,series(),"99"));
  JSONObject s=series();s.getJSONObject("episodes").getJSONArray("1").put(s.getJSONObject("episodes").getJSONArray("1").getJSONObject(0));assertNull(IntroMarker.find(manifest(),s,"99"));
 }
 @Test public void invalidRangesRejected()throws Exception{
  for(long[] r:new long[][]{{-1,80000,600000},{20000,20000,600000},{20000,700000,600000},{20000,250000,600000}}){JSONObject m=manifest();JSONObject v=m.getJSONArray("markers").getJSONObject(0);v.put("start_ms",r[0]);v.put("end_ms",r[1]);v.put("duration_ms",r[2]);assertNull(IntroMarker.find(m,series(),"99"));}
 }
 @Test public void missingMalformedAndUnsupportedSchema()throws Exception{
  assertNull(IntroMarker.find(null,series(),"99"));assertNull(IntroMarker.find(manifest(),null,"99"));assertNull(IntroMarker.find(new JSONObject(),series(),"99"));
  JSONObject m=manifest();m.put("schema",2);assertNull(IntroMarker.find(m,series(),"99"));
 }
}

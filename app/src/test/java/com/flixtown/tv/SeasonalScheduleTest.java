package com.flixtown.tv;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SeasonalScheduleTest {
    private static long d(String s){return SeasonalSchedule.parseDate(s);}
    private static String pick(SeasonalSchedule s,String date){SeasonalSchedule.Event e=s.select(d(date));return e==null?"default":e.id;}
    private final SeasonalSchedule def=SeasonalSchedule.defaults();

    @Test public void fixedEventsIncludeTheirFirstAndLastDay(){
        assertEquals("valentines",pick(def,"2027-02-10"));assertEquals("valentines",pick(def,"2027-02-15"));assertEquals("default",pick(def,"2027-02-16"));
        assertEquals("default",pick(def,"2027-03-12"));assertEquals("st_patricks",pick(def,"2027-03-13"));assertEquals("st_patricks",pick(def,"2027-03-18"));
        assertEquals("independence_day",pick(def,"2027-07-01"));assertEquals("independence_day",pick(def,"2027-07-05"));assertEquals("default",pick(def,"2027-07-06"));
        assertEquals("default",pick(def,"2026-10-14"));assertEquals("halloween",pick(def,"2026-10-15"));assertEquals("halloween",pick(def,"2026-10-31"));
        assertEquals("default",pick(def,"2026-11-01"));assertEquals("default",pick(def,"2026-06-15"));
    }
    @Test public void christmasAndNewYearAcrossTheYearBoundary(){
        assertEquals("default",pick(def,"2026-11-30"));assertEquals("christmas",pick(def,"2026-12-01"));assertEquals("christmas",pick(def,"2026-12-26"));
        assertEquals("new_year",pick(def,"2026-12-27"));assertEquals("new_year",pick(def,"2026-12-31"));
        assertEquals("new_year",pick(def,"2027-01-01"));assertEquals("new_year",pick(def,"2027-01-03"));assertEquals("default",pick(def,"2027-01-04"));
        assertEquals("new_year",pick(def,"2029-01-02"));
    }
    @Test public void easterThanksgivingAndMemorialDayFollowEachYear(){
        assertEquals(d("2026-04-05"),SeasonalSchedule.easter(2026));assertEquals(d("2027-03-28"),SeasonalSchedule.easter(2027));assertEquals(d("2028-04-16"),SeasonalSchedule.easter(2028));
        assertEquals(d("2026-11-26"),SeasonalSchedule.thanksgiving(2026));assertEquals(d("2027-11-25"),SeasonalSchedule.thanksgiving(2027));
        assertEquals(d("2026-05-25"),SeasonalSchedule.memorial(2026));assertEquals(d("2027-05-31"),SeasonalSchedule.memorial(2027));
        assertEquals("easter",pick(def,"2027-03-22"));assertEquals("easter",pick(def,"2027-03-29"));assertEquals("default",pick(def,"2027-03-30"));
        assertEquals("easter",pick(def,"2028-04-10"));assertEquals("default",pick(def,"2028-03-28"));   // a different Easter each year
        assertEquals("thanksgiving",pick(def,"2026-11-23"));assertEquals("thanksgiving",pick(def,"2026-11-27"));assertEquals("default",pick(def,"2026-11-28"));
        assertEquals("memorial_day",pick(def,"2027-05-28"));assertEquals("memorial_day",pick(def,"2027-05-31"));assertEquals("default",pick(def,"2027-06-01"));
    }
    @Test public void higherPriorityWinsThenShorterRangeThenId() throws Exception {
        SeasonalSchedule s=SeasonalSchedule.parse(new JSONObject("{\"version\":2,\"events\":["
            +"{\"id\":\"long_b\",\"rule\":\"fixed\",\"start\":\"10-01\",\"end\":\"10-31\",\"priority\":40},"
            +"{\"id\":\"short_c\",\"rule\":\"fixed\",\"start\":\"10-20\",\"end\":\"10-22\",\"priority\":40},"
            +"{\"id\":\"same_a\",\"rule\":\"fixed\",\"start\":\"10-20\",\"end\":\"10-22\",\"priority\":40},"
            +"{\"id\":\"top\",\"rule\":\"fixed\",\"start\":\"10-30\",\"end\":\"10-31\",\"priority\":90}]}"),true);
        assertEquals("long_b",pick(s,"2026-10-05"));
        assertEquals("same_a",pick(s,"2026-10-21"));     // equal priority and length: alphabetical id
        assertEquals("top",pick(s,"2026-10-31"));
    }
    @Test public void perYearDatesReplaceTheRuleForThatYearOnly() throws Exception {
        SeasonalSchedule s=SeasonalSchedule.parse(new JSONObject("{\"version\":2,\"events\":[{\"id\":\"easter\",\"rule\":\"easter\",\"start_offset\":-2,\"end_offset\":0,"
            +"\"dates\":{\"2027\":[\"2027-03-01\",\"2027-03-03\"]}}]}"),true);
        assertEquals("easter",pick(s,"2027-03-02"));assertEquals("default",pick(s,"2027-03-27"));
        assertEquals("easter",pick(s,"2028-04-15"));   // 2028 still follows the rule
    }
    @Test public void disabledEventsScheduleAndExpiredConfigFallBack() throws Exception {
        SeasonalSchedule off=SeasonalSchedule.parse(new JSONObject("{\"version\":2,\"enabled\":false,\"events\":[{\"id\":\"x\",\"start\":\"01-01\",\"end\":\"12-31\"}]}"),true);
        assertEquals("default",pick(off,"2027-06-01"));
        SeasonalSchedule one=SeasonalSchedule.parse(new JSONObject("{\"version\":2,\"events\":[{\"id\":\"x\",\"enabled\":false,\"start\":\"01-01\",\"end\":\"12-31\"}]}"),true);
        assertEquals("default",pick(one,"2027-06-01"));
        SeasonalSchedule old=SeasonalSchedule.parse(new JSONObject("{\"version\":2,\"valid_until\":\"2026-01-31\",\"events\":[{\"id\":\"x\",\"start\":\"01-01\",\"end\":\"12-31\"}]}"),true);
        assertTrue(old.expired(d("2026-02-01")));assertEquals("default",pick(old,"2026-02-01"));
    }
    @Test public void malformedConfigurationIsRejected(){
        String[] bad={"{\"events\":[]}","{\"version\":1}","{\"version\":1,\"events\":[{\"id\":\"Bad Id\",\"start\":\"01-01\",\"end\":\"01-02\"}]}",
            "{\"version\":1,\"events\":[{\"id\":\"a\",\"start\":\"13-01\",\"end\":\"01-02\"}]}","{\"version\":1,\"events\":[{\"id\":\"a\",\"rule\":\"moon\"}]}",
            "{\"version\":1,\"events\":[{\"id\":\"a\",\"start\":\"01-01\",\"end\":\"01-02\"},{\"id\":\"a\",\"start\":\"02-01\",\"end\":\"02-02\"}]}",
            "{\"version\":1,\"valid_until\":\"soon\",\"events\":[]}","{\"version\":1,\"events\":[{\"id\":\"a\",\"rule\":\"easter\",\"start_offset\":5,\"end_offset\":-5}]}"};
        for(String b:bad){try{SeasonalSchedule.parse(new JSONObject(b),true);fail("accepted: "+b);}catch(Exception expected){}}
    }
    @Test public void everydayPictureIsReadAndChecked() throws Exception {
        SeasonalSchedule s=SeasonalSchedule.parse(new JSONObject("{\"version\":1,\"enabled\":false,\"default_image\":\"https://panelsandapps.com/a/everyday-0123456789abcdef.jpg\",\"events\":[]}"),true,u->u.startsWith("https://panelsandapps.com/"));
        assertEquals("https://panelsandapps.com/a/everyday-0123456789abcdef.jpg",s.defaultImage);
        assertNull(s.select(d("2026-12-10")));   // seasonal off: no event, the everyday picture is what shows
        SeasonalSchedule bad=SeasonalSchedule.parse(new JSONObject("{\"version\":1,\"default_image\":\"https://elsewhere.example/x.jpg\",\"events\":[]}"),true,u->u.startsWith("https://panelsandapps.com/"));
        assertEquals("",bad.defaultImage);
        assertEquals("",SeasonalSchedule.defaults().defaultImage);   // built-in: the bundled background and logo
    }
    @Test public void displayTimeIsBoundedAndImagesAreChecked() throws Exception {
        SeasonalSchedule s=SeasonalSchedule.parse(new JSONObject("{\"version\":1,\"display_ms\":60000,\"events\":[{\"id\":\"a\",\"start\":\"01-01\",\"end\":\"12-31\",\"image\":\"http://evil.example/x.jpg\"}]}"),true,u->u.startsWith("https://panelsandapps.com/"));
        assertEquals(3000,s.displayMs);assertEquals("",s.events.get(0).image);   // the address is dropped, the event keeps the normal background
        assertEquals(800,SeasonalSchedule.parse(new JSONObject("{\"version\":1,\"display_ms\":1,\"events\":[]}"),true).displayMs);
    }
}

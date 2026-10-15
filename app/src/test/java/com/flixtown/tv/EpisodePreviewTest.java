package com.flixtown.tv;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class EpisodePreviewTest {
    @Test public void readsXtreamEpisodeInfo()throws Exception{
        JSONObject ep=new JSONObject().put("season",2).put("episode_num",3).put("title","The Visitor")
            .put("info",new JSONObject().put("plot","An unexpected guest arrives.")
                .put("movie_image","https://example.com/still.jpg").put("duration_secs",2940).put("air_date","2026-02-04"));
        assertEquals("S2 · E3  —  The Visitor",EpisodePreview.title(ep));
        assertEquals("An unexpected guest arrives.",EpisodePreview.description(ep));
        assertEquals("https://example.com/still.jpg",EpisodePreview.art(ep));
        assertEquals("49 min   ·   Aired 2026-02-04",EpisodePreview.meta(ep));
    }
    @Test public void emptyPlotFallsBackToOverviewNotSeriesPlot()throws Exception{
        JSONObject ep=new JSONObject().put("info",new JSONObject().put("plot","").put("overview","Episode-specific overview"));
        assertEquals("Episode-specific overview",EpisodePreview.description(ep));
    }
    @Test public void missingMetadataIsHonestAndDoesNotInventValues(){
        JSONObject ep=new JSONObject();
        assertEquals("Episode description unavailable.",EpisodePreview.description(ep));
        assertEquals("",EpisodePreview.art(ep));assertEquals("",EpisodePreview.meta(ep));
        assertEquals("Episode",EpisodePreview.title(ep));
    }
    @Test public void rootMetadataAndStringDurationAreSupported()throws Exception{
        JSONObject ep=new JSONObject().put("description","Root overview").put("still_url","https://example.com/a.jpg")
            .put("info",new JSONObject().put("duration","01:02:30"));
        assertEquals("Root overview",EpisodePreview.description(ep));
        assertEquals("62 min",EpisodePreview.meta(ep));assertEquals("https://example.com/a.jpg",EpisodePreview.art(ep));
    }
    @Test public void nullAndUnsafeArtworkDoNotBecomeUrls()throws Exception{
        JSONObject ep=new JSONObject().put("info",new JSONObject().put("plot","null").put("movie_image","file:///private"));
        assertEquals("Episode description unavailable.",EpisodePreview.description(ep));assertEquals("",EpisodePreview.art(ep));
    }
    @Test public void differentEpisodesNeverShareSynopsis()throws Exception{
        JSONObject a=new JSONObject().put("overview","First episode");
        JSONObject b=new JSONObject().put("overview","Second episode");
        assertNotEquals(EpisodePreview.description(a),EpisodePreview.description(b));
    }
}

package com.flixtown.tv;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ExternalSubtitlesTest {
    private static final String ENDPOINT="https://panel.example/api/subtitles/movie/4368?username=test&password=p%26x";
    private JSONObject track(String name,String mime)throws Exception{
        return new JSONObject().put("name",name).put("mime",mime).put("language","en").put("hearing_impaired",true);
    }
    private JSONObject reply(JSONArray tracks)throws Exception{return new JSONObject().put("schema",1).put("tracks",tracks);}
    @Test public void sidecarUsesAuthenticatedSameOriginAndEscapedFilename()throws Exception{
        JSONObject t=track("Godzilla Minus One (2023).en.hi.srt","application/x-subrip").put("url","https://evil.example/steal");
        List<ExternalSubtitles.Track> tracks=ExternalSubtitles.parse(ENDPOINT,reply(new JSONArray().put(t)));
        assertEquals(1,tracks.size());
        ExternalSubtitles.Track s=tracks.get(0);
        assertTrue(s.url.startsWith(ENDPOINT.replace("?","/file?")));
        assertTrue(s.url.endsWith("&name=Godzilla+Minus+One+%282023%29.en.hi.srt"));
        assertFalse(s.url.contains("evil.example"));assertTrue(s.hearingImpaired);
    }
    @Test public void rejectsPathsMimeAndDuplicates()throws Exception{
        JSONArray a=new JSONArray().put(track("../secret.srt","application/x-subrip"))
            .put(track("folder\\secret.srt","application/x-subrip"))
            .put(track("movie.en.srt","text/html"))
            .put(track("good.en.srt","application/x-subrip")).put(track("good.en.srt","application/x-subrip"));
        assertEquals(1,ExternalSubtitles.parse(ENDPOINT,reply(a)).size());
    }
    @Test public void supportsVttAndLimitsTracks()throws Exception{
        JSONArray a=new JSONArray();for(int i=0;i<40;i++)a.put(track("movie.en."+i+".vtt","text/vtt"));
        List<ExternalSubtitles.Track> tracks=ExternalSubtitles.parse(ENDPOINT,reply(a));
        assertEquals(32,tracks.size());assertEquals("text/vtt",tracks.get(0).mime);
    }
    @Test public void emptyIsValidButUnknownSchemaIsNot()throws Exception{
        assertTrue(ExternalSubtitles.parse(ENDPOINT,reply(new JSONArray())).isEmpty());
        try{ExternalSubtitles.parse(ENDPOINT,new JSONObject().put("schema",2));fail();}catch(java.io.IOException expected){}
    }
}

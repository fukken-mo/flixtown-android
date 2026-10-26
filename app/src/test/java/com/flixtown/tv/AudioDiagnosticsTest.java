package com.flixtown.tv;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class AudioDiagnosticsTest {
    private static AudioDiagnostics.Track t(String mime,String lang,int ch,int support,boolean selected){
        return new AudioDiagnostics.Track(mime,null,lang,ch,48000,support,selected);}
    private static final int OK=AudioDiagnostics.HANDLED,NO=AudioDiagnostics.SUBTYPE;

    @Test public void codecNames(){
        assertEquals("eac3-joc",AudioDiagnostics.codec("audio/eac3-joc",null));
        assertEquals("dts-hd",AudioDiagnostics.codec("audio/vnd.dts.hd",null));
        assertEquals("dts-x",AudioDiagnostics.codec("audio/vnd.dts.hd","dtsx"));
        assertEquals("truehd",AudioDiagnostics.codec("audio/true-hd",null));
        assertEquals("aac",AudioDiagnostics.codec("audio/mp4a-latm",null));
        assertEquals("unknown",AudioDiagnostics.codec(null,null));
        assertEquals("DTS 5.1",AudioDiagnostics.friendly(t("audio/vnd.dts","en",6,NO,false)));
        assertEquals("Dolby Digital Plus 7.1",AudioDiagnostics.friendly(t("audio/eac3","en",8,OK,true)));
    }
    @Test public void reportFieldNamesTheSelectedTrack(){
        List<AudioDiagnostics.Track> l=Arrays.asList(t("audio/vnd.dts","en",6,NO,false),t("audio/mp4a-latm","es",2,OK,true));
        assertEquals("aac 2ch 48k es ok",AudioDiagnostics.reportField(l));
        assertEquals("",AudioDiagnostics.errorField(l,Collections.emptyList()));
    }
    @Test public void noPlayableTrackIsExplainedInTheExistingFields(){
        List<AudioDiagnostics.Track> l=Arrays.asList(t("audio/vnd.dts","en",6,NO,false),t("audio/true-hd","en",8,NO,false));
        String f=AudioDiagnostics.reportField(l);
        assertTrue(f,f.startsWith("none selected: 2 tracks, 0 playable: dts 6ch 48k en unsupported"));
        assertTrue(f.length()<=80);
        assertEquals("audio:no_supported_track",AudioDiagnostics.errorField(l,Collections.emptyList()));
        assertTrue(AudioDiagnostics.nonePlayable(l));
    }
    @Test public void playableButNothingSelected(){
        List<AudioDiagnostics.Track> l=Collections.singletonList(t("audio/ac3","en",6,OK,false));
        assertEquals("audio:no_track_selected",AudioDiagnostics.errorField(l,Collections.emptyList()));
        assertFalse(AudioDiagnostics.nonePlayable(l));
    }
    @Test public void noAudioInStream(){
        assertEquals("no audio track in stream",AudioDiagnostics.reportField(new ArrayList<>()));
        assertEquals("",AudioDiagnostics.errorField(new ArrayList<>(),null));
    }
    @Test public void latestAudioErrorWinsAndOnlyClassNamesAreKept(){
        List<String> errors=AudioDiagnostics.keepLast(new ArrayList<>(),"underrun",4);
        errors=AudioDiagnostics.keepLast(errors,AudioDiagnostics.errorName("sink",new IllegalStateException("http://host/user/pass/1.mkv")),4);
        assertEquals("audio:sink_IllegalStateException",AudioDiagnostics.errorField(Collections.emptyList(),errors));
        assertEquals("duplicates are not repeated",2,AudioDiagnostics.keepLast(errors,"underrun",4).size());
    }
    @Test public void detailHasTechnicalFactsButNoAddressOrLabel(){
        List<AudioDiagnostics.Track> l=Arrays.asList(t("audio/vnd.dts","en",6,NO,false),t("audio/mp4a-latm","es",2,OK,true));
        String d=AudioDiagnostics.detail(l,"c2.android.aac.decoder","pcm16 2ch 48k decoded","pass=none max2ch out=hdmi dec[dts]=none",
            Collections.singletonList("underrun"));
        assertTrue(d,d.contains("dts 6ch 48k en unsupported"));
        assertTrue(d.contains("aac 2ch 48k es ok *selected"));
        assertTrue(d.contains("decoder=c2.android.aac.decoder"));
        assertTrue(d.contains("dec[dts]=none"));
        assertTrue(d.contains("errors=underrun"));
        assertTrue(d.length()<=1000);
        assertEquals("anything with an address is dropped",
            "",PlaybackReport.clean(AudioDiagnostics.detail(l,"x","y","http://host/live",null),1000));
    }
    @Test public void reportCarriesTheDiagnosticsField()throws Exception{
        PlaybackReport r=new PlaybackReport("series","1259","19671",1000,34,"Google Chromecast","video/hevc 3840x2160",
            "none selected: 1 track, 0 playable: dts 6ch 48k en unsupported","audio:no_supported_track","tracks=dts 6ch 48k en unsupported");
        org.json.JSONObject o=r.payload(1);
        assertEquals("no_audio",o.getString("issue"));
        assertEquals("audio:no_supported_track",o.getString("error_code"));
        assertEquals("tracks=dts 6ch 48k en unsupported",o.getString("audio_diagnostics"));
        assertEquals(19671,o.getInt("episode_id"));
    }
}

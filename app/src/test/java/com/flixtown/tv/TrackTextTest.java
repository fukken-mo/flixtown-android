package com.flixtown.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrackTextTest {
    @Test public void languagesHaveTheirPlainNames() {
        assertEquals("English", TrackText.languageLabel("en"));
        assertEquals("EN", TrackText.languageCode("eng"));
        assertEquals("PT", TrackText.languageCode("pt-br"));
        assertEquals("DE", TrackText.languageCode("deu"));
        assertEquals("", TrackText.languageCode("und"));
        assertEquals("English", TrackText.languageLabel("eng"));
        assertEquals("Portuguese (Brazil)", TrackText.languageLabel("pt-br"));
        assertEquals("Portuguese (Brazil)", TrackText.languageLabel("pt_BR"));
        assertEquals("", TrackText.languageLabel("und"));
        assertEquals("", TrackText.languageLabel(null));
    }

    @Test public void audioRowsShowTheLanguageAndPlainDetails() {
        assertArrayEquals(new String[]{"English", "Stereo"}, TrackText.trackParts("English · Stereo · AAC"));
        assertArrayEquals(new String[]{"English", "5.1 surround · Dolby Digital"}, TrackText.trackParts("English · 5.1 · Dolby Digital"));
        assertArrayEquals(new String[]{"English (Commentary)", "Stereo"}, TrackText.trackParts("English (Commentary) · Stereo · MP3"));
        assertArrayEquals(new String[]{"German", "5.1 surround · DTS · Can't play on this TV"},
            TrackText.trackParts("German · 5.1 · DTS — not supported on this TV"));
    }

    @Test public void subtitleRowsKeepForcedAndExternalAsDetails() {
        assertArrayEquals(new String[]{"French", "Forced"}, TrackText.trackParts("French (forced)"));
        assertArrayEquals(new String[]{"English", "SDH · External"}, TrackText.trackParts("English · SDH · External"));
        assertArrayEquals(new String[]{"Off", ""}, TrackText.trackParts("Off"));
    }
}

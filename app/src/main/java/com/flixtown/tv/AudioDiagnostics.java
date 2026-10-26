package com.flixtown.tv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Audio facts for a playback report and the Playback info screen (pure, unit-tested).
 *
 * Only technical facts: codec, channels, sample rate, language code, whether this TV can play the
 * track, and decoder / output / error class names. No stream address, account, title, track label
 * (free text from the file) or exception message is ever included.
 */
final class AudioDiagnostics {
    private AudioDiagnostics(){}
    /** Media3 C.FORMAT_* values: 4 handled, 3 exceeds capabilities, 2 DRM, 1 unsupported subtype, 0 unsupported type. */
    static final int HANDLED=4,EXCEEDS=3,DRM=2,SUBTYPE=1,TYPE=0;

    static final class Track {
        final String mime,codecs,language;final int channels,sampleRate,support;final boolean selected;
        Track(String mime,String codecs,String language,int channels,int sampleRate,int support,boolean selected){
            this.mime=mime==null?"":mime;this.codecs=codecs==null?"":codecs;this.language=language==null?"":language;
            this.channels=channels;this.sampleRate=sampleRate;this.support=support;this.selected=selected;}
        boolean playable(){return support==HANDLED||support==EXCEEDS;}
    }

    /** Short codec name: "eac3-joc", "dts-hd", "truehd", "aac" ... */
    static String codec(String mime,String codecs){
        String m=mime==null?"":mime.toLowerCase(Locale.US);
        switch(m){
            case "audio/mp4a-latm":return "aac";
            case "audio/ac3":return "ac3";
            case "audio/eac3":return "eac3";
            case "audio/eac3-joc":return "eac3-joc";
            case "audio/ac4":return "ac4";
            case "audio/vnd.dts":return "dts";
            case "audio/vnd.dts.hd":return codecs!=null&&codecs.contains("dtsx")?"dts-x":"dts-hd";
            case "audio/vnd.dts.uhd":return "dts-uhd";
            case "audio/true-hd":return "truehd";
            case "audio/mpeg":return "mp3";
            case "audio/mpeg-l2":return "mp2";
            case "audio/opus":return "opus";
            case "audio/vorbis":return "vorbis";
            case "audio/flac":return "flac";
            case "audio/raw":return "pcm";
            default:return m.startsWith("audio/")?m.substring(6):m.isEmpty()?"unknown":m;
        }
    }
    static String support(int s){
        switch(s){case HANDLED:return "ok";case EXCEEDS:return "exceeds";case DRM:return "drm";case SUBTYPE:return "unsupported";default:return "no-renderer";}
    }
    /** Viewer-facing codec name: "DTS 5.1", "Dolby Digital Plus 7.1", "AAC stereo". */
    static String friendly(Track t){
        String c=codec(t.mime,t.codecs),name;
        switch(c){
            case "ac3":name="Dolby Digital";break;case "eac3":name="Dolby Digital Plus";break;case "eac3-joc":name="Dolby Atmos (DD+)";break;
            case "truehd":name="Dolby TrueHD";break;case "ac4":name="Dolby AC-4";break;
            case "dts":name="DTS";break;case "dts-hd":name="DTS-HD";break;case "dts-x":name="DTS:X";break;case "dts-uhd":name="DTS:X";break;
            default:name=c.toUpperCase(Locale.US);
        }
        String ch=t.channels==1?"mono":t.channels==2?"stereo":t.channels==6?"5.1":t.channels==8?"7.1":t.channels>0?t.channels+"ch":"";
        return ch.isEmpty()?name:name+" "+ch;
    }
    static String describe(Track t){
        StringBuilder b=new StringBuilder(codec(t.mime,t.codecs));
        if(t.channels>0)b.append(' ').append(t.channels).append("ch");
        if(t.sampleRate>0)b.append(' ').append(t.sampleRate/1000).append('k');
        if(!t.language.isEmpty()&&!"und".equals(t.language))b.append(' ').append(t.language.replaceAll("[^A-Za-z-]",""));
        b.append(' ').append(support(t.support));
        return b.toString();
    }
    static Track selected(List<Track> tracks){for(Track t:tracks)if(t.selected)return t;return null;}
    static boolean noneSelected(List<Track> tracks){return !tracks.isEmpty() && selected(tracks)==null;}
    static boolean nonePlayable(List<Track> tracks){for(Track t:tracks)if(t.playable())return false;return !tracks.isEmpty();}

    /**
     * Fits the existing 80-character audio_format report field: the selected track, or why there is none
     * ("none selected: 2 tracks, 0 playable: dts 6ch 48k eng unsupported; ...").
     */
    static String reportField(List<Track> tracks){
        Track s=selected(tracks);
        if(s!=null)return clip(describe(s),80);
        if(tracks.isEmpty())return "no audio track in stream";
        int playable=0;for(Track t:tracks)if(t.playable())playable++;
        StringBuilder b=new StringBuilder("none selected: ").append(tracks.size()).append(tracks.size()==1?" track, ":" tracks, ").append(playable).append(" playable: ");
        for(int i=0;i<tracks.size();i++){if(i>0)b.append("; ");b.append(describe(tracks.get(i)));}
        return clip(b.toString(),80);
    }
    /** Full detail (up to 1000 characters) for the report's audio_diagnostics field and Playback info. */
    static String detail(List<Track> tracks,String decoder,String output,String device,List<String> errors){
        StringBuilder b=new StringBuilder("tracks=");
        if(tracks.isEmpty())b.append("none");
        for(int i=0;i<tracks.size();i++){if(i>0)b.append(", ");Track t=tracks.get(i);b.append(describe(t));if(t.selected)b.append(" *selected");}
        b.append(" | decoder=").append(blank(decoder)).append(" | output=").append(blank(output)).append(" | device=").append(blank(device));
        if(errors!=null&&!errors.isEmpty())b.append(" | errors=").append(join(", ",errors));
        return clip(PlaybackReport.clean(b.toString(),2000),1000);
    }
    /** Short error_code for an audio problem that did not stop playback ("" when there is none). */
    static String errorField(List<Track> tracks,List<String> errors){
        if(errors!=null&&!errors.isEmpty())return clip("audio:"+errors.get(errors.size()-1),80);
        if(nonePlayable(tracks))return "audio:no_supported_track";
        if(noneSelected(tracks))return "audio:no_track_selected";
        return "";
    }
    /** Only the class name of an exception (its message can contain an address). */
    static String errorName(String where,Throwable e){
        if(e==null)return where;
        String n=e.getClass().getName();n=n.substring(n.lastIndexOf('.')+1);
        Throwable c=e.getCause();
        if(c!=null){String cn=c.getClass().getName();n+="/"+cn.substring(cn.lastIndexOf('.')+1);}
        return where+"_"+n;
    }
    static List<String> keepLast(List<String> list,String add,int max){
        List<String> out=new ArrayList<>(list);if(!out.contains(add))out.add(add);
        while(out.size()>max)out.remove(0);
        return out;
    }
    /** Joins with a separator (the JDK's join needs API 26; the app supports API 23). */
    static String join(String sep,Iterable<String> parts){StringBuilder b=new StringBuilder();for(String p:parts){if(b.length()>0)b.append(sep);b.append(p);}return b.toString();}
    private static String blank(String s){return s==null||s.isEmpty()?"-":s;}
    private static String clip(String s,int max){return s.length()>max?s.substring(0,max):s;}
}

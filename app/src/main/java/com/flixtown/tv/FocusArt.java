package com.flixtown.tv;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rules for the full-screen artwork behind Home's rows (pure, unit-tested).
 *
 * Only real landscape artwork is used, at a size that suits the TV: a poster or a thumbnail is never
 * stretched across the screen (the background stays the normal branded one instead), and TMDB links
 * that point at a small rendition are asked for a large one.
 */
final class FocusArt {
    private FocusArt(){}
    /** A decoded backdrop must be at least this share of the screen width, and landscape. */
    static final float MIN_WIDTH_SHARE=0.5f,MIN_ASPECT=1.3f;
    private static final Pattern TMDB=Pattern.compile("^(https?://image\\.tmdb\\.org/t/p/)(w\\d+|original)(/.+)$");

    /** The landscape artwork for a title, or null when there is none (never the poster). */
    static String landscape(String poster,String... candidates){
        for(String url:candidates){
            if(url==null)continue;url=url.trim();
            if(url.isEmpty() || url.equals(poster))continue;
            if(url.startsWith("http://")||url.startsWith("https://")||url.startsWith("demo:"))return url;
        }
        return null;
    }
    /** TMDB renditions: w1280 for screens up to 1280 px wide, the original above that (often 1920 × 1080). */
    static String sized(String url,int screenWidth){
        if(url==null)return null;
        Matcher m=TMDB.matcher(url);if(!m.matches())return url;
        String want=screenWidth<=1280?"w1280":"original",have=m.group(2);
        if(have.equals("original") || have.equals(want))return url;
        int w=Integer.parseInt(have.substring(1));
        if(want.equals("w1280") && w>=1280)return url;
        return m.group(1)+want+m.group(3);
    }
    /** Decode width for the screen: the full width, but never more than 1920 px. */
    static int decodeWidth(int screenWidth){return Math.max(640,Math.min(1920,screenWidth));}
    /** True when a decoded image is real landscape art big enough for the screen (else: branded background). */
    static boolean suitable(int width,int height,int screenWidth){
        if(width<=0||height<=0)return false;
        return width>=screenWidth*MIN_WIDTH_SHARE && width>=height*MIN_ASPECT;
    }
}

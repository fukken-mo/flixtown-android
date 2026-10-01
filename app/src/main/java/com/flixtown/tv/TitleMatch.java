package com.flixtown.tv;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Title clean-up and comparison, the same rules as the panel's tmdb-match.php: provider
 * decorations ("EN - ", "4K-EN - ", "|EN| ", "[4K]", "(2019)") are removed, and titles compare
 * case-, accent- and punctuation-insensitively with "&" = "and" and no leading "The".
 * Only exact normalised matches count.
 */
final class TitleMatch {
    private TitleMatch(){}
    private static final Pattern BRACKET_PREFIX=Pattern.compile("^(?:\\[[^\\]]{1,12}\\]|\\|[^|]{1,12}\\|)\\s*(?:[-:|]\\s*)?");
    private static final Pattern CODE_PREFIX=Pattern.compile("^[A-Z0-9+]{2,5}(?:[- ][A-Z0-9+]{2,5})?\\s*(?:\\|\\s*|-\\s+)");
    private static final Pattern COUNTRY_PREFIX=Pattern.compile("^[A-Z]{2}:\\s+");
    private static final Pattern TAG=Pattern.compile("\\s*[\\[(](?:4K|UHD|FHD|HD|SD|HDR|HEVC|1080p|720p|2160p|MULTI(?:[- ]?SUBS?)?|VOSTFR|SUBS?|DUB(?:BED)?|[A-Z]{2})[\\])]",Pattern.CASE_INSENSITIVE);
    private static final Pattern QUALITY_SUFFIX=Pattern.compile("\\s+(?:4K|UHD|FHD|HDR|1080p|720p|2160p)$",Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_SUFFIX=Pattern.compile("^(.*\\S)\\s*[\\[(]((?:19|20)\\d{2})[\\])]$");
    private static final Pattern MARKS=Pattern.compile("\\p{Mn}+");
    private static final Pattern NOT_WORD=Pattern.compile("[^\\p{L}\\p{N}]+");

    static final class Clean{final String title;final int year;Clean(String t,int y){title=t;year=y;}}

    static Clean clean(String raw){
        String s=raw==null?"":raw.replaceAll("\\s+"," ").trim(),original=s;int year=0;
        for(int i=0;i<4;i++){
            String before=s;
            s=BRACKET_PREFIX.matcher(s).replaceFirst("");
            s=CODE_PREFIX.matcher(s).replaceFirst("");
            s=COUNTRY_PREFIX.matcher(s).replaceFirst("");
            s=TAG.matcher(s).replaceAll("");
            s=QUALITY_SUFFIX.matcher(s).replaceFirst("");
            Matcher m=YEAR_SUFFIX.matcher(s);
            if(m.matches()){s=m.group(1);year=Integer.parseInt(m.group(2));}
            s=s.trim();
            if(s.equals(before))break;
        }
        return new Clean(s.isEmpty()?original:s,year);
    }

    static String normalize(String raw){
        String s=clean(raw).title;
        s=MARKS.matcher(Normalizer.normalize(s,Normalizer.Form.NFD)).replaceAll("");
        s=s.toLowerCase(Locale.ROOT).replace("&"," and ").replaceAll("['’`]","");
        s=NOT_WORD.matcher(s).replaceAll(" ").trim();
        if(s.startsWith("the "))s=s.substring(4);
        return s;
    }
}

package com.flixtown.tv;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** A NEW badge means released in the last 30 UTC calendar days, never recently uploaded. */
final class ReleaseDates {
    static final long DAY=86400L, WINDOW=30*DAY;
    private ReleaseDates(){}
    static long parse(String value){
        if(value==null || !value.matches("\\d{4}-\\d{2}-\\d{2}"))return 0;
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
        format.setLenient(false);format.setTimeZone(TimeZone.getTimeZone("UTC"));
        ParsePosition pos=new ParsePosition(0);Date date=format.parse(value,pos);
        return date!=null && pos.getIndex()==value.length()?date.getTime()/1000:0;
    }
    static boolean recent(long release,long now){
        long today=Math.floorDiv(now,DAY)*DAY;
        return release>0 && release<=today && today-release<WINDOW;
    }
}

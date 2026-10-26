package com.flixtown.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Which seasonal startup background applies on a given local date (pure Java, unit-tested).
 *
 * Every event has an inclusive date range, computed per year from a rule:
 *   fixed         "start"/"end" as MM-DD; a range may cross the new year (Dec 27 – Jan 3)
 *   easter        Western (Gregorian) Easter Sunday + start_offset … end_offset days
 *   thanksgiving  U.S. Thanksgiving (4th Thursday of November) + offsets
 *   memorial      U.S. Memorial Day (last Monday of May) + offsets
 * Any event can also give exact dates for a particular year ("dates": {"2027": ["2027-03-25","2027-03-29"]}),
 * which replace the rule for that year. Start and end days are both included.
 *
 * When several events cover the day, the highest priority wins; on a tie the shorter (more specific)
 * range wins, then the alphabetically first id, so exactly one event (or none: the normal background)
 * is chosen, deterministically.
 */
final class SeasonalSchedule {
    static final class Event {
        final String id,name,rule,image,imageSha256;final boolean enabled;final int priority;
        final int startMonthDay,endMonthDay,startOffset,endOffset;   // fixed: month*100+day
        final JSONObject dates;
        Event(String id,String name,String rule,boolean enabled,int priority,int start,int end,int startOffset,int endOffset,JSONObject dates,String image,String imageSha256){
            this.id=id;this.name=name;this.rule=rule;this.enabled=enabled;this.priority=priority;this.startMonthDay=start;this.endMonthDay=end;
            this.startOffset=startOffset;this.endOffset=endOffset;this.dates=dates;this.image=image;this.imageSha256=imageSha256;}
        /** {startDay, endDay} (epoch days) of this event's occurrence that starts in {@code year}, or null. */
        long[] range(int year){
            JSONArray exact=dates==null?null:dates.optJSONArray(String.valueOf(year));
            if(exact!=null && exact.length()==2){long a=parseDate(exact.optString(0)),b=parseDate(exact.optString(1));
                return a!=Long.MIN_VALUE && b!=Long.MIN_VALUE && b>=a && b-a<=62?new long[]{a,b}:null;}
            switch(rule){
                case "fixed":{long a=day(year,startMonthDay/100,startMonthDay%100);
                    int endYear=endMonthDay<startMonthDay?year+1:year;return new long[]{a,day(endYear,endMonthDay/100,endMonthDay%100)};}
                case "easter":{long e=easter(year);return new long[]{e+startOffset,e+endOffset};}
                case "thanksgiving":{long t=thanksgiving(year);return new long[]{t+startOffset,t+endOffset};}
                case "memorial":{long m=memorial(year);return new long[]{m+startOffset,m+endOffset};}
                default:return null;
            }
        }
        boolean covers(long today){
            int year=civil(today)[0];
            for(int y=year-1;y<=year;y++){long[] r=range(y);if(r!=null && today>=r[0] && today<=r[1])return true;}
            return false;
        }
        long length(long today){
            int year=civil(today)[0];
            for(int y=year-1;y<=year;y++){long[] r=range(y);if(r!=null && today>=r[0] && today<=r[1])return r[1]-r[0];}
            return Long.MAX_VALUE;
        }
    }

    final boolean enabled;final int displayMs;final List<Event> events;final long validUntil;
    /** The everyday design: shown when no event applies, the event has no picture, or seasonal is off. */
    final String defaultImage,defaultImageSha256;
    static final int MIN_DISPLAY_MS=800,MAX_DISPLAY_MS=3000,DEFAULT_DISPLAY_MS=1500;
    private SeasonalSchedule(boolean enabled,int displayMs,List<Event> events,long validUntil,String defaultImage,String defaultImageSha256){
        this.enabled=enabled;this.displayMs=displayMs;this.events=Collections.unmodifiableList(events);this.validUntil=validUntil;
        this.defaultImage=defaultImage;this.defaultImageSha256=defaultImageSha256;}

    /** The event for {@code today} (epoch day), or null for the normal background. */
    Event select(long today){
        if(!enabled || (validUntil!=Long.MIN_VALUE && today>validUntil))return null;
        Event best=null;
        for(Event e:events){
            if(!e.enabled || !e.covers(today))continue;
            if(best==null || e.priority>best.priority
                || (e.priority==best.priority && (e.length(today)<best.length(today)
                    || (e.length(today)==best.length(today) && e.id.compareTo(best.id)<0))))best=e;
        }
        return best;
    }
    boolean expired(long today){return validUntil!=Long.MIN_VALUE && today>validUntil;}

    /** The built-in schedule: used offline, on a first launch, and whenever the panel's copy is unusable. */
    static SeasonalSchedule defaults(){
        try{return parse(new JSONObject(DEFAULT_JSON),false);}catch(Exception e){throw new IllegalStateException(e);}
    }
    static final String DEFAULT_JSON="{\"version\":1,\"enabled\":true,\"display_ms\":1500,\"events\":["
        +"{\"id\":\"new_year\",\"name\":\"New Year\",\"rule\":\"fixed\",\"start\":\"12-27\",\"end\":\"01-03\",\"priority\":60},"
        +"{\"id\":\"valentines\",\"name\":\"Valentine's Day\",\"rule\":\"fixed\",\"start\":\"02-10\",\"end\":\"02-15\",\"priority\":40},"
        +"{\"id\":\"st_patricks\",\"name\":\"St. Patrick's Day\",\"rule\":\"fixed\",\"start\":\"03-13\",\"end\":\"03-18\",\"priority\":40},"
        +"{\"id\":\"easter\",\"name\":\"Easter\",\"rule\":\"easter\",\"start_offset\":-6,\"end_offset\":1,\"priority\":45},"
        +"{\"id\":\"memorial_day\",\"name\":\"Memorial Day\",\"rule\":\"memorial\",\"start_offset\":-3,\"end_offset\":0,\"priority\":40},"
        +"{\"id\":\"independence_day\",\"name\":\"Independence Day\",\"rule\":\"fixed\",\"start\":\"07-01\",\"end\":\"07-05\",\"priority\":40},"
        +"{\"id\":\"halloween\",\"name\":\"Halloween\",\"rule\":\"fixed\",\"start\":\"10-15\",\"end\":\"10-31\",\"priority\":40},"
        +"{\"id\":\"thanksgiving\",\"name\":\"Thanksgiving\",\"rule\":\"thanksgiving\",\"start_offset\":-3,\"end_offset\":1,\"priority\":40},"
        +"{\"id\":\"christmas\",\"name\":\"Christmas\",\"rule\":\"fixed\",\"start\":\"12-01\",\"end\":\"12-26\",\"priority\":50}]}";

    /**
     * Strict parse of the panel's schedule. Throws on anything malformed, so a bad answer never
     * replaces the last good one. {@code imageCheck} validates image addresses (null to skip).
     */
    interface ImageCheck{boolean ok(String url);}
    static SeasonalSchedule parse(JSONObject o,boolean requireVersion) throws Exception {return parse(o,requireVersion,null);}
    static SeasonalSchedule parse(JSONObject o,boolean requireVersion,ImageCheck imageCheck) throws Exception {
        if(o==null)throw new IllegalArgumentException("empty");
        if(requireVersion && o.optInt("version",0)<=0)throw new IllegalArgumentException("version");
        int display=Math.max(MIN_DISPLAY_MS,Math.min(MAX_DISPLAY_MS,o.optInt("display_ms",DEFAULT_DISPLAY_MS)));
        JSONArray list=o.optJSONArray("events");if(list==null||list.length()>40)throw new IllegalArgumentException("events");
        List<Event> events=new ArrayList<>();java.util.Set<String> ids=new java.util.HashSet<>();
        for(int i=0;i<list.length();i++){
            JSONObject e=list.optJSONObject(i);if(e==null)throw new IllegalArgumentException("event "+i);
            String id=e.optString("id","");if(!id.matches("[a-z0-9_]{1,40}") || !ids.add(id))throw new IllegalArgumentException("id "+id);
            String rule=e.optString("rule","fixed");int start=0,end=0;
            if(rule.equals("fixed")){start=monthDay(e.optString("start"));end=monthDay(e.optString("end"));}
            else if(!rule.equals("easter")&&!rule.equals("thanksgiving")&&!rule.equals("memorial"))throw new IllegalArgumentException("rule "+rule);
            int so=e.optInt("start_offset",0),eo=e.optInt("end_offset",0);
            if(so<-60||eo>60||eo<so)throw new IllegalArgumentException("offsets "+id);
            String image=e.optString("image","").trim();
            if(!image.isEmpty() && imageCheck!=null && !imageCheck.ok(image))image="";      // an unusable address: this event uses the normal background
            String sha=e.optString("image_sha256","").trim().toLowerCase(Locale.US);if(!sha.matches("[0-9a-f]{64}"))sha="";
            events.add(new Event(id,e.optString("name",id),rule,e.optBoolean("enabled",true),Math.max(0,Math.min(1000,e.optInt("priority",40))),
                start,end,so,eo,e.optJSONObject("dates"),image,sha));
        }
        long valid=o.has("valid_until")?parseDate(o.optString("valid_until")):Long.MIN_VALUE;
        if(o.has("valid_until") && valid==Long.MIN_VALUE)throw new IllegalArgumentException("valid_until");
        String everyday=o.optString("default_image","").trim();
        if(!everyday.isEmpty() && imageCheck!=null && !imageCheck.ok(everyday))everyday="";
        String everydaySha=o.optString("default_image_sha256","").trim().toLowerCase(Locale.US);if(!everydaySha.matches("[0-9a-f]{64}"))everydaySha="";
        return new SeasonalSchedule(o.optBoolean("enabled",true),display,events,valid,everyday,everydaySha);
    }
    private static int monthDay(String s){
        if(s==null || !s.matches("\\d{2}-\\d{2}"))throw new IllegalArgumentException("date "+s);
        int m=Integer.parseInt(s.substring(0,2)),d=Integer.parseInt(s.substring(3));
        if(m<1||m>12||d<1||d>daysIn(2024,m))throw new IllegalArgumentException("date "+s);
        return m*100+d;
    }
    /** "YYYY-MM-DD" to an epoch day, or Long.MIN_VALUE. */
    static long parseDate(String s){
        if(s==null || !s.matches("\\d{4}-\\d{2}-\\d{2}"))return Long.MIN_VALUE;
        int y=Integer.parseInt(s.substring(0,4)),m=Integer.parseInt(s.substring(5,7)),d=Integer.parseInt(s.substring(8));
        if(m<1||m>12||d<1||d>daysIn(y,m))return Long.MIN_VALUE;
        return day(y,m,d);
    }

    /* ---------------- Calendar arithmetic (proleptic Gregorian, epoch day 0 = 1970-01-01) ---------------- */

    static long day(int y,int m,int d){
        if(m==2 && d==29 && daysIn(y,2)==28)d=28;   // a Feb 29 rule in a common year falls on Feb 28
        y-=m<=2?1:0;long era=(y>=0?y:y-399)/400;long yoe=y-era*400;
        long doy=(153L*(m+(m>2?-3:9))+2)/5+d-1;long doe=yoe*365+yoe/4-yoe/100+doy;
        return era*146097+doe-719468;
    }
    static int[] civil(long z){
        z+=719468;long era=(z>=0?z:z-146096)/146097;long doe=z-era*146097;
        long yoe=(doe-doe/1460+doe/36524-doe/146096)/365;long y=yoe+era*400;long doy=doe-(365*yoe+yoe/4-yoe/100);
        long mp=(5*doy+2)/153;long d=doy-(153*mp+2)/5+1;long m=mp<10?mp+3:mp-9;
        return new int[]{(int)(y+(m<=2?1:0)),(int)m,(int)d};
    }
    static int daysIn(int y,int m){
        switch(m){case 2:return (y%4==0&&(y%100!=0||y%400==0))?29:28;case 4:case 6:case 9:case 11:return 30;default:return 31;}
    }
    /** 0 = Monday … 6 = Sunday. */
    static int weekday(long day){return (int)Math.floorMod(day+3,7L);}
    /** Western Easter Sunday (Anonymous Gregorian algorithm). */
    static long easter(int y){
        int a=y%19,b=y/100,c=y%100,d=b/4,e=b%4,f=(b+8)/25,g=(b-f+1)/3,h=(19*a+b-d-g+15)%30,i=c/4,k=c%4,
            l=(32+2*e+2*i-h-k)%7,m=(a+11*h+22*l)/451,month=(h+l-7*m+114)/31,dayOfMonth=((h+l-7*m+114)%31)+1;
        return day(y,month,dayOfMonth);
    }
    /** U.S. Thanksgiving: the fourth Thursday of November. */
    static long thanksgiving(int y){long first=day(y,11,1);int toThursday=Math.floorMod(3-weekday(first),7);return first+toThursday+21;}
    /** U.S. Memorial Day: the last Monday of May. */
    static long memorial(int y){long last=day(y,5,31);return last-weekday(last);}
}

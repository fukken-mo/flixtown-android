package com.flixtown.tv;

import java.util.ArrayList;
import java.util.List;

/** Small, local viewing ledger. Seeks, buffering, pauses and replayed spans never add coverage. */
final class WatchCoverage {
    private static final int MAX_RANGES=256;
    private final List<long[]> ranges=new ArrayList<>();
    private long duration,lastPosition=-1,lastTime=-1;
    private boolean lastPlaying;

    WatchCoverage(long duration,String saved){
        this.duration=duration;
        if(saved==null||saved.length()>14000)return;
        try{
            String[] parts=saved.split(";");
            if(parts.length>MAX_RANGES+1||Long.parseLong(parts[0])!=duration)return;
            long end=-1;
            for(int i=1;i<parts.length;i++){
                String[] pair=parts[i].split(":");
                if(pair.length!=2)throw new IllegalArgumentException();
                long a=Long.parseLong(pair[0]),b=Long.parseLong(pair[1]);
                if(a<0||b<=a||b>duration||a<=end)throw new IllegalArgumentException();
                ranges.add(new long[]{a,b});end=b;
            }
        }catch(RuntimeException invalid){ranges.clear();}
    }
    void discontinuity(){lastPosition=-1;lastTime=-1;lastPlaying=false;}
    void sample(long position,long now,boolean playing,float speed){
        if(duration<=0||position<0||position>duration){discontinuity();return;}
        long wall=now-lastTime,delta=position-lastPosition;
        // Reject lifecycle gaps and implausible jumps even if a seek callback was missed.
        if(lastPlaying&&playing&&lastPosition>=0&&wall>0&&wall<=2000&&delta>0
                &&!Float.isNaN(speed)&&!Float.isInfinite(speed)&&speed>0&&delta<=wall*speed+750){
            add(lastPosition,position);
        }
        lastPosition=position;lastTime=now;lastPlaying=playing;
    }
    private void add(long a,long b){
        int i=0;while(i<ranges.size()&&ranges.get(i)[1]<a)i++;
        int start=i;while(i<ranges.size()&&ranges.get(i)[0]<=b){a=Math.min(a,ranges.get(i)[0]);b=Math.max(b,ranges.get(i)[1]);i++;}
        if(start==i&&ranges.size()>=MAX_RANGES)return; // bounded, conservatively undercounts
        for(int n=i-1;n>=start;n--)ranges.remove(n);
        ranges.add(start,new long[]{a,b});
    }
    long viewed(){long total=0;for(long[] r:ranges)total+=r[1]-r[0];return total;}
    boolean complete(long position){
        return duration>0&&position>=0&&position<=duration
                &&position>=duration-duration/10&&duration-position<=180000
                &&viewed()>=duration-duration/10;
    }
    String encode(){StringBuilder b=new StringBuilder().append(duration);for(long[] r:ranges)b.append(';').append(r[0]).append(':').append(r[1]);return b.toString();}
}

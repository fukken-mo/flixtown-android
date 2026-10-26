package com.flixtown.tv;

import java.util.Locale;

/**
 * Remote seeking rules (pure Java, unit-tested).
 * A press moves 10 seconds. Holding Left/Right speeds up the longer it is held, so the middle of a
 * long movie is a few seconds away: 10 s per step for the first 1.5 s, then 30 s, then 1 minute
 * (from 3 s), then 3 minutes (from 5 s). Key repeats arrive about every 150 ms; nothing reaches the
 * player until the remote goes quiet, so a hold is one network seek, not one per repeat.
 */
final class SeekStep {
    private SeekStep(){}
    /** Never seek closer than this to the end, so seeking cannot end the episode and start the next one. */
    static final long END_GUARD_MS=5000;
    static long step(long heldMs){
        if(heldMs<1500)return 10_000;
        if(heldMs<3000)return 30_000;
        if(heldMs<5000)return 60_000;
        return 180_000;
    }
    /** Inside [0, duration − 5 s]; with an unknown duration only the start is a bound. */
    static long clamp(long target,long durationMs){
        long t=Math.max(0,target);
        if(durationMs>0)t=Math.min(t,Math.max(0,durationMs-END_GUARD_MS));
        return t;
    }
    static long compose(int hours,int minutes,int seconds){return ((hours*60L+minutes)*60L+seconds)*1000L;}
    static int[] split(long ms){long s=Math.max(0,ms)/1000;return new int[]{(int)(s/3600),(int)(s/60%60),(int)(s%60)};}
    static String time(long ms){
        int[] t=split(ms);
        return t[0]>0?String.format(Locale.US,"%d:%02d:%02d",t[0],t[1],t[2]):String.format(Locale.US,"%d:%02d",t[1],t[2]);
    }
    /** "+12:30" / "−1:05:00": how far the pending target is from where playback is. */
    static String offset(long fromMs,long toMs){long d=toMs-fromMs;return (d<0?"−":"+")+time(Math.abs(d));}
}

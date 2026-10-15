package com.flixtown.tv;

/**
 * Which later episodes the player is given (pure Java, unit-tested). The player has no episode list,
 * so Details passes the episodes after the "next" one, across seasons, up to a limit. When the list
 * is cut off, or unknown, the player must not treat the last episode it knows as the end of the show.
 */
final class EpisodeQueue {
    private EpisodeQueue(){}
    /** {from, count, truncated(0/1)} for a list of {@code size} episodes whose "next" is at {@code nextIndex}. */
    static int[] window(int size,int nextIndex,int max){
        int from=nextIndex+1,count=Math.max(0,Math.min(max,size-from));
        return new int[]{from,count,from+count<size?1:0};
    }
}

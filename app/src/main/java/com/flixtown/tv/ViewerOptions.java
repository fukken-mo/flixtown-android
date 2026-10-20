package com.flixtown.tv;

/** Small validated choices shared by the player and tests. */
final class ViewerOptions {
    static final String SIZE="subtitle_size",STYLE="subtitle_style",SPEED="playback_speed";
    static final float[] SPEEDS={.75f,1f,1.25f,1.5f};
    static final float[] SIZES={.85f,1f,1.2f,1.4f};
    static int index(float value,float[] values){for(int i=0;i<values.length;i++)if(Math.abs(value-values[i])<.001f)return i;return 1;}
    static float speed(float value){return SPEEDS[index(value,SPEEDS)];}
    static float size(float value){return SIZES[index(value,SIZES)];}
    static boolean ready(String state){return "available".equals(state)||"local".equals(state);}
    static boolean newlyReady(String old,String now){return !ready(old)&&ready(now);}
    static String watchedKey(String kind,String id,String episode){
        if(!"movie".equals(kind)&&!"series".equals(kind))throw new IllegalArgumentException("media type");
        if(id==null||id.isEmpty()||("series".equals(kind)&&(episode==null||episode.isEmpty())))throw new IllegalArgumentException("media id");
        return "watched_"+kind+":"+id+("series".equals(kind)?":"+episode:"");
    }
}

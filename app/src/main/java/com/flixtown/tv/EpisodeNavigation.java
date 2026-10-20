package com.flixtown.tv;

import java.util.ArrayList;
import java.util.List;

/** Bounded, ordered episode snapshot. No guessed IDs, URLs, or network requests. */
final class EpisodeNavigation {
    static final class Episode {
        final String id,extension,label;
        Episode(String id,String extension,String label){
            this.id=id;this.extension=extension==null||extension.isEmpty()?"mp4":extension;
            this.label=label==null?"":label;
        }
    }
    private final List<Episode> episodes=new ArrayList<>();
    private int index;
    final boolean completeAfter;
    EpisodeNavigation(String[] ids,String[] extensions,String[] labels,int index,boolean completeAfter){
        if(ids==null || index<0 || index>=ids.length)throw new IllegalArgumentException("Missing current episode");
        for(int i=0;i<ids.length;i++){
            if(ids[i]==null||ids[i].isEmpty())throw new IllegalArgumentException("Invalid episode");
            episodes.add(new Episode(ids[i],extensions!=null&&i<extensions.length?extensions[i]:null,
                labels!=null&&i<labels.length?labels[i]:null));
        }
        this.index=index;this.completeAfter=completeAfter;
    }
    static EpisodeNavigation from(String[] previousIds,String[] previousExts,String[] previousLabels,
        String currentId,String currentExt,String currentLabel,String nextId,String nextExt,String nextLabel,
        String[] laterIds,String[] laterExts,String[] laterLabels,boolean truncated){
        List<Episode> all=new ArrayList<>();
        append(all,previousIds,previousExts,previousLabels);
        int current=all.size();all.add(new Episode(currentId,currentExt,currentLabel));
        if(nextId!=null&&!nextId.isEmpty())all.add(new Episode(nextId,nextExt,nextLabel));
        append(all,laterIds,laterExts,laterLabels);
        String[] ids=new String[all.size()],exts=new String[all.size()],labels=new String[all.size()];
        for(int i=0;i<all.size();i++){Episode e=all.get(i);ids[i]=e.id;exts[i]=e.extension;labels[i]=e.label;}
        return new EpisodeNavigation(ids,exts,labels,current,laterIds!=null&&!truncated);
    }
    private static void append(List<Episode> out,String[] ids,String[] exts,String[] labels){
        if(ids==null)return;
        for(int i=0;i<ids.length;i++)if(ids[i]!=null&&!ids[i].isEmpty())
            out.add(new Episode(ids[i],exts!=null&&i<exts.length?exts[i]:null,labels!=null&&i<labels.length?labels[i]:null));
    }
    Episode current(){return episodes.get(index);}
    Episode adjacent(int direction){int target=index+direction;return (direction==-1||direction==1)&&target>=0&&target<episodes.size()?episodes.get(target):null;}
    boolean move(int direction){if(adjacent(direction)==null)return false;index+=direction;return true;}
    int index(){return index;}
    String[] ids(){String[] out=new String[episodes.size()];for(int i=0;i<out.length;i++)out[i]=episodes.get(i).id;return out;}
    String[] extensions(){String[] out=new String[episodes.size()];for(int i=0;i<out.length;i++)out[i]=episodes.get(i).extension;return out;}
    String[] labels(){String[] out=new String[episodes.size()];for(int i=0;i<out.length;i++)out[i]=episodes.get(i).label;return out;}
}

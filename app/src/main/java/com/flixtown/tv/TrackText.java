package com.flixtown.tv;

import java.util.Locale;

/** How audio and subtitle tracks are named in the player. */
final class TrackText {
    private TrackText(){}
    /** "Portuguese (Brazil)" for pt-BR / pt-br, "English" for en or eng; "" when unknown. */
    static String languageLabel(String code){
        if(code==null||code.isEmpty()||"und".equals(code))return "";
        Locale l=Locale.forLanguageTag(code.replace('_','-'));String name=l.getDisplayName(Locale.US);
        if(name.isEmpty()||name.equalsIgnoreCase(code))name=new Locale(code).getDisplayLanguage(Locale.US);
        return name.isEmpty()?code:name;
    }
    /**
     * One track label ("English · 5.1 · Dolby Digital", "German — not supported on this TV") as the side panel shows
     * it: the language, and plain details below. AAC and MP3 are left out (every TV plays them; they mean nothing to viewers).
     */
    static String[] trackParts(String label){
        boolean unsupported=label.endsWith(" — not supported on this TV");
        String core=unsupported?label.substring(0,label.length()-" — not supported on this TV".length()):label;
        String[] parts=core.split(" · ");
        StringBuilder detail=new StringBuilder();
        for(int i=1;i<parts.length;i++){String p=parts[i];
            if(p.equals("AAC")||p.equals("MP3"))continue;
            if(p.equals("5.1")||p.equals("7.1"))p+=" surround";
            if(detail.length()>0)detail.append(" · ");detail.append(p);}
        String name=parts[0];
        if(name.endsWith(" (forced)")){name=name.substring(0,name.length()-9);detail.insert(0,detail.length()>0?"Forced · ":"Forced");}
        if(unsupported){if(detail.length()>0)detail.append(" · ");detail.append("Can't play on this TV");}
        return new String[]{name,detail.toString()};
    }
}

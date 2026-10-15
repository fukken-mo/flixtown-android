package com.flixtown.tv;

import java.util.regex.Pattern;

/**
 * Text that is safe to log or show. Stream, catalog and account addresses carry the customer's
 * username and password (player_api.php?username=…&password=…, /movie/USER/PASS/ID.ext), so
 * exceptions are never logged as raw throwables: only their types and redacted messages.
 */
final class Logs {
    private Logs(){}
    /** Any URL keeps only its scheme and host; everything after the host (path, query, tokens) is removed. */
    private static final Pattern URL=Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*)://([^/\\s?#\"'<>]+)[^\\s\"'<>]*");
    private static final Pattern SECRET=Pattern.compile("(?i)\\b(username|password|pass|user|token|session|verifier|key)=[^&\\s\"'<>]*");

    static String redact(String text){
        if(text==null || text.isEmpty())return "";
        String s=URL.matcher(text).replaceAll("$1://$2/[redacted]");
        return SECRET.matcher(s).replaceAll("$1=[redacted]");
    }
    /** Media3's own error logs print stack traces whose causes can name the stream address: messages only. */
    static void quietMedia(){androidx.media3.common.util.Log.setLogStackTraces(false);}
    /** "IOException: message ← cause: message", redacted, without stack traces. */
    static String safe(Throwable t){
        StringBuilder out=new StringBuilder();
        for(int depth=0;t!=null && depth<4;t=t.getCause(),depth++){
            if(depth>0)out.append(" <- ");
            out.append(t.getClass().getSimpleName());
            String m=redact(t.getMessage());if(!m.isEmpty())out.append(": ").append(m);
        }
        return out.toString();
    }
}

package com.flixtown.tv;

import java.util.HashSet;
import java.util.Set;

/** Process-local opening lifecycle, independent of network sessions and selected profile data. */
final class ProfileOpening {
    private final Set<String> chosen=new HashSet<>();
    private int generation;
    private boolean returningHome;
    synchronized void opened(){generation++;chosen.clear();returningHome=false;}
    synchronized boolean needsChoice(String scope){return scope!=null&&!scope.isEmpty()&&!chosen.contains(scope);}
    synchronized void chosen(String scope){if(scope!=null&&!scope.isEmpty())chosen.add(scope);}
    synchronized int generation(){return generation;}
    synchronized void returningHome(String scope){chosen(scope);returningHome=true;}
    synchronized void foreground(boolean home){
        if(home&&returningHome){returningHome=false;return;}
        opened();
    }
    synchronized void homeResumed(){returningHome=false;}
}

package com.flixtown.tv;

import java.util.HashSet;
import java.util.Set;

/** Process-local opening lifecycle, independent of network sessions and selected profile data. */
final class ProfileOpening {
    private final Set<String> chosen=new HashSet<>();
    private int generation;
    private boolean returningHome;
    private String externalReturn;
    synchronized void opened(){generation++;chosen.clear();returningHome=false;externalReturn=null;}
    synchronized boolean needsChoice(String scope){return scope!=null&&!scope.isEmpty()&&!chosen.contains(scope);}
    synchronized void chosen(String scope){if(scope!=null&&!scope.isEmpty())chosen.add(scope);}
    synchronized int generation(){return generation;}
    synchronized void returningHome(String scope){chosen(scope);returningHome=true;}
    synchronized void externalLaunched(String token){externalReturn=token;}
    synchronized void externalResumed(String token){if(token!=null&&token.equals(externalReturn))externalReturn=null;}
    synchronized void foreground(boolean home){foreground(home,null);}
    synchronized void foreground(boolean home,String token){
        boolean trailerReturn=token!=null&&token.equals(externalReturn);externalReturn=null;
        if(trailerReturn)return;
        if(home&&returningHome){returningHome=false;return;}
        opened();
    }
    synchronized void homeResumed(){returningHome=false;}
}

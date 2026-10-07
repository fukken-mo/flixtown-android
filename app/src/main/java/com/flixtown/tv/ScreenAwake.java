package com.flixtown.tv;

import android.app.Activity;
import android.util.Log;
import android.view.WindowManager;

/**
 * Keeps the TV from starting its screensaver while a Flix Town browsing or playback screen is in
 * front (Home with its Movies / TV Shows / My List / Search / Settings tabs, Details, cast pages and
 * the player). It is only a window flag: set in onResume, cleared in onPause, so the TV's own
 * screensaver and sleep timer work normally as soon as Flix Town is in the background or closed.
 * No wake lock, no service, no change to the device's screensaver settings.
 */
final class ScreenAwake {
    private ScreenAwake(){}
    static void on(Activity activity){set(activity,true);}
    static void off(Activity activity){set(activity,false);}
    private static void set(Activity activity,boolean on){
        if(on)activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(BuildConfig.DEMO)Log.i("FlixTownQA","screen_awake "+activity.getClass().getSimpleName()+"="+on);
    }
}

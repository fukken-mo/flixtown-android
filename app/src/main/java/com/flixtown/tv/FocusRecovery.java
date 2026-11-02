package com.flixtown.tv;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Android 9 can lose a window-focus notification when a dialog opens or closes in the same moment as another
 * window change (closing one dialog and opening the next, or pressing Back right after OK). The window keeps input
 * focus in the window manager, but its views never hear so and drop every remote key, so the screen looks frozen.
 *
 * When a window that should have focus has not heard so within a moment, its focusability is toggled briefly so
 * the window manager recomputes focus and reports it again. Rules:
 * <ul>
 * <li><b>Every window behind stays out of it.</b> Before a dialog is made unfocusable, every window of ours behind
 *     it (the activity's own window and any older dialogs still open under it) is made unfocusable as well, so the
 *     window manager has no window of ours to hand focus to: remote keys wait and go to the dialog once it is
 *     focusable again. As a second guard, any key that still reaches one of those windows during recovery is
 *     swallowed, together with its matching key-up. Only flags this class added are cleared again, and each
 *     window's own callback is put back.</li>
 * <li><b>Cancelled cleanly.</b> Pending work stops and every flag and the key guard are restored when the dialog
 *     closes, a newer dialog opens, or the activity pauses, stops or is destroyed.</li>
 * <li><b>Never against a legitimate owner.</b> Nothing happens while a newer dialog is on top or the on-screen
 *     keyboard is up, and recovery ends as soon as the window hears any real focus change (gained: done; lost:
 *     another window owns focus). A try the window manager answers with no focus change at all also ends it: the
 *     window was never the focused one, so another window owns focus. Toggling only ever lets the window manager
 *     re-pick the topmost focusable window, so it cannot take focus from a window above ours. At most four tries.</li>
 * </ul>
 * Main thread only.
 */
final class FocusRecovery {
    private FocusRecovery(){}
    private static final int NOT_FOCUSABLE=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    private static final long FIRST_CHECK_MS=250,HOLD_MS=80,SCREEN_RELEASE_MS=60,RETRY_MS=350;
    private static final int MAX_TRIES=4;
    private static final Handler main=new Handler(Looper.getMainLooper());
    /** Dialogs shown with {@link Ui#showWindow} and still attached, per activity, newest last. */
    private static final Map<Activity,List<Dialog>> open=new WeakHashMap<>();
    private static final List<Recovery> active=new ArrayList<>();
    private static final Set<Activity> paused=Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean lifecycleWatched;

    /** QA builds only: tries made, and keys the guard kept from the screen behind. */
    static volatile int recoveries,keysBlocked;

    static void dialogShown(Activity activity,Dialog dialog){
        Window window=dialog.getWindow();if(window==null)return;
        watchLifecycle(activity);
        List<Dialog> stack=open.get(activity);if(stack==null){stack=new ArrayList<>();open.put(activity,stack);}
        stack.remove(dialog);stack.add(dialog);
        cancel(activity);                                  // a newer dialog takes over from the screen and older dialogs
        Recovery recovery=new Recovery(activity,dialog,0,false);recovery.start(FIRST_CHECK_MS);
        final List<Dialog> dialogs=stack;
        window.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            @Override public void onViewAttachedToWindow(View v){}
            @Override public void onViewDetachedFromWindow(View v){v.removeOnAttachStateChangeListener(this);
                recovery.windowGone=true;recovery.finish();
                for(Recovery r:new ArrayList<>(active))r.forget(window);   // it may be a window held behind another dialog
                boolean wasTop=!dialogs.isEmpty()&&dialogs.get(dialogs.size()-1)==dialog;dialogs.remove(dialog);
                if(!wasTop||!usable(activity))return;
                // Focus returns to the dialog below, or to the screen once the last dialog has closed.
                cancel(activity);
                new Recovery(activity,dialogs.isEmpty()?null:dialogs.get(dialogs.size()-1),0,false).start(FIRST_CHECK_MS);
            }
        });
    }

    /** QA builds only: act as if {@code dialog} lost its focus notification, holding it unfocusable for {@code holdMs}. */
    static boolean forceForTest(Dialog dialog,long holdMs,boolean blockScreen){
        if(!BuildConfig.DEMO)return false;
        Activity activity=null;for(Map.Entry<Activity,List<Dialog>> e:open.entrySet())if(e.getValue().contains(dialog))activity=e.getKey();
        if(activity==null)return false;
        cancel(activity);Recovery r=new Recovery(activity,dialog,holdMs,!blockScreen);
        if(!r.wanted()){r.finish();return false;}
        r.start(0);return true;
    }
    /** QA builds only: a dialog is being held unfocusable right now. */
    static boolean holdingForTest(){for(Recovery r:active)if(r.windowBlocked)return true;return false;}
    /** QA builds only: recoveries pending for a dialog, or for an activity's own window (null: all). */
    static int activeForTest(Object target){int n=0;for(Recovery r:active)if(target==null||r.dialog==target||(r.dialog==null&&r.activity==target))n++;return n;}

    private static boolean usable(Activity a){return !a.isFinishing()&&!a.isDestroyed()&&!paused.contains(a);}
    private static void cancel(Activity activity){for(Recovery r:new ArrayList<>(active))if(r.activity==activity)r.finish();}

    private static void watchLifecycle(Activity activity){
        if(lifecycleWatched)return;lifecycleWatched=true;
        activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            @Override public void onActivityCreated(Activity a,Bundle s){}
            @Override public void onActivityStarted(Activity a){}
            @Override public void onActivityResumed(Activity a){paused.remove(a);}
            @Override public void onActivityPaused(Activity a){paused.add(a);cancel(a);}
            @Override public void onActivityStopped(Activity a){paused.add(a);cancel(a);}
            @Override public void onActivitySaveInstanceState(Activity a,Bundle s){}
            @Override public void onActivityDestroyed(Activity a){cancel(a);open.remove(a);paused.remove(a);}
        });
    }

    /** The on-screen keyboard is up over this activity or one of its dialogs: it owns input, leave focus alone. */
    static boolean keyboardShown(Activity activity){
        List<View> roots=new ArrayList<>();roots.add(activity.getWindow().getDecorView());
        List<Dialog> stack=open.get(activity);if(stack!=null)for(Dialog d:stack)if(d.getWindow()!=null)roots.add(d.getWindow().getDecorView());
        if(Build.VERSION.SDK_INT>=30){
            for(View root:roots){WindowInsets insets=root.getRootWindowInsets();if(insets!=null&&insets.isVisible(WindowInsets.Type.ime()))return true;}
            return false;
        }
        InputMethodManager imm=(InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if(imm==null||!imm.isAcceptingText())return false;
        int screen=activity.getResources().getDisplayMetrics().heightPixels;Rect frame=new Rect();
        for(View root:roots){if(!root.isAttachedToWindow())continue;root.getWindowVisibleDisplayFrame(frame);if(screen-frame.bottom>screen/6)return true;}
        return false;
    }

    private static final class Recovery implements Runnable,ViewTreeObserver.OnWindowFocusChangeListener {
        private static final int CHECK=0,RELEASE=1,RELEASE_SCREEN=2;
        final Activity activity;final Dialog dialog;final Window window;final View decor;
        /** Windows behind a recovering dialog: guarded, and those this recovery made unfocusable (to clear again). */
        final Map<Window,KeyGuard> guards=new java.util.LinkedHashMap<>();final List<Window> blocked=new ArrayList<>();
        final long hold;final boolean forced,screenBlockOff;
        int tries,phase=CHECK;long ownUntil;boolean windowBlocked,done,windowGone,heardDuringTry;

        Recovery(Activity activity,Dialog dialog,long holdForTest,boolean screenBlockOff){
            this.activity=activity;this.dialog=dialog;
            window=dialog!=null?dialog.getWindow():activity.getWindow();decor=window.getDecorView();
            forced=holdForTest>0;hold=forced?holdForTest:HOLD_MS;this.screenBlockOff=screenBlockOff;
        }
        void start(long delay){active.add(this);decor.getViewTreeObserver().addOnWindowFocusChangeListener(this);main.postDelayed(this,delay);}

        boolean wanted(){
            if(!usable(activity)||!decor.isAttachedToWindow()||decor.getWindowVisibility()!=View.VISIBLE)return false;
            List<Dialog> stack=open.get(activity);
            if(dialog!=null){if(!dialog.isShowing()||stack==null||stack.isEmpty()||stack.get(stack.size()-1)!=dialog)return false;}
            else if(stack!=null&&!stack.isEmpty())return false;
            return !keyboardShown(activity);
        }

        @Override public void onWindowFocusChanged(boolean hasFocus){
            if(done)return;
            heardDuringTry=true;
            if(hasFocus){finish();return;}                              // focus arrived: nothing to recover
            if(SystemClock.uptimeMillis()>=ownUntil)finish();           // a real loss, not our toggle: another window owns focus
        }

        @Override public void run(){
            if(done)return;
            switch(phase){
                case CHECK:
                    if(!wanted()||(decor.hasWindowFocus()&&!(forced&&tries==0))||tries>=MAX_TRIES){finish();return;}
                    // A try the window manager answered with no focus change at all means it never had this window
                    // focused: another window owns focus, so stop rather than toggle again.
                    if(tries>0&&!heardDuringTry){
                        if(BuildConfig.DEMO)android.util.Log.i("FlixTownQA","focus_recovery stop window="+window.getAttributes().getTitle()+" reason=another window owns focus");
                        finish();return;}
                    tries++;heardDuringTry=false;
                    if(BuildConfig.DEMO){recoveries++;android.util.Log.i("FlixTownQA","focus_recovery window="+window.getAttributes().getTitle()+" try="+tries);}
                    ownUntil=Long.MAX_VALUE;
                    for(Window behind:behind()){
                        if(!guards.containsKey(behind)){KeyGuard g=KeyGuard.install(behind);if(g!=null)guards.put(behind,g);}
                        if(!screenBlockOff&&!blocked.contains(behind)&&(behind.getAttributes().flags&NOT_FOCUSABLE)==0){behind.addFlags(NOT_FOCUSABLE);blocked.add(behind);}
                    }
                    window.addFlags(NOT_FOCUSABLE);windowBlocked=true;
                    phase=RELEASE;main.postDelayed(this,hold);return;
                case RELEASE:
                    window.clearFlags(NOT_FOCUSABLE);windowBlocked=false;
                    if(!blocked.isEmpty()){phase=RELEASE_SCREEN;main.postDelayed(this,SCREEN_RELEASE_MS);return;}
                    next();return;
                case RELEASE_SCREEN:
                    unblock();next();
            }
        }
        /** The activity window and every older dialog still open, when this recovery is for a dialog (it is the top one). */
        private List<Window> behind(){
            List<Window> out=new ArrayList<>();if(dialog==null)return out;
            out.add(activity.getWindow());
            List<Dialog> stack=open.get(activity);
            if(stack!=null)for(Dialog d:stack)if(d!=dialog&&d.isShowing()&&d.getWindow()!=null&&d.getWindow().getDecorView().isAttachedToWindow())out.add(d.getWindow());
            return out;
        }
        private void unblock(){for(Window w:blocked)w.clearFlags(NOT_FOCUSABLE);blocked.clear();}
        /** A window behind this dialog has gone: nothing of ours is left on it to restore. */
        void forget(Window gone){blocked.remove(gone);KeyGuard g=guards.remove(gone);if(g!=null)g.restore();}
        private void next(){phase=CHECK;ownUntil=SystemClock.uptimeMillis()+RETRY_MS;main.postDelayed(this,RETRY_MS);}

        void finish(){
            if(done)return;done=true;
            main.removeCallbacks(this);active.remove(this);
            if(windowBlocked&&!windowGone)window.clearFlags(NOT_FOCUSABLE);
            unblock();windowBlocked=false;
            ViewTreeObserver observer=decor.getViewTreeObserver();if(observer.isAlive())observer.removeOnWindowFocusChangeListener(this);
            for(KeyGuard g:guards.values())g.release();guards.clear();
        }
    }

    /** Swallows keys that reach a window behind a dialog while a dialog is recovering focus, and their matching key-ups. */
    private static final class KeyGuard implements InvocationHandler {
        final Window screen;final Window.Callback original,proxy;final Set<Integer> held=new HashSet<>();boolean releasing;
        private KeyGuard(Window screen,Window.Callback original){
            this.screen=screen;this.original=original;
            proxy=(Window.Callback)Proxy.newProxyInstance(FocusRecovery.class.getClassLoader(),new Class<?>[]{Window.Callback.class},this);
        }
        static KeyGuard install(Window screen){
            Window.Callback cb=screen.getCallback();if(cb==null)return null;
            KeyGuard g=new KeyGuard(screen,cb);screen.setCallback(g.proxy);return g;
        }
        @Override public Object invoke(Object self,Method method,Object[] args)throws Throwable{
            String name=method.getName();
            if(args!=null&&args.length==1&&args[0] instanceof KeyEvent&&("dispatchKeyEvent".equals(name)||"dispatchKeyShortcutEvent".equals(name))){
                KeyEvent e=(KeyEvent)args[0];int code=e.getKeyCode();
                boolean volume=code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN||code==KeyEvent.KEYCODE_VOLUME_MUTE;
                if(!volume){
                    if(!releasing){
                        if(e.getAction()==KeyEvent.ACTION_DOWN)held.add(code);else if(e.getAction()==KeyEvent.ACTION_UP)held.remove(code);
                        if(BuildConfig.DEMO){keysBlocked++;android.util.Log.i("FlixTownQA","focus_recovery blocked "+KeyEvent.keyCodeToString(code)+" action="+e.getAction());}
                        return true;
                    }
                    if(e.getAction()==KeyEvent.ACTION_UP&&held.remove(code)){if(held.isEmpty())restore();return true;}
                }
            }
            if("equals".equals(name)&&args!=null&&args.length==1)return self==args[0];
            if("hashCode".equals(name)&&(args==null||args.length==0))return System.identityHashCode(self);
            try{return method.invoke(original,args);}catch(InvocationTargetException x){throw x.getCause();}
        }
        void release(){releasing=true;if(held.isEmpty())restore();else main.postDelayed(this::restore,1000);}
        void restore(){held.clear();if(screen.getCallback()==proxy)screen.setCallback(original);}
    }
}

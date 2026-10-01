package com.flixtown.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * App updates from the Flix Town panel (app-update.php).
 *
 * The panel reads the version from inside the APK at its update link. This class asks it once in
 * the background after launch and whenever Settings › Check for app updates is selected, offers
 * the update only when its versionCode is higher than this app's, downloads it fresh (never from a
 * cache), checks its SHA-256, package ID, versionCode and signing key, and then hands it to
 * Android's installer, which asks the viewer to confirm.
 *
 * State is kept for the whole process, so Settings shows it and a download continues when the
 * screen changes. A failed check is always reported as failed, never as "up to date".
 */
final class AppUpdates {
    enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }
    interface Listener { void onUpdateState(); }

    private static final long STARTUP_DELAY_MS=6000;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static State state=State.IDLE;
    private static UpdateInfo info;          // the newer release, while AVAILABLE / DOWNLOADING / READY
    private static String failure="";
    private static int percent;
    private static File ready;
    private static long checkedAt;
    private static boolean startupDone,downloadRunning;
    private static long laterFor;            // versionCode the viewer postponed this session

    private final HomeActivity activity;
    private Listener listener;
    private Ui.Progress progress;
    private boolean manualCheck;

    AppUpdates(HomeActivity activity){this.activity=activity;cleanOldDownloads(activity);}
    void setListener(Listener l){listener=l;}

    /* ---------------- What Settings shows ---------------- */

    static State state(){return state;}
    String rowValue(){
        switch(state){
            case CHECKING:return "Checking…";
            case UP_TO_DATE:return "Up to date";
            case AVAILABLE:return "Version "+info.versionName+" available";
            case DOWNLOADING:return "Downloading "+percent+"%";
            case READY:return "Ready to install";
            case FAILED:return "Check failed";
            default:return "";
        }
    }
    String rowDetail(){
        switch(state){
            case CHECKING:return "Asking your Flix Town panel for the newest version";
            case UP_TO_DATE:return "Version "+BuildConfig.VERSION_NAME+" is the newest on your panel · checked "+ago(checkedAt);
            case AVAILABLE:return "Select to download and install"+(info.sizeLabel().isEmpty()?"":" ("+info.sizeLabel()+")");
            case DOWNLOADING:return "Downloading version "+info.versionName+". You can keep watching.";
            case READY:return "Downloaded and verified. Select to open the installer.";
            case FAILED:return failure;
            default:return "Checks your Flix Town panel for a newer version";
        }
    }
    private static String ago(long at){long s=(SystemClock.elapsedRealtime()-at)/1000;
        return s<60?"just now":s<3600?(s/60)+" min ago":(s/3600)+" h ago";}

    /** Settings › Check for app updates. */
    void select(){
        switch(state){
            case CHECKING:toast("Checking for updates…");return;
            case DOWNLOADING:showProgress();return;
            case READY:install();return;
            case AVAILABLE:offer(true);return;
            default:check(true);
        }
    }

    /** Once per launch, in the background, after the catalog has had its head start. */
    void checkOnStartup(){
        if(startupDone)return;startupDone=true;
        MAIN.postDelayed(()->{if(!activity.isFinishing() && state!=State.CHECKING && state!=State.DOWNLOADING && state!=State.READY)check(false);},STARTUP_DELAY_MS);
    }

    /* ---------------- Checking ---------------- */

    private void check(boolean manual){
        manualCheck=manual;
        setState(State.CHECKING);
        if(manual)toast("Checking for updates…");
        String pkg=activity.getPackageName();long installed=BuildConfig.VERSION_CODE;
        Api.IO.execute(()->{
            UpdateInfo found=null;String problem=null;
            try{
                String url=BuildConfig.UPDATE_URL+"?package="+Api.enc(pkg)+"&version_code="+installed+"&_ft="+System.currentTimeMillis();
                found=UpdateInfo.parse(new JSONObject(get(url)),pkg,installed,BuildConfig.DEMO);
                if(found!=null && !found.signerSha256.isEmpty()){
                    String mine=installedSigner(activity);
                    if(mine!=null && !mine.equals(found.signerSha256))
                        problem="Version "+found.versionName+" on the panel is signed with a different key than this app, so Android would refuse to install it over Flix Town.";
                }
            }catch(UpdateInfo.Problem p){problem=p.getMessage();}
            catch(PanelProblem p){problem=p.getMessage();}
            catch(IOException e){problem="Couldn't reach the Flix Town panel. Check the TV's internet connection and try again.";}
            catch(Exception e){problem="The panel's answer could not be read.";}
            UpdateInfo result=found;String error=problem;
            MAIN.post(()->finishCheck(result,error));
        });
    }
    private void finishCheck(UpdateInfo found,String error){
        checkedAt=SystemClock.elapsedRealtime();
        if(error!=null){failure=error;setState(State.FAILED);
            if(manualCheck && !activity.isFinishing())Ui.message(activity,"Couldn't check for updates",error);return;}
        if(found==null){info=null;setState(State.UP_TO_DATE);
            if(manualCheck && !activity.isFinishing())Ui.message(activity,"Flix Town is up to date","You have version "+BuildConfig.VERSION_NAME+", the newest on your panel.");return;}
        info=found;setState(State.AVAILABLE);
        if(manualCheck || found.required || laterFor!=found.versionCode)offer(manualCheck);
    }

    private void offer(boolean fromSettings){
        if(activity.isFinishing() || info==null)return;
        UpdateInfo u=info;
        String title="Flix Town "+u.versionName+" is available";
        String message=(u.notes.isEmpty()?"A new version of Flix Town is ready.":u.notes)
            +"\n\nYou have "+BuildConfig.VERSION_NAME+". Your sign-in and settings stay as they are."+(u.sizeLabel().isEmpty()?"":" Download: "+u.sizeLabel()+".");
        String[] buttons=u.required?new String[]{"Update now"}:new String[]{"Update now","Later"};
        Ui.dialog(activity,title,message,buttons,0,i->{if(i==0)download();else laterFor=u.versionCode;},()->{if(!u.required)laterFor=u.versionCode;});
    }

    /* ---------------- Downloading ---------------- */

    private void download(){
        if(info==null || downloadRunning)return;
        UpdateInfo u=info;downloadRunning=true;percent=0;setState(State.DOWNLOADING);showProgress();
        Context app=activity.getApplicationContext();String pkg=activity.getPackageName();
        Api.IO.execute(()->{
            File dir=new File(app.getFilesDir(),"updates");
            File target=new File(dir,"FlixTown-"+u.versionCode+".apk");
            String problem=null;
            try{
                if(!dir.isDirectory() && !dir.mkdirs())throw new IOException("Could not create the update folder.");
                // First with a unique parameter (no CDN or proxy copy); a link that rejects it gets one plain retry.
                String digest;
                try{digest=fetch(UpdateInfo.cacheBusted(u.apkUrl,String.valueOf(System.currentTimeMillis())),target,u.size);}
                catch(HttpStatus e){if(e.code<400 || e.code>=500)throw e;digest=fetch(u.apkUrl,target,u.size);}
                if(!digest.equals(u.sha256))
                    throw new Verify("The downloaded file doesn't match the checksum on the panel. If a new APK was just uploaded, try again in a few minutes.");
                verifyPackage(app,target,pkg,u);
            }catch(Verify v){problem=v.getMessage();}
            catch(HttpStatus e){problem="The update link returned HTTP "+e.code+".";}
            catch(IOException e){problem="The download stopped. Check the TV's internet connection and try again.";}
            catch(Exception e){problem="The update could not be prepared.";}
            if(problem!=null)target.delete();
            String error=problem;
            MAIN.post(()->{downloadRunning=false;
                if(error!=null){failure=error;setState(State.FAILED);dismissProgress();
                    if(!activity.isFinishing())Ui.message(activity,"Update failed",error);return;}
                ready=target;setState(State.READY);dismissProgress();install();});
        });
    }

    /** Streams the APK to {@code target} and returns its SHA-256. No HTTP cache is used. */
    private String fetch(String address,File target,long expectedSize) throws Exception {
        HttpURLConnection c=open(address);
        try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(target)){
            MessageDigest sha=MessageDigest.getInstance("SHA-256");
            long total=c.getContentLength()>0?c.getContentLength():expectedSize,done=0,lastPost=0;
            byte[] buffer=new byte[65536];int n;
            while((n=in.read(buffer))!=-1){
                out.write(buffer,0,n);sha.update(buffer,0,n);done+=n;
                if(done>300L*1024*1024)throw new Verify("The update file is unexpectedly large.");
                long now=SystemClock.elapsedRealtime();
                if(total>0 && now-lastPost>250){lastPost=now;int p=(int)Math.min(99,done*100/total);long d=done;
                    MAIN.post(()->{percent=p;notifyState();updateProgress(d,total);});}
            }
            return UpdateInfo.hex(sha.digest());
        }finally{c.disconnect();}
    }

    /** Follows redirects itself (Android does not follow http↔https switches) and never uses a cache. */
    private static HttpURLConnection open(String address) throws IOException {
        String url=address;
        for(int hop=0;hop<6;hop++){
            if(!url.startsWith("https://") && !(BuildConfig.DEMO && url.startsWith("http://")))
                throw new Verify("The update link redirects to an insecure address.");
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);
            c.setRequestProperty("Cache-Control","no-cache, no-store");c.setRequestProperty("Pragma","no-cache");
            int code=c.getResponseCode();
            if(code>=300 && code<400 && c.getHeaderField("Location")!=null){
                url=new URL(new URL(url),c.getHeaderField("Location")).toString();c.disconnect();continue;}
            if(code!=200){c.disconnect();throw new HttpStatus(code);}
            return c;
        }
        throw new IOException("Too many redirects");
    }

    private static String get(String address) throws IOException {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        c.setUseCaches(false);c.setConnectTimeout(8000);c.setReadTimeout(12000);
        c.setRequestProperty("Cache-Control","no-cache");c.setRequestProperty("Pragma","no-cache");
        try{
            int code=c.getResponseCode();
            if(code==404)throw new HttpStatus(code);
            InputStream in=code>=400?c.getErrorStream():c.getInputStream();
            if(in==null)throw new IOException("HTTP "+code);
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
            try(InputStream s=in){while((n=s.read(b))!=-1){out.write(b,0,n);if(out.size()>65536)throw new IOException("Answer too large");}}
            return out.toString("UTF-8");
        }catch(HttpStatus e){
            throw new PanelProblem("The update service isn't installed on the Flix Town panel yet.");
        }finally{c.disconnect();}
    }

    /* ---------------- Verifying ---------------- */

    /** Package ID, versionCode and signing key must match what this app can be updated to. */
    private static void verifyPackage(Context c,File apk,String ownPackage,UpdateInfo u) throws Verify {
        PackageManager pm=c.getPackageManager();
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo archive=pm.getPackageArchiveInfo(apk.getAbsolutePath(),flags);
        if(archive==null)throw new Verify("The downloaded file is not a valid Android app.");
        if(!ownPackage.equals(archive.packageName))throw new Verify("The downloaded app is "+archive.packageName+", not Flix Town.");
        long code=Build.VERSION.SDK_INT>=28?archive.getLongVersionCode():archive.versionCode;
        if(code!=u.versionCode)throw new Verify("The downloaded app is version "+code+", but the panel announced "+u.versionCode+". Try again in a few minutes.");
        if(code<=BuildConfig.VERSION_CODE)throw new Verify("The downloaded app is not newer than this one.");
        String theirs=signer(archive),mine=installedSigner(c);
        if(theirs!=null && mine!=null && !theirs.equals(mine))
            throw new Verify("The update is signed with a different key than this app, so Android would refuse to install it over Flix Town.");
    }
    static String installedSigner(Context c){
        try{
            int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
            return signer(c.getPackageManager().getPackageInfo(c.getPackageName(),flags));
        }catch(Exception e){return null;}
    }
    /** SHA-256 of the signing certificate, the same value the panel shows. */
    @SuppressWarnings("deprecation")
    private static String signer(PackageInfo p){
        try{
            Signature[] s=null;
            if(Build.VERSION.SDK_INT>=28 && p.signingInfo!=null)s=p.signingInfo.getApkContentsSigners();
            if((s==null || s.length==0) && p.signatures!=null)s=p.signatures;
            if(s==null || s.length==0)return null;
            return UpdateInfo.hex(MessageDigest.getInstance("SHA-256").digest(s[0].toByteArray()));
        }catch(Exception e){return null;}
    }

    /* ---------------- Installing ---------------- */

    /** Opens Android's installer for a verified download (after the install permission, if needed). */
    void install(){
        if(ready==null || !ready.isFile()){ready=null;if(state==State.READY){info=null;setState(State.IDLE);}return;}
        if(Build.VERSION.SDK_INT>=26 && !activity.getPackageManager().canRequestPackageInstalls()){
            Ui.dialog(activity,"Allow Flix Town to install its update","Android asks once. On the next screen, turn on \"Allow from this source\", then press Back.",
                new String[]{"Continue","Not now"},0,i->{if(i!=0)return;
                    try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));}
                    catch(Exception e){toast("Allow installing unknown apps for Flix Town in the TV's settings.");}},null);
            return;
        }
        try{
            Uri content=FileProvider.getUriForFile(activity,activity.getPackageName()+".fileprovider",ready);
            Intent intent=new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(content,"application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        }catch(Exception e){failure="Android's installer could not be opened.";setState(State.FAILED);}
    }
    /** Back from the "install unknown apps" screen: continue where the viewer left off. */
    void resume(){if(state==State.READY && Build.VERSION.SDK_INT>=26 && activity.getPackageManager().canRequestPackageInstalls())install();}

    /** Earlier downloads are of no use once this version (or newer) is installed. */
    private static void cleanOldDownloads(Context c){
        File[] files=new File(c.getFilesDir(),"updates").listFiles();if(files==null)return;
        for(File f:files){
            String n=f.getName();long code=-1;
            try{code=Long.parseLong(n.replaceAll("[^0-9]",""));}catch(Exception ignored){}
            if(code<=BuildConfig.VERSION_CODE && !(ready!=null && ready.equals(f)))f.delete();
        }
    }

    /* ---------------- UI plumbing ---------------- */

    private void showProgress(){
        if(activity.isFinishing() || info==null)return;
        if(progress!=null && progress.isShowing())return;
        progress=Ui.progress(activity,"Downloading Flix Town "+info.versionName,"Hide",null);
        progress.set("Starting…",percent);
    }
    private void updateProgress(long done,long total){
        if(progress!=null && progress.isShowing())
            progress.set(String.format(Locale.US,"%d%%  ·  %.1f of %.1f MB",percent,done/1048576.0,total/1048576.0),percent);
    }
    private void dismissProgress(){if(progress!=null)progress.dismiss();progress=null;}
    private void setState(State s){state=s;notifyState();}
    private void notifyState(){if(listener!=null)listener.onUpdateState();}
    private void toast(String text){if(!activity.isFinishing())Toast.makeText(activity,text,Toast.LENGTH_SHORT).show();}
    void destroy(){dismissProgress();listener=null;}

    private static final class Verify extends IOException { Verify(String m){super(m);} }
    private static final class HttpStatus extends IOException { final int code;HttpStatus(int code){super("HTTP "+code);this.code=code;} }
    private static final class PanelProblem extends IOException { PanelProblem(String m){super(m);} }
}

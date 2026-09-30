package com.flixtown.tv;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.File;

/** Checks panel release metadata and hands the exact package to Android's installer. */
final class AppUpdates {
    private final HomeActivity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private int promptedVersion,expectedVersion;
    private File pendingFile;
    private long downloadId=-1;
    AppUpdates(HomeActivity activity){this.activity=activity;}
    /** Settings › Check for app updates: asks the panel now and always reports what it found. */
    void checkNow(){
        Toast.makeText(activity,"Checking for updates…",Toast.LENGTH_SHORT).show();
        Api.IO.execute(()->{
            try{JSONObject config=Api.get(BuildConfig.PANEL_URL+"config.php");
                handler.post(()->{if(!activity.isFinishing())report(config);});
            }catch(Exception e){handler.post(()->{if(!activity.isFinishing())
                Ui.message(activity,"Couldn't check for updates","The Flix Town panel couldn't be reached. Check the TV's internet connection and try again.");});}
        });
    }
    private void report(JSONObject config){
        int latest=config.optInt("update_version_code",0);
        if(latest<=BuildConfig.VERSION_CODE){
            Ui.message(activity,"Flix Town is up to date","You have version "+BuildConfig.VERSION_NAME+".");return;}
        if(BuildConfig.PREVIEW){
            Ui.message(activity,"An update is on your panel","Version "+latest+" is available for the main Flix Town app. This preview build doesn't install panel updates.");return;}
        promptedVersion=0;
        if(!check(config))Ui.message(activity,"Update not available","Your panel lists version "+latest+", but its download link isn't a secure https link.");
    }
    /** Offers the panel's update when it is newer; returns whether the offer was shown. */
    boolean check(JSONObject config){
        int latest=config.optInt("update_version_code",0);
        String url=config.optString("update_apk_url","");
        if(latest<=BuildConfig.VERSION_CODE || latest<=promptedVersion || !url.startsWith("https://"))return false;
        promptedVersion=latest;
        String notes=config.optString("update_notes","").trim();
        String title=notes.isEmpty()?"A new Flix Town update is ready":"Flix Town update: "+notes;
        boolean required=config.optBoolean("update_required",false);
        Ui.options(activity,title,required?new String[]{"Update now"}:new String[]{"Update now","Later"},selection->{
            if(selection==0)download(url,latest);
        });
        return true;
    }
    private void download(String url,int version){
        if(downloadId!=-1)return;
        try{
            Uri uri=Uri.parse(url);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null)throw new IllegalArgumentException("Invalid APK link");
            File directory=new File(activity.getExternalFilesDir(null),"updates");
            if(!directory.exists() && !directory.mkdirs())throw new IllegalStateException("Could not create update folder");
            pendingFile=new File(directory,"FlixTown-"+version+".apk");
            if(pendingFile.exists() && !pendingFile.delete())throw new IllegalStateException("Could not replace old download");
            expectedVersion=version;
            DownloadManager.Request request=new DownloadManager.Request(uri);
            request.setTitle("Flix Town update");request.setDescription("Downloading version "+version);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(activity,null,"updates/FlixTown-"+version+".apk");
            DownloadManager service=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if(service==null)throw new IllegalStateException("Downloads unavailable");
            downloadId=service.enqueue(request);
            Toast.makeText(activity,"Downloading Flix Town update",Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::poll,1300);
        }catch(Exception error){downloadId=-1;Toast.makeText(activity,"Could not start the update download",Toast.LENGTH_LONG).show();}
    }
    private void poll(){
        if(downloadId<0 || activity.isFinishing())return;
        DownloadManager service=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if(service==null)return;
        try(android.database.Cursor cursor=service.query(new DownloadManager.Query().setFilterById(downloadId))){
            if(cursor==null || !cursor.moveToFirst()){downloadId=-1;return;}
            int status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if(status==DownloadManager.STATUS_SUCCESSFUL){downloadId=-1;installIfReady();return;}
            if(status==DownloadManager.STATUS_FAILED){downloadId=-1;Toast.makeText(activity,"Update download failed. Try again later.",Toast.LENGTH_LONG).show();return;}
        }catch(Exception error){downloadId=-1;Toast.makeText(activity,"Update download could not be checked",Toast.LENGTH_LONG).show();return;}
        handler.postDelayed(this::poll,1400);
    }
    void installIfReady(){
        if(pendingFile==null || !pendingFile.isFile())return;
        PackageInfo info=activity.getPackageManager().getPackageArchiveInfo(pendingFile.getAbsolutePath(),0);
        long code=info==null?0:Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
        if(info==null || !activity.getPackageName().equals(info.packageName) || code<expectedVersion){
            pendingFile=null;Toast.makeText(activity,"Update file is not a valid Flix Town APK",Toast.LENGTH_LONG).show();return;
        }
        if(Build.VERSION.SDK_INT>=26 && !activity.getPackageManager().canRequestPackageInstalls()){
            Intent settings=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName()));
            try{activity.startActivity(settings);}catch(Exception error){Toast.makeText(activity,"Allow installation from Flix Town in device settings",Toast.LENGTH_LONG).show();}
            return;
        }
        File file=pendingFile;pendingFile=null;
        try{
            Uri content=FileProvider.getUriForFile(activity,activity.getPackageName()+".fileprovider",file);
            Intent install=new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(content,"application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
        }catch(Exception error){Toast.makeText(activity,"Could not open the Android installer",Toast.LENGTH_LONG).show();}
    }
    void destroy(){handler.removeCallbacksAndMessages(null);}
}

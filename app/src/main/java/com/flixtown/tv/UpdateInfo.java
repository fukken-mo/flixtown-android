package com.flixtown.tv;

import org.json.JSONObject;
import java.util.Locale;

/**
 * One answer from the panel's app-update.php, already checked: whatever this returns can be
 * trusted to describe an APK for this app. Pure Java so it is unit-tested without a device.
 */
final class UpdateInfo {
    /** The panel answered, but it could not be used. Never treated as "up to date". */
    static final class Problem extends Exception { Problem(String message){super(message);} }

    final long versionCode;final String versionName,apkUrl,sha256,signerSha256,notes;final boolean required;final long size;

    private UpdateInfo(long versionCode,String versionName,String apkUrl,String sha256,String signerSha256,String notes,boolean required,long size){
        this.versionCode=versionCode;this.versionName=versionName;this.apkUrl=apkUrl;this.sha256=sha256;
        this.signerSha256=signerSha256;this.notes=notes;this.required=required;this.size=size;
    }

    /**
     * @return the newer release for {@code ownPackage}, or null when the panel has nothing newer
     *         than {@code installedVersionCode} for this app.
     * @throws Problem when the panel reports a failure or returns something unusable.
     */
    static UpdateInfo parse(JSONObject r,String ownPackage,long installedVersionCode,boolean allowHttp) throws Problem {
        if(r==null)throw new Problem("The panel sent an empty answer.");
        if(!r.optBoolean("ok",false)){
            String error=r.optString("error","").trim();
            throw new Problem(error.isEmpty()?"The panel could not check for updates.":error);
        }
        long code=r.optLong("update_version_code",0);
        if(code<=0)return null;                                       // nothing published for this app
        String pkg=r.optString("package","");
        if(!ownPackage.equals(pkg))return null;                       // published APK is a different app
        if(code<=installedVersionCode)return null;                    // already on this version or newer
        String url=r.optString("update_apk_url","").trim();
        String sha=r.optString("update_sha256","").trim().toLowerCase(Locale.US);
        if(!(url.startsWith("https://") || (allowHttp && url.startsWith("http://"))))
            throw new Problem("The update link on the panel is not a secure https link.");
        if(!sha.matches("[0-9a-f]{64}"))throw new Problem("The panel did not provide the update's checksum.");
        String signer=r.isNull("update_signer_sha256")?"":r.optString("update_signer_sha256","").trim().toLowerCase(Locale.US);
        String name=r.optString("update_version_name","").trim();
        return new UpdateInfo(code,name.isEmpty()?String.valueOf(code):name,url,sha,signer.matches("[0-9a-f]{64}")?signer:"",
            r.optString("update_notes","").trim(),r.optBoolean("update_required",false),r.optLong("update_size",0));
    }

    /** Adds a unique parameter so caches and CDNs cannot answer with an older copy. */
    static String cacheBusted(String url,String token){
        int hash=url.indexOf('#');String base=hash<0?url:url.substring(0,hash),fragment=hash<0?"":url.substring(hash);
        return base+(base.indexOf('?')<0?"?":"&")+"_ft="+token+fragment;
    }

    static String hex(byte[] bytes){
        StringBuilder out=new StringBuilder(bytes.length*2);
        for(byte b:bytes)out.append(String.format(Locale.US,"%02x",b&0xff));
        return out.toString();
    }

    String sizeLabel(){return size<=0?"":String.format(Locale.US,"%.1f MB",size/1048576.0);}
}

package com.flixtown.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Profile metadata only. Does not read credentials, replace preferences or enable Kids filtering. */
final class ProfileCatalog {
    static final int MAX_PROFILES=6, AVATAR_COUNT=28, MAX_NAME_CODEPOINTS=24;
    static final String DEFAULT_ID="default";
    static final class Profile {
        final String id,name,avatar;
        final boolean kids;
        Profile(String id,String name,String avatar,boolean kids){
            if(!validId(id)||!validAvatar(avatar))throw new IllegalArgumentException("profile");
            this.id=id;this.name=normalizeName(name);this.avatar=avatar;this.kids=kids;
            if(DEFAULT_ID.equals(id)&&kids)throw new IllegalArgumentException("default profile");
        }
        JSONObject json()throws Exception{return new JSONObject().put("id",id).put("name",name).put("avatar",avatar).put("kids",kids);}
    }
    final List<Profile> profiles;
    final String selected;
    ProfileCatalog(List<Profile> profiles,String selected){
        if(profiles==null||profiles.isEmpty()||profiles.size()>MAX_PROFILES)throw new IllegalArgumentException("profiles");
        Set<String> ids=new HashSet<>();boolean hasDefault=false;
        for(Profile p:profiles){if(p==null||!ids.add(p.id))throw new IllegalArgumentException("duplicate profile");hasDefault|=DEFAULT_ID.equals(p.id);}
        if(!hasDefault||!ids.contains(selected))throw new IllegalArgumentException("selection");
        this.profiles=Collections.unmodifiableList(new ArrayList<>(profiles));this.selected=selected;
    }
    static ProfileCatalog initial(){return new ProfileCatalog(Collections.singletonList(new Profile(DEFAULT_ID,"My profile","avatar_01",false)),DEFAULT_ID);}
    ProfileCatalog add(String name,String avatar,boolean kids){
        List<Profile> next=new ArrayList<>(profiles);next.add(new Profile(UUID.randomUUID().toString(),name,avatar,kids));return new ProfileCatalog(next,selected);
    }
    ProfileCatalog edit(String id,String name,String avatar,boolean kids){
        List<Profile> next=new ArrayList<>();boolean found=false;
        for(Profile p:profiles){if(p.id.equals(id)){next.add(new Profile(id,name,avatar,kids));found=true;}else next.add(p);}
        if(!found)throw new IllegalArgumentException("unknown profile");return new ProfileCatalog(next,selected);
    }
    ProfileCatalog select(String id){return new ProfileCatalog(profiles,id);}
    ProfileCatalog remove(String id){
        if(DEFAULT_ID.equals(id))throw new IllegalArgumentException("default profile");
        List<Profile> next=new ArrayList<>();boolean found=false;
        for(Profile p:profiles)if(p.id.equals(id))found=true;else next.add(p);
        if(!found)throw new IllegalArgumentException("unknown profile");
        return new ProfileCatalog(next,id.equals(selected)?DEFAULT_ID:selected);
    }
    String encode()throws Exception{
        JSONArray items=new JSONArray();for(Profile p:profiles)items.put(p.json());
        return new JSONObject().put("version",1).put("selected",selected).put("profiles",items).toString();
    }
    /** Reject a malformed catalog as a whole, rather than silently deleting valid profiles in it. */
    static ProfileCatalog decode(String raw)throws Exception{
        if(raw==null||raw.length()>8192)throw new IllegalArgumentException("catalog");
        JSONObject json=new JSONObject(raw);
        if(json.getInt("version")!=1)throw new IllegalArgumentException("version");
        JSONArray items=json.getJSONArray("profiles");if(items.length()>MAX_PROFILES)throw new IllegalArgumentException("profiles");
        List<Profile> result=new ArrayList<>();
        for(int i=0;i<items.length();i++){
            JSONObject p=items.getJSONObject(i);
            if(!(p.get("id") instanceof String)||!(p.get("name") instanceof String)||!(p.get("avatar") instanceof String)||!(p.get("kids") instanceof Boolean))throw new IllegalArgumentException("types");
            result.add(new Profile(p.getString("id"),p.getString("name"),p.getString("avatar"),p.getBoolean("kids")));
        }
        return new ProfileCatalog(result,json.getString("selected"));
    }
    static String normalizeName(String raw){
        if(raw==null)throw new IllegalArgumentException("name");String name=raw.trim();
        if(name.isEmpty()||name.codePointCount(0,name.length())>MAX_NAME_CODEPOINTS)throw new IllegalArgumentException("name");
        for(int i=0;i<name.length();){int cp=name.codePointAt(i);
            if(Character.isISOControl(cp)||Character.getType(cp)==Character.FORMAT||Character.getType(cp)==Character.SURROGATE)throw new IllegalArgumentException("name");
            i+=Character.charCount(cp);
        }return name;
    }
    static boolean validId(String id){return DEFAULT_ID.equals(id)||(id!=null&&id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));}
    static boolean validAvatar(String id){
        if(id==null||!id.matches("avatar_[0-9]{2}"))return false;
        int n=Integer.parseInt(id.substring(7));return n>=1&&n<=AVATAR_COUNT;
    }
    /** Stable local namespace; default must retain the existing account progress namespace. */
    static String historyScope(String accountFingerprint,String profile){
        if(accountFingerprint==null||!accountFingerprint.matches("[0-9a-f]{64}")||!validId(profile))throw new IllegalArgumentException("scope");
        return accountFingerprint+":"+profile;
    }
}

package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.*;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Remote-first profile selection and name/avatar editing. No playback runs under this screen. */
public class ProfilesActivity extends Activity implements StartupRefresh.Listener {
    static final String EXTRA_ENTRY="profile_entry";
    private boolean entry;
    private int loadGeneration;
    private LinearLayout root,body;
    private TextView status;
    private boolean online=false,busy=true,manage=false,closed=false;
    private String startedScope,startedId,editingId=null,avatar="avatar_01";
    private EditText name;
    private final Map<String,Drawable> art=new HashMap<>();
    private String restoreId,restoreName,restoreAvatar;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        String[] account=AccountStore.read(this);if(account==null){finish();return;}
        entry=getIntent().getBooleanExtra(EXTRA_ENTRY,false);
        SignOut.rememberOwner(this,account[0]);
        if(entry)Profiles.configure(this,StartupRefresh.config());
        startedScope=Profiles.scope(this);startedId=Profiles.activeId(this);
        if(state!=null){restoreId=state.getString("edit");restoreName=state.getString("name");restoreAvatar=state.getString("avatar");manage=state.getBoolean("manage");}
        root=Ui.column(this);root.setBackgroundColor(0xFF101016);root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(Ui.safeX(this),Ui.safeY(this),Ui.safeX(this),Ui.safeY(this));setContentView(root);
        body=Ui.column(this);body.setGravity(Gravity.CENTER_HORIZONTAL);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        status=Ui.text(this,"Loading profiles…",14);status.setGravity(Gravity.CENTER);status.setTextColor(Ui.TEXT_2);root.addView(status,new LinearLayout.LayoutParams(-1,Ui.dp(this,36)));
        picker();
        if(getIntent().getBooleanExtra("cached",false)){busy=false;online=!getIntent().getBooleanExtra("offline",false);picker();}else refresh();
        StartupRefresh.watchForeground(getApplication());
        if(entry)StartupRefresh.listen(this);
        if(entry&&!StartupRefresh.startedThisProcess())StartupRefresh.start(this,false);
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putBoolean("manage",manage);if(editingId!=null){out.putString("edit",editingId);out.putString("name",name.getText().toString());out.putString("avatar",avatar);}}
    @Override protected void onResume(){super.onResume();ScreenAwake.on(this);if(startedScope!=null&&!startedScope.equals(Profiles.scope(this)))finish();}
    @Override protected void onPause(){ScreenAwake.off(this);super.onPause();}
    @Override protected void onDestroy(){closed=true;loadGeneration++;StartupRefresh.unlisten(this);super.onDestroy();}
    private boolean live(){return !closed&&!isFinishing()&&startedScope.equals(Profiles.scope(this));}
    private void refresh(){busy=true;final int request=++loadGeneration;Profiles.refresh(this,(ok,error)->{if(request!=loadGeneration||!live())return;busy=false;online=ok;
        if(restoreId!=null&&ok){String id=restoreId;restoreId=null;ProfileCatalog.Profile p=find(id);if(p!=null||id.equals("new")){editor(p);name.setText(restoreName);if(ProfileCatalog.validAvatar(restoreAvatar)){avatar=restoreAvatar;paintAvatars();}return;}}
        picker();status.setText(ok?"Choose a profile. Use Manage profiles to change a name or avatar.":error);
    });}
    private ProfileCatalog.Profile find(String id){for(ProfileCatalog.Profile p:Profiles.catalog(this).profiles)if(p.id.equals(id))return p;return null;}
    private TextView button(String text,Runnable action){
        TextView b=Ui.heading(this,text,16);b.setGravity(Gravity.CENTER);b.setFocusable(true);b.setFocusableInTouchMode(true);b.setClickable(true);Ui.pad(b,this,18,10,18,10);
        b.setBackground(Ui.pill(this,false,false,12));b.setOnFocusChangeListener((v,f)->b.setBackground(Ui.pill(this,f,false,12)));
        b.setOnClickListener(v->{if(!busy)action.run();});return b;
    }
    private void heading(String title,String subtitle){TextView h=Ui.heading(this,title,30);h.setGravity(Gravity.CENTER);body.addView(h);TextView t=Ui.text(this,subtitle,14);t.setTextColor(Ui.TEXT_2);t.setGravity(Gravity.CENTER);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=Ui.dp(this,6);p.bottomMargin=Ui.dp(this,16);body.addView(t,p);}
    private void picker(){
        editingId=null;body.removeAllViews();heading(manage?"Manage profiles":"Who’s watching?",manage?"Choose a profile to edit its name and picture.":"Your own Continue Watching, My List and watched episodes.");
        GridLayout grid=new GridLayout(this);grid.setColumnCount(3);grid.setAlignmentMode(GridLayout.ALIGN_MARGINS);body.addView(grid,new LinearLayout.LayoutParams(-2,-2));
        ProfileCatalog data=Profiles.catalog(this);View selected=null;
        for(ProfileCatalog.Profile p:data.profiles){
            LinearLayout card=Ui.column(this);card.setGravity(Gravity.CENTER);card.setFocusable(true);card.setClickable(true);Ui.pad(card,this,8,7,8,6);
            card.setContentDescription(p.name+(p.id.equals(data.selected)?", current profile":""));
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageDrawable(drawable(p.avatar));card.addView(image,new LinearLayout.LayoutParams(Ui.dp(this,88),Ui.dp(this,88)));
            TextView label=Ui.heading(this,p.name,16);label.setGravity(Gravity.CENTER);label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(label,new LinearLayout.LayoutParams(-1,Ui.dp(this,42)));
            styleCard(card,false,p.id.equals(data.selected));card.setOnFocusChangeListener((v,f)->styleCard(card,f,p.id.equals(data.selected)));
            card.setOnClickListener(v->{if(busy)return;if(manage){if(online)editor(p);}else choose(p.id);});
            GridLayout.LayoutParams gp=new GridLayout.LayoutParams();gp.width=Ui.dp(this,170);gp.height=Ui.dp(this,151);gp.setMargins(Ui.dp(this,5),Ui.dp(this,3),Ui.dp(this,5),Ui.dp(this,3));grid.addView(card,gp);
            if(p.id.equals(data.selected))selected=card;
        }
        if(online&&data.profiles.size()<ProfileCatalog.MAX_PROFILES){TextView add=button("+  Add profile",()->editor(null));GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=Ui.dp(this,170);p.height=Ui.dp(this,151);p.setMargins(Ui.dp(this,5),Ui.dp(this,3),Ui.dp(this,5),Ui.dp(this,3));grid.addView(add,p);}
        LinearLayout actions=Ui.row(this);actions.setGravity(Gravity.CENTER);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.topMargin=Ui.dp(this,12);body.addView(actions,ap);
        if(online)actions.addView(button(manage?"Done":"Manage profiles",()->{manage=!manage;picker();}));
        else if(!busy)actions.addView(button("Retry",this::refresh));
        if(!busy)status.setText(online?"Up to six profiles. Kids restrictions are not available in this Preview.":"Offline: choose a saved profile, or Retry to manage profiles.");
        if(selected!=null)selected.requestFocus();
    }
    private void styleCard(View card,boolean focus,boolean current){GradientDrawable bg=Ui.rounded(focus?0xFF43242B:0xFF1D1D27,14,this);bg.setStroke(Ui.dp(this,focus?3:1),focus?0xFFE65B60:current?0xFFB84850:0xFF34343F);card.setBackground(bg);}
    private Drawable drawable(String id){Drawable d=art.get(id);if(d!=null)return d;
        try(InputStream in=getAssets().open("profile-avatars/"+id+".webp")){android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inSampleSize=Ui.dp(this,88)<=192?2:1;android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeStream(in,null,options);if(bitmap==null)return null;d=new android.graphics.drawable.BitmapDrawable(getResources(),bitmap);art.put(id,d);return d;}catch(Exception e){return null;}}
    private final Map<String,View> avatarViews=new HashMap<>();
    private void paintAvatars(){for(Map.Entry<String,View> e:avatarViews.entrySet())styleCard(e.getValue(),e.getValue().hasFocus(),e.getKey().equals(avatar));}
    private void editor(ProfileCatalog.Profile profile){
        editingId=profile==null?"new":profile.id;avatar=profile==null?"avatar_01":profile.avatar;body.removeAllViews();avatarViews.clear();
        heading(profile==null?"Add profile":"Edit profile","Choose any picture and enter your own name.");
        name=new EditText(this);name.setSingleLine(true);name.setTextColor(Ui.TEXT);name.setTextSize(18);name.setHintTextColor(Ui.TEXT_3);name.setHint("Profile name");name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(48)});name.setText(profile==null?"":profile.name);Ui.pad(name,this,14,4,14,4);name.setBackground(Ui.rounded(0xFF252530,8,this));body.addView(name,new LinearLayout.LayoutParams(Ui.dp(this,420),Ui.dp(this,42)));
        GridLayout grid=new GridLayout(this);grid.setColumnCount(7);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-2,-2);gp.topMargin=Ui.dp(this,9);body.addView(grid,gp);
        for(int i=1;i<=28;i++){
            String id=String.format(java.util.Locale.ROOT,"avatar_%02d",i);ImageView image=new ImageView(this);image.setImageDrawable(drawable(id));image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setPadding(Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4));image.setContentDescription("Avatar "+i);image.setFocusable(true);image.setClickable(true);
            GridLayout.LayoutParams ip=new GridLayout.LayoutParams();ip.width=Ui.dp(this,61);ip.height=Ui.dp(this,61);ip.setMargins(Ui.dp(this,3),Ui.dp(this,2),Ui.dp(this,3),Ui.dp(this,2));grid.addView(image,ip);avatarViews.put(id,image);
            image.setOnFocusChangeListener((v,f)->styleCard(v,f,id.equals(avatar)));image.setOnClickListener(v->{if(!busy){avatar=id;paintAvatars();}});
        }paintAvatars();
        LinearLayout actions=Ui.row(this);actions.setGravity(Gravity.CENTER);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.topMargin=Ui.dp(this,8);body.addView(actions,ap);
        actions.addView(button("Save",this::save));actions.addView(button("Cancel",this::picker));
        if(profile!=null&&!profile.id.equals("default"))actions.addView(button("Delete profile",()->Ui.dialog(this,"Delete "+profile.name+"?","This profile will no longer be available on your TVs. Other profiles are kept.",new String[]{"Delete","Cancel"},1,which->{if(which==0)mutate("delete",null,profile.id);},null )));
        status.setText("Names can contain up to 24 characters. Pictures do not enable Kids mode.");name.requestFocus();
    }
    private void save(){
        try{String text=ProfileCatalog.normalizeName(name.getText().toString());String id=editingId.equals("new")?UUID.randomUUID().toString():editingId;
            ProfileCatalog.Profile p=new ProfileCatalog.Profile(id,text,avatar,false);mutate(editingId.equals("new")?"create":"update",p,null);
        }catch(IllegalArgumentException e){status.setText("Enter a name with 1–24 characters.");name.requestFocus();}
    }
    private void mutate(String kind,ProfileCatalog.Profile profile,String id){
        if(busy)return;busy=true;status.setText("Saving profile…");((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);
        final int request=++loadGeneration;Profiles.mutate(this,kind,profile,id,(ok,error)->{if(request!=loadGeneration||!live())return;busy=false;if(ok){picker();status.setText("Profile saved.");}else{status.setText(error);if(error.contains("another TV")){editingId=null;refresh();}}});
    }
    private void choose(String id){try{AccountSync.detachProfile();Profiles.select(this,id);returnHome();}catch(IllegalArgumentException e){status.setText("This profile is not available.");}}
    private void returnHome(){Profiles.returningHome(this);startActivity(new Intent(this,HomeActivity.class).putExtra(HomeActivity.EXTRA_FRESH,entry&&getIntent().getBooleanExtra(HomeActivity.EXTRA_FRESH,false)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));overridePendingTransition(0,0);finish();}
    @Override public void onBackPressed(){
        if(editingId!=null){if(!busy)picker();return;}
        if(manage){if(!busy){manage=false;picker();}return;}
        if(entry){finishAffinity();return;}
        if(!busy)returnHome();
    }
    @Override public void onConfig(org.json.JSONObject config){
        if(closed||isFinishing())return;
        Profiles.configure(this,config);
        String current=Profiles.scope(this);
        if(!current.equals(startedScope)){
            loadGeneration++;startedScope=current;startedId=Profiles.activeId(this);
            restoreId=null;editingId=null;online=false;busy=true;picker();refresh();
        }
    }
    @Override public void onResult(StartupRefresh.Result result){
        if(!closed&&!isFinishing()&&result.state==StartupRefresh.EXPIRED){
            startActivity(new Intent(this,RenewalActivity.class));finish();
        }
    }
}

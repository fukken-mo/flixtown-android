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
    private FrameLayout root;private LinearLayout body,statusRow;
    private TextView status;private ProgressBar spinner;
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
        stage();
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
        if(restoreId!=null&&ok){String id=restoreId;restoreId=null;ProfileCatalog.Profile p=find(id);if(p!=null||id.equals("new")){editor(p);name.setText(restoreName);name.setSelection(name.length());if(ProfileCatalog.validAvatar(restoreAvatar)){avatar=restoreAvatar;paintAvatars();}return;}}
        picker();if(!ok)note(error,false);
    });}
    private ProfileCatalog.Profile find(String id){for(ProfileCatalog.Profile p:Profiles.catalog(this).profiles)if(p.id.equals(id))return p;return null;}
    /*
     * Presentation. The screen is a dark stage with a faint crimson bloom, the Flix Town mark in the
     * corner and one centred column: heading, the avatars in a single balanced row (larger for one to
     * four profiles, smaller for five or six), then quieter secondary actions and a status line.
     * Focus is a ring with a soft red glow around the avatar and a small scale on the avatar frame
     * only; every frame keeps room for that scale, so nothing reflows or clips.
     */
    private static final int RING_PAD=12;   // dp around each picker avatar: focus ring, glow and scale room
    private void stage(){
        root=new FrameLayout(this);root.setBackgroundColor(Ui.BG);root.setClipChildren(false);setContentView(root);
        View glow=new View(this);glow.setBackgroundResource(R.drawable.startup_glow);root.addView(glow,new FrameLayout.LayoutParams(-1,-1));
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.flix_logo);logo.setScaleType(ImageView.ScaleType.FIT_START);logo.setAdjustViewBounds(true);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Ui.dp(this,96),Ui.dp(this,40),Gravity.TOP|Gravity.START);lp.leftMargin=Ui.safeX(this);lp.topMargin=Ui.safeY(this);root.addView(logo,lp);
        LinearLayout page=Ui.column(this);page.setGravity(Gravity.CENTER);page.setClipChildren(false);page.setClipToPadding(false);
        page.setPadding(Ui.safeX(this),Ui.safeY(this),Ui.safeX(this),Ui.safeY(this));root.addView(page,new FrameLayout.LayoutParams(-1,-1));
        body=Ui.column(this);body.setGravity(Gravity.CENTER_HORIZONTAL);body.setClipChildren(false);body.setClipToPadding(false);page.addView(body,new LinearLayout.LayoutParams(-1,-2));
        statusRow=Ui.row(this);statusRow.setGravity(Gravity.CENTER);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,Ui.dp(this,30));sp.topMargin=Ui.dp(this,14);page.addView(statusRow,sp);
        spinner=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.GLOW));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(Ui.dp(this,20),Ui.dp(this,20));pp.rightMargin=Ui.dp(this,10);statusRow.addView(spinner,pp);
        status=Ui.text(this,"",14);status.setTextColor(Ui.TEXT_2);status.setGravity(Gravity.CENTER);status.setMaxLines(2);statusRow.addView(status,new LinearLayout.LayoutParams(-2,-2));
        note("Loading profiles…",true);
    }
    /** The status line under the actions: a message, with a small spinner while something is in progress. */
    private void note(String text,boolean working){status.setText(text==null?"":text);spinner.setVisibility(working?View.VISIBLE:View.GONE);statusRow.setVisibility(text==null||text.isEmpty()?View.INVISIBLE:View.VISIBLE);}
    /** Secondary actions (Add, Manage, Done, Retry, Cancel): quiet outline, the Flix Town focus style. */
    private TextView button(String text,Runnable action){
        Button b=Ui.button(this,text);b.setTextSize(15);b.setPadding(Ui.dp(this,22),0,Ui.dp(this,22),0);
        b.setBackground(ghost());b.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused},{}},new int[]{Color.WHITE,Ui.TEXT_2}));
        b.setOnClickListener(v->{if(!busy)action.run();});return b;
    }
    private android.graphics.drawable.StateListDrawable ghost(){
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        GradientDrawable on=Ui.rounded(Ui.FOCUS_FILL,22,this);on.setStroke(Ui.dp(this,2),Ui.GLOW);
        GradientDrawable off=Ui.rounded(0x00000000,22,this);off.setStroke(Ui.dp(this,1),0x40FFFFFF);
        states.addState(new int[]{android.R.attr.state_focused},on);states.addState(new int[]{},off);states.setExitFadeDuration(90);return states;
    }
    private void addAction(LinearLayout actions,View b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,Ui.dp(this,44));p.leftMargin=p.rightMargin=Ui.dp(this,8);actions.addView(b,p);}
    /** LEFT on the first action and RIGHT on the last stay in the row instead of jumping up to the avatars. */
    private static void containRow(LinearLayout actions){
        int n=actions.getChildCount();if(n==0)return;
        View first=actions.getChildAt(0),last=actions.getChildAt(n-1);
        for(View v:new View[]{first,last})if(v.getId()==View.NO_ID)v.setId(View.generateViewId());
        first.setNextFocusLeftId(first.getId());last.setNextFocusRightId(last.getId());
    }
    private void heading(String title,String subtitle){
        TextView h=Ui.heading(this,title,34);h.setGravity(Gravity.CENTER);h.setLetterSpacing(-0.01f);body.addView(h,new LinearLayout.LayoutParams(-1,-2));
        TextView t=Ui.text(this,subtitle,15);t.setTextColor(Ui.TEXT_3);t.setGravity(Gravity.CENTER);t.setVisibility(subtitle==null?View.GONE:View.VISIBLE);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=Ui.dp(this,6);body.addView(t,p);
    }
    private void picker(){
        editingId=null;body.removeAllViews();
        heading(manage?"Manage profiles":"Who’s watching?",manage?"Select a profile to change its name or picture.":null);
        ProfileCatalog data=Profiles.catalog(this);View selected=null;
        int count=data.profiles.size();boolean roomy=count<=4;
        int size=roomy?136:100,frame=size+2*RING_PAD,tile=roomy?frame+16:frame+4,gap=roomy?12:6;
        LinearLayout row=Ui.row(this);row.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);row.setClipChildren(false);row.setClipToPadding(false);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-2,-2);rp.topMargin=Ui.dp(this,manage?30:38);body.addView(row,rp);
        for(ProfileCatalog.Profile p:data.profiles){
            LinearLayout card=Ui.column(this);card.setGravity(Gravity.CENTER_HORIZONTAL);card.setFocusable(true);card.setClickable(true);card.setClipChildren(false);card.setClipToPadding(false);
            card.setContentDescription(p.name+(p.id.equals(data.selected)?", current profile":"")+(manage?", edit":""));
            FrameLayout avatarFrame=new FrameLayout(this);avatarFrame.setClipChildren(false);
            AvatarRing ring=new AvatarRing(Ui.dp(this,RING_PAD),false);avatarFrame.setBackground(ring);
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageDrawable(drawable(p.avatar));
            FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(Ui.dp(this,size),Ui.dp(this,size),Gravity.CENTER);avatarFrame.addView(image,ip);
            if(manage){
                View scrim=new View(this);GradientDrawable dim=new GradientDrawable();dim.setShape(GradientDrawable.OVAL);dim.setColor(0x8C08080B);scrim.setBackground(dim);avatarFrame.addView(scrim,new FrameLayout.LayoutParams(Ui.dp(this,size),Ui.dp(this,size),Gravity.CENTER));
                ImageView pencil=new ImageView(this);pencil.setImageResource(R.drawable.ic_profile_edit);int icon=Ui.dp(this,roomy?34:28);avatarFrame.addView(pencil,new FrameLayout.LayoutParams(icon,icon,Gravity.CENTER));
            }
            card.addView(avatarFrame,new LinearLayout.LayoutParams(Ui.dp(this,frame),Ui.dp(this,frame)));
            TextView label=Ui.heading(this,p.name,roomy?18:16);label.setGravity(Gravity.CENTER_HORIZONTAL);label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setTextColor(Ui.TEXT_2);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(Ui.dp(this,tile),-2);lp.topMargin=Ui.dp(this,4);card.addView(label,lp);
            card.setOnFocusChangeListener((v,f)->{ring.setFocused(f);label.setTextColor(f?Color.WHITE:Ui.TEXT_2);
                avatarFrame.animate().scaleX(f?1.06f:1f).scaleY(f?1.06f:1f).setDuration(140).start();});
            card.setOnClickListener(v->{if(busy)return;if(manage){if(online)editor(p);}else choose(p.id);});
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(Ui.dp(this,tile),-2);cp.leftMargin=cp.rightMargin=Ui.dp(this,gap);row.addView(card,cp);
            card.setAlpha(busy?0.55f:1f);
            if(p.id.equals(data.selected))selected=card;
        }
        LinearLayout actions=Ui.row(this);actions.setGravity(Gravity.CENTER);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.topMargin=Ui.dp(this,30);body.addView(actions,ap);
        if(online&&count<ProfileCatalog.MAX_PROFILES)addAction(actions,button("+  Add profile",()->editor(null)));
        if(online)addAction(actions,button(manage?"Done":"Manage profiles",()->{manage=!manage;picker();}));
        else if(!busy)addAction(actions,button("Retry",this::refresh));
        containRow(actions);
        if(busy)note("Loading profiles…",true);
        else note(online?"":"You’re offline. You can still choose a saved profile.",false);
        if(selected!=null)selected.requestFocus();else if(row.getChildCount()>0)row.getChildAt(0).requestFocus();
    }
    /** Focus ring and soft red glow drawn inside the avatar frame's padding (no blur, no offscreen layer). */
    static final class AvatarRing extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float pad;private final boolean small;private boolean focused,selected;
        AvatarRing(float padPx,boolean small){this.pad=padPx;this.small=small;paint.setStyle(android.graphics.Paint.Style.STROKE);}
        void setFocused(boolean f){if(f!=focused){focused=f;invalidateSelf();}}
        void setSelected(boolean s){if(s!=selected){selected=s;invalidateSelf();}}
        @Override public void draw(android.graphics.Canvas canvas){
            if(!focused&&!selected)return;
            android.graphics.Rect b=getBounds();float cx=b.exactCenterX(),cy=b.exactCenterY(),r=Math.min(b.width(),b.height())/2f-pad;
            float unit=pad/(small?6f:12f);
            if(focused){   // glow: concentric strokes fading outwards from the ring
                for(int i=0;i<6;i++){paint.setStrokeWidth(unit*1.6f);paint.setColor(Ui.alpha(Ui.GLOW,0.30f*(1f-i/6f)));canvas.drawCircle(cx,cy,r+unit*(4.5f+i*1.3f),paint);}
            }
            paint.setStrokeWidth(unit*(small?2.6f:3f));paint.setColor(focused?Color.WHITE:Ui.GLOW);canvas.drawCircle(cx,cy,r+unit*(small?2.2f:2.6f),paint);
        }
        @Override public void setAlpha(int a){}
        @Override public void setColorFilter(android.graphics.ColorFilter f){}
        @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    private Drawable drawable(String id){Drawable d=art.get(id);if(d!=null)return d;
        try(InputStream in=getAssets().open("profile-avatars/"+id+".webp")){android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inSampleSize=Ui.dp(this,136)*2<=384?2:1;android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeStream(in,null,options);if(bitmap==null)return null;d=new android.graphics.drawable.BitmapDrawable(getResources(),bitmap);art.put(id,d);return d;}catch(Exception e){return null;}}
    private final Map<String,View> avatarViews=new HashMap<>();
    private final Map<String,AvatarRing> avatarRings=new HashMap<>();
    private ImageView preview;private AvatarRing previewRing;private Button saveButton;
    private void paintAvatars(){
        for(Map.Entry<String,AvatarRing> e:avatarRings.entrySet())e.getValue().setSelected(e.getKey().equals(avatar));
        if(preview!=null)preview.setImageDrawable(drawable(avatar));
    }
    private void editor(ProfileCatalog.Profile profile){
        editingId=profile==null?"new":profile.id;avatar=profile==null?"avatar_01":profile.avatar;body.removeAllViews();avatarViews.clear();avatarRings.clear();
        heading(profile==null?"Add profile":"Edit profile","Choose a picture and a name.");
        LinearLayout content=Ui.row(this);content.setGravity(Gravity.CENTER);content.setClipChildren(false);content.setClipToPadding(false);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.topMargin=Ui.dp(this,24);body.addView(content,cp);
        // Left: the chosen picture, large, with the name under it.
        LinearLayout left=Ui.column(this);left.setGravity(Gravity.CENTER_HORIZONTAL);left.setClipChildren(false);content.addView(left,new LinearLayout.LayoutParams(Ui.dp(this,300),-2));
        FrameLayout previewFrame=new FrameLayout(this);previewRing=new AvatarRing(Ui.dp(this,RING_PAD),false);previewRing.setSelected(true);previewFrame.setBackground(previewRing);
        preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        previewFrame.addView(preview,new FrameLayout.LayoutParams(Ui.dp(this,128),Ui.dp(this,128),Gravity.CENTER));
        left.addView(previewFrame,new LinearLayout.LayoutParams(Ui.dp(this,128+2*RING_PAD),Ui.dp(this,128+2*RING_PAD)));
        name=new EditText(this);name.setSingleLine(true);name.setTextColor(Ui.TEXT);name.setTextSize(18);name.setHintTextColor(Ui.TEXT_3);name.setHint("Profile name");name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(48)});name.setText(profile==null?"":profile.name);
        name.setGravity(Gravity.CENTER);Ui.pad(name,this,14,0,14,0);name.setBackgroundResource(R.drawable.edit_field);name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        // The cursor starts at the end, so one RIGHT press moves on to the pictures (LEFT still edits).
        name.setOnFocusChangeListener((v,f)->{if(f)name.setSelection(name.length());});
        // From the end of the name, RIGHT goes to the chosen picture and DOWN to Save (LEFT and RIGHT still edit inside the name).
        name.setOnKeyListener((v,code,e)->{
            if(e.getAction()!=android.view.KeyEvent.ACTION_DOWN)return false;
            if(code==android.view.KeyEvent.KEYCODE_DPAD_RIGHT&&name.getSelectionStart()==name.length()&&name.getSelectionEnd()==name.length()){View target=avatarViews.get(avatar);if(target!=null)return target.requestFocus();}
            if(code==android.view.KeyEvent.KEYCODE_DPAD_DOWN&&saveButton!=null)return saveButton.requestFocus();
            return false;
        });
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,Ui.dp(this,50));np.topMargin=Ui.dp(this,14);left.addView(name,np);
        TextView hint=Ui.text(this,"Up to 24 characters",13);hint.setTextColor(Ui.TEXT_3);hint.setGravity(Gravity.CENTER);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=Ui.dp(this,6);left.addView(hint,hp);
        // Right: every picture, selected one ringed in red.
        GridLayout grid=new GridLayout(this);grid.setColumnCount(7);grid.setClipChildren(false);grid.setClipToPadding(false);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-2,-2);gp.leftMargin=Ui.dp(this,44);content.addView(grid,gp);
        for(int i=1;i<=28;i++){
            String id=String.format(java.util.Locale.ROOT,"avatar_%02d",i);ImageView image=new ImageView(this);image.setImageDrawable(drawable(id));image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad=Ui.dp(this,6);image.setPadding(pad,pad,pad,pad);AvatarRing ring=new AvatarRing(pad,true);image.setBackground(ring);
            image.setContentDescription("Avatar "+i);image.setFocusable(true);image.setClickable(true);
            GridLayout.LayoutParams ip=new GridLayout.LayoutParams();ip.width=Ui.dp(this,60);ip.height=Ui.dp(this,60);ip.setMargins(Ui.dp(this,3),Ui.dp(this,3),Ui.dp(this,3),Ui.dp(this,3));grid.addView(image,ip);
            avatarViews.put(id,image);avatarRings.put(id,ring);
            image.setOnFocusChangeListener((v,f)->{ring.setFocused(f);v.animate().scaleX(f?1.1f:1f).scaleY(f?1.1f:1f).setDuration(120).start();});
            image.setOnClickListener(v->{if(!busy){avatar=id;paintAvatars();}});
        }paintAvatars();
        LinearLayout actions=Ui.row(this);actions.setGravity(Gravity.CENTER);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.topMargin=Ui.dp(this,26);body.addView(actions,ap);
        saveButton=Ui.primaryButton(this,"Save");saveButton.setTextSize(16);saveButton.setOnClickListener(v->{if(!busy)save();});
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(Ui.dp(this,150),Ui.dp(this,48));sp.leftMargin=sp.rightMargin=Ui.dp(this,8);actions.addView(saveButton,sp);
        Button cancel=Ui.button(this,"Cancel");cancel.setTextSize(16);cancel.setOnClickListener(v->{if(!busy)picker();});
        LinearLayout.LayoutParams cnp=new LinearLayout.LayoutParams(Ui.dp(this,150),Ui.dp(this,48));cnp.leftMargin=cnp.rightMargin=Ui.dp(this,8);actions.addView(cancel,cnp);
        if(profile!=null&&!profile.id.equals("default")){
            TextView delete=button("Delete profile",()->Ui.dialog(this,"Delete "+profile.name+"?","This profile will no longer be available on your TVs. Other profiles are kept.",new String[]{"Delete","Cancel"},1,which->{if(which==0)mutate("delete",null,profile.id);},null ));
            LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-2,Ui.dp(this,44));dp.leftMargin=Ui.dp(this,24);actions.addView(delete,dp);
        }
        containRow(actions);
        note("",false);name.requestFocus();
    }
    private void save(){
        try{String text=ProfileCatalog.normalizeName(name.getText().toString());String id=editingId.equals("new")?UUID.randomUUID().toString():editingId;
            ProfileCatalog.Profile p=new ProfileCatalog.Profile(id,text,avatar,false);mutate(editingId.equals("new")?"create":"update",p,null);
        }catch(IllegalArgumentException e){note("Enter a name with 1–24 characters.",false);name.requestFocus();}
    }
    private void mutate(String kind,ProfileCatalog.Profile profile,String id){
        if(busy)return;busy=true;note("Saving profile…",true);((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);
        final int request=++loadGeneration;Profiles.mutate(this,kind,profile,id,(ok,error)->{if(request!=loadGeneration||!live())return;busy=false;if(ok){picker();note("Profile saved.",false);}else{note(error,false);if(error.contains("another TV")){editingId=null;refresh();}}});
    }
    private void choose(String id){try{AccountSync.detachProfile();Profiles.select(this,id);returnHome();}catch(IllegalArgumentException e){note("This profile is not available.",false);}}
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

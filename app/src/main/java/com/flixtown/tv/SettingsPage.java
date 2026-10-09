package com.flixtown.tv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.Locale;

/**
 * The Settings list, shown in the browse grid as a single column so it shares the grid's focus
 * handling (menu hand-off, Left opens the menu, focus kept when returning).
 *
 * Every row is something the app really does. Preferences are stored in the app's preferences
 * and read by the player each time playback starts.
 */
final class SettingsPage extends RecyclerView.Adapter<SettingsPage.Holder> {
    interface Host { void refreshCatalog(); void checkForUpdates(); void signOut(); String updateValue(); String updateDetail();
        /** The backend now reports the account as not active: the same path as the startup refresh (renewal screen). */
        void accountEnded(); }

    static final String AUTOPLAY="autoplay_next",AUDIO_LANGUAGE="audio_language",SUBTITLES_ON="subtitles_on",
        SUBTITLE_LANGUAGE="subtitle_language",LAST_REFRESH="last_refresh_at";
    /** Settings survive signing out; everything else about the account does not. */
    static final String[] PLAYBACK_KEYS={AUTOPLAY,AUDIO_LANGUAGE,SUBTITLES_ON,SUBTITLE_LANGUAGE};

    // ISO 639-1 codes; Media3 matches them against the stream's own language tags (en = eng, …).
    static final String[] LANGUAGE_CODES={"en","es","fr","de","it","pt","ar","hi","tr","ru","nl","pl","el","ja","ko","zh"};

    private static final int ACCOUNT=0,REFRESH=1,IMAGES=2,AUTOPLAY_ROW=3,AUDIO=4,SUBTITLES=5,SUBTITLE_LANG=6,UPDATES=7,VERSION=8,SIGN_OUT=9;
    private static final int[] ROWS={ACCOUNT,REFRESH,IMAGES,AUTOPLAY_ROW,AUDIO,SUBTITLES,SUBTITLE_LANG,UPDATES,VERSION,SIGN_OUT};
    static final int ROW_WIDTH_DP=760;

    private final Activity a;private final Host host;private final SharedPreferences prefs;
    private long imageBytes=-1;

    SettingsPage(Activity a,Host host){this.a=a;this.host=host;this.prefs=Api.prefs(a);setHasStableIds(true);measureImages();}

    @Override public int getItemCount(){return ROWS.length;}
    @Override public long getItemId(int position){return ROWS[position];}

    static final class Holder extends RecyclerView.ViewHolder{
        final TextView group,title,detail,value;final LinearLayout card,stats;int row;
        Holder(View v,TextView group,LinearLayout card,TextView title,TextView detail,TextView value,LinearLayout stats){
            super(v);this.group=group;this.card=card;this.title=title;this.detail=detail;this.value=value;this.stats=stats;}
    }

    @Override public Holder onCreateViewHolder(ViewGroup parent,int type){
        LinearLayout item=new LinearLayout(a);item.setOrientation(LinearLayout.VERTICAL);item.setClipChildren(false);item.setClipToPadding(false);
        item.setLayoutParams(new RecyclerView.LayoutParams(Ui.dp(a,ROW_WIDTH_DP+16),ViewGroup.LayoutParams.WRAP_CONTENT));
        item.setPadding(Ui.dp(a,8),0,Ui.dp(a,8),0);
        TextView group=Ui.text(a,"",12);group.setTextColor(Ui.TEXT_3);group.setLetterSpacing(0.16f);group.setAllCaps(true);
        group.setPadding(Ui.dp(a,6),Ui.dp(a,18),0,Ui.dp(a,8));item.addView(group);

        LinearLayout card=new LinearLayout(a);card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(Ui.dp(a,62));Ui.pad(card,a,22,10,22,10);
        card.setFocusable(true);card.setFocusableInTouchMode(true);card.setClickable(true);
        item.addView(card,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams ip=(LinearLayout.LayoutParams)card.getLayoutParams();ip.bottomMargin=Ui.dp(a,6);

        LinearLayout words=Ui.column(a);
        card.addView(words,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        TextView title=Ui.heading(a,"",18);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);words.addView(title);
        TextView detail=Ui.text(a,"",14);detail.setTextColor(Ui.TEXT_2);detail.setMaxLines(2);detail.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);dp.topMargin=Ui.dp(a,2);
        words.addView(detail,dp);
        // Account only: Status, Expiration and Connections as large tiles under the title.
        LinearLayout stats=Ui.row(a);stats.setVisibility(View.GONE);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin=Ui.dp(a,12);sp.bottomMargin=Ui.dp(a,4);words.addView(stats,sp);
        TextView value=Ui.text(a,"",15);value.setSingleLine(true);value.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);vp.leftMargin=Ui.dp(a,20);
        card.addView(value,vp);

        Holder h=new Holder(item,group,card,title,detail,value,stats);
        GradientDrawable normal=Ui.pill(a,false,false,14),focused=Ui.pill(a,true,false,14);card.setBackground(normal);
        card.setOnFocusChangeListener((v,f)->{card.setBackground(f?focused:normal);
            card.animate().scaleX(f?1.015f:1f).scaleY(f?1.015f:1f).setDuration(130).start();
            styleValue(h,f);});
        card.setOnClickListener(v->onSelect(h));
        return h;
    }

    @Override public void onBindViewHolder(Holder h,int position){
        h.row=ROWS[position];
        String group=groupFor(h.row);
        h.group.setVisibility(group==null?View.GONE:View.VISIBLE);if(group!=null)h.group.setText(group);
        h.title.setText(title(h.row));
        String detail=detail(h.row);h.detail.setVisibility(detail.isEmpty()?View.GONE:View.VISIBLE);h.detail.setText(detail);
        h.value.setText(value(h.row));
        bindStats(h);
        h.card.setContentDescription(h.row==ACCOUNT?accountSummary():title(h.row)+". "+value(h.row));
        styleValue(h,h.card.isFocused());
    }

    /** On/Off values are small pills; other values are plain text with a chevron for pickers. */
    private void styleValue(Holder h,boolean focused){
        boolean toggle=h.row==AUTOPLAY_ROW||h.row==SUBTITLES;
        if(toggle){boolean on=prefs.getBoolean(h.row==AUTOPLAY_ROW?AUTOPLAY:SUBTITLES_ON,h.row==AUTOPLAY_ROW);
            GradientDrawable pill=Ui.rounded(on?(focused?0x40FFFFFF:0x33CE4B4A):0x1AFFFFFF,8,a);
            pill.setStroke(Ui.dp(a,1),on?(focused?0x80FFFFFF:0x80CE4B4A):0x33FFFFFF);
            h.value.setBackground(pill);Ui.pad(h.value,a,14,4,14,4);h.value.setMinWidth(Ui.dp(a,64));
            h.value.setTextColor(on||focused?Ui.TEXT:Ui.TEXT_2);
        }else{h.value.setBackground(null);h.value.setPadding(0,0,0,0);h.value.setMinWidth(0);
            h.value.setTextColor(focused?Ui.TEXT:Ui.TEXT_2);}
        h.title.setTextColor(h.row==SIGN_OUT&&!focused?0xFFF0A29F:Ui.TEXT);
    }

    private static String groupFor(int row){
        switch(row){case ACCOUNT:return "Account";case REFRESH:return "Content";case AUTOPLAY_ROW:return "Playback";case UPDATES:return "App";default:return null;}
    }
    private static String title(int row){
        switch(row){
            case ACCOUNT:return "Subscription";
            case REFRESH:return "Check for new movies and shows";
            case IMAGES:return "Clear image cache";
            case AUTOPLAY_ROW:return "Autoplay next episode";
            case AUDIO:return "Preferred audio language";
            case SUBTITLES:return "Subtitles";
            case SUBTITLE_LANG:return "Subtitle language";
            case UPDATES:return "Check for app updates";
            case VERSION:return "App version";
            default:return "Sign out";
        }
    }
    private String detail(int row){
        switch(row){
            case ACCOUNT:{String[] account=AccountStore.read(a);
                return (account==null?"":"Signed in as "+account[0]+"  ·  ")+accountState();}
            case REFRESH:{if(StartupRefresh.running())return "Checking now…";
                long at=prefs.getLong(LAST_REFRESH,0);
                if(at<=0)return "Updates your account and the full catalog";
                long now=System.currentTimeMillis();
                return now-at<DateUtils.MINUTE_IN_MILLIS?"Last updated just now":"Last updated "+DateUtils.getRelativeTimeSpanString(at,now,DateUtils.MINUTE_IN_MILLIS);}
            case IMAGES:return imageBytes<0?"Posters and artwork saved on this TV":imageBytes==0?"No artwork is saved on this TV right now":
                "Posters and artwork use "+megabytes(imageBytes)+". They download again as you browse.";
            case AUTOPLAY_ROW:return "Starts the next episode when one ends, with a countdown you can cancel";
            case AUDIO:return "Used when a title has more than one audio track";
            case SUBTITLES:return "Turn on to show subtitles automatically when a title has them";
            case SUBTITLE_LANG:return prefs.getBoolean(SUBTITLES_ON,false)?"Chosen when subtitles start automatically":"Used when subtitles are on";
            case UPDATES:return host.updateDetail();
            case VERSION:return "";
            default:return "Removes this account from the TV";
        }
    }
    private String value(int row){
        switch(row){
            case ACCOUNT:return "Check now  ›";
            case AUTOPLAY_ROW:return prefs.getBoolean(AUTOPLAY,true)?"On":"Off";
            case SUBTITLES:return prefs.getBoolean(SUBTITLES_ON,false)?"On":"Off";
            case AUDIO:{String code=prefs.getString(AUDIO_LANGUAGE,"");return code.isEmpty()?"Stream default  ›":languageName(code)+"  ›";}
            case SUBTITLE_LANG:{String code=prefs.getString(SUBTITLE_LANGUAGE,"");return code.isEmpty()?"Device language  ›":languageName(code)+"  ›";}
            case VERSION:return BuildConfig.VERSION_NAME;
            case UPDATES:return host.updateValue();
            case IMAGES:return imageBytes<=0?"":megabytes(imageBytes);
            default:return "";
        }
    }
    /** When the details were last confirmed by the server, or that this check is running or failed. */
    private String accountState(){
        if(AccountInfo.checking())return "Checking your subscription…";
        long at=AccountInfo.checkedAt(a),now=System.currentTimeMillis();
        String when=at<=0?"":now-at<DateUtils.MINUTE_IN_MILLIS?"just now":DateUtils.getRelativeTimeSpanString(at,now,DateUtils.MINUTE_IN_MILLIS).toString();
        if(AccountInfo.lastCheckFailed(a))return when.isEmpty()?"Couldn't check your subscription. Select to try again.":"Couldn't refresh. Showing details from "+when+".";
        return when.isEmpty()?"Select to check your subscription now.":"Updated "+when;
    }
    /** Large tiles: Status, Expiration (a date or Never) and Connections, each only when the server reported it. */
    private void bindStats(Holder h){
        h.stats.removeAllViews();
        if(h.row!=ACCOUNT){h.stats.setVisibility(View.GONE);return;}
        String status=AccountInfo.status(a),expiration=AccountInfo.expiration(a);int connections=AccountInfo.connections(a);
        boolean active=AccountInfo.isActive(status);
        addStat(h.stats,"Status",status.isEmpty()?"Unknown":active?"Active":status,status.isEmpty()?Ui.TEXT_2:active?0xFF6FCF97:0xFFF0A29F,!status.isEmpty());
        if(!expiration.isEmpty())addStat(h.stats,"Expiration",expiration,Ui.TEXT,false);
        if(connections>0)addStat(h.stats,"Connections",String.valueOf(connections),Ui.TEXT,false);
        h.stats.setVisibility(View.VISIBLE);
    }
    private void addStat(LinearLayout stats,String label,String value,int color,boolean dot){
        LinearLayout tile=Ui.column(a);
        GradientDrawable bg=Ui.rounded(0x14FFFFFF,10,a);bg.setStroke(Ui.dp(a,1),0x1FFFFFFF);tile.setBackground(bg);
        Ui.pad(tile,a,16,10,16,12);
        TextView l=Ui.text(a,label,12);l.setTextColor(Ui.TEXT_3);l.setLetterSpacing(0.14f);l.setAllCaps(true);tile.addView(l);
        TextView v=Ui.heading(a,dot?"●  "+value:value,24);v.setTextColor(Ui.TEXT);v.setSingleLine(true);
        if(dot){android.text.SpannableString text=new android.text.SpannableString(v.getText());
            text.setSpan(new android.text.style.ForegroundColorSpan(color),0,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);v.setText(text);}
        else v.setTextColor(color);
        LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);vp.topMargin=Ui.dp(a,2);
        tile.addView(v,vp);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);
        if(stats.getChildCount()>0)tp.leftMargin=Ui.dp(a,10);
        stats.addView(tile,tp);
    }
    /** Read aloud by TalkBack and used by the emulator check: "Subscription. Status Active. Expiration Never. Connections 3." */
    private String accountSummary(){
        StringBuilder out=new StringBuilder("Subscription. Status ").append(AccountInfo.status(a).isEmpty()?"Unknown":AccountInfo.status(a)).append('.');
        String expiration=AccountInfo.expiration(a);if(!expiration.isEmpty())out.append(" Expiration ").append(expiration).append('.');
        int connections=AccountInfo.connections(a);if(connections>0)out.append(" Connections ").append(connections).append('.');
        return out.toString();
    }
    /** Settings was opened: ask the server for the current details (account only, quick) and redraw. */
    void checkAccount(boolean force){
        AccountInfo.refresh(a,force,saved->{
            if(a.isFinishing())return;
            refreshStatus();
            if(saved && !AccountInfo.isActive(AccountInfo.status(a)))host.accountEnded();
            else if(force && !saved)android.widget.Toast.makeText(a,"Couldn't reach the server. Showing your last saved details.",android.widget.Toast.LENGTH_SHORT).show();
        });
        if(AccountInfo.checking())notifyItemChanged(indexOf(ACCOUNT));
    }
    static String languageName(String code){
        Locale l=new Locale(code);String name=l.getDisplayLanguage(Locale.getDefault());
        return name.isEmpty()?code:Character.toUpperCase(name.charAt(0))+name.substring(1);
    }
    private static String megabytes(long bytes){
        if(bytes<1024*1024)return bytes<=0?"0 MB":"<1 MB";
        return String.format(Locale.US,"%.0f MB",bytes/1048576.0);
    }

    private void onSelect(Holder h){
        switch(h.row){
            case ACCOUNT:checkAccount(true);break;
            case REFRESH:host.refreshCatalog();notifyItemChanged(indexOf(REFRESH));break;
            case IMAGES:
                Images.clearCache(new android.os.Handler(android.os.Looper.getMainLooper()),freed->{
                    imageBytes=0;notifyItemChanged(indexOf(IMAGES));
                    android.widget.Toast.makeText(a,freed>0?"Cleared "+megabytes(freed)+" of saved artwork":"There was no saved artwork to clear",android.widget.Toast.LENGTH_SHORT).show();});
                break;
            case AUTOPLAY_ROW:prefs.edit().putBoolean(AUTOPLAY,!prefs.getBoolean(AUTOPLAY,true)).apply();notifyItemChanged(indexOf(AUTOPLAY_ROW));break;
            case SUBTITLES:prefs.edit().putBoolean(SUBTITLES_ON,!prefs.getBoolean(SUBTITLES_ON,false)).apply();
                notifyItemChanged(indexOf(SUBTITLES));notifyItemChanged(indexOf(SUBTITLE_LANG));break;
            case AUDIO:pickLanguage("Preferred audio language","Stream default",AUDIO_LANGUAGE,AUDIO);break;
            case SUBTITLE_LANG:pickLanguage("Subtitle language","Device language ("+Locale.getDefault().getDisplayLanguage()+")",SUBTITLE_LANGUAGE,SUBTITLE_LANG);break;
            case UPDATES:host.checkForUpdates();break;
            case SIGN_OUT:host.signOut();break;
            default:break;   // app version is information only
        }
    }
    private void pickLanguage(String title,String first,String key,int row){
        String[] labels=new String[LANGUAGE_CODES.length+1];labels[0]=first;
        String current=prefs.getString(key,"");int selected=0;
        for(int i=0;i<LANGUAGE_CODES.length;i++){labels[i+1]=languageName(LANGUAGE_CODES[i]);if(LANGUAGE_CODES[i].equals(current))selected=i+1;}
        Ui.picker(a,title,labels,selected,which->{
            prefs.edit().putString(key,which==0?"":LANGUAGE_CODES[which-1]).apply();notifyItemChanged(indexOf(row));});
    }
    private static int indexOf(int row){for(int i=0;i<ROWS.length;i++)if(ROWS[i]==row)return i;return 0;}

    /** Checking, up to date, available, downloading, ready or failed. */
    void updateRowChanged(){notifyItemChanged(indexOf(UPDATES));}
    /** Refreshes the rows whose text depends on the account or the last refresh. */
    void refreshStatus(){notifyItemChanged(indexOf(ACCOUNT));notifyItemChanged(indexOf(REFRESH));}
    private void measureImages(){Api.IO.execute(()->{long bytes=Images.diskBytes();
        a.runOnUiThread(()->{imageBytes=bytes;if(!a.isFinishing())notifyItemChanged(indexOf(IMAGES));});});}
}

package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expired account, and renewing early from Settings.
 *
 * In-app renewal (when the panel config has renewal_api_url): plans and prices come from the
 * renewal service, the customer pays with Cash App Pay by scanning a QR code drawn here
 * (RenewalPayment), and the service renews the account once the payment is confirmed. Nothing
 * secret is in the app; see RenewalApi.
 *
 * Without renewal_api_url this is the original screen, unchanged:
 *
 * Status and expiry come from the Xtream account response (user_info.status / exp_date). Plans,
 * prices and the Cash App destination come from the panel config (plans, cashapp_url); nothing is
 * hard-coded. Paying or scanning never changes the account here: the viewer sends a renewal
 * request to the panel, the operator confirms payment, and the account turns Active on the server.
 * This screen re-checks the account every minute and opens Flix Town as soon as it is Active.
 */
public class RenewalActivity extends Activity {
    private static final String[][] PLANS={{"1m","1 month"},{"3m","3 months"},{"6m","6 months"},{"12m","12 months"}};
    private static final Pattern CASHTAG=Pattern.compile("^https://cash\\.app/(\\$[A-Za-z0-9_-]{1,40})/?$");
    private static final long RECHECK_MS=60000;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String selected="1m";
    private TextView status,accountLine,payTitle,payAmount,payDestination,payNote;
    private LinearLayout plans;private ImageView cashQr;private View qrFrame;
    private JSONObject planConfig;private String cashUrl="";
    private boolean checking;
    /** Opened from Settings by an active account: no automatic return to Home. */
    static final String EXTRA_EARLY="renew_early";
    private boolean inApp,early,renewedHere;

    @Override public void onCreate(Bundle b){super.onCreate(b);
        early=getIntent().getBooleanExtra(EXTRA_EARLY,false);
        if(RenewalApi.available(this)){inApp=true;drawInApp();loadContext();confirmOffer();}
        else{draw();showAccount();loadConfig();}
        checkAccount(false);}
    @Override protected void onStart(){super.onStart();handler.postDelayed(recheck,RECHECK_MS);if(payment!=null)payment.resume();}
    @Override protected void onStop(){handler.removeCallbacks(recheck);if(payment!=null)payment.pause();super.onStop();}
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event){
        if(event.getKeyCode()==android.view.KeyEvent.KEYCODE_BACK && payment!=null && payment.isOpen()){
            if(event.getAction()==android.view.KeyEvent.ACTION_UP)payment.onBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private final Runnable recheck=new Runnable(){@Override public void run(){checkAccount(false);handler.postDelayed(this,RECHECK_MS);}};

    private void draw(){
        FrameLayout shell=new FrameLayout(this);shell.setBackgroundColor(Ui.BG);setContentView(shell);
        View wash=new View(this);wash.setBackgroundResource(R.drawable.login_backdrop);shell.addView(wash,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout root=Ui.row(this);root.setClipChildren(false);
        root.setPadding(Ui.dp(this,44),Ui.safeY(this),Ui.dp(this,56),Ui.safeY(this));
        shell.addView(root,new FrameLayout.LayoutParams(-1,-1));

        ScrollView leftScroll=new ScrollView(this);leftScroll.setVerticalScrollBarEnabled(false);leftScroll.setClipChildren(false);leftScroll.setClipToPadding(false);
        root.addView(leftScroll,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout left=Ui.column(this);left.setClipChildren(false);left.setPadding(Ui.dp(this,12),Ui.dp(this,6),Ui.dp(this,28),Ui.dp(this,6));   // room for the focus scale and halo
        leftScroll.addView(left);
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.flix_logo);logo.setScaleType(ImageView.ScaleType.FIT_START);
        // Logo and the account switch share the top row, so the whole screen fits at 720p and 1080p.
        LinearLayout top=Ui.row(this);top.setClipChildren(false);top.setGravity(Gravity.CENTER_VERTICAL);left.addView(top,new LinearLayout.LayoutParams(-1,-2));
        top.addView(logo,new LinearLayout.LayoutParams(0,Ui.dp(this,50),1));
        Button logout=Ui.button(this,"Use a different account");logout.setTextSize(14);logout.setPadding(Ui.dp(this,18),0,Ui.dp(this,18),0);
        top.addView(logout,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,42)));
        TextView heading=Ui.heading(this,"Your subscription has ended",28);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=Ui.dp(this,8);left.addView(heading,hp);
        accountLine=Ui.text(this,"",16);accountLine.setTextColor(Ui.TEXT_2);left.addView(accountLine);
        TextView how=Ui.text(this,"",15);how.setTextColor(Ui.TEXT);how.setLineSpacing(0,1.2f);
        how.setText("1   Choose a plan below\n2   Pay with Cash App using the code on the right\n3   Send your request. Flix Town reopens once payment is confirmed.");
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,-2);wp.topMargin=Ui.dp(this,10);left.addView(how,wp);

        plans=Ui.column(this);plans.setClipChildren(false);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.topMargin=Ui.dp(this,6);left.addView(plans,pp);showPlans();

        EditText phone=new EditText(this);phone.setHint("Phone number (so we can reach you)");phone.setTextColor(Ui.TEXT);phone.setHintTextColor(Ui.TEXT_3);
        phone.setSingleLine(true);phone.setInputType(InputType.TYPE_CLASS_PHONE);phone.setTextSize(16);
        phone.setBackgroundResource(R.drawable.edit_field);phone.setPadding(Ui.dp(this,16),0,Ui.dp(this,16),0);
        LinearLayout.LayoutParams php=new LinearLayout.LayoutParams(-1,Ui.dp(this,48));php.topMargin=Ui.dp(this,8);left.addView(phone,php);

        LinearLayout actions=Ui.row(this);actions.setClipChildren(false);left.addView(actions);
        Button submit=Ui.primaryButton(this,"Send renewal request");actions.addView(submit,buttonParams(0,0));
        submit.setOnClickListener(v->{
            String[] account=AccountStore.read(this);if(account==null){status.setText("Please sign in again.");return;}
            status.setTextColor(Ui.TEXT_2);status.setText("Sending your request…");
            Api.IO.execute(()->{try{Api.post("renewal-request",new JSONObject()
                .put("username",account[0]).put("phone",phone.getText().toString().trim()).put("plan",selected));
                runOnUiThread(()->status.setText("Request sent. After you pay, Flix Town reopens here as soon as the payment is confirmed."));
            }catch(Exception e){runOnUiThread(()->{status.setTextColor(0xFFF0A29F);status.setText("Couldn't send the request: "+e.getMessage());});}});
        });
        Button check=Ui.button(this,"Check again");actions.addView(check,buttonParams(0,12));check.setOnClickListener(v->checkAccount(true));
        // The answer to "Send" / "Check again" sits right under those buttons, inside the safe area.
        status=Ui.text(this,"",15);status.setTextColor(Ui.TEXT_2);status.setMaxLines(3);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=Ui.dp(this,8);left.addView(status,sp);
        logout.setOnClickListener(v->Ui.dialog(this,"Sign out of this account?","You can sign in with another account on the next screen.",
            new String[]{"Cancel","Sign out"},0,i->{if(i==1){AccountStore.clear(this);Api.prefs(this).edit().remove("expired").apply();
                startActivity(new Intent(this,LoginActivity.class));finish();}},null));

        // Cash App card
        LinearLayout card=Ui.column(this);card.setGravity(Gravity.CENTER_HORIZONTAL);card.setBackground(Ui.glass(this,20));
        Ui.pad(card,this,26,22,26,20);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(Ui.dp(this,320),ViewGroup.LayoutParams.WRAP_CONTENT);cp.gravity=Gravity.CENTER_VERTICAL;
        root.addView(card,cp);
        payTitle=Ui.heading(this,"Pay with Cash App",20);card.addView(payTitle);
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundResource(R.drawable.qr_frame);Ui.pad(frame,this,10,10,10,10);qrFrame=frame;
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(Ui.dp(this,210),Ui.dp(this,210));fp.topMargin=Ui.dp(this,14);card.addView(frame,fp);
        cashQr=new ImageView(this);cashQr.setScaleType(ImageView.ScaleType.FIT_CENTER);frame.addView(cashQr,new FrameLayout.LayoutParams(-1,-1));
        payAmount=Ui.heading(this,"",24);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-2,-2);ap.topMargin=Ui.dp(this,12);card.addView(payAmount,ap);
        payDestination=Ui.text(this,"",15);payDestination.setTextColor(Ui.TEXT_2);card.addView(payDestination);
        payNote=Ui.text(this,"Scan with your phone to pay. Your account is renewed after the payment is confirmed, not when the link is opened.",13);
        payNote.setTextColor(Ui.TEXT_3);payNote.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=Ui.dp(this,10);card.addView(payNote,np);
        updatePayment();
        submit.requestFocus();
    }
    private LinearLayout.LayoutParams buttonParams(int width,int leftMargin){
        LinearLayout.LayoutParams p=width==0?new LinearLayout.LayoutParams(0,Ui.dp(this,50),1):new LinearLayout.LayoutParams(width==-2?ViewGroup.LayoutParams.WRAP_CONTENT:width,Ui.dp(this,50));
        p.topMargin=Ui.dp(this,8);p.leftMargin=Ui.dp(this,leftMargin);return p;
    }
    /** Account line from the real Xtream response saved by sign-in or the startup refresh. */
    private void showAccount(){
        String[] account=AccountStore.read(this);
        String name=account==null?"":account[0];
        String state=Api.prefs(this).getString("account_status","Expired");
        String exp=Api.prefs(this).getString("account_exp_date","");
        String when="";
        try{long seconds=Long.parseLong(exp.trim());if(seconds>0)when=DateFormat.getDateInstance(DateFormat.LONG,Locale.getDefault()).format(new Date(seconds*1000));}catch(Exception ignored){}
        StringBuilder line=new StringBuilder();
        if(!name.isEmpty())line.append("Account ").append(name);
        if(!when.isEmpty())line.append(line.length()>0?"  ·  ":"").append("expired ").append(when);
        else if(!state.isEmpty() && !"Active".equalsIgnoreCase(state))line.append(line.length()>0?"  ·  ":"").append("status: ").append(state.toLowerCase(Locale.US));
        accountLine.setText(line.toString());
    }
    private void showPlans(){
        View focused=getCurrentFocus();String focusedPlan=focused!=null && focused.getTag() instanceof String?(String)focused.getTag():null;
        plans.removeAllViews();JSONObject values=planConfig==null?null:planConfig.optJSONObject("plans");
        LinearLayout line=null;
        for(int i=0;i<PLANS.length;i++){String[] choice=PLANS[i];String price=values==null?"":values.optString(choice[0],"");
            if(i%2==0){line=Ui.row(this);line.setClipChildren(false);plans.addView(line);}
            boolean chosen=choice[0].equals(selected);
            Button b=Ui.button(this,(chosen?"✓  ":"")+choice[1]+(price.isEmpty()?"":"  ·  $"+price));b.setTextSize(16);
            if(chosen)Ui.styleButton(b,Ui.SELECTED);
            Button self=b;
            line.addView(b,buttonParams(0,i%2==0?0:12));
            b.setTag(choice[0]);
            b.setOnClickListener(v->{selected=choice[0];status.setTextColor(Ui.TEXT_2);status.setText(choice[1]+" selected");showPlans();updatePayment();
                View again=plans.findViewWithTag(choice[0]);if(again!=null)again.requestFocus();});
            if(choice[0].equals(focusedPlan))self.post(self::requestFocus);
        }
    }
    private String priceFor(String plan){JSONObject values=planConfig==null?null:planConfig.optJSONObject("plans");
        return values==null?"":values.optString(plan,"").trim();}
    /** Builds the Cash App QR from the operator's configured destination. */
    private void updatePayment(){
        String price=priceFor(selected);
        if(!cashUrl.startsWith("https://")){
            qrFrame.setVisibility(View.GONE);payAmount.setText("");
            payDestination.setText(planConfig==null?"Loading payment details…":"Cash App payment isn't set up yet. Please contact support to renew.");
            payNote.setVisibility(View.GONE);return;
        }
        qrFrame.setVisibility(View.VISIBLE);payNote.setVisibility(View.VISIBLE);
        String target=cashUrl;Matcher tag=CASHTAG.matcher(cashUrl);
        boolean numeric=price.matches("\\d{1,5}(\\.\\d{1,2})?");
        // cash.app/$tag/amount pre-fills the amount; only used for a plain cashtag link and a numeric price.
        if(tag.matches() && numeric)target=cashUrl.replaceAll("/+$","")+"/"+price;
        payAmount.setText(numeric?"$"+price:"");
        payDestination.setText(tag.matches()?"Send to "+tag.group(1):"Operator's Cash App payment page");
        String finalTarget=target;
        Api.IO.execute(()->{try{Bitmap qr=makeQr(finalTarget);runOnUiThread(()->cashQr.setImageBitmap(qr));}catch(Exception ignored){}});
    }
    private void loadConfig(){Api.IO.execute(()->{try{JSONObject config=Api.get(BuildConfig.PANEL_URL+"config.php");
        RenewalApi.remember(this,config);
        runOnUiThread(()->{if(isFinishing())return;
            if(RenewalApi.available(this)){recreate();return;}   // the panel now offers in-app renewal
            planConfig=config;cashUrl=config.optString("cashapp_url","").trim();showPlans();updatePayment();});
    }catch(Exception e){runOnUiThread(()->{status.setText("Couldn't load plans. Check the TV's internet connection.");
        payDestination.setText("Payment details are unavailable right now.");});}});}
    private static Bitmap makeQr(String value)throws Exception{
        Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.MARGIN,1);
        int size=380;BitMatrix matrix=new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,size,size,hints);
        int[] pixels=new int[size*size];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)pixels[y*size+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;
        return Bitmap.createBitmap(pixels,size,size,Bitmap.Config.RGB_565);
    }
    /** Asks the server; only an Active status from the account API reopens the app. */
    private void checkAccount(boolean userAsked){
        if(checking)return;checking=true;
        if(userAsked){status.setTextColor(Ui.TEXT_2);status.setText("Checking your account…");}
        Api.IO.execute(()->{try{
            JSONObject result=Api.get(Api.accountUrl(this));JSONObject info=result.optJSONObject("user_info");
            boolean active=info!=null&&"Active".equalsIgnoreCase(info.optString("status"));
            if(info!=null)AccountInfo.save(this,result);
            runOnUiThread(()->{checking=false;if(isFinishing())return;
                if(inApp){showSummary();
                    if(active && (early||renewedHere||(payment!=null&&payment.isOpen())))return;
                    if(!active){if(userAsked)loadContext();return;}
                }else showAccount();
                if(active){Api.prefs(this).edit().putBoolean("expired",false).apply();
                    StartupRefresh.start(this,false);
                    Intent home=new Intent(this,HomeActivity.class);home.putExtra(HomeActivity.EXTRA_FRESH,true);startActivity(home);finish();}
                else if(userAsked)status.setText("Your account hasn't been renewed yet. It can take a few minutes after payment.");});
        }catch(Exception e){runOnUiThread(()->{checking=false;if(userAsked)status.setText("Couldn't check the account right now.");});}});}

    /* ---------------- In-app renewal ---------------- */

    private FrameLayout shell;private RenewalPayment payment;
    private TextView heading,subline;private LinearLayout summary,planRow,actions;private Button pay,secondary;
    private JSONObject context;private int chosenMonths;private boolean loading;
    private final java.util.List<View> planTiles=new java.util.ArrayList<>();

    private void drawInApp(){
        shell=new FrameLayout(this);shell.setBackgroundColor(Ui.BG);setContentView(shell);
        View glow=new View(this);glow.setBackgroundResource(R.drawable.renew_glow);shell.addView(glow,new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);scroll.setClipToPadding(false);shell.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout col=Ui.column(this);col.setGravity(Gravity.CENTER_HORIZONTAL);col.setClipChildren(false);col.setClipToPadding(false);
        col.setPadding(Ui.safeX(this),Ui.safeY(this),Ui.safeX(this),Ui.safeY(this));
        scroll.addView(col,new FrameLayout.LayoutParams(-1,-2));

        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.flix_logo);logo.setAdjustViewBounds(true);
        col.addView(logo,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,62)));
        heading=Ui.heading(this,"",30);heading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-2,-2);hp.topMargin=Ui.dp(this,6);col.addView(heading,hp);
        subline=Ui.text(this,"",16);subline.setTextColor(Ui.TEXT_2);subline.setGravity(Gravity.CENTER);col.addView(subline);

        summary=Ui.row(this);summary.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,-2);sp.topMargin=Ui.dp(this,14);col.addView(summary,sp);

        planRow=Ui.row(this);planRow.setGravity(Gravity.CENTER);planRow.setClipChildren(false);planRow.setClipToPadding(false);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-2,-2);pp.topMargin=Ui.dp(this,16);col.addView(planRow,pp);

        pay=Ui.primaryButton(this,"Pay with Cash App Pay");pay.setTextSize(20);pay.setId(View.generateViewId());
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(Ui.dp(this,480),Ui.dp(this,58));bp.topMargin=Ui.dp(this,16);
        col.addView(pay,bp);pay.setOnClickListener(v->onPrimary());

        status=Ui.text(this,"",16);status.setTextColor(Ui.TEXT_2);status.setGravity(Gravity.CENTER);status.setMaxLines(2);
        LinearLayout.LayoutParams stp=new LinearLayout.LayoutParams(Ui.dp(this,640),ViewGroup.LayoutParams.WRAP_CONTENT);stp.topMargin=Ui.dp(this,8);
        col.addView(status,stp);

        actions=Ui.row(this);actions.setGravity(Gravity.CENTER);actions.setClipChildren(false);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-2,-2);ap.topMargin=Ui.dp(this,6);col.addView(actions,ap);
        Button check=Ui.button(this,"Check again");check.setTextSize(16);
        actions.addView(check,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,48)));
        check.setOnClickListener(v->{status.setTextColor(Ui.TEXT_2);status.setText("Checking your account…");checkAccount(true);loadContext();});
        secondary=Ui.button(this,early?"Back":"Use a different account");secondary.setTextSize(16);
        LinearLayout.LayoutParams sbp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(this,48));sbp.leftMargin=Ui.dp(this,12);
        actions.addView(secondary,sbp);
        secondary.setOnClickListener(v->{if(early){finish();return;}
            Ui.dialog(this,"Sign out of this account?","You can sign in with another account on the next screen.",
                new String[]{"Cancel","Sign out"},0,i->{if(i==1){AccountStore.clear(this);RenewalApi.forget();Api.prefs(this).edit().remove("expired").apply();
                    startActivity(new Intent(this,LoginActivity.class));finish();}},null);});

        // D-pad: plans (Left/Right) → Pay → Check again / second button; nothing wraps or escapes sideways.
        check.setId(View.generateViewId());secondary.setId(View.generateViewId());
        pay.setNextFocusDownId(check.getId());pay.setNextFocusLeftId(pay.getId());pay.setNextFocusRightId(pay.getId());
        check.setNextFocusUpId(pay.getId());check.setNextFocusLeftId(check.getId());check.setNextFocusRightId(secondary.getId());
        check.setNextFocusDownId(check.getId());
        secondary.setNextFocusUpId(pay.getId());secondary.setNextFocusLeftId(check.getId());secondary.setNextFocusRightId(secondary.getId());
        secondary.setNextFocusDownId(secondary.getId());

        payment=new RenewalPayment(this,shell,new RenewalPayment.Listener(){
            @Override public void onRenewed(long newExpiresAt){renewedHere=true;
                Api.prefs(RenewalActivity.this).edit().putBoolean("expired",false).apply();
                // The account details come from the Flix Town backend again, not from this screen.
                AccountInfo.refresh(RenewalActivity.this,true,saved->{if(!isFinishing()){showSummary();loadContext();}});}
            @Override public void onClosed(String last){afterPayment(last);}
        });
        showSummary();showHeading();pay.setEnabled(false);
        status.setText("Loading your plans…");
        pay.post(pay::requestFocus);
    }
    private void showHeading(){
        String[] account=AccountStore.read(this);
        boolean active=AccountInfo.isActive(AccountInfo.status(this));
        heading.setText(renewedHere&&active?"You're all set":active?"Renew your subscription":"Your subscription has ended");
        subline.setText(account==null?"":"Signed in as "+account[0]);
    }
    /** Current plan (when known), expiry and devices: from the renewal service, else the last account check. */
    private void showSummary(){
        if(summary==null)return;
        summary.removeAllViews();
        JSONObject acc=context==null?null:context.optJSONObject("account");
        String plan=acc==null||acc.isNull("plan_label")?"":acc.optString("plan_label","");
        String expires;long exp=acc==null||acc.isNull("expires_at")?-1:acc.optLong("expires_at",-1);
        if(acc!=null)expires=exp<0?"Never":AccountInfo.formatDate(this,exp);else expires=AccountInfo.expiration(this);
        boolean past=acc!=null?exp>=0&&exp*1000<System.currentTimeMillis():!AccountInfo.isActive(AccountInfo.status(this));
        int devices=acc!=null?acc.optInt("devices",0):AccountInfo.connections(this);
        if(renewedHere){expires=AccountInfo.expiration(this);past=!AccountInfo.isActive(AccountInfo.status(this));}
        if(!plan.isEmpty())addSummary("Current plan",plan);
        if(!expires.isEmpty())addSummary(past&&!"Never".equals(expires)?"Expired on":"Expires on",expires);
        if(devices>0)addSummary("Devices",String.valueOf(devices));
        showHeading();
    }
    private void addSummary(String label,String value){
        LinearLayout tile=Ui.column(this);tile.setGravity(Gravity.CENTER_HORIZONTAL);
        android.graphics.drawable.GradientDrawable bg=Ui.rounded(0x14FFFFFF,12,this);bg.setStroke(Ui.dp(this,1),0x1FFFFFFF);tile.setBackground(bg);Ui.pad(tile,this,22,10,22,12);
        TextView l=Ui.text(this,label,12);l.setTextColor(Ui.TEXT_3);l.setLetterSpacing(0.14f);l.setAllCaps(true);tile.addView(l);
        TextView v=Ui.heading(this,value,24);tile.addView(v);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(Ui.dp(this,190),ViewGroup.LayoutParams.WRAP_CONTENT);
        if(summary.getChildCount()>0)lp.leftMargin=Ui.dp(this,12);
        summary.addView(tile,lp);
        tile.setContentDescription(label+" "+value);
    }
    /** The panel config still names the renewal service? If the operator removed it, use the original screen. */
    private void confirmOffer(){Api.IO.execute(()->{try{JSONObject config=Api.get(BuildConfig.PANEL_URL+"config.php");
        RenewalApi.remember(this,config);
        runOnUiThread(()->{if(!isFinishing() && !RenewalApi.available(this) && (payment==null||!payment.isOpen()) && !early)recreate();});
    }catch(Exception ignored){}});}
    /** Asks the renewal service for the account, plans and prices. */
    private void loadContext(){
        if(loading)return;loading=true;
        Api.IO.execute(()->{RenewalApi.Reply r;try{r=RenewalApi.context(this);}catch(Exception e){r=null;}
            RenewalApi.Reply reply=r;
            runOnUiThread(()->{loading=false;if(isFinishing())return;
                if(reply==null){if(context==null)showLoadProblem("We couldn't load your plans. Check the TV's internet connection, then try again.");return;}
                if(!reply.ok){showLoadProblem(reply.message.isEmpty()?"Renewing isn't available right now. Please try again later.":reply.message);return;}
                context=reply.body;showSummary();showPlans2();});});
    }
    private void showLoadProblem(String text){
        planRow.removeAllViews();planTiles.clear();
        status.setTextColor(0xFFF0A29F);status.setText(text);
        pay.setText("Try again");pay.setEnabled(true);pay.setTag("retry");
        if(getCurrentFocus()==null||!getCurrentFocus().isShown())pay.requestFocus();
    }
    /** The plan tiles (focus selects a plan; OK moves on to Pay). */
    private void showPlans2(){
        planRow.removeAllViews();planTiles.clear();pay.setTag(null);
        JSONObject acc=context.optJSONObject("account");
        boolean renewable=acc!=null&&acc.optBoolean("renewable");
        if(renewedHere && AccountInfo.isActive(AccountInfo.status(this)) && !early){
            status.setTextColor(0xFF6FCF97);status.setText("Renewal successful. Your new expiration date is "+AccountInfo.expiration(this)+".");
            pay.setText("Start watching");pay.setEnabled(true);pay.setTag("watch");pay.requestFocus();return;}
        if(!renewable){
            boolean never=acc!=null&&acc.isNull("expires_at")&&!"Disabled".equals(acc.optString("status"));
            status.setTextColor(Ui.TEXT_2);
            status.setText(never?"Your plan never expires, so there's nothing to renew.":"Please contact support to renew this account.");
            pay.setText(early?"Back":"Check again");pay.setEnabled(true);pay.setTag(early?"back":"check");pay.requestFocus();return;}
        if(!context.optBoolean("payments_available",true)){
            status.setTextColor(Ui.TEXT_2);status.setText("Cash App Pay isn't available right now. Please try again later.");
            pay.setText("Try again");pay.setEnabled(true);pay.setTag("retry");pay.requestFocus();return;}
        org.json.JSONArray list=context.optJSONArray("plans");
        int current=acc.isNull("plan_months")?0:acc.optInt("plan_months",0);
        if(chosenMonths==0)chosenMonths=current;
        View focus=null;boolean known=false;
        for(int i=0;list!=null&&i<list.length();i++){JSONObject p=list.optJSONObject(i);if(p==null)continue;
            if(p.optInt("months")==chosenMonths)known=true;}
        if(!known&&list!=null&&list.length()>0)chosenMonths=list.optJSONObject(0).optInt("months");
        for(int i=0;list!=null&&i<list.length();i++){JSONObject p=list.optJSONObject(i);if(p==null)continue;
            View tile=planTile(p,p.optInt("months")==current);planTiles.add(tile);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(Ui.dp(this,170),Ui.dp(this,98));
            if(planRow.getChildCount()>0)lp.leftMargin=Ui.dp(this,14);
            planRow.addView(tile,lp);if(p.optInt("months")==chosenMonths)focus=tile;}
        status.setTextColor(Ui.TEXT_2);status.setText(early?"Choose a plan. The time is added to your current expiration date.":"Choose a plan, then pay with Cash App Pay on your phone.");
        updatePay();
        // Ends of the row stay put; Up has nothing above; Down goes to Pay.
        for(int i=0;i<planTiles.size();i++){View t=planTiles.get(i);t.setNextFocusUpId(t.getId());
            if(i==0)t.setNextFocusLeftId(t.getId());if(i==planTiles.size()-1)t.setNextFocusRightId(t.getId());}
        if(focus!=null){View f=focus;f.post(f::requestFocus);}
    }
    private View planTile(JSONObject p,boolean current){
        int months=p.optInt("months");String label=p.optString("label");String price=priceText(p.optString("price"));
        LinearLayout tile=Ui.column(this);tile.setGravity(Gravity.CENTER);tile.setFocusable(true);tile.setFocusableInTouchMode(true);tile.setClickable(true);
        tile.setId(View.generateViewId());tile.setTag(months);
        TextView name=Ui.heading(this,label,18);name.setGravity(Gravity.CENTER);tile.addView(name);
        TextView cost=Ui.heading(this,price,32);cost.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD));cost.setGravity(Gravity.CENTER);tile.addView(cost);
        TextView tag=Ui.text(this,current?"Current plan":"",12);tag.setTextColor(Ui.TEXT_3);tag.setLetterSpacing(0.1f);tag.setAllCaps(true);tag.setGravity(Gravity.CENTER);
        tag.setVisibility(current?View.VISIBLE:View.GONE);tile.addView(tag);
        tile.setContentDescription(label+" "+price);
        tile.setNextFocusDownId(pay.getId());
        Runnable style=()->{boolean f=tile.isFocused(),sel=months==chosenMonths;
            tile.setBackground(Ui.pill(this,f,sel,14));name.setTextColor(f||sel?Ui.TEXT:Ui.TEXT_2);cost.setTextColor(f||sel?Ui.TEXT:Ui.TEXT_2);};
        style.run();
        tile.setOnFocusChangeListener((v,f)->{Ui.focusScale(v,f);
            if(f && chosenMonths!=months){chosenMonths=months;for(View t:planTiles)if(t!=tile)restyle(t);updatePay();}
            style.run();if(f)pay.setNextFocusUpId(tile.getId());});
        tile.setOnClickListener(v->{chosenMonths=months;updatePay();pay.requestFocus();});
        tile.setTag(R.id.plan_style,style);
        return tile;
    }
    private void restyle(View t){Object s=t.getTag(R.id.plan_style);if(s instanceof Runnable)((Runnable)s).run();}
    private static String priceText(String price){return "$"+(price.endsWith(".00")?price.substring(0,price.length()-3):price);}
    private JSONObject chosenPlan(){
        org.json.JSONArray list=context==null?null:context.optJSONArray("plans");
        for(int i=0;list!=null&&i<list.length();i++){JSONObject p=list.optJSONObject(i);if(p!=null&&p.optInt("months")==chosenMonths)return p;}
        return null;
    }
    private void updatePay(){
        JSONObject p=chosenPlan();
        pay.setText(p==null?"Pay with Cash App Pay":"Pay with Cash App Pay  ·  "+priceText(p.optString("price")));
        pay.setEnabled(p!=null);
    }
    private void onPrimary(){
        Object tag=pay.getTag();
        if("retry".equals(tag)){status.setTextColor(Ui.TEXT_2);status.setText("Loading your plans…");loadContext();return;}
        if("back".equals(tag)){finish();return;}
        if("check".equals(tag)){checkAccount(true);loadContext();return;}
        if("watch".equals(tag)){StartupRefresh.start(this,false);
            Intent home=new Intent(this,HomeActivity.class);home.putExtra(HomeActivity.EXTRA_FRESH,true);startActivity(home);finish();return;}
        JSONObject p=chosenPlan();if(p==null)return;
        payment.start(p.optInt("months"),p.optString("label"),p.optString("price"));
    }
    /** Back on the renewal screen after the payment step closed. */
    private void afterPayment(String last){
        if("cancelled".equals(last)){status.setTextColor(Ui.TEXT_2);status.setText("Payment cancelled. You can choose a plan and try again.");}
        if(renewedHere){showSummary();if(context!=null)showPlans2();
            if(early){status.setTextColor(0xFF6FCF97);status.setText("Renewal successful. Your new expiration date is "+AccountInfo.expiration(this)+".");}
            pay.requestFocus();return;}
        View again=null;for(View t:planTiles)if(Integer.valueOf(chosenMonths).equals(t.getTag()))again=t;
        if(again!=null)again.requestFocus();else pay.requestFocus();
    }
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
}

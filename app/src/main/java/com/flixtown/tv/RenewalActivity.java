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
 * Expired account.
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

    @Override public void onCreate(Bundle b){super.onCreate(b);draw();showAccount();loadConfig();checkAccount(false);}
    @Override protected void onStart(){super.onStart();handler.postDelayed(recheck,RECHECK_MS);}
    @Override protected void onStop(){handler.removeCallbacks(recheck);super.onStop();}
    private final Runnable recheck=new Runnable(){@Override public void run(){checkAccount(false);handler.postDelayed(this,RECHECK_MS);}};

    private void draw(){
        FrameLayout shell=new FrameLayout(this);shell.setBackgroundColor(Ui.BG);setContentView(shell);
        View wash=new View(this);wash.setBackgroundResource(R.drawable.login_backdrop);shell.addView(wash,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout root=Ui.row(this);root.setClipChildren(false);
        root.setPadding(Ui.dp(this,56),Ui.safeY(this),Ui.dp(this,56),Ui.safeY(this));
        shell.addView(root,new FrameLayout.LayoutParams(-1,-1));

        ScrollView leftScroll=new ScrollView(this);leftScroll.setVerticalScrollBarEnabled(false);leftScroll.setClipChildren(false);leftScroll.setClipToPadding(false);
        root.addView(leftScroll,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout left=Ui.column(this);left.setClipChildren(false);left.setPadding(Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,28),Ui.dp(this,4));
        leftScroll.addView(left);
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.flix_logo);logo.setScaleType(ImageView.ScaleType.FIT_START);
        left.addView(logo,new LinearLayout.LayoutParams(Ui.dp(this,120),Ui.dp(this,64)));
        TextView heading=Ui.heading(this,"Your subscription has ended",28);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=Ui.dp(this,8);left.addView(heading,hp);
        accountLine=Ui.text(this,"",16);accountLine.setTextColor(Ui.TEXT_2);left.addView(accountLine);
        TextView how=Ui.text(this,"",15);how.setTextColor(Ui.TEXT);how.setLineSpacing(0,1.35f);
        how.setText("1   Choose a plan below\n2   Pay with Cash App using the code on the right\n3   Send your renewal request. Flix Town reopens once your payment is confirmed.");
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,-2);wp.topMargin=Ui.dp(this,14);left.addView(how,wp);

        plans=Ui.column(this);plans.setClipChildren(false);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.topMargin=Ui.dp(this,6);left.addView(plans,pp);showPlans();

        EditText phone=new EditText(this);phone.setHint("Phone number (so we can reach you)");phone.setTextColor(Ui.TEXT);phone.setHintTextColor(Ui.TEXT_3);
        phone.setSingleLine(true);phone.setInputType(InputType.TYPE_CLASS_PHONE);phone.setTextSize(16);
        phone.setBackgroundResource(R.drawable.edit_field);phone.setPadding(Ui.dp(this,16),0,Ui.dp(this,16),0);
        LinearLayout.LayoutParams php=new LinearLayout.LayoutParams(-1,Ui.dp(this,48));php.topMargin=Ui.dp(this,12);left.addView(phone,php);

        LinearLayout actions=Ui.row(this);actions.setClipChildren(false);left.addView(actions);
        Button submit=Ui.button(this,"Send renewal request");actions.addView(submit,buttonParams(0,0));
        submit.setOnClickListener(v->{
            String[] account=AccountStore.read(this);if(account==null){status.setText("Please sign in again.");return;}
            status.setTextColor(Ui.TEXT_2);status.setText("Sending your request…");
            Api.IO.execute(()->{try{Api.post("renewal-request",new JSONObject()
                .put("username",account[0]).put("phone",phone.getText().toString().trim()).put("plan",selected));
                runOnUiThread(()->status.setText("Request sent. After you pay, Flix Town reopens here as soon as the payment is confirmed."));
            }catch(Exception e){runOnUiThread(()->{status.setTextColor(0xFFF0A29F);status.setText("Couldn't send the request: "+e.getMessage());});}});
        });
        Button check=Ui.button(this,"Check again");actions.addView(check,buttonParams(0,12));check.setOnClickListener(v->checkAccount(true));
        Button logout=Ui.button(this,"Use a different account");
        LinearLayout.LayoutParams lp=buttonParams(-2,0);left.addView(logout,lp);logout.setTextSize(15);
        logout.setOnClickListener(v->Ui.dialog(this,"Sign out of this account?","You can sign in with another account on the next screen.",
            new String[]{"Cancel","Sign out"},0,i->{if(i==1){AccountStore.clear(this);Api.prefs(this).edit().remove("expired").apply();
                startActivity(new Intent(this,LoginActivity.class));finish();}},null));
        status=Ui.text(this,"",15);status.setTextColor(Ui.TEXT_2);status.setMaxLines(3);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=Ui.dp(this,10);left.addView(status,sp);

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
        LinearLayout.LayoutParams p=width==0?new LinearLayout.LayoutParams(0,Ui.dp(this,48),1):new LinearLayout.LayoutParams(width==-2?ViewGroup.LayoutParams.WRAP_CONTENT:width,Ui.dp(this,48));
        p.topMargin=Ui.dp(this,10);p.leftMargin=Ui.dp(this,leftMargin);return p;
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
            if(chosen && !b.isFocused())b.setBackground(Ui.pill(this,false,true,24));
            Button self=b;
            b.setOnFocusChangeListener((v,f)->{v.setBackground(Ui.pill(this,f,chosen,24));
                v.animate().scaleX(f?1.04f:1f).scaleY(f?1.04f:1f).setDuration(120).start();});
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
        runOnUiThread(()->{planConfig=config;cashUrl=config.optString("cashapp_url","").trim();showPlans();updatePayment();});
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
            if(info!=null)Api.prefs(this).edit().putString("account_status",info.optString("status",""))
                .putString("account_exp_date",info.optString("exp_date","")).apply();
            runOnUiThread(()->{checking=false;if(isFinishing())return;showAccount();
                if(active){Api.prefs(this).edit().putBoolean("expired",false).apply();
                    StartupRefresh.start(this,false);
                    Intent home=new Intent(this,HomeActivity.class);home.putExtra(HomeActivity.EXTRA_FRESH,true);startActivity(home);finish();}
                else if(userAsked)status.setText("Your account hasn't been renewed yet. It can take a few minutes after payment.");});
        }catch(Exception e){runOnUiThread(()->{checking=false;if(userAsked)status.setText("Couldn't check the account right now.");});}});}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
}

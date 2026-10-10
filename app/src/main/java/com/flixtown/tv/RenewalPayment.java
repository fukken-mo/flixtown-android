package com.flixtown.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.json.JSONObject;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Cash App Pay step of the in-app renewal, drawn over the renewal screen.
 *
 * The renewal service creates the checkout and returns its public link; this draws that link as a
 * QR code (no browser on the TV). The customer scans it with their phone and approves the payment
 * in Cash App. The TV then asks the service every few seconds how it is going: waiting, processing,
 * renewed, cancelled, expired, failed or needs assistance. Only the service decides that a payment
 * succeeded and the account was renewed; a network hiccup while checking is never shown as a failure.
 */
final class RenewalPayment {
    interface Listener { void onRenewed(long newExpiresAt); void onClosed(String lastState); }

    private static final long POLL_MS=3000;
    private final Activity a;private final FrameLayout host;private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private FrameLayout overlay;private LinearLayout card;
    private TextView title,plan,steps,state,timer;private ImageView qr;private FrameLayout qrFrame;
    private LinearLayout buttons;
    private String paymentId,lastState="",qrUrl="";
    private int months;private String label,price;
    private long expiresAtElapsed;
    private int generation;private boolean polling,paused,busy;

    RenewalPayment(Activity a,FrameLayout host,Listener listener){this.a=a;this.host=host;this.listener=listener;}

    boolean isOpen(){return overlay!=null;}
    /** True while money may be moving: the customer must not be told it failed or be sent away. */
    boolean inProgress(){return isOpen() && ("processing".equals(lastState)||"waiting".equals(lastState)||lastState.isEmpty());}

    /** Opens the overlay and asks the service for a new Cash App Pay checkout for this plan. */
    void start(int months,String label,String price){
        this.months=months;this.label=label;this.price=price;
        if(overlay==null)build();
        paymentId=null;lastState="";qrUrl="";
        showCreating();
        int token=++generation;
        Api.IO.execute(()->{RenewalApi.Reply r;try{r=RenewalApi.create(a,months);}catch(Exception e){r=null;}
            RenewalApi.Reply reply=r;
            a.runOnUiThread(()->{if(token!=generation||overlay==null)return;
                if(reply==null){showProblem("We couldn't reach Cash App Pay","Check the TV's internet connection, then try again.",true);return;}
                if(!reply.ok){showProblem("Cash App Pay isn't available",reply.message.isEmpty()?"Please try again in a moment.":reply.message,!"not_renewable".equals(reply.error));return;}
                apply(reply.body.optJSONObject("payment"));
                startPolling();});});
    }

    /* ---------------- layout ---------------- */

    private void build(){
        overlay=new FrameLayout(a);overlay.setBackgroundColor(0xEB050507);overlay.setClickable(true);overlay.setFocusable(false);
        View glow=new View(a);glow.setBackgroundResource(R.drawable.renew_glow);overlay.addView(glow,new FrameLayout.LayoutParams(-1,-1));
        card=Ui.row(a);card.setGravity(Gravity.CENTER_VERTICAL);card.setBackground(Ui.glass(a,24));card.setClipChildren(false);
        Ui.pad(card,a,34,30,38,30);
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(Ui.dp(a,720),ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.CENTER);
        overlay.addView(card,cp);

        qrFrame=new FrameLayout(a);qrFrame.setBackgroundResource(R.drawable.qr_frame);Ui.pad(qrFrame,a,12,12,12,12);
        card.addView(qrFrame,new LinearLayout.LayoutParams(Ui.dp(a,250),Ui.dp(a,250)));
        qr=new ImageView(a);qr.setScaleType(ImageView.ScaleType.FIT_CENTER);qrFrame.addView(qr,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout words=Ui.column(a);words.setClipChildren(false);
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);wp.leftMargin=Ui.dp(a,32);
        card.addView(words,wp);
        title=Ui.heading(a,"",26);words.addView(title);
        plan=Ui.heading(a,"",20);plan.setTextColor(Ui.TEXT_2);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-2,-2);pp.topMargin=Ui.dp(a,4);words.addView(plan,pp);
        steps=Ui.text(a,"",17);steps.setLineSpacing(0,1.25f);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=Ui.dp(a,14);words.addView(steps,sp);
        state=Ui.heading(a,"",18);
        LinearLayout.LayoutParams stp=new LinearLayout.LayoutParams(-1,-2);stp.topMargin=Ui.dp(a,16);words.addView(state,stp);
        timer=Ui.text(a,"",14);timer.setTextColor(Ui.TEXT_3);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.topMargin=Ui.dp(a,4);words.addView(timer,tp);
        buttons=Ui.row(a);buttons.setClipChildren(false);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=Ui.dp(a,18);words.addView(buttons,bp);
        // The renewal screen underneath must not take focus while the payment step is up.
        for(int i=0;i<host.getChildCount();i++){View c=host.getChildAt(i);
            if(c instanceof ViewGroup)((ViewGroup)c).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);}
        host.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
    }
    /** Replaces the buttons; the first one gets focus. Labels and actions alternate. */
    private void setButtons(Object... pairs){
        buttons.removeAllViews();Button first=null;
        for(int i=0;i<pairs.length;i+=2){String text=(String)pairs[i];Runnable action=(Runnable)pairs[i+1];
            Button b=i==0&&!Ui.dismissive(text)?Ui.primaryButton(a,text):Ui.button(a,text);b.setTextSize(18);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(a,54));
            if(i>0)lp.leftMargin=Ui.dp(a,12);
            b.setMinWidth(Ui.dp(a,150));buttons.addView(b,lp);b.setOnClickListener(v->action.run());
            if(first==null)first=b;}
        if(first!=null){Button f=first;f.post(f::requestFocus);}
    }
    private void setState(String text,int color,boolean dot){
        if(!dot){state.setText(text);state.setTextColor(color);return;}
        android.text.SpannableString s=new android.text.SpannableString("●  "+text);
        s.setSpan(new android.text.style.ForegroundColorSpan(color),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        state.setText(s);state.setTextColor(Ui.TEXT);
    }

    /* ---------------- states ---------------- */

    private void showCreating(){
        title.setTextColor(Ui.TEXT);
        qrFrame.setVisibility(View.VISIBLE);qr.setImageDrawable(null);qr.setAlpha(1f);
        title.setText("Pay with Cash App Pay");plan.setText(label+"  ·  $"+price);
        steps.setText("Preparing your secure payment code…");setState("",Ui.TEXT_2,false);timer.setText("");
        setButtons("Cancel",(Runnable)this::cancel);
    }
    private void apply(JSONObject p){
        if(p==null)return;
        if(paymentId==null)paymentId=p.optString("id",null);
        String s=p.optString("state","processing");
        if(p.has("expires_in"))expiresAtElapsed=android.os.SystemClock.elapsedRealtime()+p.optLong("expires_in")*1000L;
        String url=p.optString("checkout_url","");
        if(!url.isEmpty() && !url.equals(qrUrl) && url.startsWith("https://")){qrUrl=url;drawQr(url);}
        if(BuildConfig.DEMO && !s.equals(lastState))Log.i("FlixTownQA","renewal state="+s+(qrUrl.isEmpty()?"":" qr="+qrUrl));
        if(s.equals(lastState)){if("waiting".equals(s))tick();return;}
        lastState=s;
        switch(s){
            case "waiting":showWaiting();break;
            case "processing":showProcessing();break;
            case "renewed":stopPolling();showRenewed(p.isNull("new_expires_at")?0:p.optLong("new_expires_at",0));break;
            case "cancelled":stopPolling();close("cancelled");break;
            case "expired":stopPolling();showProblem("This code has expired","No payment was taken. Get a new code to try again.",true);break;
            case "failed":stopPolling();showProblem("The payment didn't go through","Nothing was renewed. You can try again, or use another Cash App account.",true);break;
            case "needs_assistance":stopPolling();showAssistance();break;
            default:showProcessing();break;
        }
    }
    private void showWaiting(){
        title.setTextColor(Ui.TEXT);
        qrFrame.setVisibility(View.VISIBLE);qr.setAlpha(1f);
        title.setText("Scan to pay with Cash App Pay");plan.setText(label+"  ·  $"+price);
        steps.setText("1   Open the camera on your phone\n2   Scan this code\n3   Approve the payment in Cash App");
        setState("Waiting for payment…",Ui.GOLD,true);tick();
        setButtons("Cancel",(Runnable)this::cancel);
    }
    private void showProcessing(){
        title.setTextColor(Ui.TEXT);
        qrFrame.setVisibility(View.VISIBLE);qr.setAlpha(0.18f);
        title.setText("Payment processing");plan.setText(label+"  ·  $"+price);
        steps.setText("We're confirming your payment and renewing your subscription. This usually takes a few seconds. Please keep this screen open.");
        setState("Payment processing…",0xFF6FB5CF,true);timer.setText("");
        buttons.removeAllViews();   // nothing to cancel once money is moving
    }
    private void showRenewed(long newExpiresAt){
        qrFrame.setVisibility(View.GONE);
        title.setText("✓  Renewal Successful");title.setTextColor(0xFF6FCF97);
        String when=newExpiresAt>0?AccountInfo.formatDate(a,newExpiresAt):"";
        plan.setText(label+"  ·  $"+price);
        steps.setText(when.isEmpty()?"Thank you! Your subscription has been renewed.":"Thank you! Your subscription now expires on "+when+".");
        setState("",Ui.TEXT,false);timer.setText("");
        setButtons("Done",(Runnable)()->close("renewed"));
        listener.onRenewed(newExpiresAt);
        handler.postDelayed(()->{if("renewed".equals(lastState))close("renewed");},6000);
    }
    private void showAssistance(){
        qrFrame.setVisibility(View.GONE);
        title.setText("We'll finish your renewal");title.setTextColor(Ui.TEXT);
        String ref=paymentId==null?"":paymentId.substring(Math.max(0,paymentId.length()-8)).toUpperCase(Locale.US);
        steps.setText("Your payment needs a quick check before your subscription is renewed. Please contact support"
            +(ref.isEmpty()?"":" and mention reference "+ref)+". Please don't pay again.");
        setState("Renewal needs assistance",Ui.GOLD,true);timer.setText("");
        setButtons("Close",(Runnable)()->close("needs_assistance"));
    }
    private void showProblem(String head,String body,boolean retry){
        qrFrame.setVisibility(View.GONE);title.setText(head);title.setTextColor(Ui.TEXT);
        plan.setText(label+"  ·  $"+price);steps.setText(body);setState("",Ui.TEXT_2,false);timer.setText("");
        if(retry)setButtons("Try again",(Runnable)()->start(months,label,price),"Close",(Runnable)()->close(lastState.isEmpty()?"error":lastState));
        else setButtons("Close",(Runnable)()->close(lastState.isEmpty()?"error":lastState));
    }
    private void tick(){
        long left=(expiresAtElapsed-android.os.SystemClock.elapsedRealtime())/1000;
        timer.setText(left>0?String.format(Locale.US,"Code expires in %d:%02d",left/60,left%60):"");
    }
    private void drawQr(String url){
        Api.IO.execute(()->{try{
            Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.MARGIN,1);
            hints.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M);
            int size=480;BitMatrix m=new QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,size,size,hints);
            int[] px=new int[size*size];for(int y=0;y<size;y++)for(int x=0;x<size;x++)px[y*size+x]=m.get(x,y)?Color.BLACK:Color.WHITE;
            Bitmap b=Bitmap.createBitmap(px,size,size,Bitmap.Config.RGB_565);
            a.runOnUiThread(()->{if(url.equals(qrUrl) && qr!=null)qr.setImageBitmap(b);});
        }catch(Exception ignored){}});
    }

    /* ---------------- checking ---------------- */

    private void startPolling(){polling=true;handler.removeCallbacks(poll);if(!paused)handler.postDelayed(poll,POLL_MS);}
    private void stopPolling(){polling=false;handler.removeCallbacks(poll);}
    private final Runnable poll=new Runnable(){@Override public void run(){
        if(!polling||paymentId==null||overlay==null)return;
        if("waiting".equals(lastState))tick();
        if(busy){handler.postDelayed(this,POLL_MS);return;}
        busy=true;int token=generation;String id=paymentId;
        Api.IO.execute(()->{RenewalApi.Reply r;try{r=RenewalApi.status(a,id);}catch(Exception e){r=null;}
            RenewalApi.Reply reply=r;
            a.runOnUiThread(()->{busy=false;if(token!=generation||overlay==null)return;
                // A failed check is not a failed payment: keep checking, show a quiet note.
                if(reply==null||!reply.ok){if(!"waiting".equals(lastState)||reply==null)timer.setText("Reconnecting…");}
                else apply(reply.body.optJSONObject("payment"));
                if(polling)handler.postDelayed(poll,POLL_MS);});});
    }};
    /** Activity left the screen / came back: stop and resume checking (the payment itself is unaffected). */
    void pause(){paused=true;handler.removeCallbacks(poll);}
    void resume(){paused=false;if(polling){handler.removeCallbacks(poll);handler.post(poll);}}

    /** Cancel button or Back: cancels an unpaid code; if it was paid meanwhile, keeps following it. */
    void cancel(){
        if(paymentId==null){close("cancelled");return;}
        String id=paymentId;int token=generation;
        setButtons();steps.setText("Cancelling…");
        Api.IO.execute(()->{RenewalApi.Reply r;try{r=RenewalApi.cancel(a,id);}catch(Exception e){r=null;}
            RenewalApi.Reply reply=r;
            a.runOnUiThread(()->{if(token!=generation||overlay==null)return;
                JSONObject p=reply!=null&&reply.ok?reply.body.optJSONObject("payment"):null;
                String s=p==null?"":p.optString("state");
                if(s.equals("cancelled")||s.equals("expired")||s.equals("failed")){close("cancelled");return;}
                if(p==null){close("cancelled");return;}   // service unreachable: the code expires on its own
                lastState="";apply(p);});});   // it was paid in the meantime: follow it to the end
    }
    /** Back key while the overlay is up. Returns true when handled. */
    boolean onBack(){
        if(overlay==null)return false;
        if("processing".equals(lastState))return true;   // never walk away from a payment being confirmed
        if("waiting".equals(lastState)||lastState.isEmpty()&&paymentId!=null)cancel();
        else close(lastState.isEmpty()?"cancelled":lastState);
        return true;
    }
    void close(String finalState){
        generation++;stopPolling();handler.removeCallbacksAndMessages(null);
        if(overlay!=null){host.removeView(overlay);overlay=null;
            for(int i=0;i<host.getChildCount();i++){View c=host.getChildAt(i);
                if(c instanceof ViewGroup)((ViewGroup)c).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);}}
        if(BuildConfig.DEMO)Log.i("FlixTownQA","renewal closed state="+finalState);
        listener.onClosed(finalState);
    }
}

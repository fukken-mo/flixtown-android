package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONObject;

public class RenewalActivity extends Activity {
    private String selected="1m"; private TextView status; private LinearLayout plans; private ImageView cashQr;
    @Override public void onCreate(Bundle b){super.onCreate(b);draw();loadConfig();}
    private void draw(){
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);Ui.pad(root,this,90,35,90,30);setContentView(root);
        root.addView(Ui.heading(this,"Renew Flix Town",28));
        TextView description=Ui.text(this,"Your account has expired. Choose a plan and send a renewal request for the same account.",17);
        Ui.pad(description,this,0,12,0,15);root.addView(description);
        LinearLayout body=Ui.row(this);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        android.widget.ScrollView leftScroll=new android.widget.ScrollView(this);leftScroll.setVerticalScrollBarEnabled(false);
        leftScroll.setClipChildren(false);leftScroll.setClipToPadding(false);body.addView(leftScroll,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout left=Ui.column(this);left.setClipChildren(false);left.setPadding(Ui.dp(this,6),Ui.dp(this,4),Ui.dp(this,6),Ui.dp(this,4));leftScroll.addView(left);
        plans=Ui.column(this);plans.setClipChildren(false);left.addView(plans);showPlans(null);
        EditText phone=new EditText(this);phone.setHint("Phone number");phone.setTextColor(Color.WHITE);phone.setSingleLine(true);
        phone.setInputType(InputType.TYPE_CLASS_PHONE);left.addView(phone,new LinearLayout.LayoutParams(-1,Ui.dp(this,54)));
        Button submit=Ui.button(this,"Request renewal");left.addView(submit,buttonParams(-1,0));submit.setOnClickListener(v->{
            String[] account=AccountStore.read(this);if(account==null){status.setText("Please sign in again");return;}
            status.setText("Sending request…");
            Api.IO.execute(()->{try{JSONObject result=Api.post("renewal-request",new JSONObject()
                .put("username",account[0]).put("phone",phone.getText().toString().trim()).put("plan",selected));
                runOnUiThread(()->status.setText("Request sent. Pay through Cash App, then wait for approval."));
            }catch(Exception e){runOnUiThread(()->status.setText(e.getMessage()));}});
        });
        LinearLayout more=Ui.row(this);more.setClipChildren(false);left.addView(more);
        Button check=Ui.button(this,"Check account");more.addView(check,buttonParams(0,0));check.setOnClickListener(v->checkAccount());
        Button logout=Ui.button(this,"Different account");more.addView(logout,buttonParams(0,10));logout.setOnClickListener(v->{AccountStore.clear(this);Api.prefs(this).edit().remove("expired").apply();startActivity(new Intent(this,LoginActivity.class));finish();});
        status=Ui.text(this,"",16);Ui.pad(status,this,0,14,0,0);left.addView(status);
        LinearLayout right=Ui.column(this);right.setGravity(Gravity.CENTER);body.addView(right,new LinearLayout.LayoutParams(0,-1,1));
        cashQr=new ImageView(this);right.addView(cashQr,new LinearLayout.LayoutParams(Ui.dp(this,190),Ui.dp(this,190)));
        TextView cashText=Ui.text(this,"Cash App",18);cashText.setGravity(Gravity.CENTER);right.addView(cashText);
        submit.requestFocus();
    }
    private JSONObject planConfig;
    /** Plans are shown two per row so labels such as "12 months  $50" are never cut off; ✓ marks the choice. */
    private void showPlans(JSONObject config){
        if(config!=null)planConfig=config;
        View focused=getCurrentFocus();int focusedIndex=-1;
        plans.removeAllViews();JSONObject values=planConfig==null?null:planConfig.optJSONObject("plans");
        String[][] choices={{"1m","1 month"},{"3m","3 months"},{"6m","6 months"},{"12m","12 months"}};
        LinearLayout line=null;
        for(int i=0;i<choices.length;i++){String[] choice=choices[i];String price=values==null?"":values.optString(choice[0],"");
            if(i%2==0){line=Ui.row(this);line.setClipChildren(false);plans.addView(line);}
            boolean chosen=choice[0].equals(selected);
            Button b=Ui.button(this,(chosen?"✓ ":"")+choice[1]+(price.isEmpty()?"":"  $"+price));
            line.addView(b,buttonParams(0,i%2==0?0:10));
            if(focused!=null && focused.getParent()!=null && focused.getParent().getParent()==plans && focused.getTag() instanceof String && choice[0].equals(focused.getTag()))focusedIndex=i;
            b.setTag(choice[0]);
            b.setOnClickListener(v->{selected=choice[0];status.setText(choice[1]+" selected");showPlans(null);focusPlan(choice[0]);});
        }
        if(focusedIndex>=0)focusPlan(choices[focusedIndex][0]);
    }
    private void focusPlan(String id){View v=plans.findViewWithTag(id);if(v!=null)v.requestFocus();}
    private LinearLayout.LayoutParams buttonParams(int width,int leftMargin){
        LinearLayout.LayoutParams p=width==0?new LinearLayout.LayoutParams(0,Ui.dp(this,48),1):new LinearLayout.LayoutParams(width,Ui.dp(this,48));
        p.topMargin=Ui.dp(this,10);p.leftMargin=Ui.dp(this,leftMargin);return p;
    }
    private void loadConfig(){Api.IO.execute(()->{try{JSONObject config=Api.get(BuildConfig.PANEL_URL+"config.php");
        String cash=config.optString("cashapp_url","");Bitmap bitmap=makeQr(cash);
        runOnUiThread(()->{showPlans(config);cashQr.setImageBitmap(bitmap);});
    }catch(Exception e){runOnUiThread(()->status.setText("Could not load plans. Check your connection."));}});}
    private Bitmap makeQr(String value)throws Exception{if(!value.startsWith("https://"))return null;
        BitMatrix matrix=new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,220,220);
        Bitmap b=Bitmap.createBitmap(220,220,Bitmap.Config.RGB_565);
        for(int y=0;y<220;y++)for(int x=0;x<220;x++)b.setPixel(x,y,matrix.get(x,y)?Color.BLACK:Color.WHITE);return b;
    }
    private void checkAccount(){status.setText("Checking account…");Api.IO.execute(()->{try{
        JSONObject result=Api.get(Api.accountUrl(this));JSONObject info=result.optJSONObject("user_info");
        boolean active=info!=null&&"Active".equalsIgnoreCase(info.optString("status"));
        runOnUiThread(()->{if(active){Api.prefs(this).edit().putBoolean("expired",false).apply();startActivity(new Intent(this,HomeActivity.class));finish();}
            else status.setText("Account is still awaiting renewal");});
    }catch(Exception e){runOnUiThread(()->status.setText("Account check unavailable"));}});}
}

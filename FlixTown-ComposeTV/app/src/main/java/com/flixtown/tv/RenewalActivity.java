package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
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
        LinearLayout left=Ui.column(this);body.addView(left,new LinearLayout.LayoutParams(0,-1,1));
        plans=Ui.row(this);left.addView(plans);showPlans(null);
        EditText phone=new EditText(this);phone.setHint("Phone number");phone.setTextColor(Color.WHITE);phone.setSingleLine(true);
        phone.setInputType(InputType.TYPE_CLASS_PHONE);left.addView(phone,new LinearLayout.LayoutParams(-1,Ui.dp(this,58)));
        Button submit=Ui.button(this,"Request renewal");left.addView(submit);submit.setOnClickListener(v->{
            String[] account=AccountStore.read(this);if(account==null){status.setText("Please sign in again");return;}
            status.setText("Sending request…");
            Api.IO.execute(()->{try{JSONObject result=Api.post("renewal-request",new JSONObject()
                .put("username",account[0]).put("phone",phone.getText().toString().trim()).put("plan",selected));
                runOnUiThread(()->status.setText("Request sent. Pay through Cash App, then wait for approval."));
            }catch(Exception e){runOnUiThread(()->status.setText(e.getMessage()));}});
        });
        Button check=Ui.button(this,"Check account again");left.addView(check);check.setOnClickListener(v->checkAccount());
        Button logout=Ui.button(this,"Use a different account");left.addView(logout);logout.setOnClickListener(v->{AccountStore.clear(this);Api.prefs(this).edit().remove("expired").apply();startActivity(new Intent(this,LoginActivity.class));finish();});
        status=Ui.text(this,"",16);Ui.pad(status,this,0,14,0,0);left.addView(status);
        LinearLayout right=Ui.column(this);right.setGravity(Gravity.CENTER);body.addView(right,new LinearLayout.LayoutParams(0,-1,1));
        cashQr=new ImageView(this);right.addView(cashQr,new LinearLayout.LayoutParams(Ui.dp(this,190),Ui.dp(this,190)));
        TextView cashText=Ui.text(this,"Cash App",18);cashText.setGravity(Gravity.CENTER);right.addView(cashText);
        submit.requestFocus();
    }
    private void showPlans(JSONObject config){
        plans.removeAllViews();JSONObject values=config==null?null:config.optJSONObject("plans");
        String[][] choices={{"1m","1 month"},{"3m","3 months"},{"6m","6 months"},{"12m","12 months"}};
        for(String[] choice:choices){String price=values==null?"":values.optString(choice[0],"");
            Button b=Ui.button(this,choice[1]+(price.isEmpty()?"":"  $"+price));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,Ui.dp(this,58),1);p.rightMargin=Ui.dp(this,8);plans.addView(b,p);
            b.setOnClickListener(v->{selected=choice[0];status.setText(choice[1]+" selected");});
        }
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

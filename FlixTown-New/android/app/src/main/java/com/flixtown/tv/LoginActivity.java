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
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONObject;

public class LoginActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling;
    private String pairCode, verifier;
    private TextView message;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (AccountStore.read(this)!=null) { home(); return; }
        render();
    }
    private void render() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(0x00000000);Ui.pad(root,this,78,27,78,18);
        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.flix_logo);
        brand.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(brand,new LinearLayout.LayoutParams(Ui.dp(this,160),Ui.dp(this,68)));
        LinearLayout body = Ui.row(this); body.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout direct = Ui.column(this); Ui.pad(direct,this,31,25,31,27);
        direct.setBackground(Ui.panel(this,18));
        TextView directEyebrow=Ui.text(this,"YOUR ACCOUNT",12);directEyebrow.setTextColor(0xFFFF6773);direct.addView(directEyebrow);
        TextView directTitle=Ui.heading(this,"Sign in",28);Ui.pad(directTitle,this,0,7,0,2);direct.addView(directTitle);
        TextView directHelp=Ui.text(this,"Enter your Flix Town account details",14);directHelp.setTextColor(0xFFADB0B9);direct.addView(directHelp);
        EditText user = field("Username",false), pass = field("Password",true);
        direct.addView(user); direct.addView(pass);
        android.widget.Button submit = Ui.button(this,"Sign in"); direct.addView(submit);
        submit.setOnClickListener(v -> validate(user.getText().toString().trim(),pass.getText().toString()));
        LinearLayout.LayoutParams directParams=new LinearLayout.LayoutParams(0,-2,1);directParams.rightMargin=Ui.dp(this,17);body.addView(direct,directParams);
        LinearLayout qr = Ui.column(this);qr.setGravity(Gravity.CENTER_HORIZONTAL);Ui.pad(qr,this,28,22,28,18);qr.setBackground(Ui.panel(this,18));
        TextView qrTitle=Ui.heading(this,"Activate with your phone",22);qr.addView(qrTitle);
        TextView qrHelp=Ui.text(this,"Scan the code to sign in on your phone",14);qrHelp.setTextColor(0xFFADB0B9);Ui.pad(qrHelp,this,0,6,0,0);qr.addView(qrHelp);
        ImageView image = new ImageView(this);image.setBackground(Ui.rounded(Color.WHITE,10,this));image.setPadding(Ui.dp(this,10),Ui.dp(this,10),Ui.dp(this,10),Ui.dp(this,10));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(this,174),Ui.dp(this,174));ip.gravity=Gravity.CENTER_HORIZONTAL;ip.topMargin=Ui.dp(this,14);qr.addView(image,ip);
        TextView code = Ui.text(this,"Loading activation code…",14);code.setTextColor(0xFFBEC0C9);code.setGravity(Gravity.CENTER);Ui.pad(code,this,0,13,0,0);qr.addView(code);
        LinearLayout.LayoutParams qrParams=new LinearLayout.LayoutParams(0,-2,1);qrParams.leftMargin=Ui.dp(this,17);body.addView(qr,qrParams);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        message = Ui.text(this,"",16); message.setTextColor(0xFFFFB3B3); root.addView(message);
        FrameLayout scene=new FrameLayout(this);scene.addView(new CinemaBackground(this),new FrameLayout.LayoutParams(-1,-1));
        scene.addView(root,new FrameLayout.LayoutParams(-1,-1));setContentView(scene);
        submit.requestFocus(); startPair(image,code);
    }
    private EditText field(String hint, boolean password) {
        EditText field = new EditText(this); field.setSingleLine(true); field.setHint(hint); field.setTextColor(Color.WHITE); field.setHintTextColor(0xFF9DA2AD);field.setTextSize(16);Ui.pad(field,this,14,0,14,0);
        GradientDrawable fieldBg=Ui.rounded(0xFF26252D,9,this);fieldBg.setStroke(Ui.dp(this,1),0xFF4B3A43);field.setBackground(fieldBg);
        if (password) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,Ui.dp(this,50)); p.setMargins(0,Ui.dp(this,13),0,0); field.setLayoutParams(p);
        return field;
    }
    private void validate(String user,String pass) {
        if (user.isEmpty() || pass.isEmpty()) { message.setText("Enter both account fields"); return; }
        message.setText("Checking your account…");
        Api.IO.execute(() -> {
            try {
                JSONObject config = Api.get(BuildConfig.PANEL_URL + "config.php");
                String server = config.getString("xtream_url");
                JSONObject account = Api.get(server.replaceAll("/+$", "") + "/player_api.php?username=" + Api.enc(user) + "&password=" + Api.enc(pass));
                JSONObject info=account.optJSONObject("user_info");
                if(info==null || (!"Active".equalsIgnoreCase(info.optString("status")) && !"Expired".equalsIgnoreCase(info.optString("status")))) throw new IllegalArgumentException("Account could not be verified");
                Api.prefs(this).edit().putBoolean("expired",!"Active".equalsIgnoreCase(info.optString("status"))).apply();
                Api.prefs(this).edit().putString("intro_url",config.optBoolean("intro_enabled")?config.optString("intro_url",""):"").apply();
                runOnUiThread(() -> saveAndGo(server,user,pass));
            } catch (Exception e) { runOnUiThread(() -> message.setText("Sign in failed: " + e.getMessage())); }
        });
    }
    private void startPair(ImageView image, TextView code) {
        Api.IO.execute(() -> {
            try {
                JSONObject result = Api.post("pair-start",new JSONObject());
                pairCode=result.getString("code"); verifier=result.getString("verifier");
                Bitmap bitmap = qr(result.getString("activation_url"), 280);
                runOnUiThread(() -> { image.setImageBitmap(bitmap); code.setText("Scan the QR code or enter " + pairCode + " at myflixtown.com/activate.php"); polling=true; poll(); });
            } catch (Exception e) { runOnUiThread(() -> code.setText("QR activation unavailable. Use your username and password.")); }
        });
    }
    private Bitmap qr(String value,int size) throws WriterException {
        BitMatrix matrix = new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,size,size);
        Bitmap b=Bitmap.createBitmap(size,size,Bitmap.Config.RGB_565);
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)b.setPixel(x,y,matrix.get(x,y)?Color.BLACK:Color.WHITE);
        return b;
    }
    private void poll() {
        if (!polling || isFinishing()) return;
        Api.IO.execute(() -> {
            try {
                JSONObject result=Api.post("pair-poll",new JSONObject().put("code",pairCode).put("verifier",verifier));
                if ("approved".equals(result.optString("status"))) {
                    JSONObject account=result.getJSONObject("account");
                    JSONObject config=Api.get(BuildConfig.PANEL_URL + "config.php");
                    Api.prefs(this).edit().putString("intro_url",config.optBoolean("intro_enabled")?config.optString("intro_url",""):"").apply();
                    runOnUiThread(() -> saveAndGo(config.optString("xtream_url"),account.optString("username"),account.optString("password")));
                } else handler.postDelayed(this::poll,3000);
            } catch (Exception e) { handler.postDelayed(this::poll,4000); }
        });
    }
    private void saveAndGo(String server,String user,String pass) {
        polling=false;
        try { AccountStore.save(this,user,pass,server);home(); }
        catch(Exception e){message.setText("Could not save account securely");}
    }
    private void home() {
        String intro=Api.prefs(this).getString("intro_url","");
        Class<?> destination=Api.prefs(this).getBoolean("expired",false)?RenewalActivity.class:(intro.isEmpty()?HomeActivity.class:IntroActivity.class);
        startActivity(new Intent(this,destination));finish();
    }
    @Override protected void onDestroy() { polling=false; handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}

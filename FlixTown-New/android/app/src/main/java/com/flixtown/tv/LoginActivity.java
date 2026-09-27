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
import android.widget.LinearLayout;
import android.widget.TextView;
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
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG); Ui.pad(root,this,96,55,96,35);
        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.flix_logo);
        brand.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(brand,new LinearLayout.LayoutParams(Ui.dp(this,210),Ui.dp(this,104)));
        LinearLayout body = Ui.row(this); body.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout direct = Ui.column(this); Ui.pad(direct,this,0,20,50,0);
        direct.addView(Ui.heading(this,"Sign in",26));
        EditText user = field("Username",false), pass = field("Password",true);
        direct.addView(user); direct.addView(pass);
        android.widget.Button submit = Ui.button(this,"Sign in"); direct.addView(submit);
        submit.setOnClickListener(v -> validate(user.getText().toString().trim(),pass.getText().toString()));
        body.addView(direct,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout qr = Ui.column(this); qr.setGravity(Gravity.CENTER); qr.addView(Ui.heading(this,"Activate with your phone",22));
        ImageView image = new ImageView(this); LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(this,210),Ui.dp(this,210)); ip.topMargin=Ui.dp(this,20); qr.addView(image,ip);
        TextView code = Ui.text(this,"Loading activation code…",18); code.setGravity(Gravity.CENTER); qr.addView(code);
        body.addView(qr,new LinearLayout.LayoutParams(0,-2,1)); root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        message = Ui.text(this,"",16); message.setTextColor(0xFFFFB3B3); root.addView(message);
        setContentView(root); submit.requestFocus(); startPair(image,code);
    }
    private EditText field(String hint, boolean password) {
        EditText field = new EditText(this); field.setSingleLine(true); field.setHint(hint); field.setTextColor(Color.WHITE); field.setHintTextColor(0xFF9DA2AD);
        if (password) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,Ui.dp(this,52)); p.setMargins(0,Ui.dp(this,16),0,0); field.setLayoutParams(p);
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

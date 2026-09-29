package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONObject;

public class LoginActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean polling;private String pairCode,verifier;
    private TextView message,code;private ImageView image;private View qrPanel,remotePanel;
    @Override public void onCreate(Bundle b){super.onCreate(b);
        if(AccountStore.read(this)!=null){home();return;}
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        setContentView(R.layout.activity_login);
        qrPanel=findViewById(R.id.qr_panel);remotePanel=findViewById(R.id.remote_panel);
        message=findViewById(R.id.login_message);code=findViewById(R.id.activation_code);image=findViewById(R.id.qr_image);
        Button remote=findViewById(R.id.remote_sign_in);
        remote.setOnClickListener(v->{qrPanel.setVisibility(View.GONE);remotePanel.setVisibility(View.VISIBLE);
            findViewById(R.id.username).requestFocus();});
        findViewById(R.id.back_to_qr).setOnClickListener(v->showQr());
        findViewById(R.id.sign_in).setOnClickListener(v->{EditText user=findViewById(R.id.username),pass=findViewById(R.id.password);
            validate(user.getText().toString().trim(),pass.getText().toString());});
        remote.requestFocus();Images.init(this);startPair();
    }
    private void showQr(){View focused=getCurrentFocus();if(focused!=null){
        InputMethodManager ime=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);ime.hideSoftInputFromWindow(focused.getWindowToken(),0);}
        remotePanel.setVisibility(View.GONE);qrPanel.setVisibility(View.VISIBLE);findViewById(R.id.remote_sign_in).requestFocus();}
    @Override public void onBackPressed(){if(remotePanel.getVisibility()==View.VISIBLE)showQr();else super.onBackPressed();}
    private void validate(String user,String pass){if(user.isEmpty()||pass.isEmpty()){message.setText("Enter both account fields");return;}
        message.setText("Checking account…");Api.IO.execute(()->{try{
            JSONObject config=Api.get(BuildConfig.PANEL_URL+"config.php");String server=config.getString("xtream_url");
            JSONObject account=Api.get(server.replaceAll("/+$","")+"/player_api.php?username="+Api.enc(user)+"&password="+Api.enc(pass));
            JSONObject info=account.optJSONObject("user_info");if(info==null||(!"Active".equalsIgnoreCase(info.optString("status"))&&
                !"Expired".equalsIgnoreCase(info.optString("status"))))throw new IllegalArgumentException("Account could not be verified");
            Api.prefs(this).edit().putBoolean("expired",!"Active".equalsIgnoreCase(info.optString("status"))).apply();
            Api.prefs(this).edit().putString("intro_url",config.optBoolean("intro_enabled")?config.optString("intro_url",""):"").apply();
            runOnUiThread(()->saveAndGo(server,user,pass));
        }catch(Exception e){runOnUiThread(()->message.setText("Sign in failed: "+e.getMessage()));}});
    }
    private void startPair(){Api.IO.execute(()->{try{
        JSONObject result=Api.post("pair-start",new JSONObject());pairCode=result.getString("code");verifier=result.getString("verifier");
        BitMatrix matrix=new QRCodeWriter().encode(result.getString("activation_url"),BarcodeFormat.QR_CODE,320,320);
        Bitmap b=Bitmap.createBitmap(320,320,Bitmap.Config.RGB_565);
        for(int y=0;y<320;y++)for(int x=0;x<320;x++)b.setPixel(x,y,matrix.get(x,y)?Color.BLACK:Color.WHITE);
        runOnUiThread(()->{image.setImageBitmap(b);code.setText("Enter "+pairCode+" on your phone");polling=true;poll();});
    }catch(Exception e){runOnUiThread(()->{code.setText("QR activation is unavailable");message.setText("Use Sign in with remote");});}});}
    private void poll(){if(!polling||isFinishing())return;Api.IO.execute(()->{try{
        JSONObject result=Api.post("pair-poll",new JSONObject().put("code",pairCode).put("verifier",verifier));
        if("approved".equals(result.optString("status"))){JSONObject account=result.getJSONObject("account");
            JSONObject config=Api.get(BuildConfig.PANEL_URL+"config.php");
            Api.prefs(this).edit().putString("intro_url",config.optBoolean("intro_enabled")?config.optString("intro_url",""):"").apply();
            runOnUiThread(()->saveAndGo(config.optString("xtream_url"),account.optString("username"),account.optString("password")));
        }else handler.postDelayed(this::poll,3000);
    }catch(Exception e){handler.postDelayed(this::poll,4000);}});}
    private void saveAndGo(String server,String user,String pass){polling=false;try{AccountStore.save(this,user,pass,server);home();}
        catch(Exception e){message.setText("Could not save account securely");}}
    private void home(){Class<?> destination=Api.prefs(this).getBoolean("expired",false)?RenewalActivity.class:HomeActivity.class;
        startActivity(new Intent(this,destination));finish();}
    // Poll only while the activation screen is visible; resume with the same code when it returns.
    @Override protected void onStop(){polling=false;handler.removeCallbacksAndMessages(null);super.onStop();}
    @Override protected void onRestart(){super.onRestart();if(pairCode!=null && AccountStore.read(this)==null){polling=true;poll();}}
    @Override protected void onDestroy(){polling=false;handler.removeCallbacksAndMessages(null);super.onDestroy();}
}

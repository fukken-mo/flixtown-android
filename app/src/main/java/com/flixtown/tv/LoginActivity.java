package com.flixtown.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONObject;
import java.util.EnumMap;
import java.util.Map;

/**
 * Launcher. Signed-in viewers go straight on (startup refresh → optional intro → Home).
 * Otherwise the QR activation is the main path and "Sign in with remote" is secondary.
 * The keyboard never opens by itself: fields only open it when the viewer presses OK on them.
 */
public class LoginActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean polling;private String pairCode,verifier;
    private TextView message,code,status;private ImageView image;private View qrPanel,remotePanel;
    @Override public void onCreate(Bundle b){super.onCreate(b);
        if(BuildConfig.DEMO)applyDemoExtras();
        if(AccountStore.read(this)!=null){continueSignedIn();return;}
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        setContentView(R.layout.activity_login);
        qrPanel=findViewById(R.id.qr_panel);remotePanel=findViewById(R.id.remote_panel);
        message=findViewById(R.id.login_message);code=findViewById(R.id.activation_code);image=findViewById(R.id.qr_image);
        status=findViewById(R.id.login_status);
        ((TextView)findViewById(R.id.steps)).setText(steps());
        Button remote=findViewById(R.id.remote_sign_in);
        // Shared Flix Town buttons: the remote form's Sign in is its one primary action.
        Ui.styleButton(remote,Ui.SECONDARY);Ui.styleButton(findViewById(R.id.sign_in),Ui.PRIMARY);Ui.styleButton(findViewById(R.id.back_to_qr),Ui.SECONDARY);
        Button keyboard=findViewById(R.id.keyboard_mode);Ui.styleButton(keyboard,Ui.SECONDARY);
        applyKeyboard(Api.prefs(this).getBoolean(LETTERS,false));
        keyboard.setOnClickListener(v->{boolean letters=!Api.prefs(this).getBoolean(LETTERS,false);
            Api.prefs(this).edit().putBoolean(LETTERS,letters).apply();applyKeyboard(letters);});
        remote.setOnClickListener(v->{qrPanel.setVisibility(View.GONE);remotePanel.setVisibility(View.VISIBLE);
            findViewById(R.id.username).requestFocus();});
        findViewById(R.id.back_to_qr).setOnClickListener(v->showQr());
        findViewById(R.id.sign_in).setOnClickListener(v->submit());
        ((EditText)findViewById(R.id.password)).setOnEditorActionListener((v,action,event)->{submit();return true;});
        remote.requestFocus();Images.init(this);startPair();
    }
    /** QA build only: lets the emulator test script start from a clean or expired account. */
    private void applyDemoExtras(){
        if(getIntent().getBooleanExtra("demo_reset",false)){AccountStore.clear(this);Api.prefs(this).edit().clear().apply();
            for(String f:new String[]{"ratings.json","series_news.json","trending.json"})new java.io.File(getFilesDir(),f).delete();}
        DemoData.expired=getIntent().getBooleanExtra("demo_expired",false);
        DemoData.offline=getIntent().getBooleanExtra("demo_offline",false);
        DemoData.slow=getIntent().getBooleanExtra("demo_slow",false);
        DemoData.intro=getIntent().getBooleanExtra("demo_intro",false);
        DemoData.holdPairing=getIntent().getBooleanExtra("demo_hold_qr",false);
        if(getIntent().getBooleanExtra("demo_continue",false)){
            // Fixed starting point for the Continue Watching test: a show at S1 E2 and a movie, both at 0:20.
            Catalog.saveProgress(this,"series","5002","9102","mkv","9103","mkv",20000,3000000);
            Catalog.saveProgress(this,"movie","1000",null,null,null,null,20000,6840000);
        }
        if(getIntent().getBooleanExtra("demo_clear_cache",false)){
            java.io.File dir=new java.io.File(getFilesDir(),"catalog");java.io.File[] files=dir.listFiles();
            if(files!=null)for(java.io.File f:files)f.delete();
        }
    }
    /** Remembered per TV: accounts are digits, but older ones may contain letters. */
    private static final String LETTERS="login_keyboard_letters";
    /** Digits keypad by default (leading zeros are kept: the fields stay text), full keyboard on request. */
    private void applyKeyboard(boolean letters){
        EditText user=findViewById(R.id.username),pass=findViewById(R.id.password);
        int userType=letters?android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            :android.text.InputType.TYPE_CLASS_NUMBER;
        int passType=letters?android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            :android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        for(EditText field:new EditText[]{user,pass}){
            int start=field.getSelectionStart();
            field.setInputType(field==user?userType:passType);
            if(field==pass)field.setTypeface(user.getTypeface());   // password input types reset the font
            if(start>=0 && start<=field.length())field.setSelection(start);
        }
        ((Button)findViewById(R.id.keyboard_mode)).setText(letters?"123":"ABC");
        View focus=getCurrentFocus();
        InputMethodManager ime=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(focus instanceof EditText && ime!=null)ime.restartInput(focus);
    }
    private CharSequence steps(){
        String[] lines={"Point your phone's camera at the code","Sign in and approve this TV","Flix Town opens here automatically"};
        SpannableStringBuilder out=new SpannableStringBuilder();
        for(int i=0;i<lines.length;i++){
            int start=out.length();out.append(String.valueOf(i+1)).append("    ");
            out.setSpan(new ForegroundColorSpan(Ui.GLOW),start,start+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.append(lines[i]);if(i<lines.length-1)out.append('\n');
        }
        return out;
    }
    private void submit(){EditText user=findViewById(R.id.username),pass=findViewById(R.id.password);
        View focused=getCurrentFocus();   // close the keyboard so the result and the buttons are visible
        if(focused!=null)((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focused.getWindowToken(),0);
        validate(user.getText().toString().trim(),pass.getText().toString());}
    private void say(String text,int color){message.setTextColor(color);message.setText(text);message.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);}
    private void showQr(){View focused=getCurrentFocus();if(focused!=null){
        InputMethodManager ime=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);ime.hideSoftInputFromWindow(focused.getWindowToken(),0);}
        remotePanel.setVisibility(View.GONE);qrPanel.setVisibility(View.VISIBLE);findViewById(R.id.remote_sign_in).requestFocus();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(remotePanel!=null && remotePanel.getVisibility()==View.VISIBLE && event.getKeyCode()==KeyEvent.KEYCODE_BACK){
            if(event.getAction()==KeyEvent.ACTION_UP)showQr();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private void validate(String user,String pass){if(user.isEmpty()||pass.isEmpty()){say("Enter your username and password.",0xFFF0A29F);return;}
        say("Checking your account…",Ui.TEXT_2);Api.IO.execute(()->{try{
            JSONObject config=Api.get(BuildConfig.PANEL_URL+"config.php");String server=config.getString("xtream_url");
            JSONObject account=Api.get(server.replaceAll("/+$","")+"/player_api.php?username="+Api.enc(user)+"&password="+Api.enc(pass));
            JSONObject info=account.optJSONObject("user_info");if(info==null||(!"Active".equalsIgnoreCase(info.optString("status"))&&
                !"Expired".equalsIgnoreCase(info.optString("status"))))throw new IllegalArgumentException("That username and password were not recognised.");
            Api.prefs(this).edit().putBoolean("expired",!"Active".equalsIgnoreCase(info.optString("status")))
                .putString("account_status",info.optString("status","")).putString("account_exp_date",info.optString("exp_date","")).apply();
            Api.prefs(this).edit().putString("intro_url",config.optBoolean("intro_enabled")?config.optString("intro_url",""):"").apply();
            runOnUiThread(()->saveAndGo(server,user,pass));
        }catch(Exception e){runOnUiThread(()->say(e instanceof IllegalArgumentException?e.getMessage():"Sign in failed. Check your connection and try again.",0xFFF0A29F));}});
    }
    private void startPair(){status.setText("Preparing your code…");Api.IO.execute(()->{try{
        JSONObject result=Api.post("pair-start",new JSONObject());pairCode=result.getString("code");verifier=result.getString("verifier");
        Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.MARGIN,1);
        int size=432;
        BitMatrix matrix=new QRCodeWriter().encode(result.getString("activation_url"),BarcodeFormat.QR_CODE,size,size,hints);
        int[] pixels=new int[size*size];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)pixels[y*size+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;
        Bitmap b=Bitmap.createBitmap(pixels,size,size,Bitmap.Config.RGB_565);
        runOnUiThread(()->{if(isFinishing())return;image.setImageBitmap(b);code.setText(pairCode);
            status.setText("Waiting for your phone…");polling=true;poll();});
    }catch(Exception e){runOnUiThread(()->{code.setText("—");status.setText("QR activation is unavailable right now. Use Sign in with remote.");});}});}
    private void poll(){if(!polling||isFinishing())return;Api.IO.execute(()->{try{
        JSONObject result=Api.post("pair-poll",new JSONObject().put("code",pairCode).put("verifier",verifier));
        if("approved".equals(result.optString("status"))){JSONObject account=result.getJSONObject("account");
            JSONObject config=Api.get(BuildConfig.PANEL_URL+"config.php");
            Api.prefs(this).edit().putString("intro_url",config.optBoolean("intro_enabled")?config.optString("intro_url",""):"").apply();
            runOnUiThread(()->{status.setText("Approved. Opening Flix Town…");
                saveAndGo(config.optString("xtream_url"),account.optString("username"),account.optString("password"));});
        }else handler.postDelayed(this::poll,3000);
    }catch(Exception e){handler.postDelayed(this::poll,4000);}});}
    private void saveAndGo(String server,String user,String pass){polling=false;try{AccountStore.save(this,user,pass,server);continueSignedIn();}
        catch(Exception e){say("Could not save the account on this TV.",0xFFF0A29F);}}
    /** Fresh launch: start the catalog refresh first so it runs during any intro. */
    private void continueSignedIn(){
        if(Api.prefs(this).getBoolean("expired",false)){startActivity(new Intent(this,RenewalActivity.class));finish();return;}
        StartupRefresh.start(this,false);
        startActivity(new Intent(this,IntroActivity.class));overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);finish();
    }
    // Poll only while the activation screen is visible; resume with the same code when it returns.
    @Override protected void onStop(){polling=false;handler.removeCallbacksAndMessages(null);super.onStop();}
    @Override protected void onRestart(){super.onRestart();if(pairCode!=null && AccountStore.read(this)==null){polling=true;poll();}}
    @Override protected void onDestroy(){polling=false;handler.removeCallbacksAndMessages(null);super.onDestroy();}
}

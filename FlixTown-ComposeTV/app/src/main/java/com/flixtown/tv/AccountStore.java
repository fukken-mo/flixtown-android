package com.flixtown.tv;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class AccountStore {
    private static final String ALIAS="flix_town_account_v2";
    private static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        SecretKey existing=(SecretKey)store.getKey(ALIAS,null);if(existing!=null)return existing;
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return gen.generateKey();
    }
    static void save(Context c,String user,String password,String server) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String data=user+"\n"+password;
        byte[] encrypted=cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        Api.prefs(c).edit().putString("account_iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
            .putString("account_data",Base64.encodeToString(encrypted,Base64.NO_WRAP)).putString("server",server).apply();
    }
    static String[] read(Context c) {
        try {
            String iv=Api.prefs(c).getString("account_iv","");String data=Api.prefs(c).getString("account_data","");
            if(iv.isEmpty()||data.isEmpty())return null;
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
            String plain=new String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),StandardCharsets.UTF_8);
            String[] parts=plain.split("\n",2);return parts.length==2?parts:null;
        } catch(Exception e){return null;}
    }
    static void clear(Context c) {Api.prefs(c).edit().remove("account_iv").remove("account_data").remove("server").apply();}
}

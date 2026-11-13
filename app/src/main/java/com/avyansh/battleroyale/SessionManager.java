package com.avyansh.battleroyale;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keystore-backed encrypted token storage; passwords are never persisted. */
public final class SessionManager {
    private static final String PREF = "avyansh_secure_session";
    private static final String ALIAS = "avyansh_session_aes";
    private SessionManager() {}
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build());
        return kg.generateKey();
    }
    public static void save(Context c, String uid, String username, String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        ByteBuffer b = ByteBuffer.allocate(4 + iv.length + encrypted.length).putInt(iv.length).put(iv).put(encrypted);
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("blob", Base64.encodeToString(b.array(), Base64.NO_WRAP))
            .putString("uid", uid).putString("username", username).apply();
    }
    public static String token(Context c) {
        try {
            String v=c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString("blob",null); if(v==null)return "";
            ByteBuffer b=ByteBuffer.wrap(Base64.decode(v,Base64.NO_WRAP)); int n=b.getInt();
            byte[] iv=new byte[n], data=new byte[b.remaining()-n]; b.get(iv); b.get(data);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            return new String(cipher.doFinal(data),StandardCharsets.UTF_8);
        } catch(Exception e){ return ""; }
    }
    public static String uid(Context c){return c.getSharedPreferences(PREF,0).getString("uid","");}
    public static String username(Context c){return c.getSharedPreferences(PREF,0).getString("username","");}
    public static void clear(Context c){c.getSharedPreferences(PREF,0).edit().clear().apply();}
    public static boolean hasSession(Context c){return !token(c).isEmpty() && !uid(c).isEmpty();}
}

package com.baima.xiehe;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 账号密码的本地加密。
 *
 * 密钥生成在 Android Keystore 里（不出芯片/系统密钥区），数据库里只存密文。
 * 换手机、清数据、重装应用都会让密钥失效，此时 decrypt 返回空串，程序自动退回手动登录。
 */
public class Secure {

    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "xiehe_pwd";
    private static final String PREFIX = "v1:";

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            KeyStore.Entry e = ks.getEntry(ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) e).getSecretKey();
            }
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /** 加密，失败返回空串（不抛异常，调用方按"没存密码"处理） */
    public static String encrypt(String plain) {
        if (plain == null || plain.length() == 0) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            return PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (Throwable t) {
            Logs.add("密码加密失败：" + t.getClass().getSimpleName());
            return "";
        }
    }

    /** 解密，失败返回空串 */
    public static String decrypt(String enc) {
        if (enc == null || !enc.startsWith(PREFIX)) return "";
        try {
            String body = enc.substring(PREFIX.length());
            int i = body.indexOf(':');
            if (i <= 0) return "";
            byte[] iv = Base64.decode(body.substring(0, i), Base64.NO_WRAP);
            byte[] ct = Base64.decode(body.substring(i + 1), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean has(String enc) {
        return enc != null && enc.startsWith(PREFIX);
    }
}

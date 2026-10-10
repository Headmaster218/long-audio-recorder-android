package io.github.headmaster218.recorder.android;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** No static initialization creates a key. remember() is reached only after explicit GUI opt-in. */
final class FtpsCredentials {
    private static final String ALIAS = "recorder-ftps-password-aes-v1";
    private FtpsCredentials() { }
    private static SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            if (!create) throw new GeneralSecurityException("Remembered credential key unavailable");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).setUnlockedDeviceRequired(true).build());
            generator.generateKey();
        }
        SecretKey key = (SecretKey) store.getKey(ALIAS,null);
        if (key == null) throw new GeneralSecurityException("Remembered credential key unavailable");
        return key;
    }
    static byte[] remember(String revision, char[] password, boolean explicitConsent) throws GeneralSecurityException, IOException {
        if (!explicitConsent) throw new GeneralSecurityException("Remembering requires explicit consent");
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] plain = new byte[encoded.remaining()]; encoded.get(plain);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key(true));
            cipher.updateAAD(revision.getBytes(StandardCharsets.US_ASCII));
            byte[] encrypted = cipher.doFinal(plain), iv = cipher.getIV();
            if (iv.length != 12) throw new GeneralSecurityException("Unexpected GCM IV size");
            byte[] stored = new byte[1 + iv.length + encrypted.length]; stored[0] = 1;
            System.arraycopy(iv,0,stored,1,iv.length); System.arraycopy(encrypted,0,stored,13,encrypted.length); return stored;
        } finally { Arrays.fill(plain,(byte) 0); if (encoded.hasArray()) Arrays.fill(encoded.array(),(byte) 0); }
    }
    static char[] unlock(String revision, byte[] stored) throws GeneralSecurityException, IOException {
        if (stored == null || stored.length < 29 || stored.length > 8192 || stored[0] != 1)
            throw new GeneralSecurityException("Invalid remembered credential");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,Arrays.copyOfRange(stored,1,13)));
        cipher.updateAAD(revision.getBytes(StandardCharsets.US_ASCII));
        byte[] plain = cipher.doFinal(stored,13,stored.length - 13);
        CharBuffer chars = null;
        try {
            chars = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain));
            char[] result = new char[chars.remaining()]; chars.get(result); return result;
        } finally { Arrays.fill(plain,(byte) 0); if (chars != null && chars.hasArray()) Arrays.fill(chars.array(),'\0'); }
    }
}

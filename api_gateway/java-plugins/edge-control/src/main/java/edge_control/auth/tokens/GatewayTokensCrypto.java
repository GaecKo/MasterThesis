package edge_control.auth.tokens;

import edge_control.exceptions.EdgeControlException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts and decrypts gateway tokens using AES-256-GCM.
 */
public class GatewayTokensCrypto {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey secretKey;

    /**
     * Loads the encryption key from the token_encryption_key secret file.
     *
     * @throws EdgeControlException If the key cannot be loaded or is invalid
     */
    public GatewayTokensCrypto() throws EdgeControlException {
        this.secretKey = loadKeyFromEnv();
    }

    /**
     * Encrypts a plaintext token using AES-256-GCM.
     *
     * Output format (Base64): [12-byte IV][ciphertext + 16-byte GCM tag]
     *
     * @param plaintext Token to encrypt
     * @return Base64-encoded encrypted token (IV prepended)
     * @throws EdgeControlException If encryption fails
     */
    public String encrypt(String plaintext) throws EdgeControlException {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));
            byte[] ivPlusCipher = new byte[iv.length + ciphertext.length];

            System.arraycopy(iv, 0, ivPlusCipher, 0, iv.length);
            System.arraycopy(ciphertext, 0, ivPlusCipher, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(ivPlusCipher);

        } catch (Exception e) {
            throw new EdgeControlException("Failed to encrypt token: " + e.getMessage());
        }
    }

    /**
     * Decrypts a token previously encrypted by encrypt().
     *
     * @param encryptedBase64 Base64-encoded encrypted token (IV prepended)
     * @return Plaintext token
     * @throws EdgeControlException If decryption or integrity check fails
     */
    public String decrypt(String encryptedBase64) throws EdgeControlException {
        try {
            byte[] ivPlusCipher = Base64.getDecoder().decode(encryptedBase64);

            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[ivPlusCipher.length - GCM_IV_LENGTH];

            System.arraycopy(ivPlusCipher, 0, iv, 0, iv.length);
            System.arraycopy(ivPlusCipher, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, "UTF-8");

        } catch (Exception e) {
            throw new EdgeControlException("Failed to decrypt token — data may be corrupted or tampered");
        }
    }

    private SecretKey loadKeyFromEnv() throws EdgeControlException {
        java.nio.file.Path secretPath = java.nio.file.Paths.get("/run/secrets/token_encryption_key");

        String base64Key;
        try {
            base64Key = java.nio.file.Files.readString(secretPath).trim();
        } catch (Exception e) {
            throw new EdgeControlException(
                    "Could not read secret file at " + secretPath + ": " + e.getMessage()
            );
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new EdgeControlException("token_encryption_key secret is not valid Base64");
        }

        if (keyBytes.length != 32) {
            throw new EdgeControlException(
                    "token_encryption_key must be 32 bytes when decoded. Got " + keyBytes.length
            );
        }

        return new SecretKeySpec(keyBytes, "AES");
    }
}


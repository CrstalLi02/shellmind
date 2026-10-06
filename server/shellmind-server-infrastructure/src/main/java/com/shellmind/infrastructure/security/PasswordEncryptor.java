package com.shellmind.infrastructure.security;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password encryptor/decryptor using AES-256-GCM.
 * The key is read from an environment variable or a default value.
 */
@Slf4j
public class PasswordEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    /**
     * Default key (override with SHELLMIND_SECRET_KEY in production).
     * Do not change this after a rename: it is used to decrypt stored SSH passwords.
     */
    private static final String DEFAULT_SECRET_KEY = "WaLiSSH2026SecretKey!!";

    /** Pre-rename environment variable, kept for existing deployments */
    private static final String LEGACY_SECRET_KEY_ENV = "WALISSH_SECRET_KEY";

    private final SecretKeySpec keySpec;

    public PasswordEncryptor() {
        this(null);
    }

    public PasswordEncryptor(String secretKey) {
        String key = secretKey;
        if (key == null || key.isBlank()) {
            key = System.getenv("SHELLMIND_SECRET_KEY");
        }
        if (key == null || key.isBlank()) {
            key = System.getenv(LEGACY_SECRET_KEY_ENV);
        }
        if (key == null || key.isBlank()) {
            key = DEFAULT_SECRET_KEY;
            log.warn("SHELLMIND_SECRET_KEY is not set; using the default key. Configure it in production.");
        }
        // AES-256 requires a 32-byte key
        byte[] keyBytes = padOrTrim(key.getBytes(StandardCharsets.UTF_8), 32);
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encrypt plaintext and return Base64 ciphertext (IV prefix included).
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Base64 of IV + ciphertext
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Password encryption failed", e);
            throw new RuntimeException("Password encryption failed", e);
        }
    }

    /**
     * Decrypt Base64 ciphertext and return plaintext.
     * If the value is not a valid ciphertext (plaintext mislabeled as encrypted), return it unchanged.
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        // Guard: return as-is when the format is not encrypted (dirty data marked encrypted=1 but stored as plaintext)
        if (!isEncrypted(ciphertext)) {
            log.warn("Password field is marked encrypted but is not a valid ciphertext; returning as plaintext: length={}, firstChar={}",
                    ciphertext.length(), ciphertext.charAt(0));
            return ciphertext;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);

            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Password decryption failed; returning original value as fallback", e);
            return ciphertext;
        }
    }

    /**
     * Whether the string looks like ciphertext (Base64 and length &gt; IV_LENGTH).
     */
    public boolean isEncrypted(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length > GCM_IV_LENGTH;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] padOrTrim(byte[] bytes, int length) {
        byte[] result = new byte[length];
        System.arraycopy(bytes, 0, result, 0, Math.min(bytes.length, length));
        return result;
    }
}

package com.aihotspot.core.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProviderCredentialCipher {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public ProviderCredentialCipher(
            @Value("${ai-hotspot.security.model-credential-master-key:local-development-model-credential-master-key}") String masterKey) {
        if (masterKey == null || masterKey.length() < 24) {
            throw new IllegalStateException("MODEL_CREDENTIAL_MASTER_KEY must contain at least 24 characters");
        }
        try {
            this.key = new SecretKeySpec(
                    MessageDigest.getInstance("SHA-256")
                            .digest(masterKey.getBytes(StandardCharsets.UTF_8)),
                    "AES");
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialize model credential encryption", error);
        }
    }

    public EncryptedValue encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.strip().getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(
                    Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(nonce));
        } catch (Exception error) {
            throw new IllegalStateException("API Key 加密失败", error);
        }
    }

    public String decrypt(String ciphertext, String nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(nonce)));
            return new String(
                    cipher.doFinal(Base64.getDecoder().decode(ciphertext)),
                    StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("API Key 解密失败；请检查 MODEL_CREDENTIAL_MASTER_KEY 是否发生变化", error);
        }
    }

    public String fingerprint(String plaintext) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(plaintext.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("API Key 指纹计算失败", error);
        }
    }

    public record EncryptedValue(String ciphertext, String nonce) {}
}

package com.aihotspot.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderCredentialCipherTests {

    @Test
    void encryptsWithRandomNonceAndDecryptsWithoutExposingPlaintext() {
        ProviderCredentialCipher cipher = new ProviderCredentialCipher(
                "test-model-credential-master-key-with-more-than-32-characters");

        ProviderCredentialCipher.EncryptedValue first = cipher.encrypt("sk-sensitive-value");
        ProviderCredentialCipher.EncryptedValue second = cipher.encrypt("sk-sensitive-value");

        assertThat(first.ciphertext()).doesNotContain("sk-sensitive-value");
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(cipher.decrypt(first.ciphertext(), first.nonce()))
                .isEqualTo("sk-sensitive-value");
        assertThat(cipher.fingerprint("sk-sensitive-value")).hasSize(64);
    }
}

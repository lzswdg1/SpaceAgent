package com.spaceagent.shared.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionedAesGcmCipherTest {

    private static final String OLD_KEY = "old-versioned-aes-gcm-test-key-1234567890";
    private static final String NEW_KEY = "new-versioned-aes-gcm-test-key-1234567890";

    @Test
    void readsLegacyV1WithPreviousKeyAndWritesCurrentV2Envelope() throws Exception {
        String legacy = legacyEncrypt(OLD_KEY, "legacy-secret");
        VersionedAesGcmCipher cipher = new VersionedAesGcmCipher(
                NEW_KEY,
                List.of(OLD_KEY),
                "TEST_ENCRYPTION_KEY"
        );

        assertThat(cipher.decrypt(legacy)).isEqualTo("legacy-secret");
        assertThat(cipher.needsRotation(legacy)).isTrue();
        assertThat(cipher.encrypt("current-secret"))
                .startsWith("v2:" + cipher.currentKeyId() + ":");
    }

    @Test
    void refusesV2EnvelopeWhenItsKeyIsUnavailable() {
        String encrypted = new VersionedAesGcmCipher(OLD_KEY, List.of(), "TEST_ENCRYPTION_KEY")
                .encrypt("secret");
        VersionedAesGcmCipher cipher = new VersionedAesGcmCipher(
                NEW_KEY,
                List.of(),
                "TEST_ENCRYPTION_KEY"
        );

        assertThatThrownBy(() -> cipher.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decrypt");
    }

    private String legacyEncrypt(String configuredKey, String plaintext) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256")
                .digest(configuredKey.getBytes(StandardCharsets.UTF_8));
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "v1:" + encoder.encodeToString(nonce) + ":" + encoder.encodeToString(ciphertext);
    }
}

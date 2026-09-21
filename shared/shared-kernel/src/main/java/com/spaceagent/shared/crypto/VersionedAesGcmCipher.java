package com.spaceagent.shared.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AES-GCM secret envelope with key identifiers and legacy v1 read support.
 */
public final class VersionedAesGcmCipher {

    private static final String LEGACY_VERSION = "v1";
    private static final String CURRENT_VERSION = "v2";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final KeyMaterial currentKey;
    private final Map<String, KeyMaterial> keysById;
    private final List<KeyMaterial> decryptionKeys;
    private final SecureRandom secureRandom = new SecureRandom();

    public VersionedAesGcmCipher(String currentSecret, List<String> previousSecrets, String propertyName) {
        this.currentKey = keyMaterial(currentSecret, propertyName);
        Map<String, KeyMaterial> configuredKeys = new LinkedHashMap<>();
        configuredKeys.put(currentKey.id(), currentKey);
        if (previousSecrets != null) {
            for (String previousSecret : previousSecrets) {
                if (previousSecret == null || previousSecret.isBlank()) {
                    continue;
                }
                KeyMaterial previousKey = keyMaterial(previousSecret, propertyName + " previous key");
                configuredKeys.putIfAbsent(previousKey.id(), previousKey);
            }
        }
        this.keysById = Map.copyOf(configuredKeys);
        this.decryptionKeys = List.copyOf(new ArrayList<>(configuredKeys.values()));
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Secret payload must not be null.");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, currentKey.key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(currentKey.id()));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return CURRENT_VERSION + ":" + currentKey.id() + ":" + encode(nonce) + ":" + encode(ciphertext);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to encrypt secret payload", error);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return "";
        }
        if (encoded.startsWith(CURRENT_VERSION + ":")) {
            return decryptCurrent(encoded);
        }
        if (encoded.startsWith(LEGACY_VERSION + ":")) {
            return decryptLegacy(encoded);
        }
        throw new IllegalStateException("Unable to decrypt secret payload: unsupported format");
    }

    public boolean needsRotation(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return false;
        }
        String[] parts = encoded.split(":", 3);
        return parts.length < 2
                || !CURRENT_VERSION.equals(parts[0])
                || !currentKey.id().equals(parts[1]);
    }

    public String currentKeyId() {
        return currentKey.id();
    }

    private String decryptCurrent(String encoded) {
        try {
            String[] parts = encoded.split(":", 4);
            if (parts.length != 4) {
                throw new IllegalArgumentException("Invalid v2 secret envelope.");
            }
            KeyMaterial key = keysById.get(parts[1]);
            if (key == null) {
                throw new IllegalArgumentException("Encryption key is not configured for key id " + parts[1]);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key.key(),
                    new GCMParameterSpec(TAG_BITS, Base64.getUrlDecoder().decode(parts[2]))
            );
            cipher.updateAAD(aad(parts[1]));
            return new String(
                    cipher.doFinal(Base64.getUrlDecoder().decode(parts[3])),
                    StandardCharsets.UTF_8
            );
        } catch (Exception error) {
            throw new IllegalStateException("Unable to decrypt secret payload", error);
        }
    }

    private String decryptLegacy(String encoded) {
        String[] parts = encoded.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalStateException("Unable to decrypt secret payload: invalid v1 envelope");
        }
        Exception lastError = null;
        for (KeyMaterial key : decryptionKeys) {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        key.key(),
                        new GCMParameterSpec(TAG_BITS, Base64.getUrlDecoder().decode(parts[1]))
                );
                return new String(
                        cipher.doFinal(Base64.getUrlDecoder().decode(parts[2])),
                        StandardCharsets.UTF_8
                );
            } catch (Exception error) {
                lastError = error;
            }
        }
        throw new IllegalStateException("Unable to decrypt secret payload", lastError);
    }

    private static KeyMaterial keyMaterial(String secret, String propertyName) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(propertyName + " must contain at least 32 characters.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            String keyId = java.util.HexFormat.of().formatHex(digest, 0, 8);
            return new KeyMaterial(keyId, new SecretKeySpec(digest, "AES"));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialize encryption key", error);
        }
    }

    private static byte[] aad(String keyId) {
        return (CURRENT_VERSION + ":" + keyId).getBytes(StandardCharsets.UTF_8);
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private record KeyMaterial(String id, SecretKeySpec key) {
    }
}

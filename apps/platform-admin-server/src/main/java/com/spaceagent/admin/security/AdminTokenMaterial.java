package com.spaceagent.admin.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class AdminTokenMaterial {
    private static final SecureRandom RANDOM = new SecureRandom();

    private AdminTokenMaterial() {}

    public static String randomToken() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash token material", error);
        }
    }

    public static boolean hashMatches(String rawValue, String expectedHash) {
        return MessageDigest.isEqual(
                sha256(rawValue).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}

package com.ratelimiter.admin.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Tenant API keys: generated here, stored only as a SHA-256 hash plus a short display
 * prefix. The gateway hashes the key a client presents and looks the hash up. A plain
 * hash, not a slow password hash, is enough: these are 256-bit random values, not
 * guessable passwords.
 */
public final class ApiKeys {

    private static final SecureRandom RANDOM = new SecureRandom();
    static final int PREFIX_LENGTH = 12;

    private ApiKeys() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "rk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** Enough of a key to tell it apart from others, never enough to use it. */
    public static String prefix(String apiKey) {
        return apiKey.substring(0, Math.min(PREFIX_LENGTH, apiKey.length()));
    }
}

package com.quidax.liquidityengine.common.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Deterministic idempotency-key derivation for cases where the caller didn't supply one
 * (e.g. system-generated settlement postings). Same logical operation -> same key, always,
 * so retries and redeliveries collapse onto the key already recorded rather than creating
 * a new "unique" operation each time.
 */
public final class IdempotencyKeys {
    private IdempotencyKeys() {}

    public static String derive(String... parts) {
        String joined = String.join("|", parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

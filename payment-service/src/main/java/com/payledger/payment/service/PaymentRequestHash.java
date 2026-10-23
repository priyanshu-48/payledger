package com.payledger.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** SHA-256 of a fixed-order canonical string; the request schema is fixed, so no JSON canonicalisation (ADR 0012). */
public final class PaymentRequestHash {

    private PaymentRequestHash() {
    }

    public static String of(String merchantId, UUID payerAccountId, long amountMinor, String currency) {
        String canonical = merchantId + "|" + payerAccountId + "|" + amountMinor + "|" + currency;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }
}

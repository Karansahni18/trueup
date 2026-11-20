package com.karan.trueup.ingestion.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class StripeSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long TOLERANCE_SECONDS = 300;

    private final Clock clock;

    public StripeSignatureVerifier(Clock clock) {
        this.clock = clock;
    }

    public boolean verify(byte[] rawBody, String signatureHeader, String secret) {
        if (rawBody == null || signatureHeader == null || secret == null) {
            return false;
        }

        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : signatureHeader.split(",")) {
            String[] keyAndValue = part.trim().split("=", 2);
            if (keyAndValue.length != 2) {
                continue;
            }
            if (keyAndValue[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(keyAndValue[1]);
                } catch (NumberFormatException ex) {
                    return false;
                }
            } else if (keyAndValue[0].equals("v1")) {
                signatures.add(keyAndValue[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            return false;
        }

        long ageSeconds = Math.abs(clock.instant().getEpochSecond() - timestamp);
        if (ageSeconds > TOLERANCE_SECONDS) {
            return false;
        }

        byte[] expected = computeSignatureHex(rawBody, timestamp, secret).getBytes(StandardCharsets.UTF_8);
        for (String candidate : signatures) {
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private String computeSignatureHex(byte[] rawBody, long timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(rawBody);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available", ex);
        }
    }
}
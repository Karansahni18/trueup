package com.karan.trueup.ingestion.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class StripeSignatureVerifierTest {

    private static final String SECRET = "whsec_test_secret";
    private static final long TIMESTAMP = 1700000000L;
    private static final String PAYLOAD = "{\"id\":\"evt_test_1\",\"type\":\"charge.succeeded\"}";
    // Computed independently with PowerShell HMACSHA256 over "1700000000.<payload>"
    private static final String VALID_SIGNATURE = "2276cc3db0acb7fe6a1f035b610dbb58e1a4ac7954df8b2008c4dcebefed6b2f";

    private static StripeSignatureVerifier verifierAt(long epochSecond) {
        return new StripeSignatureVerifier(Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String validHeader() {
        return "t=" + TIMESTAMP + ",v1=" + VALID_SIGNATURE;
    }

    @Test
    void acceptsValidSignature() {
        assertThat(verifierAt(TIMESTAMP).verify(bytes(PAYLOAD), validHeader(), SECRET)).isTrue();
    }

    @Test
    void rejectsTamperedPayload() {
        byte[] tampered = bytes(PAYLOAD.replace("charge.succeeded", "charge.refunded"));

        assertThat(verifierAt(TIMESTAMP).verify(tampered, validHeader(), SECRET)).isFalse();
    }

    @Test
    void rejectsWrongSecret() {
        assertThat(verifierAt(TIMESTAMP).verify(bytes(PAYLOAD), validHeader(), "whsec_other_secret")).isFalse();
    }

    @Test
    void acceptsSignatureJustInsideTheTolerance() {
        assertThat(verifierAt(TIMESTAMP + 299).verify(bytes(PAYLOAD), validHeader(), SECRET)).isTrue();
    }

    @Test
    void rejectsSignatureOlderThanTheTolerance() {
        assertThat(verifierAt(TIMESTAMP + 301).verify(bytes(PAYLOAD), validHeader(), SECRET)).isFalse();
    }

    @Test
    void rejectsSignatureFromTheFuture() {
        assertThat(verifierAt(TIMESTAMP - 301).verify(bytes(PAYLOAD), validHeader(), SECRET)).isFalse();
    }

    @Test
    void acceptsWhenAnyOfSeveralV1SignaturesMatches() {
        String header = "t=" + TIMESTAMP + ",v1=" + "0".repeat(64) + ",v1=" + VALID_SIGNATURE;

        assertThat(verifierAt(TIMESTAMP).verify(bytes(PAYLOAD), header, SECRET)).isTrue();
    }

    @Test
    void rejectsMalformedHeadersWithoutThrowing() {
        StripeSignatureVerifier verifier = verifierAt(TIMESTAMP);
        String[] badHeaders = {
                null, "", "garbage", "t=abc,v1=" + VALID_SIGNATURE,
                "v1=" + VALID_SIGNATURE, "t=" + TIMESTAMP, "t=" + TIMESTAMP + ",v1="
        };

        for (String header : badHeaders) {
            assertThat(verifier.verify(bytes(PAYLOAD), header, SECRET))
                    .as("header: %s", header)
                    .isFalse();
        }
    }
}
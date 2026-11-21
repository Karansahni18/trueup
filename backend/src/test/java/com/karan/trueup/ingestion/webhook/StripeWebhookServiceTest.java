package com.karan.trueup.ingestion.webhook;

import com.karan.trueup.ingestion.Provider;
import com.karan.trueup.ingestion.ProviderConnection;
import com.karan.trueup.ingestion.ProviderConnectionRepository;
import com.karan.trueup.ingestion.ProviderEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeWebhookServiceTest {

    private static final String EVENT =
            "{\"id\":\"evt_1\",\"type\":\"charge.succeeded\",\"data\":{\"object\":{\"amount\":10000}}}";

    @Mock
    ProviderConnectionRepository connectionRepository;

    @Mock
    StripeSignatureVerifier signatureVerifier;

    @Mock
    ProviderEventStore eventStore;

    StripeWebhookService service;

    private final UUID tenantId = UUID.randomUUID();
    private final ProviderConnection connection =
            new ProviderConnection(tenantId, Provider.STRIPE, "key-1", "whsec_x");

    @BeforeEach
    void setUp() {
        service = new StripeWebhookService(
                connectionRepository, signatureVerifier, eventStore, JsonMapper.builder().build());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void knownConnection() {
        when(connectionRepository.findByWebhookKeyAndProvider("key-1", Provider.STRIPE))
                .thenReturn(Optional.of(connection));
    }

    private void signatureIs(boolean valid) {
        when(signatureVerifier.verify(any(byte[].class), eq("sig"), eq("whsec_x"))).thenReturn(valid);
    }

    @Test
    void unknownAddress_isReported_andNothingElseHappens() {
        when(connectionRepository.findByWebhookKeyAndProvider("nope", Provider.STRIPE))
                .thenReturn(Optional.empty());

        assertThat(service.receive("nope", "sig", bytes(EVENT))).isEqualTo(WebhookOutcome.UNKNOWN_ENDPOINT);

        verifyNoInteractions(signatureVerifier, eventStore);
    }

    @Test
    void badSignature_isRejected_andNothingIsSaved() {
        knownConnection();
        signatureIs(false);

        assertThat(service.receive("key-1", "sig", bytes(EVENT))).isEqualTo(WebhookOutcome.INVALID_SIGNATURE);

        verifyNoInteractions(eventStore);
    }

    @Test
    void newValidEvent_isSaved() {
        knownConnection();
        signatureIs(true);
        when(eventStore.insertIfNew(tenantId, Provider.STRIPE, "evt_1", "charge.succeeded", EVENT))
                .thenReturn(true);

        assertThat(service.receive("key-1", "sig", bytes(EVENT))).isEqualTo(WebhookOutcome.STORED);

        verify(eventStore).insertIfNew(tenantId, Provider.STRIPE, "evt_1", "charge.succeeded", EVENT);
    }

    @Test
    void eventWeAlreadyHave_isReportedAsDuplicate() {
        knownConnection();
        signatureIs(true);
        when(eventStore.insertIfNew(tenantId, Provider.STRIPE, "evt_1", "charge.succeeded", EVENT))
                .thenReturn(false);

        assertThat(service.receive("key-1", "sig", bytes(EVENT))).isEqualTo(WebhookOutcome.DUPLICATE);
    }

    @Test
    void messageThatIsNotJson_isRejected() {
        knownConnection();
        signatureIs(true);

        assertThat(service.receive("key-1", "sig", bytes("this is not json")))
                .isEqualTo(WebhookOutcome.INVALID_PAYLOAD);

        verifyNoInteractions(eventStore);
    }

    @Test
    void messageWithoutAnId_isRejected() {
        knownConnection();
        signatureIs(true);

        assertThat(service.receive("key-1", "sig", bytes("{\"type\":\"charge.succeeded\"}")))
                .isEqualTo(WebhookOutcome.INVALID_PAYLOAD);

        verifyNoInteractions(eventStore);
    }
}
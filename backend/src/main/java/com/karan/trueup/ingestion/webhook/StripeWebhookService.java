package com.karan.trueup.ingestion.webhook;

import com.karan.trueup.ingestion.Provider;
import com.karan.trueup.ingestion.ProviderConnection;
import com.karan.trueup.ingestion.ProviderConnectionRepository;
import com.karan.trueup.ingestion.ProviderEventStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Service
public class StripeWebhookService {

    private static final int MAX_FIELD_LENGTH = 100;

    private final ProviderConnectionRepository connectionRepository;
    private final StripeSignatureVerifier signatureVerifier;
    private final ProviderEventStore eventStore;
    private final JsonMapper jsonMapper;

    public StripeWebhookService(ProviderConnectionRepository connectionRepository,
                                StripeSignatureVerifier signatureVerifier,
                                ProviderEventStore eventStore,
                                JsonMapper jsonMapper) {
        this.connectionRepository = connectionRepository;
        this.signatureVerifier = signatureVerifier;
        this.eventStore = eventStore;
        this.jsonMapper = jsonMapper;
    }

    public WebhookOutcome receive(String webhookKey, String signatureHeader, byte[] rawBody) {
        Optional<ProviderConnection> connection =
                connectionRepository.findByWebhookKeyAndProvider(webhookKey, Provider.STRIPE);
        if (connection.isEmpty()) {
            return WebhookOutcome.UNKNOWN_ENDPOINT;
        }

        if (!signatureVerifier.verify(rawBody, signatureHeader, connection.get().getSigningSecret())) {
            return WebhookOutcome.INVALID_SIGNATURE;
        }

        StripeEventEnvelope envelope = readEnvelope(rawBody);
        if (envelope == null || isInvalid(envelope.id()) || isInvalid(envelope.type())) {
            return WebhookOutcome.INVALID_PAYLOAD;
        }

        boolean stored = eventStore.insertIfNew(
                connection.get().getTenantId(), Provider.STRIPE,
                envelope.id(), envelope.type(), new String(rawBody, StandardCharsets.UTF_8));
        return stored ? WebhookOutcome.STORED : WebhookOutcome.DUPLICATE;
    }

    private StripeEventEnvelope readEnvelope(byte[] rawBody) {
        try {
            return jsonMapper.readValue(rawBody, StripeEventEnvelope.class);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean isInvalid(String value) {
        return value == null || value.isBlank() || value.length() > MAX_FIELD_LENGTH;
    }
}
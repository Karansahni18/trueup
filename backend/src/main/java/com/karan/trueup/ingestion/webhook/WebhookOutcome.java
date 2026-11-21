package com.karan.trueup.ingestion.webhook;

public enum WebhookOutcome {
    STORED,
    DUPLICATE,
    UNKNOWN_ENDPOINT,
    INVALID_SIGNATURE,
    INVALID_PAYLOAD
}
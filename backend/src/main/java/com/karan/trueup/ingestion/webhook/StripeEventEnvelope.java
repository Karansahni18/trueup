package com.karan.trueup.ingestion.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StripeEventEnvelope(String id, String type) {
}
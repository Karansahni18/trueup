package com.karan.trueup.ingestion.processing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StripeEventPayload(String id, String type, Long created, Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(StripeObject object) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StripeObject(String id, Long amount, String currency, Long created,
                               @JsonProperty("arrival_date") Long arrivalDate) {
    }
}
package com.karan.trueup.ingestion.processing;

import com.karan.trueup.ingestion.Provider;
import com.karan.trueup.ingestion.ProviderEvent;

public interface ProviderEventHandler {

    boolean supports(Provider provider, String eventType);

    void handle(ProviderEvent event, String payloadJson);
}
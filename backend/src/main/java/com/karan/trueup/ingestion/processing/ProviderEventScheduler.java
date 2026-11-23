package com.karan.trueup.ingestion.processing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "trueup.events.worker.enabled", havingValue = "true")
public class ProviderEventScheduler {

    private static final int BATCH_SIZE = 50;

    private final ProviderEventProcessor processor;

    public ProviderEventScheduler(ProviderEventProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${trueup.events.worker.poll-interval-ms:5000}")
    public void run() {
        processor.processDueEvents(BATCH_SIZE);
    }
}
package com.karan.trueup.ingestion.webhook;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class WebhookConfig {

    @Bean
    StripeSignatureVerifier stripeSignatureVerifier() {
        return new StripeSignatureVerifier(Clock.systemUTC());
    }
}
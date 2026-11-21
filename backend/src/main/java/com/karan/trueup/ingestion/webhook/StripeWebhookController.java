package com.karan.trueup.ingestion.webhook;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks/stripe")
public class StripeWebhookController {

    private final StripeWebhookService webhookService;

    public StripeWebhookController(StripeWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/{webhookKey}")
    public ResponseEntity<Void> receive(
            @PathVariable String webhookKey,
            @RequestHeader(name = "Stripe-Signature", required = false) String signature,
            @RequestBody(required = false) byte[] rawBody) {

        WebhookOutcome outcome = webhookService.receive(webhookKey, signature, rawBody);

        return switch (outcome) {
            case STORED, DUPLICATE -> ResponseEntity.ok().build();
            case UNKNOWN_ENDPOINT -> ResponseEntity.notFound().build();
            case INVALID_SIGNATURE, INVALID_PAYLOAD -> ResponseEntity.badRequest().build();
        };
    }
}

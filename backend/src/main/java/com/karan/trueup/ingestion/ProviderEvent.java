package com.karan.trueup.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provider_events")
public class ProviderEvent {

    public static final int MAX_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_SECONDS = 30;
    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Provider provider;

    @Column(name = "external_event_id", nullable = false, updatable = false, length = 100)
    private String externalEventId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    protected ProviderEvent() {
        // required by JPA
    }

    public void markProcessed(Instant now) {
        this.status = EventStatus.PROCESSED;
        this.processedAt = now;
        this.lastError = null;
    }

    public void markFailed(String error, Instant now) {
        this.lastError = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        if (attempts >= MAX_ATTEMPTS) {
            this.status = EventStatus.DEAD;
        } else {
            this.status = EventStatus.FAILED;
            this.nextAttemptAt = now.plusSeconds(BASE_BACKOFF_SECONDS << (attempts - 1));
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Provider getProvider() {
        return provider;
    }

    public String getExternalEventId() {
        return externalEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public EventStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public String getLastError() {
        return lastError;
    }
}
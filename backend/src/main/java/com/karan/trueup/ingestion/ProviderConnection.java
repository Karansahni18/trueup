package com.karan.trueup.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provider_connections")
public class ProviderConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Provider provider;

    @Column(name = "webhook_key", nullable = false, updatable = false, length = 64)
    private String webhookKey;

    @Column(name = "signing_secret", nullable = false, length = 200)
    private String signingSecret;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ProviderConnection() {
        // required by JPA
    }

    public ProviderConnection(UUID tenantId, Provider provider, String webhookKey, String signingSecret) {
        this.tenantId = tenantId;
        this.provider = provider;
        this.webhookKey = webhookKey;
        this.signingSecret = signingSecret;
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

    public String getWebhookKey() {
        return webhookKey;
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

package com.karan.trueup.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProviderConnectionRepository extends JpaRepository<ProviderConnection, UUID> {

    Optional<ProviderConnection> findByWebhookKeyAndProvider(String webhookKey, Provider provider);
}
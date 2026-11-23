package com.karan.trueup.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProviderEventRepository extends JpaRepository<ProviderEvent, UUID> {

    Optional<ProviderEvent> findByTenantIdAndExternalEventId(UUID tenantId, String externalEventId);
}
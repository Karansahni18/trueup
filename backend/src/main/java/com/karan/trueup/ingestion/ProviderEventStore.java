package com.karan.trueup.ingestion;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public class ProviderEventStore {

    @PersistenceContext
    private EntityManager entityManager;

    /** Saves the event. Returns true if it was new, false if we already had it. */
    @Transactional
    public boolean insertIfNew(UUID tenantId, Provider provider, String externalEventId,
                               String eventType, String payloadJson) {
        int inserted = entityManager.createNativeQuery("""
                        INSERT INTO provider_events (tenant_id, provider, external_event_id, event_type, payload)
                        VALUES (:tenantId, :provider, :externalEventId, :eventType, CAST(:payload AS jsonb))
                        ON CONFLICT (tenant_id, provider, external_event_id) DO NOTHING
                        """)
                .setParameter("tenantId", tenantId)
                .setParameter("provider", provider.name())
                .setParameter("externalEventId", externalEventId)
                .setParameter("eventType", eventType)
                .setParameter("payload", payloadJson)
                .executeUpdate();
        return inserted == 1;
    }

    public long countFor(UUID tenantId, String externalEventId) {
        Number count = (Number) entityManager.createNativeQuery("""
                        SELECT count(*) FROM provider_events
                        WHERE tenant_id = :tenantId AND external_event_id = :externalEventId
                        """)
                .setParameter("tenantId", tenantId)
                .setParameter("externalEventId", externalEventId)
                .getSingleResult();
        return count.longValue();
    }

    /** Reads the saved message back as plain JSON text. */
    public String loadPayload(UUID eventId) {
        return (String) entityManager
                .createNativeQuery("SELECT CAST(payload AS text) FROM provider_events WHERE id = :id")
                .setParameter("id", eventId)
                .getSingleResult();
    }
}
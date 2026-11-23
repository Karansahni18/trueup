package com.karan.trueup.ingestion.processing;

import com.karan.trueup.ingestion.ProviderEvent;
import com.karan.trueup.ingestion.ProviderEventRepository;
import com.karan.trueup.ingestion.ProviderEventStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProviderEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(ProviderEventProcessor.class);

    @PersistenceContext
    private EntityManager entityManager;

    private final TransactionTemplate transactionTemplate;
    private final ProviderEventRepository eventRepository;
    private final ProviderEventStore eventStore;
    private final ObjectProvider<ProviderEventHandler> handlers;
    private final Clock clock;
    private final int leaseSeconds;

    public ProviderEventProcessor(TransactionTemplate transactionTemplate,
                                  ProviderEventRepository eventRepository,
                                  ProviderEventStore eventStore,
                                  ObjectProvider<ProviderEventHandler> handlers,
                                  Clock clock,
                                  @Value("${trueup.events.worker.lease-seconds:300}") int leaseSeconds) {
        this.transactionTemplate = transactionTemplate;
        this.eventRepository = eventRepository;
        this.eventStore = eventStore;
        this.handlers = handlers;
        this.clock = clock;
        this.leaseSeconds = leaseSeconds;
    }

    /** Handles up to maxEvents due events, one at a time. Returns how many it picked up. */
    public int processDueEvents(int maxEvents) {
        int picked = 0;
        while (picked < maxEvents) {
            Optional<UUID> claimed = claimNext();
            if (claimed.isEmpty()) {
                break;
            }
            process(claimed.get());
            picked++;
        }
        return picked;
    }

    // Step 1: write our name on the next due task, with a deadline. Others skip tasks that are taken.
    private Optional<UUID> claimNext() {
        return Optional.ofNullable(transactionTemplate.execute(status -> {
            List<?> rows = entityManager.createNativeQuery("""
                            UPDATE provider_events
                            SET status = 'PROCESSING',
                                attempts = attempts + 1,
                                next_attempt_at = now() + (CAST(:leaseSeconds AS integer) * INTERVAL '1 second')
                            WHERE id = (
                                SELECT id FROM provider_events
                                WHERE status IN ('RECEIVED', 'FAILED', 'PROCESSING')
                                  AND next_attempt_at <= now()
                                ORDER BY next_attempt_at
                                LIMIT 1
                                FOR UPDATE SKIP LOCKED
                            )
                            RETURNING id
                            """)
                    .setParameter("leaseSeconds", leaseSeconds)
                    .getResultList();
            return rows.isEmpty() ? null : (UUID) rows.get(0);
        }));
    }

    // Step 2: do the work and tick the task off. If anything goes wrong, undo the work and note the failure.
    private void process(UUID eventId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                ProviderEvent event = eventRepository.findById(eventId).orElseThrow();
                dispatch(event, eventStore.loadPayload(eventId));
                event.markProcessed(clock.instant());
            });
        } catch (RuntimeException failure) {
            log.warn("Event {} failed: {}", eventId, failure.toString());
            recordFailure(eventId, failure);
        }
    }

    private void dispatch(ProviderEvent event, String payloadJson) {
        for (ProviderEventHandler handler : handlers.orderedStream().toList()) {
            if (handler.supports(event.getProvider(), event.getEventType())) {
                handler.handle(event, payloadJson);
                return;
            }
        }
        log.debug("No handler for {} events, marking {} as processed", event.getEventType(), event.getId());
    }

    // Step 3: write down what went wrong, and when to try again (or give up).
    private void recordFailure(UUID eventId, RuntimeException failure) {
        transactionTemplate.executeWithoutResult(status -> {
            ProviderEvent event = eventRepository.findById(eventId).orElseThrow();
            event.markFailed(failure.getClass().getSimpleName() + ": " + failure.getMessage(), clock.instant());
        });
    }
}
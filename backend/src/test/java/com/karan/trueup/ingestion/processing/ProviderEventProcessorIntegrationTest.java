package com.karan.trueup.ingestion.processing;

import com.karan.trueup.TestcontainersConfiguration;
import com.karan.trueup.ingestion.EventStatus;
import com.karan.trueup.ingestion.Provider;
import com.karan.trueup.ingestion.ProviderEvent;
import com.karan.trueup.ingestion.ProviderEventRepository;
import com.karan.trueup.ingestion.ProviderEventStore;
import com.karan.trueup.tenant.Tenant;
import com.karan.trueup.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProviderEventProcessorIntegrationTest {

    /** Every event id our test handler has handled. */
    static final Queue<String> HANDLED = new ConcurrentLinkedQueue<>();

    /** A pretend handler: "test.ok" events work, "test.fail" events always crash. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestHandlers {

        @Bean
        ProviderEventHandler testEventHandler() {
            return new ProviderEventHandler() {
                @Override
                public boolean supports(Provider provider, String eventType) {
                    return eventType.startsWith("test.");
                }

                @Override
                public void handle(ProviderEvent event, String payloadJson) {
                    if (event.getEventType().equals("test.fail")) {
                        throw new IllegalStateException("boom");
                    }
                    HANDLED.add(event.getExternalEventId());
                }
            };
        }
    }

    @Autowired
    ProviderEventProcessor processor;

    @Autowired
    ProviderEventStore eventStore;

    @Autowired
    ProviderEventRepository eventRepository;

    @Autowired
    TenantRepository tenantRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @PersistenceContext
    EntityManager entityManager;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        HANDLED.clear();
        tenantId = tenantRepository.save(new Tenant("Processor IT " + UUID.randomUUID())).getId();
    }

    private String newEvent(String type) {
        String id = "evt_" + UUID.randomUUID();
        eventStore.insertIfNew(tenantId, Provider.STRIPE, id, type, "{\"id\":\"" + id + "\"}");
        return id;
    }

    private ProviderEvent load(String externalEventId) {
        return eventRepository.findByTenantIdAndExternalEventId(tenantId, externalEventId).orElseThrow();
    }

    /** Pretend time has passed: make the event due right now. */
    private void makeDueNow(UUID eventId) {
        transactionTemplate.executeWithoutResult(status -> entityManager
                .createNativeQuery("UPDATE provider_events SET next_attempt_at = now() WHERE id = :id")
                .setParameter("id", eventId)
                .executeUpdate());
    }

    /** Pretend a worker is busy with the event (or crashed while busy). */
    private void setProcessing(UUID eventId, String deadlineSql) {
        transactionTemplate.executeWithoutResult(status -> entityManager
                .createNativeQuery("UPDATE provider_events SET status = 'PROCESSING', next_attempt_at = "
                        + deadlineSql + " WHERE id = :id")
                .setParameter("id", eventId)
                .executeUpdate());
    }

    @Test
    void supportedEvent_isHandledOnce_andMarkedProcessed() {
        String id = newEvent("test.ok");

        processor.processDueEvents(10);

        ProviderEvent event = load(id);
        assertThat(event.getStatus()).isEqualTo(EventStatus.PROCESSED);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getProcessedAt()).isNotNull();
        assertThat(HANDLED).containsExactly(id);
    }

    @Test
    void eventNobodyKnowsHowToHandle_isMarkedProcessedAndIgnored() {
        String id = newEvent("something.unknown");

        processor.processDueEvents(10);

        assertThat(load(id).getStatus()).isEqualTo(EventStatus.PROCESSED);
        assertThat(HANDLED).isEmpty();
    }

    @Test
    void failingEvent_isMarkedFailed_andRetriedLater() {
        String id = newEvent("test.fail");

        processor.processDueEvents(10);

        ProviderEvent event = load(id);
        assertThat(event.getStatus()).isEqualTo(EventStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("boom");
        assertThat(event.getNextAttemptAt()).isAfter(Instant.now());

        processor.processDueEvents(10);

        assertThat(load(id).getAttempts()).as("not due yet, so it was left alone").isEqualTo(1);
    }

    @Test
    void eventThatKeepsFailing_becomesDeadAfterTheMaximumTries() {
        String id = newEvent("test.fail");
        UUID eventId = load(id).getId();

        for (int attempt = 1; attempt <= ProviderEvent.MAX_ATTEMPTS; attempt++) {
            makeDueNow(eventId);
            processor.processDueEvents(10);
        }

        assertThat(load(id).getStatus()).isEqualTo(EventStatus.DEAD);
        assertThat(load(id).getAttempts()).isEqualTo(ProviderEvent.MAX_ATTEMPTS);

        makeDueNow(eventId);
        processor.processDueEvents(10);

        assertThat(load(id).getAttempts()).as("dead events are never picked up again")
                .isEqualTo(ProviderEvent.MAX_ATTEMPTS);
    }

    @Test
    void eventsAreNotHandledTwice_evenWithSeveralWorkersAtTheSameTime() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(newEvent("test.ok"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> workers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                workers.add(pool.submit(() -> processor.processDueEvents(100)));
            }
            for (Future<Integer> worker : workers) {
                worker.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(HANDLED).hasSize(20).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
        for (String id : ids) {
            assertThat(load(id).getStatus()).isEqualTo(EventStatus.PROCESSED);
        }
    }

    @Test
    void eventAbandonedByACrashedWorker_isPickedUpAgainOnlyAfterItsDeadline() {
        String id = newEvent("test.ok");
        UUID eventId = load(id).getId();

        setProcessing(eventId, "now() + interval '10 minutes'");
        processor.processDueEvents(10);

        assertThat(load(id).getStatus()).as("someone is still working on it").isEqualTo(EventStatus.PROCESSING);
        assertThat(HANDLED).isEmpty();

        setProcessing(eventId, "now() - interval '1 minute'");
        processor.processDueEvents(10);

        assertThat(load(id).getStatus()).isEqualTo(EventStatus.PROCESSED);
        assertThat(HANDLED).containsExactly(id);
    }
}
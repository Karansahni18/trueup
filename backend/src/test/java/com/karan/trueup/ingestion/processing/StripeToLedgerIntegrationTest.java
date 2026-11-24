package com.karan.trueup.ingestion.processing;

import com.karan.trueup.TestcontainersConfiguration;
import com.karan.trueup.auth.AuthService;
import com.karan.trueup.auth.dto.RegisterRequest;
import com.karan.trueup.ingestion.EventStatus;
import com.karan.trueup.ingestion.Provider;
import com.karan.trueup.ingestion.ProviderConnection;
import com.karan.trueup.ingestion.ProviderConnectionRepository;
import com.karan.trueup.ingestion.ProviderEvent;
import com.karan.trueup.ingestion.ProviderEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class StripeToLedgerIntegrationTest {

    private static final String SECRET = "whsec_flow_secret";

    @Value("${local.server.port}")
    int port;

    @Autowired
    AuthService authService;

    @Autowired
    ProviderConnectionRepository connectionRepository;

    @Autowired
    ProviderEventRepository eventRepository;

    @Autowired
    ProviderEventProcessor processor;

    @PersistenceContext
    EntityManager entityManager;

    private final HttpClient client = HttpClient.newHttpClient();
    private UUID tenantId;
    private String webhookKey;

    @BeforeEach
    void registerBusinessAndConnectStripe() {
        String unique = UUID.randomUUID().toString();
        tenantId = authService.register(new RegisterRequest(
                "Flow IT " + unique, "flow-" + unique + "@flow.test", "Str0ngPass!")).tenantId();
        webhookKey = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        connectionRepository.save(new ProviderConnection(tenantId, Provider.STRIPE, webhookKey, SECRET));
    }

    private static String chargeEvent(String eventId, long amount, String currency) {
        return "{\"id\":\"" + eventId + "\",\"type\":\"charge.succeeded\",\"created\":1700000000,"
                + "\"data\":{\"object\":{\"id\":\"ch_" + eventId + "\",\"amount\":" + amount
                + ",\"currency\":\"" + currency + "\",\"created\":1700000000}}}";
    }

    private static String payoutEvent(String eventId, long amount) {
        return "{\"id\":\"" + eventId + "\",\"type\":\"payout.paid\",\"created\":1700000100,"
                + "\"data\":{\"object\":{\"id\":\"po_" + eventId + "\",\"amount\":" + amount
                + ",\"currency\":\"usd\",\"arrival_date\":1700006400}}}";
    }

    private static String sign(long timestamp, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Sends a signed message to our door, like Stripe would. Returns the HTTP status. */
    private int post(String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/webhooks/stripe/" + webhookKey))
                .header("Content-Type", "application/json")
                .header("Stripe-Signature", "t=" + timestamp + ",v1=" + sign(timestamp, payload))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Money in the account box: what went in (debits) minus what came out (credits). */
    private long balance(String accountCode) {
        Number total = (Number) entityManager.createNativeQuery("""
                        SELECT COALESCE(SUM(CASE WHEN l.direction = 'DEBIT'
                                                 THEN l.amount_minor ELSE -l.amount_minor END), 0)
                        FROM journal_lines l
                        JOIN accounts a ON a.id = l.account_id AND a.tenant_id = l.tenant_id
                        WHERE l.tenant_id = :tenantId AND a.code = :code
                        """)
                .setParameter("tenantId", tenantId)
                .setParameter("code", accountCode)
                .getSingleResult();
        return total.longValue();
    }

    private ProviderEvent load(String externalEventId) {
        return eventRepository.findByTenantIdAndExternalEventId(tenantId, externalEventId).orElseThrow();
    }

    @Test
    void chargeThenPayout_leavesTheStripeFeeInTheClearingBox() throws Exception {
        String chargeId = "evt_" + UUID.randomUUID();
        String payoutId = "evt_" + UUID.randomUUID();

        assertThat(post(chargeEvent(chargeId, 10000, "usd"))).isEqualTo(200);
        assertThat(post(payoutEvent(payoutId, 9680))).isEqualTo(200);
        processor.processDueEvents(100);

        assertThat(balance("1000")).as("Bank").isEqualTo(9680L);
        assertThat(balance("1100")).as("Clearing: the unrecorded fee").isEqualTo(320L);
        assertThat(balance("4000")).as("Sales (money coming in shows as negative)").isEqualTo(-10000L);
        assertThat(load(chargeId).getStatus()).isEqualTo(EventStatus.PROCESSED);
        assertThat(load(payoutId).getStatus()).isEqualTo(EventStatus.PROCESSED);
    }

    @Test
    void processingAgain_doesNotRecordTheSameMoneyTwice() throws Exception {
        assertThat(post(chargeEvent("evt_" + UUID.randomUUID(), 10000, "usd"))).isEqualTo(200);

        processor.processDueEvents(100);
        processor.processDueEvents(100);

        assertThat(balance("1100")).isEqualTo(10000L);
    }

    @Test
    void sameStripeMessageDeliveredTwice_isRecordedOnce() throws Exception {
        String payload = chargeEvent("evt_" + UUID.randomUUID(), 10000, "usd");

        assertThat(post(payload)).isEqualTo(200);
        assertThat(post(payload)).isEqualTo(200);
        processor.processDueEvents(100);

        assertThat(balance("1100")).isEqualTo(10000L);
    }

    @Test
    void chargeInAnUnexpectedCurrency_failsAndLeavesTheLedgerUntouched() throws Exception {
        String eventId = "evt_" + UUID.randomUUID();

        assertThat(post(chargeEvent(eventId, 5000, "eur"))).isEqualTo(200);
        processor.processDueEvents(100);

        ProviderEvent event = load(eventId);
        assertThat(event.getStatus()).isEqualTo(EventStatus.FAILED);
        assertThat(event.getLastError()).contains("EUR");
        assertThat(balance("1100")).isEqualTo(0L);
    }
}
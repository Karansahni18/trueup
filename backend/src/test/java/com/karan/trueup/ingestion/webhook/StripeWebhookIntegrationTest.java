package com.karan.trueup.ingestion.webhook;

import com.karan.trueup.TestcontainersConfiguration;
import com.karan.trueup.ingestion.Provider;
import com.karan.trueup.ingestion.ProviderConnection;
import com.karan.trueup.ingestion.ProviderConnectionRepository;
import com.karan.trueup.ingestion.ProviderEventStore;
import com.karan.trueup.tenant.Tenant;
import com.karan.trueup.tenant.TenantRepository;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class StripeWebhookIntegrationTest {

    private static final String SECRET = "whsec_integration_secret";

    @Value("${local.server.port}")
    int port;

    @Autowired
    TenantRepository tenantRepository;

    @Autowired
    ProviderConnectionRepository connectionRepository;

    @Autowired
    ProviderEventStore eventStore;

    private final HttpClient client = HttpClient.newHttpClient();
    private UUID tenantId;
    private String webhookKey;

    @BeforeEach
    void createTenantAndConnection() {
        tenantId = tenantRepository.save(new Tenant("Webhook IT " + UUID.randomUUID())).getId();
        webhookKey = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        connectionRepository.save(new ProviderConnection(tenantId, Provider.STRIPE, webhookKey, SECRET));
    }

    private static String sign(String secret, long timestamp, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String signedHeader(String secret, String payload) {
        long timestamp = Instant.now().getEpochSecond();
        return "t=" + timestamp + ",v1=" + sign(secret, timestamp, payload);
    }

    private static String eventJson(String eventId) {
        return "{\"id\":\"" + eventId + "\",\"type\":\"charge.succeeded\","
                + "\"data\":{\"object\":{\"amount\":10000}}}";
    }

    private int post(String key, String payload, String signatureHeader) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/webhooks/stripe/" + key))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (signatureHeader != null) {
            request.header("Stripe-Signature", signatureHeader);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void signedEvent_isAcceptedAndSavedOnce() throws Exception {
        String eventId = "evt_" + UUID.randomUUID();
        String payload = eventJson(eventId);

        assertThat(post(webhookKey, payload, signedHeader(SECRET, payload))).isEqualTo(200);

        assertThat(eventStore.countFor(tenantId, eventId)).isEqualTo(1L);
    }

    @Test
    void sameEventSentTwice_isSavedOnlyOnce() throws Exception {
        String eventId = "evt_" + UUID.randomUUID();
        String payload = eventJson(eventId);

        assertThat(post(webhookKey, payload, signedHeader(SECRET, payload))).isEqualTo(200);
        assertThat(post(webhookKey, payload, signedHeader(SECRET, payload))).isEqualTo(200);

        assertThat(eventStore.countFor(tenantId, eventId)).isEqualTo(1L);
    }

    @Test
    void sameEventSentTenTimesAtTheSameInstant_isSavedOnlyOnce() throws Exception {
        String eventId = "evt_" + UUID.randomUUID();
        String payload = eventJson(eventId);
        int senders = 10;

        ExecutorService pool = Executors.newFixedThreadPool(senders);
        try {
            CountDownLatch ready = new CountDownLatch(senders);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < senders; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return post(webhookKey, payload, signedHeader(SECRET, payload));
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(eventStore.countFor(tenantId, eventId)).isEqualTo(1L);
    }

    @Test
    void signatureMadeWithTheWrongSecret_isRejectedAndNothingIsSaved() throws Exception {
        String eventId = "evt_" + UUID.randomUUID();
        String payload = eventJson(eventId);

        assertThat(post(webhookKey, payload, signedHeader("whsec_wrong_secret", payload))).isEqualTo(400);

        assertThat(eventStore.countFor(tenantId, eventId)).isEqualTo(0L);
    }

    @Test
    void messageChangedAfterSigning_isRejectedAndNothingIsSaved() throws Exception {
        String eventId = "evt_" + UUID.randomUUID();
        String signedPayload = eventJson(eventId);
        String changedPayload = signedPayload.replace("10000", "99999");

        assertThat(post(webhookKey, changedPayload, signedHeader(SECRET, signedPayload))).isEqualTo(400);

        assertThat(eventStore.countFor(tenantId, eventId)).isEqualTo(0L);
    }

    @Test
    void missingSignatureHeader_isRejected() throws Exception {
        String payload = eventJson("evt_" + UUID.randomUUID());

        assertThat(post(webhookKey, payload, null)).isEqualTo(400);
    }

    @Test
    void unknownAddress_returnsNotFound() throws Exception {
        String payload = eventJson("evt_" + UUID.randomUUID());

        assertThat(post("this-key-does-not-exist", payload, signedHeader(SECRET, payload))).isEqualTo(404);
    }
}

package com.karan.trueup.banking;

import com.karan.trueup.TestcontainersConfiguration;
import com.karan.trueup.auth.AuthService;
import com.karan.trueup.auth.JwtService;
import com.karan.trueup.auth.dto.RegisterRequest;
import com.karan.trueup.auth.dto.RegisterResponse;
import com.karan.trueup.tenant.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class BankStatementApiIntegrationTest {

    private static final String CSV = String.join("\n",
            "date,description,amount,reference",
            "2026-11-15,STRIPE PAYOUT,96.80,po_demo",
            "2026-11-16,\"ACME, INC\",-12.50,") + "\n";

    @Value("${local.server.port}")
    int port;

    @Autowired
    AuthService authService;

    @Autowired
    JwtService jwtService;

    private final HttpClient client = HttpClient.newHttpClient();
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void registerTwoBusinesses() {
        tokenA = tokenForNewBusiness();
        tokenB = tokenForNewBusiness();
    }

    private String tokenForNewBusiness() {
        String unique = UUID.randomUUID().toString();
        RegisterResponse registered = authService.register(new RegisterRequest(
                "Bank IT " + unique, "bank-" + unique + "@bank.test", "Str0ngPass!"));
        return jwtService.generateToken(registered.userId(), registered.tenantId(), Role.OWNER);
    }

    private HttpResponse<String> upload(String token, String csv) throws Exception {
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/api/bank-imports?filename=test.csv"))
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString(csv));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> list(String token) throws Exception {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/api/bank-transactions"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void uploadWithoutALogin_isRefused() throws Exception {
        assertThat(upload(null, CSV).statusCode()).isEqualTo(401);
    }

    @Test
    void uploadedTransactionsAppearInTheList() throws Exception {
        HttpResponse<String> uploaded = upload(tokenA, CSV);

        assertThat(uploaded.statusCode()).isEqualTo(201);
        assertThat(uploaded.body()).contains("\"rowCount\":2").contains("\"currency\":\"USD\"");

        HttpResponse<String> listed = list(tokenA);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body())
                .contains("\"description\":\"STRIPE PAYOUT\"")
                .contains("\"description\":\"ACME, INC\"")
                .contains("\"amountMinor\":9680")
                .contains("\"amountMinor\":-1250");
    }

    @Test
    void theSameFileTwice_isRefusedTheSecondTime() throws Exception {
        assertThat(upload(tokenA, CSV).statusCode()).isEqualTo(201);

        HttpResponse<String> second = upload(tokenA, CSV);

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("already been imported");
    }

    @Test
    void anotherBusinessNeverSeesTheseTransactions() throws Exception {
        assertThat(upload(tokenA, CSV).statusCode()).isEqualTo(201);

        HttpResponse<String> listedByB = list(tokenB);

        assertThat(listedByB.statusCode()).isEqualTo(200);
        assertThat(listedByB.body()).isEqualTo("[]");
    }

    @Test
    void theSameFileCanBeImportedByAnotherBusiness() throws Exception {
        assertThat(upload(tokenA, CSV).statusCode()).isEqualTo(201);

        assertThat(upload(tokenB, CSV).statusCode()).isEqualTo(201);
    }

    @Test
    void aBadFileIsRefusedWithAReadableListOfProblems() throws Exception {
        HttpResponse<String> response = upload(tokenA, "date,description,amount\n2026-11-15,Deposit,abc\n");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"problems\":[").contains("Row 1");
        assertThat(list(tokenA).body()).isEqualTo("[]");
    }
}
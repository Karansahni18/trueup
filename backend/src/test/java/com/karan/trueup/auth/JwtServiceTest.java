package com.karan.trueup.auth;

import com.karan.trueup.tenant.Role;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-that-is-at-least-32-bytes-long!";
    private static final String OTHER_SECRET = "a-completely-different-secret-for-the-test-1234";

    private final JwtService jwtService = new JwtService(SECRET, 3600);

    @Test
    void generatedToken_roundTripsThroughParse() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String token = jwtService.generateToken(userId, tenantId, Role.OWNER);
        AuthenticatedUser user = jwtService.parse(token);

        assertThat(user.userId()).isEqualTo(userId);
        assertThat(user.tenantId()).isEqualTo(tenantId);
        assertThat(user.role()).isEqualTo(Role.OWNER);
    }

    @Test
    void parse_rejectsTokenSignedWithDifferentSecret() {
        String token = new JwtService(OTHER_SECRET, 3600)
                .generateToken(UUID.randomUUID(), UUID.randomUUID(), Role.MEMBER);

        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void parse_rejectsExpiredToken() {
        String token = new JwtService(SECRET, -10)
                .generateToken(UUID.randomUUID(), UUID.randomUUID(), Role.MEMBER);

        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void parse_rejectsTamperedPayload() {
        String token = jwtService.generateToken(UUID.randomUUID(), UUID.randomUUID(), Role.MEMBER);
        String[] parts = token.split("\\.");
        String otherPayload = new JwtService(OTHER_SECRET, 3600)
                .generateToken(UUID.randomUUID(), UUID.randomUUID(), Role.OWNER)
                .split("\\.")[1];
        String tampered = parts[0] + "." + otherPayload + "." + parts[2];

        assertThatThrownBy(() -> jwtService.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void parse_rejectsGarbage() {
        assertThatThrownBy(() -> jwtService.parse("not.a.token")).isInstanceOf(JwtException.class);
    }
}
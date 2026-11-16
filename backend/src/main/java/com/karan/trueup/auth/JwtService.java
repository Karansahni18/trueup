package com.karan.trueup.auth;

import com.karan.trueup.tenant.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private static final String CLAIM_TENANT_ID = "tenantId";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${trueup.jwt.secret}") String secret,
                      @Value("${trueup.jwt.ttl-seconds}") long ttlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public String generateToken(UUID userId, UUID tenantId, Role role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_TENANT_ID, tenantId.toString())
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    public AuthenticatedUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String tenantId = claims.get(CLAIM_TENANT_ID, String.class);
        String role = claims.get(CLAIM_ROLE, String.class);
        if (claims.getSubject() == null || tenantId == null || role == null) {
            throw new JwtException("Token is missing required claims");
        }

        try {
            return new AuthenticatedUser(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(tenantId),
                    Role.valueOf(role));
        } catch (IllegalArgumentException ex) {
            throw new JwtException("Token claims are malformed", ex);
        }
    }
}
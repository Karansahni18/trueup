package com.karan.trueup.auth;

import com.karan.trueup.tenant.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthFilterTest {

    private static final String SECRET = "unit-test-secret-that-is-at-least-32-bytes-long!";
    private static final String OTHER_SECRET = "a-completely-different-secret-for-the-test-1234";

    private final JwtService jwtService = new JwtService(SECRET, 3600);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtService);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockFilterChain run(String authorizationHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    @Test
    void validBearerToken_authenticatesTheRequest() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, tenantId, Role.OWNER);

        MockFilterChain chain = run("Bearer " + token);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo(new AuthenticatedUser(userId, tenantId, Role.OWNER));
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_OWNER");
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void missingHeader_leavesRequestUnauthenticated() throws Exception {
        MockFilterChain chain = run(null);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void garbageToken_leavesRequestUnauthenticated() throws Exception {
        MockFilterChain chain = run("Bearer not.a.token");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void tokenSignedWithAnotherSecret_leavesRequestUnauthenticated() throws Exception {
        String forged = new JwtService(OTHER_SECRET, 3600)
                .generateToken(UUID.randomUUID(), UUID.randomUUID(), Role.OWNER);

        run("Bearer " + forged);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nonBearerScheme_isIgnored() throws Exception {
        run("Basic dXNlcjpwYXNz");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
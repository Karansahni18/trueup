package com.karan.trueup.auth;

import com.karan.trueup.auth.dto.RegisterRequest;
import com.karan.trueup.auth.dto.RegisterResponse;
import com.karan.trueup.common.exception.ConflictException;
import com.karan.trueup.tenant.Role;
import com.karan.trueup.tenant.Tenant;
import com.karan.trueup.tenant.TenantRepository;
import com.karan.trueup.tenant.User;
import com.karan.trueup.tenant.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    TenantRepository tenantRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(tenantRepository, userRepository, passwordEncoder);
    }

    @Test
    void register_createsTenantAndOwnerWithHashedPassword() {
        when(userRepository.emailExists("owner@acme.test")).thenReturn(false);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode("Str0ngPass!")).thenReturn("hashed-value");

        RegisterResponse response = authService.register(
                new RegisterRequest("  Acme Store ", "Owner@Acme.Test ", "Str0ngPass!"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getEmail()).isEqualTo("owner@acme.test");
        assertThat(saved.getPasswordHash()).isEqualTo("hashed-value");
        assertThat(saved.getRole()).isEqualTo(Role.OWNER);
        assertThat(saved.getTenant().getName()).isEqualTo("Acme Store");
        assertThat(response.email()).isEqualTo("owner@acme.test");
    }

    @Test
    void register_rejectsEmailThatAlreadyExists() {
        when(userRepository.emailExists("owner@acme.test")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("Acme", "owner@acme.test", "Str0ngPass!")))
                .isInstanceOf(ConflictException.class);

        verify(tenantRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void register_convertsUniqueViolationIntoConflict() {
        when(userRepository.emailExists("owner@acme.test")).thenReturn(false);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode("Str0ngPass!")).thenReturn("hashed-value");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("Acme", "owner@acme.test", "Str0ngPass!")))
                .isInstanceOf(ConflictException.class);
    }
}
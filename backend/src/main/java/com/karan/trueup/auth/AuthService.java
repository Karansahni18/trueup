package com.karan.trueup.auth;

import com.karan.trueup.auth.dto.RegisterRequest;
import com.karan.trueup.auth.dto.RegisterResponse;
import com.karan.trueup.common.exception.ConflictException;
import com.karan.trueup.tenant.Role;
import com.karan.trueup.tenant.Tenant;
import com.karan.trueup.tenant.TenantRepository;
import com.karan.trueup.tenant.User;
import com.karan.trueup.tenant.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {

    private static final String EMAIL_TAKEN = "An account with this email already exists.";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(TenantRepository tenantRepository,
                       UserRepository userRepository,
                       PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        if (userRepository.emailExists(email)) {
            throw new ConflictException(EMAIL_TAKEN);
        }

        Tenant tenant = tenantRepository.save(new Tenant(request.businessName().trim()));
        User owner = new User(tenant, email, passwordEncoder.encode(request.password()), Role.OWNER);

        try {
            userRepository.saveAndFlush(owner);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(EMAIL_TAKEN);
        }

        return new RegisterResponse(tenant.getId(), owner.getId(), owner.getEmail());
    }
}
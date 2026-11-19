package com.karan.trueup.auth;

import com.karan.trueup.auth.dto.LoginRequest;
import com.karan.trueup.auth.dto.LoginResponse;
import com.karan.trueup.auth.dto.RegisterRequest;
import com.karan.trueup.auth.dto.RegisterResponse;
import com.karan.trueup.common.exception.ConflictException;
import com.karan.trueup.common.exception.UnauthorizedException;
import com.karan.trueup.ledger.ChartOfAccountsService;
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
import java.util.Optional;

@Service
public class AuthService {

    private static final String EMAIL_TAKEN = "An account with this email already exists.";
    private static final String BAD_CREDENTIALS = "Invalid email or password.";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ChartOfAccountsService chartOfAccountsService;
    private final String dummyHash;

    public AuthService(TenantRepository tenantRepository,
                       UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       ChartOfAccountsService chartOfAccountsService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.chartOfAccountsService = chartOfAccountsService;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        if (userRepository.emailExists(email)) {
            throw new ConflictException(EMAIL_TAKEN);
        }

        Tenant tenant = tenantRepository.save(new Tenant(request.businessName().trim()));
        chartOfAccountsService.createDefaultAccounts(tenant.getId(), ChartOfAccountsService.DEFAULT_CURRENCY);
        User owner = new User(tenant, email, passwordEncoder.encode(request.password()), Role.OWNER);

        try {
            userRepository.saveAndFlush(owner);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(EMAIL_TAKEN);
        }

        return new RegisterResponse(tenant.getId(), owner.getId(), owner.getEmail());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Optional<User> found = userRepository.findByEmailWithTenant(email);

        // Always run one BCrypt comparison so response time does not reveal whether the email exists.
        String hashToCheck = found.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        if (found.isEmpty() || !passwordMatches) {
            throw new UnauthorizedException(BAD_CREDENTIALS);
        }

        User user = found.get();
        String token = jwtService.generateToken(user.getId(), user.getTenant().getId(), user.getRole());
        return new LoginResponse(token, "Bearer", jwtService.getTtlSeconds());
    }
}
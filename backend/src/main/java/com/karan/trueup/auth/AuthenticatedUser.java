package com.karan.trueup.auth;

import com.karan.trueup.tenant.Role;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, UUID tenantId, Role role) {
}
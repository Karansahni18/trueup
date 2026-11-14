package com.karan.trueup.auth.dto;

import java.util.UUID;

public record RegisterResponse(UUID tenantId, UUID userId, String email) {
}
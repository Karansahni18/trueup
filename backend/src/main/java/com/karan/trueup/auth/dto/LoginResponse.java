package com.karan.trueup.auth.dto;

public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds) {
}
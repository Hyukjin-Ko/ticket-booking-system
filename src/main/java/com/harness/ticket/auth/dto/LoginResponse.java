package com.harness.ticket.auth.dto;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        long expiresInSec
) {}

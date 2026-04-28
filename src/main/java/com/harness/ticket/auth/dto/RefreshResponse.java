package com.harness.ticket.auth.dto;

public record RefreshResponse(
        String accessToken,
        String refreshToken,
        long expiresInSec
) {}

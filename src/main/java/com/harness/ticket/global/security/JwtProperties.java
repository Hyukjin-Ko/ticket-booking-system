package com.harness.ticket.global.security;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
@Getter
@RequiredArgsConstructor
public class JwtProperties {

    private final String secret;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;
}

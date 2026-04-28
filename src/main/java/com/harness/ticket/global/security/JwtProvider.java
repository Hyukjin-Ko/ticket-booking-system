package com.harness.ticket.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtProvider {

    private static final int MIN_SECRET_BYTES = 32;
    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_USERNAME = "username";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final Clock clock;
    private final SecretKey key;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public JwtProvider(JwtProperties properties, Clock clock) {
        byte[] secretBytes = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes (256 bits)");
        }
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.accessTtlMinutes = properties.getAccessTtlMinutes();
        this.refreshTtlDays = properties.getRefreshTtlDays();
    }

    public String createAccess(Long userId, String username) {
        Instant now = Instant.now(clock);
        Instant exp = now.plus(Duration.ofMinutes(accessTtlMinutes));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    public String createRefresh(Long userId) {
        Instant now = Instant.now(clock);
        Instant exp = now.plus(Duration.ofDays(refreshTtlDays));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    public Jws<Claims> parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(Instant.now(clock)))
                .build()
                .parseSignedClaims(token);
    }

    public long getAccessTtlSec() {
        return accessTtlMinutes * 60L;
    }

    public long getRefreshTtlSec() {
        return refreshTtlDays * 86400L;
    }
}

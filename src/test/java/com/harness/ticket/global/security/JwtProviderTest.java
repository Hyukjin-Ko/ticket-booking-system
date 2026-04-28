package com.harness.ticket.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

    private static final String VALID_SECRET = "dGVzdC1zZWNyZXQtbXVzdC1iZS1hdC1sZWFzdC0zMi1ieXRlcy1sb25n";
    private static final int ACCESS_TTL_MINUTES = 15;
    private static final int REFRESH_TTL_DAYS = 14;

    private JwtProperties props(String secret) {
        return new JwtProperties(secret, ACCESS_TTL_MINUTES, REFRESH_TTL_DAYS);
    }

    private Clock fixedAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    void createAccess_includesSubAndUsernameAndAccessType() {
        Instant now = Instant.parse("2026-04-28T00:00:00Z");
        JwtProvider provider = new JwtProvider(props(VALID_SECRET), fixedAt(now));

        String token = provider.createAccess(42L, "alice");

        Jws<Claims> jws = provider.parseAndValidate(token);
        Claims c = jws.getPayload();
        assertThat(c.getSubject()).isEqualTo("42");
        assertThat(c.get("username", String.class)).isEqualTo("alice");
        assertThat(c.get("type", String.class)).isEqualTo("access");
        assertThat(c.getIssuedAt().toInstant()).isEqualTo(now);
        assertThat(c.getExpiration().toInstant()).isEqualTo(now.plusSeconds(ACCESS_TTL_MINUTES * 60L));
    }

    @Test
    void createRefresh_includesSubAndRefreshType() {
        Instant now = Instant.parse("2026-04-28T00:00:00Z");
        JwtProvider provider = new JwtProvider(props(VALID_SECRET), fixedAt(now));

        String token = provider.createRefresh(7L);

        Claims c = provider.parseAndValidate(token).getPayload();
        assertThat(c.getSubject()).isEqualTo("7");
        assertThat(c.get("type", String.class)).isEqualTo("refresh");
        assertThat(c.getIssuedAt().toInstant()).isEqualTo(now);
        assertThat(c.getExpiration().toInstant()).isEqualTo(now.plusSeconds(REFRESH_TTL_DAYS * 86400L));
    }

    @Test
    void parseAndValidate_expiredToken_throwsExpired() {
        Instant past = Instant.parse("2026-04-28T00:00:00Z");
        JwtProvider issuer = new JwtProvider(props(VALID_SECRET), fixedAt(past));
        String token = issuer.createAccess(1L, "bob");

        Instant later = past.plusSeconds(ACCESS_TTL_MINUTES * 60L + 1);
        JwtProvider verifier = new JwtProvider(props(VALID_SECRET), fixedAt(later));

        assertThatThrownBy(() -> verifier.parseAndValidate(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void parseAndValidate_tamperedSignature_throwsJwtException() {
        Instant now = Instant.parse("2026-04-28T00:00:00Z");
        JwtProvider provider = new JwtProvider(props(VALID_SECRET), fixedAt(now));
        String token = provider.createAccess(1L, "bob");

        int dot = token.lastIndexOf('.');
        String tampered = token.substring(0, dot + 1) + flipLastChar(token.substring(dot + 1));

        assertThatThrownBy(() -> provider.parseAndValidate(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void constructor_secretShorterThan32Bytes_throwsIllegalState() {
        String shortSecret = "short-secret";
        assertThat(shortSecret.getBytes().length).isLessThan(32);

        assertThatThrownBy(() -> new JwtProvider(props(shortSecret), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void getTtlSeconds_returnExpectedValues() {
        JwtProvider provider = new JwtProvider(props(VALID_SECRET), Clock.systemUTC());

        assertThat(provider.getAccessTtlSec()).isEqualTo(ACCESS_TTL_MINUTES * 60L);
        assertThat(provider.getRefreshTtlSec()).isEqualTo(REFRESH_TTL_DAYS * 86400L);
    }

    private String flipLastChar(String s) {
        char last = s.charAt(s.length() - 1);
        char replacement = (last == 'A') ? 'B' : 'A';
        return s.substring(0, s.length() - 1) + replacement;
    }
}

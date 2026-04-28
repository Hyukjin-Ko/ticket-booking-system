package com.harness.ticket.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class JwtAuthFilterTest {

    private static final String VALID_SECRET = "dGVzdC1zZWNyZXQtbXVzdC1iZS1hdC1sZWFzdC0zMi1ieXRlcy1sb25n";

    private JwtProvider jwtProvider;
    private JwtAuthFilter filter;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-28T00:00:00Z"), ZoneOffset.UTC);
        JwtProperties props = new JwtProperties(VALID_SECRET, 15, 14);
        jwtProvider = new JwtProvider(props, clock);
        filter = new JwtAuthFilter(jwtProvider);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .addFilters(filter)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthorizationHeader_securityContextStaysEmpty() throws Exception {
        mockMvc.perform(get("/test/me"))
                .andExpect(status().isOk())
                .andExpect(content().string("anonymous"));
    }

    @Test
    void validAccessToken_principalIsUserId() throws Exception {
        String token = jwtProvider.createAccess(42L, "alice");

        mockMvc.perform(get("/test/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("42"));
    }

    @Test
    void expiredAccessToken_securityContextStaysEmpty() throws Exception {
        Clock past = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        JwtProvider expiredIssuer = new JwtProvider(new JwtProperties(VALID_SECRET, 15, 14), past);
        String token = expiredIssuer.createAccess(99L, "ghost");

        mockMvc.perform(get("/test/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("anonymous"));
    }

    @Test
    void refreshToken_typeMismatch_doesNotPopulateContext() throws Exception {
        String refresh = jwtProvider.createRefresh(42L);

        mockMvc.perform(get("/test/me").header("Authorization", "Bearer " + refresh))
                .andExpect(status().isOk())
                .andExpect(content().string("anonymous"));
    }

    @Test
    void wrongPrefix_isIgnored() throws Exception {
        String token = jwtProvider.createAccess(42L, "alice");

        mockMvc.perform(get("/test/me").header("Authorization", "Token " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("anonymous"));
    }

    @Test
    void shouldNotFilter_actuatorPathsAreSkipped() throws Exception {
        HttpServletRequest health = mockRequest("/actuator/health");
        HttpServletRequest info = mockRequest("/actuator/info");
        HttpServletRequest other = mockRequest("/test/me");

        assertThat(invokeShouldNotFilter(health)).isTrue();
        assertThat(invokeShouldNotFilter(info)).isTrue();
        assertThat(invokeShouldNotFilter(other)).isFalse();
    }

    private HttpServletRequest mockRequest(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI(uri);
        return req;
    }

    private boolean invokeShouldNotFilter(HttpServletRequest request) throws Exception {
        var method = JwtAuthFilter.class.getDeclaredMethod("shouldNotFilter", HttpServletRequest.class);
        method.setAccessible(true);
        return (boolean) method.invoke(filter, request);
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {
        @GetMapping("/me")
        String me() {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth.getPrincipal() == null) {
                return "anonymous";
            }
            return String.valueOf(auth.getPrincipal());
        }
    }
}

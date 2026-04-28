package com.harness.ticket.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.harness.ticket.support.IntegrationTestSupport;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;

class SecurityConfigContextTest extends IntegrationTestSupport {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Test
    void securityFilterChainBeanIsPresent() {
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).isNotEmpty();
    }

    @Test
    void jwtAuthFilterIsRegisteredInFilterChain() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/anything");

        var filters = filterChainProxy.getFilters("/anything");
        assertThat(filters).extracting(Filter::getClass).contains(JwtAuthFilter.class);
    }
}

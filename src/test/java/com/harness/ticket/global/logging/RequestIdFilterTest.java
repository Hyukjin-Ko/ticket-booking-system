package com.harness.ticket.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class RequestIdFilterTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .addFilters(new RequestIdFilter())
            .build();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void responseContainsGeneratedRequestId() throws Exception {
        MvcResult result = mockMvc.perform(get("/ping"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andReturn();

        String requestId = result.getResponse().getHeader("X-Request-Id");
        assertThat(requestId).isNotBlank();
    }

    @Test
    void mdcIsClearedAfterRequest() throws Exception {
        mockMvc.perform(get("/ping")).andExpect(status().isOk());

        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void incomingRequestIdHeaderIsPropagated() throws Exception {
        String incoming = "client-supplied-trace-id";

        mockMvc.perform(get("/ping").header("X-Request-Id", incoming))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", incoming));
    }

    @RestController
    static class TestController {
        @GetMapping("/ping")
        String ping() {
            return "pong";
        }
    }
}

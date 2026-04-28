package com.harness.ticket.global.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harness.ticket.global.response.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void businessExceptionMapsToErrorCodeStatus() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(ErrorCode.NOT_FOUND.getDefaultMessage()));
    }

    @Test
    void validationExceptionReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data.errors", hasSize(1)))
                .andExpect(jsonPath("$.data.errors[0].field").value("name"));
    }

    @Test
    void illegalStateMapsTo409() throws Exception {
        mockMvc.perform(get("/test/illegal-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void optimisticLockingMapsTo409() throws Exception {
        mockMvc.perform(get("/test/optimistic"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("RESERVATION_CONFLICT"));
    }

    @Test
    void unhandledExceptionMapsTo500WithGenericMessage() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.getDefaultMessage()))
                .andExpect(jsonPath("$.message").value(containsString("서버 오류")));
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/business")
        void business() {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody Payload payload) {
        }

        @GetMapping("/illegal-state")
        void illegalState() {
            throw new IllegalStateException("invalid state transition");
        }

        @GetMapping("/optimistic")
        void optimistic() {
            throw new ObjectOptimisticLockingFailureException("Reservation", 1L);
        }

        @GetMapping("/boom")
        void boom() {
            throw new RuntimeException("internal secret should not leak");
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    static class Payload {
        @NotBlank
        private String name;
    }
}

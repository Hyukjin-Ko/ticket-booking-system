package com.harness.ticket.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void successWrapsDataWithDefaultCodeAndMessage() {
        ApiResponse<String> response = ApiResponse.success("hello");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getCode()).isEqualTo("OK");
        assertThat(response.getMessage()).isEqualTo("성공");
        assertThat(response.getData()).isEqualTo("hello");
    }

    @Test
    void successWithCustomMessageOverridesDefault() {
        ApiResponse<Integer> response = ApiResponse.success(42, "조회 완료");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getCode()).isEqualTo("OK");
        assertThat(response.getMessage()).isEqualTo("조회 완료");
        assertThat(response.getData()).isEqualTo(42);
    }

    @Test
    void errorMapsErrorCodeFields() {
        ApiResponse<Object> response = ApiResponse.error(ErrorCode.INVALID_REQUEST);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getMessage()).isEqualTo(ErrorCode.INVALID_REQUEST.getDefaultMessage());
        assertThat(response.getData()).isNull();
    }

    @Test
    void errorWithDataIncludesData() {
        ApiResponse<String> response = ApiResponse.error(ErrorCode.CONFLICT, "extra");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo("CONFLICT");
        assertThat(response.getData()).isEqualTo("extra");
    }

    @Test
    void nullDataIsOmittedFromJson() throws Exception {
        ApiResponse<Object> response = ApiResponse.error(ErrorCode.NOT_FOUND);

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).doesNotContain("\"data\"");
        assertThat(json).contains("\"success\":false");
        assertThat(json).contains("\"code\":\"NOT_FOUND\"");
    }
}

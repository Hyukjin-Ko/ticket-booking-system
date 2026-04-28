package com.harness.ticket.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

    @Test
    void invalidRequestIs400() {
        assertThat(ErrorCode.INVALID_REQUEST.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.INVALID_REQUEST.getCode()).isEqualTo("INVALID_REQUEST");
        assertThat(ErrorCode.INVALID_REQUEST.getDefaultMessage()).isEqualTo("요청이 올바르지 않습니다");
    }

    @Test
    void unauthorizedIs401() {
        assertThat(ErrorCode.UNAUTHORIZED.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.UNAUTHORIZED.getCode()).isEqualTo("UNAUTHORIZED");
        assertThat(ErrorCode.UNAUTHORIZED.getDefaultMessage()).isEqualTo("인증이 필요합니다");
    }

    @Test
    void forbiddenIs403() {
        assertThat(ErrorCode.FORBIDDEN.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.FORBIDDEN.getCode()).isEqualTo("FORBIDDEN");
        assertThat(ErrorCode.FORBIDDEN.getDefaultMessage()).isEqualTo("권한이 없습니다");
    }

    @Test
    void notFoundIs404() {
        assertThat(ErrorCode.NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.NOT_FOUND.getCode()).isEqualTo("NOT_FOUND");
        assertThat(ErrorCode.NOT_FOUND.getDefaultMessage()).isEqualTo("리소스를 찾을 수 없습니다");
    }

    @Test
    void conflictIs409() {
        assertThat(ErrorCode.CONFLICT.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CONFLICT.getCode()).isEqualTo("CONFLICT");
        assertThat(ErrorCode.CONFLICT.getDefaultMessage()).isEqualTo("요청이 충돌합니다");
    }

    @Test
    void internalErrorIs500() {
        assertThat(ErrorCode.INTERNAL_ERROR.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ErrorCode.INTERNAL_ERROR.getCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(ErrorCode.INTERNAL_ERROR.getDefaultMessage()).isEqualTo("서버 오류가 발생했습니다");
    }

    @Test
    void allErrorCodesHaveNonNullFields() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(errorCode.getHttpStatus()).isNotNull();
            assertThat(errorCode.getCode()).isNotBlank();
            assertThat(errorCode.getDefaultMessage()).isNotBlank();
        }
    }
}

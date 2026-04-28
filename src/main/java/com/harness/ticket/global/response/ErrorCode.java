package com.harness.ticket.global.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청이 올바르지 않습니다"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 토큰입니다"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "토큰이 만료되었습니다"),
    REUSE_DETECTED(HttpStatus.UNAUTHORIZED, "REUSE_DETECTED", "재사용된 refresh 토큰이 감지되어 재로그인이 필요합니다"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "아이디 또는 비밀번호가 일치하지 않습니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "리소스를 찾을 수 없습니다"),
    CONFLICT(HttpStatus.CONFLICT, "CONFLICT", "요청이 충돌합니다"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "USERNAME_TAKEN", "이미 사용 중인 아이디입니다"),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "SEAT_NOT_FOUND", "좌석을 찾을 수 없습니다"),
    SEAT_ALREADY_HELD(HttpStatus.CONFLICT, "SEAT_ALREADY_HELD", "이미 선점된 좌석입니다"),
    SEAT_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "SEAT_LIMIT_EXCEEDED", "1인당 4좌석까지만 선점할 수 있습니다"),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "예약을 찾을 수 없습니다"),
    RESERVATION_CONFLICT(HttpStatus.CONFLICT, "RESERVATION_CONFLICT", "동시 처리 충돌이 발생했습니다"),
    INVALID_RESERVATION_STATE(HttpStatus.CONFLICT, "INVALID_RESERVATION_STATE", "예약 상태에서 허용되지 않는 작업입니다"),
    PAYMENT_FAILED(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_FAILED", "결제에 실패했습니다"),
    EXPIRED_RESERVATION(HttpStatus.GONE, "EXPIRED_RESERVATION", "선점 시간이 만료되었습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 오류가 발생했습니다");

    private final HttpStatus httpStatus;
    private final String code;
    private final String defaultMessage;
}

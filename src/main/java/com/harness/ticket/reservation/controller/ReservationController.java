package com.harness.ticket.reservation.controller;

import com.harness.ticket.global.response.ApiResponse;
import com.harness.ticket.reservation.dto.ReservationRequest;
import com.harness.ticket.reservation.dto.ReservationResponse;
import com.harness.ticket.reservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    public ResponseEntity<ApiResponse<ReservationResponse>> reserve(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody ReservationRequest req) {
        ReservationResponse res = reservationService.reserve(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(res, "좌석이 선점되었습니다"));
    }

    @PostMapping("/{id}/pay")
    public ApiResponse<ReservationResponse> pay(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id,
            @RequestHeader(value = "X-Mock-Pay-Result", required = false) String mockHeader) {
        ReservationResponse res = reservationService.pay(userId, id, mockHeader);
        return ApiResponse.success(res, "결제가 완료되었습니다");
    }
}

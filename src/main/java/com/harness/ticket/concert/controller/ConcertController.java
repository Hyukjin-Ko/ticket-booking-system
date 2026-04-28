package com.harness.ticket.concert.controller;

import com.harness.ticket.concert.dto.ConcertResponse;
import com.harness.ticket.concert.dto.SeatResponse;
import com.harness.ticket.concert.service.ConcertQueryService;
import com.harness.ticket.global.exception.BusinessException;
import com.harness.ticket.global.response.ApiResponse;
import com.harness.ticket.global.response.ErrorCode;
import com.harness.ticket.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/concerts")
@RequiredArgsConstructor
public class ConcertController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ConcertQueryService concertQueryService;

    @GetMapping
    public ApiResponse<PageResponse<ConcertResponse>> list(
            @PageableDefault(size = 20) Pageable pageable) {
        validateSize(pageable);
        return ApiResponse.success(concertQueryService.findAll(pageable));
    }

    @GetMapping("/{concertId}/seats")
    public ApiResponse<PageResponse<SeatResponse>> seats(
            @PathVariable Long concertId,
            @PageableDefault(size = 20) Pageable pageable) {
        validateSize(pageable);
        return ApiResponse.success(concertQueryService.findSeats(concertId, pageable));
    }

    private void validateSize(Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "페이지 크기는 100을 넘을 수 없습니다");
        }
    }
}

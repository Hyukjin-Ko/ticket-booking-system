package com.harness.ticket.queue.controller;

import com.harness.ticket.global.response.ApiResponse;
import com.harness.ticket.queue.dto.QueueStatusResponse;
import com.harness.ticket.queue.service.QueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    @PostMapping("/{showId}/enter")
    public ApiResponse<QueueStatusResponse> enter(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long showId) {
        return ApiResponse.success(queueService.enter(showId, userId), "대기열에 입장했습니다");
    }

    @GetMapping("/{showId}/me")
    public ApiResponse<QueueStatusResponse> me(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long showId) {
        return ApiResponse.success(queueService.getMyStatus(showId, userId));
    }
}

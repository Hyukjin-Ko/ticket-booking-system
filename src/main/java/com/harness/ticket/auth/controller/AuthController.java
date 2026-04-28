package com.harness.ticket.auth.controller;

import com.harness.ticket.auth.dto.LoginRequest;
import com.harness.ticket.auth.dto.LoginResponse;
import com.harness.ticket.auth.dto.RefreshRequest;
import com.harness.ticket.auth.dto.RefreshResponse;
import com.harness.ticket.auth.dto.SignupRequest;
import com.harness.ticket.auth.dto.SignupResponse;
import com.harness.ticket.auth.service.AuthService;
import com.harness.ticket.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequest req) {
        SignupResponse res = authService.signup(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(res, "회원가입이 완료되었습니다"));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.success(authService.login(req), "로그인 되었습니다");
    }

    @PostMapping("/refresh")
    public ApiResponse<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ApiResponse.success(authService.refresh(req), "토큰이 갱신되었습니다");
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal Long userId) {
        authService.logout(userId);
        return ApiResponse.success(null, "로그아웃이 되었습니다");
    }
}

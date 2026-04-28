package com.harness.ticket.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank
        @Size(min = 4, max = 50)
        @Pattern(regexp = "^[가-힣A-Za-z0-9]+$", message = "한글, 영문, 숫자만 사용 가능합니다")
        String username,

        @NotBlank
        @Size(min = 8, max = 50)
        @Pattern(regexp = "^[A-Za-z0-9]+$", message = "비밀번호는 영문/숫자만 가능합니다")
        String password
) {}

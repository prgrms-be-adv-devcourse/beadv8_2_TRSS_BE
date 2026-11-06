package com.backend.boundedContext.member.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 판매자 입점 신청 요청. 번호류는 하이픈이 있어도 되고, 저장할 때 숫자만 남긴다.
 */
public record SellerApplyRequest(
        @Schema(example = "팔레트 뷰티")
        @NotBlank @Size(max = 100)
        String businessName,

        @Schema(example = "123-45-67890")
        @NotBlank
        @Pattern(regexp = "^\\d{3}-?\\d{2}-?\\d{5}$", message = "사업자등록번호는 숫자 10자리입니다.")
        String businessNumber,

        @Schema(example = "홍길동")
        @NotBlank @Size(max = 50)
        String representativeName,

        @Schema(example = "010-1234-5678")
        @NotBlank
        @Pattern(regexp = "^01\\d-?\\d{3,4}-?\\d{4}$", message = "휴대폰 번호 형식이 올바르지 않습니다.")
        String phoneNumber,

        @Schema(example = "국민은행")
        @NotBlank @Size(max = 30)
        String bankName,

        @Schema(example = "123456-01-123456")
        @NotBlank
        @Pattern(regexp = "^\\d[\\d-]{6,28}\\d$", message = "계좌번호는 숫자와 하이픈을 포함하여 8~30자입니다.")
        String accountNumber,

        @Schema(example = "홍길동")
        @NotBlank @Size(max = 50)
        String accountHolder
) {
}

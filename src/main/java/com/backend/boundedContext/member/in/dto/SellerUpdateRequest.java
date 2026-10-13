package com.backend.boundedContext.member.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 판매자 정보 수정. 바꿀 값만 보낸다. 보내지 않은(null) 값은 그대로 둔다.
 * 정산 계좌는 은행·계좌번호·예금주를 함께 보내야 한다(일부만 오면 400). 형식은 입점 신청과 같다.
 */
public record SellerUpdateRequest(
        @Schema(example = "팔레트 뷰티 2호점")
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "상호는 공백일 수 없습니다.")
        String businessName,

        @Schema(example = "010-9876-5432")
        @Pattern(regexp = "^01\\d-?\\d{3,4}-?\\d{4}$", message = "휴대폰 번호 형식이 올바르지 않습니다.")
        String phoneNumber,

        @Schema(example = "신한은행")
        @Size(max = 30) @Pattern(regexp = ".*\\S.*", message = "은행명은 공백일 수 없습니다.")
        String bankName,

        @Schema(example = "110-123-456789")
        @Pattern(regexp = "^\\d[\\d-]{6,28}\\d$", message = "계좌번호는 숫자와 하이픈을 포함하여 8~30자입니다.")
        String accountNumber,

        @Schema(example = "홍길동")
        @Size(max = 50) @Pattern(regexp = ".*\\S.*", message = "예금주는 공백일 수 없습니다.")
        String accountHolder
) {

    // 정산 계좌 칸 중 하나라도 보냈으면 계좌를 바꾸려는 요청 (셋이 다 있는지는 Seller.changeAccount가 확인)
    public boolean hasAccount() {
        return bankName != null || accountNumber != null || accountHolder != null;
    }
}

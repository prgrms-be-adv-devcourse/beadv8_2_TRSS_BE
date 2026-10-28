package com.backend.boundedContext.member.app.dto;

import java.time.LocalDateTime;

import com.backend.boundedContext.member.domain.Seller;
import com.backend.shared.member.dto.SellerStatus;

// 관리자 판매자 목록 항목 (신청일, 상호명, 사업자번호, 신청자 이메일, 상태)
public record AdminSellerDto(
        Long sellerId,
        LocalDateTime appliedAt,
        String businessName,
        String businessNumber,  // 반려·본인 철회 뒤에는 비어 있다
        String email,
        SellerStatus status
) {
    public static AdminSellerDto from(Seller seller) {
        return new AdminSellerDto(
                seller.getId(),
                seller.getAppliedAt(),
                seller.getBusinessName(),
                seller.getBusinessNumber(),
                seller.getMember().getEmail(),
                seller.getStatus()
        );
    }
}

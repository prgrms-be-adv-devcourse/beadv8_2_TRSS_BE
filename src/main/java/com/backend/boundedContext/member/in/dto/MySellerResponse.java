package com.backend.boundedContext.member.in.dto;

import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.shared.member.dto.SellerStatus;

import java.time.LocalDateTime;

// 내 판매자 정보·신청 상태. 계좌번호는 끝 4자리만, 반려·본인 철회 뒤에는 사업자번호가 비어 있다
public record MySellerResponse(
        Long sellerId,
        SellerStatus status,
        String businessName,
        String businessNumber,
        String representativeName,
        String phoneNumber,
        String bankName,
        String accountNumber,
        String accountHolder,
        String reason,                  // 가장 최근 상태 변경 사유 (반려·철회 요청·철회 거절·제재)
        LocalDateTime appliedAt,
        LocalDateTime approvedAt,       // 승인 전·반려·재신청 뒤에는 null
        long unsettledAmount            // 아직 지급되지 않은 정산 금액. 0일 때만 철회 요청 가능
) {
    public static MySellerResponse from(Seller seller, SellerStatusHistory latest, long unsettledAmount) {
        return new MySellerResponse(
                seller.getId(),
                seller.getStatus(),
                seller.getBusinessName(),
                seller.getBusinessNumber(),
                seller.getRepresentativeName(),
                seller.getPhoneNumber(),
                seller.getBankName(),
                seller.getMaskedAccountNumber(),
                seller.getAccountHolder(),
                latest == null ? null : latest.getReason(),
                seller.getAppliedAt(),
                seller.getApprovedAt(),
                unsettledAmount
        );
    }
}

package com.backend.boundedContext.member.in.dto;

import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.WithdrawType;
import com.backend.shared.member.dto.SellerStatus;

import java.time.LocalDateTime;

// 내 판매자 정보·신청 상태. 계좌번호는 끝 4자리만
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
        String rejectedReason,
        WithdrawType withdrawType,
        String withdrawReason,
        LocalDateTime appliedAt,
        LocalDateTime approvedAt,
        LocalDateTime withdrawRequestedAt,
        LocalDateTime withdrawnAt
) {
    public static MySellerResponse from(Seller seller) {
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
                seller.getRejectedReason(),
                seller.getWithdrawType(),
                seller.getWithdrawReason(),
                seller.getAppliedAt(),
                seller.getApprovedAt(),
                seller.getWithdrawRequestedAt(),
                seller.getWithdrawnAt()
        );
    }
}

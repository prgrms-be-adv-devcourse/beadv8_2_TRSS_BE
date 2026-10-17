package com.backend.boundedContext.member.in.dto;

import com.backend.boundedContext.member.domain.Seller;
import com.backend.shared.member.dto.SellerStatus;

import java.time.LocalDateTime;

public record SellerApplyResponse(
        Long sellerId,
        SellerStatus status,
        LocalDateTime appliedAt
) {
    public static SellerApplyResponse from(Seller seller) {
        return new SellerApplyResponse(seller.getId(), seller.getStatus(), seller.getAppliedAt());
    }
}

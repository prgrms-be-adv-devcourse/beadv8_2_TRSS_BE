package com.backend.shared.member.dto;

public record SellerDto(
        Long sellerId,
        Long memberId,
        String businessName,
        SellerStatus status
) {

    public boolean canSell() {
        return status == SellerStatus.APPROVED;
    }
}

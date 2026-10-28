package com.backend.boundedContext.member.app.dto;

import java.util.ArrayList;
import java.util.List;

import com.backend.boundedContext.member.domain.WithdrawalBlockReason;

// 탈퇴 조건 조회 결과. reasons가 비어 있으면 탈퇴할 수 있음
public record WithdrawalCheckDto(
        boolean withdrawable,
        boolean hasActiveOrders,
        boolean hasActiveSales,
        long unsettledAmount,
        long balance,
        boolean hasPendingTopups,
        List<WithdrawalBlockReason> reasons
) {
    public static WithdrawalCheckDto of(boolean hasActiveOrders, boolean hasActiveSales, long unsettledAmount, long balance, boolean hasPendingTopups, boolean sellerActive) {
        List<WithdrawalBlockReason> reasons = new ArrayList<>();
        if (hasActiveOrders) reasons.add(WithdrawalBlockReason.ACTIVE_ORDERS);
        if (hasActiveSales) reasons.add(WithdrawalBlockReason.ACTIVE_SALES);
        if (unsettledAmount > 0) reasons.add(WithdrawalBlockReason.UNSETTLED_AMOUNT);
        if (balance > 0) reasons.add(WithdrawalBlockReason.BALANCE_REMAINING);
        if (hasPendingTopups) reasons.add(WithdrawalBlockReason.PENDING_TOPUPS);
        if (sellerActive) reasons.add(WithdrawalBlockReason.SELLER_ACTIVE);

        return new WithdrawalCheckDto(reasons.isEmpty(), hasActiveOrders, hasActiveSales, unsettledAmount, balance, hasPendingTopups, List.copyOf(reasons));
    }
}

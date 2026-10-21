package com.backend.boundedContext.payment.out;

import java.time.LocalDate;

import org.springframework.data.jpa.domain.Specification;

import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;

// 내 예치금 거래 내역 검색 조건. 조건이 없으면(null) 걸지 않음
public final class WalletTransactionSpecifications {

    private WalletTransactionSpecifications() {
    }

    public static Specification<WalletTransaction> walletEquals(Long walletId) {
        return (root, query, cb) -> cb.equal(root.get("wallet").get("id"), walletId);
    }

    public static Specification<WalletTransaction> typeEquals(WalletTransactionType type) {
        if (type == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    // 거래일이 from 이후 (from 당일 포함)
    public static Specification<WalletTransaction> createdFrom(LocalDate from) {
        if (from == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay());
    }

    // 거래일이 to 이전 (to 당일 포함)
    public static Specification<WalletTransaction> createdTo(LocalDate to) {
        if (to == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay());
    }
}

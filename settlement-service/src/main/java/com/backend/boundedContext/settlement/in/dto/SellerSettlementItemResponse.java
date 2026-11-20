package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;

import java.time.LocalDateTime;

/**
 * 판매자 정산서 상세의 품목별 내역 1건 (정산서에 묶인 정산 후보)
 * settlementMonth는 품목의 원래 정산월(구매 확정월)이고, 정산서의 정산월과 다르면 carriedOver=true(이월된 품목)
 * productName·optionName은 주문할 때의 이름(이름이 실리기 전 후보는 null), salePrice는 상품 쿠폰 할인 뒤 금액
 */
public record SellerSettlementItemResponse(
        Long orderId,
        Long orderItemId,
        String productName,
        String optionName,
        long salePrice,
        long productCouponDiscount,
        int feeRate,
        long fee,
        long payoutAmount,
        LocalDateTime confirmedAt,
        String settlementMonth,
        boolean carriedOver
) {
    public static SellerSettlementItemResponse of(SettlementCandidate c, String settlementMonthOfStatement) {
        return new SellerSettlementItemResponse(c.getOrderId(), c.getOrderItemId(), c.getProductName(),
                c.getOptionName(), c.getSalePrice(), c.getProductCouponDiscount(), c.getFeeRate(),
                c.getFee(), c.getPayoutAmount(), c.getConfirmedAt(), c.getSettlementMonth(),
                !c.getSettlementMonth().equals(settlementMonthOfStatement));
    }
}
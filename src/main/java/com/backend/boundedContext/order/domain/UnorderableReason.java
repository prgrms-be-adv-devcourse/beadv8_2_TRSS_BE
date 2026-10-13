package com.backend.boundedContext.order.domain;

import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.ProductUnsaleableReason;

/**
 * 장바구니 항목 주문 불가사유
 */
public enum UnorderableReason {
    PRODUCT_DELETED,     // 상품이 삭제됐거나 존재하지 않음
    OPTION_DELETED,      // 옵션이 삭제됨
    PRODUCT_STOPPED,     // 판매중지
    CATEGORY_INACTIVE,   // 카테고리 비활성
    SOLD_OUT,            // 품절 (옵션 재고 0)
    STOCK_INSUFFICIENT,  // 재고가 담은 수량보다 적음
    SELF_PRODUCT,        // 본인이 판매하는 상품
    IN_PENDING_ORDER;    // 결제 대기 중인 주문에 포함

    /**
     * 주문할 수 없는 사유를 판단한다.
     *
     * @param option         옵션 현재 정보 (존재하지 않으면 null)
     * @param quantity       장바구니에 담은 수량
     * @param selfProduct    본인이 판매하는 상품인지
     * @param inPendingOrder 결제 대기 주문에 포함됐는지
     */
    public static UnorderableReason resolve(OptionSnapshotDto option, int quantity, boolean selfProduct, boolean inPendingOrder) {
        if (option == null) return PRODUCT_DELETED;
        if (!option.saleable()) return fromUnsaleable(option.unsaleableReason());
        if (option.stock() == 0) return SOLD_OUT;
        if (option.stock() < quantity) return STOCK_INSUFFICIENT;
        if (selfProduct) return SELF_PRODUCT;
        if (inPendingOrder) return IN_PENDING_ORDER;

        return null;
    }

    // 상품 모듈의 판매 불가 사유를 주문 쪽 사유로 옮긴다
    private static UnorderableReason fromUnsaleable(ProductUnsaleableReason reason) {
        if (reason == null) {
            throw new IllegalStateException("판매 불가 옵션에 사유가 없습니다.");
        }

        return switch (reason) {
            case PRODUCT_DELETED -> PRODUCT_DELETED;
            case OPTION_DELETED -> OPTION_DELETED;
            case PRODUCT_STOPPED -> PRODUCT_STOPPED;
            case CATEGORY_INACTIVE -> CATEGORY_INACTIVE;
        };
    }
}

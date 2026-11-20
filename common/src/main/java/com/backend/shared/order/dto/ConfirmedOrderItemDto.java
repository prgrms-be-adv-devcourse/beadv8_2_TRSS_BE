package com.backend.shared.order.dto;

/**
 * 구매확정 주문 항목.
 *
 * @param lineAmount            할인 전 항목 금액(단가 × 수량)
 * @param productCouponDiscount 항목 금액에서 빠진 상품 쿠폰 할인액(없으면 0).
 *                              이 필드가 없던 이전 메시지도 읽을 수 있게 Long으로 받고, 값이 없으면 0으로 본다.
 *                              (long으로 하면 값이 빠진 JSON을 읽을 때 오류가 난다)
 *                              장바구니 쿠폰 할인은 결제 시 플랫폼 지원금으로 홀딩을 보전하므로 넣지 않는다.
 * @param productName           주문할 때의 상품명 스냅샷(주문 항목 product_name). 정산 내역·엑셀 표시용.
 *                              이 필드가 없던 이전 메시지는 null로 읽힌다.
 * @param optionName            주문할 때의 옵션명 스냅샷(주문 항목 option_name). productName과 같이 이전 메시지는 null.
 */
public record ConfirmedOrderItemDto(Long orderItemId, Long productId, Long sellerId,
                                    long unitPrice, int quantity, long lineAmount,
                                    Long productCouponDiscount, String productName, String optionName) {

    public ConfirmedOrderItemDto {
        if (productCouponDiscount == null) {
            productCouponDiscount = 0L;
        }
    }

    /** 상품 쿠폰 할인이 없고 이름이 없는 항목(할인액 0, 이름 null) */
    public ConfirmedOrderItemDto(Long orderItemId, Long productId, Long sellerId,
                                 long unitPrice, int quantity, long lineAmount) {
        this(orderItemId, productId, sellerId, unitPrice, quantity, lineAmount, 0L);
    }

    /** 이름이 없는 항목(이름 null) */
    public ConfirmedOrderItemDto(Long orderItemId, Long productId, Long sellerId,
                                 long unitPrice, int quantity, long lineAmount, Long productCouponDiscount) {
        this(orderItemId, productId, sellerId, unitPrice, quantity, lineAmount, productCouponDiscount, null, null);
    }
}

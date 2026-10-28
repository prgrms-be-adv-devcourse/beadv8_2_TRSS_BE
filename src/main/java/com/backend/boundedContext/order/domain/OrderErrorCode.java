package com.backend.boundedContext.order.domain;

import com.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 주문 컨텍스트(장바구니·주문) 오류 코드
 */
@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum OrderErrorCode implements ErrorCode {

    // 장바구니
    CART_ITEM_NOT_FOUND(404, "장바구니 항목을 찾을 수 없습니다."),
    CART_QUANTITY_INVALID(400, "장바구니 수량은 1개 이상, 최대 수량 이하로만 담을 수 있습니다."),
    SELF_PURCHASE_NOT_ALLOWED(400, "본인이 판매하는 상품은 구매할 수 없습니다."),
    CART_ITEM_IN_PENDING_ORDER(409, "결제 대기 중인 주문에 포함된 상품입니다."),

    // 상품 관련
    PRODUCT_NOT_FOUND(404, "상품을 찾을 수 없습니다."),
    PRODUCT_NOT_ON_SALE(409, "판매 중인 상품이 아닙니다."),
    STOCK_EXCEEDED(400, "재고보다 많은 수량을 담을 수 없습니다."),

    // 주문
    ORDER_NOT_FOUND(404, "주문을 찾을 수 없습니다."),
    IDEMPOTENCY_KEY_INVALID(400, "Idempotency-Key는 1자 이상 64자 이하로 보내 주세요."),
    PRICE_CHANGED(409, "상품 가격이 변경되었습니다. 금액을 다시 확인해 주세요."),
    ORDER_STATE_INVALID(409, "현재 주문 상태에서는 처리할 수 없습니다."),
    ORDER_EXPIRED(409, "결제 가능 시간이 지난 주문입니다."),
    RETURN_ALREADY_REQUESTED(409, "이미 반품을 요청한 주문입니다."),
    ORDER_ITEM_EMPTY(400, "주문할 상품을 선택해 주세요."),
    ORDER_OPTION_DUPLICATED(400, "한 주문에 같은 옵션을 두 번 담을 수 없습니다."),
    ORDER_QUANTITY_INVALID(400, "주문 수량은 1개 이상이어야 합니다."),
    ORDER_PERIOD_INVALID(400, "조회 시작일은 종료일보다 늦을 수 없습니다."),
    ORDER_STATUS_INVALID(400, "조회할 수 없는 주문 상태입니다."),
    ORDER_NOT_CANCELABLE(409, "결제 전 주문만 취소할 수 있습니다."),
    ORDER_NOT_CONFIRMABLE(409, "결제 완료된 주문만 구매 확정할 수 있습니다."),

    // 결제
    INSUFFICIENT_BALANCE(402, "예치금 잔액이 부족합니다.");

    private final int status;
    private final String msg;
}

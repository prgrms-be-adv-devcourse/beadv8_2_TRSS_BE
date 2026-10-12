package com.backend.boundedContext.order.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 주문 컨텍스트 정책 값
 */
@Component
@Getter
public class OrderPolicy {
    // 주문 생성 후 결제 제한 시간(분)
    private final int expireMinutes;

    // 결제 후 자동 구매 확정까지 걸리는 일수
    private final int autoConfirmDays;

    // 장바구니 항목 하나에 담을 수 있는 최대 수량
    private final int cartMaxQuantity;

    public OrderPolicy(
            @Value("${custom.order.expireMinutes}") int expireMinutes,
            @Value("${custom.order.autoConfirmDays}") int autoConfirmDays,
            @Value("${custom.order.cartMaxQuantity}") int cartMaxQuantity
    ) {
        this.expireMinutes = expireMinutes;
        this.autoConfirmDays = autoConfirmDays;
        this.cartMaxQuantity = cartMaxQuantity;
    }
}

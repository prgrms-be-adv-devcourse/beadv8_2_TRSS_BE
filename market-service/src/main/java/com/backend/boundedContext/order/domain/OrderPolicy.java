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

    // 주문 스케줄러(자동 구매 확정·결제 만료)가 한 번에 조회하는 주문 수
    private final int schedulerBatchSize;

    // 결제 만료 시각 후 만료 취소까지 기다리는 시간(분) - 만료 직전에 시작된 결제가 끝날 여유
    private final int expireGraceMinutes;

    public OrderPolicy(
            @Value("${custom.order.expireMinutes}") int expireMinutes,
            @Value("${custom.order.autoConfirmDays}") int autoConfirmDays,
            @Value("${custom.order.cartMaxQuantity}") int cartMaxQuantity,
            @Value("${custom.order.schedulerBatchSize}") int schedulerBatchSize,
            @Value("${custom.order.expireGraceMinutes}") int expireGraceMinutes
    ) {
        this.expireMinutes = expireMinutes;
        this.autoConfirmDays = autoConfirmDays;
        this.cartMaxQuantity = cartMaxQuantity;
        this.schedulerBatchSize = schedulerBatchSize;
        this.expireGraceMinutes = expireGraceMinutes;
    }
}

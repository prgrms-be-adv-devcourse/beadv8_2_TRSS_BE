package com.backend.boundedContext.payment.app.dto;

import java.util.Map;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.out.pg.PgCheckout;

/**
 * 충전 준비 결과
 * - 토스: orderId·amount를 결제창 요청(SDK)에 그대로 사용. checkout은 null
 * - 헥토: checkout.params를 checkout.url 결제창에 그대로 보냄 (금액 암호화·해시는 서버가 만듦)
 */
public record TopupPrepareDto(
        Long topupId,
        String orderId,
        long amount,
        PgProvider pgProvider,
        Checkout checkout
) {
    public static TopupPrepareDto of(Topup topup, PgCheckout checkout) {
        return new TopupPrepareDto(topup.getId(), topup.getOrderId(), topup.getAmount(), topup.getPgProvider(),
                checkout != null ? new Checkout(checkout.url(), checkout.params()) : null);
    }

    // 결제창 주소와 요청 값
    public record Checkout(
            String url,
            Map<String, String> params
    ) {}
}

package com.backend.boundedContext.payment.out.pg;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.Wallet;

class PgPaymentRefTest {

    @Test
    @DisplayName("충전 건의 주문번호·결제키·금액·결제 요청 시각·승인 요청 시각으로 PG 조회 정보를 만든다")
    void from() {
        LocalDateTime requestedAt = LocalDateTime.of(2026, 11, 15, 10, 0);
        Topup topup = Topup.create(1L, Wallet.create(1L), 20_000, PgProvider.TOSS, requestedAt);
        topup.markProcessing("pk_1", requestedAt.plusMinutes(3));

        PgPaymentRef ref = PgPaymentRef.from(topup);

        assertThat(ref).isEqualTo(new PgPaymentRef(topup.getOrderId(), "pk_1", 20_000, requestedAt, requestedAt.plusMinutes(3)));
    }
}

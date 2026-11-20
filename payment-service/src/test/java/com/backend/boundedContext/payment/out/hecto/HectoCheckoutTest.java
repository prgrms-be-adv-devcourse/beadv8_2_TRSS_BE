package com.backend.boundedContext.payment.out.hecto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.out.pg.PgCheckout;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;

class HectoCheckoutTest {

    private static final String ORDER_ID = "TOPUP-123e4567-e89b-12d3-a456-426614174000";
    private static final String WINDOW_URL = "https://tbnpg.settlebank.co.kr/card/main.do";

    private final HectoCrypto crypto = new HectoCrypto("0123456789abcdef0123456789abcdef", "TEST_HASH_KEY");
    private final HectoCheckout checkout = new HectoCheckout(crypto, "test_mcht", WINDOW_URL, "https://palette.example");

    @Test
    @DisplayName("결제 요청 시각을 요청 일시로, 금액은 암호화하고 해시를 붙여 결제창 요청 값을 만든다")
    void create() {
        PgPaymentRef payment = new PgPaymentRef(ORDER_ID, null, 20_000, LocalDateTime.of(2026, 11, 15, 9, 5, 7), null);

        PgCheckout result = checkout.create(payment);

        assertThat(result.url()).isEqualTo(WINDOW_URL);
        assertThat(result.params())
                .containsEntry("mchtId", "test_mcht")
                .containsEntry("method", "card")
                .containsEntry("trdDt", "20261115")
                .containsEntry("trdTm", "090507")
                .containsEntry("mchtTrdNo", ORDER_ID)
                .containsEntry("trdAmt", crypto.encryptAmount(20_000))
                .containsEntry("pktHash", crypto.hash("test_mcht", "card", ORDER_ID, "20261115", "090507", "20000"))
                .containsEntry("nextUrl", "https://palette.example/api/v1/topups/hecto/next")
                .containsEntry("cancUrl", "https://palette.example/api/v1/topups/hecto/canc")
                .containsEntry("notiUrl", "https://palette.example/api/v1/topups/hecto/noti")
                .containsKeys("mchtName", "mchtEName", "pmtPrdtNm");
    }
}

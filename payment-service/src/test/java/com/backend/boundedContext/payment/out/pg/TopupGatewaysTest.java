package com.backend.boundedContext.payment.out.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.support.TestPgCircuitBreakers;

class TopupGatewaysTest {

    @Test
    @DisplayName("충전 건의 PG로 포트를 찾고, 돌려준 포트는 그 PG로 호출을 넘긴다")
    void get() {
        TopupGatewayPort toss = port(PgProvider.TOSS);
        PgPaymentResult approved = new PgPaymentResult("pk_1", "TOPUP-1", PgPaymentStatus.APPROVED, "DONE", 10_000, 0, PgPaymentMethod.CARD, null);
        given(toss.confirm("pk_1", "TOPUP-1", 10_000)).willReturn(approved);

        TopupGatewayPort found = new TopupGateways(List.of(toss), TestPgCircuitBreakers.create()).get(PgProvider.TOSS);

        assertThat(found.provider()).isEqualTo(PgProvider.TOSS);
        assertThat(found.confirm("pk_1", "TOPUP-1", 10_000)).isEqualTo(approved);
    }

    @Test
    @DisplayName("돌려준 포트로 부른 PG 호출의 결과 모름은 서킷 브레이커에 세어진다")
    void get_recordsCircuit() {
        TopupGatewayPort toss = port(PgProvider.TOSS);
        given(toss.confirm("pk_1", "TOPUP-1", 10_000)).willThrow(new PgResultUnknownException(new RuntimeException("Read timed out")));
        PgCircuitBreakers circuitBreakers = TestPgCircuitBreakers.create();
        TopupGatewayPort found = new TopupGateways(List.of(toss), circuitBreakers).get(PgProvider.TOSS);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> found.confirm("pk_1", "TOPUP-1", 10_000)).isInstanceOf(PgResultUnknownException.class);
        }

        assertThat(circuitBreakers.isAvailable(PgProvider.TOSS)).isFalse();
    }

    @Test
    @DisplayName("연동하지 않은 PG면 IllegalStateException")
    void get_notRegistered() {
        TopupGateways gateways = new TopupGateways(List.of(), TestPgCircuitBreakers.create());

        assertThatThrownBy(() -> gateways.get(PgProvider.TOSS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("충전 준비에 쓸 수 있는 PG: 연동했고 서킷이 OPEN이 아니어야 한다")
    void isAvailable() {
        TopupGatewayPort toss = port(PgProvider.TOSS);
        given(toss.getPayment(any())).willThrow(new PgResultUnknownException(new RuntimeException("Connection reset")));
        TopupGateways gateways = new TopupGateways(List.of(toss), TestPgCircuitBreakers.create());

        assertThat(gateways.isAvailable(PgProvider.TOSS)).isTrue();
        assertThat(gateways.isAvailable(PgProvider.HECTO)).isFalse();

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> gateways.get(PgProvider.TOSS).getPayment(null)).isInstanceOf(PgResultUnknownException.class);
        }
        assertThat(gateways.isAvailable(PgProvider.TOSS)).isFalse();
    }

    @Test
    @DisplayName("연동 여부 확인: 연동한 PG면 통과하고, 아니면 IllegalStateException")
    void checkSupported() {
        TopupGateways gateways = new TopupGateways(List.of(port(PgProvider.TOSS)), TestPgCircuitBreakers.create());
        TopupGateways empty = new TopupGateways(List.of(), TestPgCircuitBreakers.create());

        gateways.checkSupported(PgProvider.TOSS);
        assertThatThrownBy(() -> empty.checkSupported(PgProvider.TOSS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("같은 PG의 포트가 두 개면 앱 시작 때 IllegalStateException")
    void duplicated() {
        assertThatThrownBy(() -> new TopupGateways(List.of(port(PgProvider.TOSS), port(PgProvider.TOSS)), TestPgCircuitBreakers.create()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static TopupGatewayPort port(PgProvider provider) {
        TopupGatewayPort port = mock(TopupGatewayPort.class);
        given(port.provider()).willReturn(provider);
        return port;
    }
}

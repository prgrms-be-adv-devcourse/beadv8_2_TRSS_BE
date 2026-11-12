package com.backend.boundedContext.payment.out.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.domain.PgProvider;

class TopupGatewaysTest {

    @Test
    @DisplayName("충전 건의 PG로 포트를 찾는다")
    void get() {
        TopupGatewayPort toss = port(PgProvider.TOSS);

        TopupGateways gateways = new TopupGateways(List.of(toss));

        assertThat(gateways.get(PgProvider.TOSS)).isSameAs(toss);
    }

    @Test
    @DisplayName("연동하지 않은 PG면 IllegalStateException")
    void get_notRegistered() {
        TopupGateways gateways = new TopupGateways(List.of());

        assertThatThrownBy(() -> gateways.get(PgProvider.TOSS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("같은 PG의 포트가 두 개면 앱 시작 때 IllegalStateException")
    void duplicated() {
        assertThatThrownBy(() -> new TopupGateways(List.of(port(PgProvider.TOSS), port(PgProvider.TOSS))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static TopupGatewayPort port(PgProvider provider) {
        TopupGatewayPort port = mock(TopupGatewayPort.class);
        given(port.provider()).willReturn(provider);
        return port;
    }
}

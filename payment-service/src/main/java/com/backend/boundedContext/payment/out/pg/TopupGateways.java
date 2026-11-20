package com.backend.boundedContext.payment.out.pg;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.PgProvider;

/**
 * PG별 TopupGatewayPort 모음. 충전 건에 기록된 PG(pgProvider)로 승인·조회할 포트를 찾음
 * 스프링이 등록된 TopupGatewayPort 구현체를 모두 넘겨줌
 * 여기서 꺼낸 포트로 PG를 부르면 결과가 서킷 브레이커에 기록됨 (호출은 막지 않음)
 */
@Component
public class TopupGateways {

    private final Map<PgProvider, TopupGatewayPort> gateways = new EnumMap<>(PgProvider.class);
    private final PgCircuitBreakers circuitBreakers;

    public TopupGateways(List<TopupGatewayPort> ports, PgCircuitBreakers circuitBreakers) {
        this.circuitBreakers = circuitBreakers;
        for (TopupGatewayPort port : ports) {
            if (gateways.putIfAbsent(port.provider(), new CircuitRecordingTopupGateway(port, circuitBreakers)) != null) {
                throw new IllegalStateException("같은 PG의 포트가 두 개 등록됐습니다: " + port.provider());
            }
        }
    }

    // 연동하지 않은 PG면 설정 문제이므로 IllegalStateException
    public TopupGatewayPort get(PgProvider provider) {
        TopupGatewayPort port = gateways.get(provider);
        if (port == null) {
            throw new IllegalStateException("연동하지 않은 PG입니다: " + provider);
        }
        return port;
    }

    // 충전 준비에 쓸 수 있는 PG인지: 연동했고 서킷이 OPEN이 아님
    public boolean isAvailable(PgProvider provider) {
        return gateways.containsKey(provider) && circuitBreakers.isAvailable(provider);
    }

    // 충전 건을 선점(Tx1)하기 전에 연동 여부만 확인. 선점 뒤에 알면 충전 건이 처리 중 상태로 남아 대사가 매 회차 실패함
    public void checkSupported(PgProvider provider) {
        get(provider);
    }
}

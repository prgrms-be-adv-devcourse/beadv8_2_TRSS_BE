package com.backend.boundedContext.payment.out.pg;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.PgProvider;

/**
 * PG별 TopupGatewayPort 모음. 충전 건에 기록된 PG(pgProvider)로 승인·조회할 포트를 찾음
 * 스프링이 등록된 TopupGatewayPort 구현체를 모두 넘겨줌
 */
@Component
public class TopupGateways {

    private final Map<PgProvider, TopupGatewayPort> gateways = new EnumMap<>(PgProvider.class);

    public TopupGateways(List<TopupGatewayPort> ports) {
        for (TopupGatewayPort port : ports) {
            if (gateways.putIfAbsent(port.provider(), port) != null) {
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
}

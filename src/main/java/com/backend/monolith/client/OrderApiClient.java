package com.backend.monolith.client;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Component;

import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderSummaryDto;
import com.backend.shared.order.out.OrderApi;

/**
 * 주문이 market-service로 옮겨 간 뒤 루트 모놀리스에 남은 임시 구현.
 * hasActiveOrders·hasActiveSales는 회원 탈퇴를 막는 검사라, false로 통과시키면 진행 중 주문이 있는 회원도 탈퇴되므로 예외를 던진다.
 * 회원 상세의 최근 주문(getRecentOrders)은 화면 표시용이라 빈 목록을 돌려준다.
 * TODO 서비스 간 연결 작업에서 market-service 내부 API를 호출하도록 교체 (회원 서비스 이동 때 그쪽으로 옮김)
 */
@Component
public class OrderApiClient implements OrderApi {

    @Override
    public int cancelPendingOrders(Collection<Long> productIds, OrderCancelType cancelType) {
        throw notConnected();
    }

    @Override
    public boolean hasActiveOrders(Long buyerId) {
        throw notConnected();
    }

    @Override
    public boolean hasActiveSales(Long sellerId) {
        throw notConnected();
    }

    @Override
    public List<OrderSummaryDto> getRecentOrders(Long buyerId, int limit) {
        return List.of();
    }

    private UnsupportedOperationException notConnected() {
        return new UnsupportedOperationException("market-service 연결 전이라 주문 API를 호출할 수 없습니다");
    }
}

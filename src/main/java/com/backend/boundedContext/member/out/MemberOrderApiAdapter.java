package com.backend.boundedContext.member.out;

import java.util.List;

import org.springframework.stereotype.Component;

import com.backend.shared.order.dto.OrderSummaryDto;
import com.backend.shared.order.out.OrderApi;

import lombok.RequiredArgsConstructor;

/**
 * 같은 앱의 OrderApi로 MemberOrderPort를 구현함 (주문 모듈 분리 전까지 사용)
 */
@Component
@RequiredArgsConstructor
public class MemberOrderApiAdapter implements MemberOrderPort {

    private final OrderApi orderApi;

    @Override
    public boolean hasActiveOrders(Long memberId) {
        return orderApi.hasActiveOrders(memberId);
    }

    @Override
    public boolean hasActiveSales(Long sellerId) {
        return orderApi.hasActiveSales(sellerId);
    }

    @Override
    public List<OrderSummaryDto> getRecentOrders(Long memberId, int limit) {
        return orderApi.getRecentOrders(memberId, limit);
    }
}

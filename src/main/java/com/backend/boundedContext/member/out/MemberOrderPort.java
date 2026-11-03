package com.backend.boundedContext.member.out;

import java.util.List;

import com.backend.shared.order.dto.OrderSummaryDto;

/**
 * 회원 컨텍스트가 쓰는 주문 기능만 담은 Port
 * 지금은 같은 앱의 OrderApi를 부르고, 주문 모듈이 분리되면 주문 내부 API를 부르는 HTTP 클라이언트로 바꿈
 */
public interface MemberOrderPort {

    // 구매자로서 진행 중인 주문이 있는지 (탈퇴 검사)
    boolean hasActiveOrders(Long memberId);

    // 판매자 상품이 들어간 진행 중인 주문이 있는지 (탈퇴 검사, 판매자 철회 승인)
    boolean hasActiveSales(Long sellerId);

    // 구매자의 최근 주문 목록, 없으면 빈 목록 (관리자 회원 상세)
    List<OrderSummaryDto> getRecentOrders(Long memberId, int limit);
}

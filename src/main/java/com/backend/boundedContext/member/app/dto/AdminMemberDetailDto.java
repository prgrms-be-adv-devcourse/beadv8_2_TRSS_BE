package com.backend.boundedContext.member.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.backend.boundedContext.member.domain.Member;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.order.dto.OrderSummaryDto;
import com.backend.shared.payment.dto.WalletSummaryDto;

// 관리자 회원 상세 (최근 주문은 주문 컨텍스트 API가 정해지면 추가)
public record AdminMemberDetailDto(
        Long memberId,
        String email,
        String name,
        MemberRole role,
        MemberStatus status,
        LocalDateTime createdAt,
        SellerStatus sellerStatus,  // 판매자 신청 이력이 없으면 null
        Wallet wallet,
        List<RecentOrder> recentOrders  // 최신순, 주문이 없으면 빈 목록
) {
    // 지갑 잔액 (사용 가능 금액, 홀딩 중 금액)
    public record Wallet(
            long balance,
            long heldAmount
    ) {}

    // 최근 주문 (주문 번호, 상태, 결제 금액, 주문 일시)
    public record RecentOrder(
            Long orderId,
            OrderStatus status,
            long totalAmount,
            LocalDateTime orderedAt
    ) {}

    public static AdminMemberDetailDto of(Member member, SellerDto seller, WalletSummaryDto wallet, List<OrderSummaryDto> orders) {
        return new AdminMemberDetailDto(
                member.getId(), member.getEmail(), member.getName(), member.getRole(), member.getStatus(), member.getCreatedAt(),
                seller == null ? null : seller.status(),
                new Wallet(wallet.balance(), wallet.heldAmount()),
                orders.stream()
                        .map(order -> new RecentOrder(order.orderId(), order.status(), order.totalAmount(), order.orderedAt()))
                        .toList()
        );
    }
}

package com.backend.boundedContext.member.app.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberType;
import com.backend.shared.member.dto.InfluencerStatus;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.order.dto.OrderSummaryDto;
import com.backend.shared.payment.dto.WalletSummaryDto;

// 관리자 회원 상세. 최근 주문은 주문을 조회하지 못하면 null, 지갑은 walletState로 있음·없음·조회 실패를 구분 (회원 정보는 그대로)
public record AdminMemberDetailDto(
        Long memberId,
        String email,
        String name,
        MemberRole role,
        MemberType memberType,  // 일반 회원·판매자(판매 중)·관리자
        MemberStatus status,
        LocalDateTime createdAt,
        SellerStatus sellerStatus,  // 판매자 신청 이력이 없으면 null
        InfluencerStatus influencerStatus,  // 인플루언서 신청 이력이 없으면 null
        WalletState walletState,        // 지갑 있음·없음·결제 조회 실패
        Wallet wallet,                  // walletState가 CREATED일 때만 있음, 그 밖에는 null
        List<RecentOrder> recentOrders  // 최신순, 주문이 없으면 빈 목록. 주문을 조회하지 못하면 null
) {
    public enum WalletState {
        CREATED,      // 지갑 있음
        NOT_CREATED,  // 지갑 없음 (가입 이벤트 처리 전·실패, 지갑을 만들지 않는 물류 담당자)
        UNAVAILABLE   // 결제를 조회하지 못해 알 수 없음
    }

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

    // wallet: 결제를 조회하지 못하면 null, 지갑이 없으면 Optional.empty()
    public static AdminMemberDetailDto of(Member member, SellerStatus sellerStatus, InfluencerStatus influencerStatus,
                                          Optional<WalletSummaryDto> wallet, List<OrderSummaryDto> orders) {
        return new AdminMemberDetailDto(
                member.getId(), member.getEmail(), member.getName(), member.getRole(),
                MemberType.of(member.getRole(), sellerStatus),
                member.getStatus(), member.getCreatedAt(),
                sellerStatus,
                influencerStatus,
                walletStateOf(wallet),
                wallet == null ? null : wallet.map(w -> new Wallet(w.balance(), w.heldAmount())).orElse(null),
                orders == null ? null : orders.stream()
                        .map(order -> new RecentOrder(order.orderId(), order.status(), order.totalAmount(), order.orderedAt()))
                        .toList()
        );
    }

    private static WalletState walletStateOf(Optional<WalletSummaryDto> wallet) {
        if (wallet == null) return WalletState.UNAVAILABLE;
        return wallet.isPresent() ? WalletState.CREATED : WalletState.NOT_CREATED;
    }
}

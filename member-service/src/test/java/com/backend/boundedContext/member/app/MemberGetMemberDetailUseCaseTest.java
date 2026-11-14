package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.app.dto.AdminMemberDetailDto;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.out.MemberOrderPort;
import com.backend.boundedContext.member.out.MemberPaymentPort;
import com.backend.boundedContext.member.out.MemberPortUnavailableException;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.ErrorCode;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.order.dto.OrderSummaryDto;
import com.backend.shared.payment.dto.WalletSummaryDto;

@ExtendWith(MockitoExtension.class)
class MemberGetMemberDetailUseCaseTest {

    @Mock
    private MemberSupport memberSupport;

    @Mock
    private MemberGetSellerUseCase memberGetSellerUseCase;

    @Mock
    private MemberPaymentPort memberPaymentPort;

    @Mock
    private MemberOrderPort memberOrderPort;

    @InjectMocks
    private MemberGetMemberDetailUseCase memberGetMemberDetailUseCase;

    // 결제가 오류로 응답했을 때 MemberInternalApiClient가 만드는 것과 같은 resultCode
    private static final ErrorCode WALLET_NOT_FOUND = new RemoteErrorCode(404, "WALLET_NOT_FOUND", "지갑을 찾을 수 없습니다.");
    private static final ErrorCode PAYMENT_BAD_REQUEST = new RemoteErrorCode(400, "INVALID_REQUEST", "잘못된 요청입니다.");

    private final Member member = Member.signUp("user@palette.com", "encoded", "홍길동");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(member, "id", 3L);
    }

    @Test
    @DisplayName("회원 기본 정보, 판매자 상태, 지갑 잔액, 최근 주문 5건을 함께 돌려준다")
    void getMemberDetail_withSeller() {
        given(memberSupport.getMember(3L)).willReturn(member);
        given(memberGetSellerUseCase.findSellerByMemberId(3L))
                .willReturn(Optional.of(new SellerDto(10L, 3L, "팔레트상점", SellerStatus.PENDING)));
        given(memberPaymentPort.getWallet(3L)).willReturn(new WalletSummaryDto(3L, 50_000L, 0L));
        given(memberOrderPort.getRecentOrders(3L, 5)).willReturn(List.of(
                new OrderSummaryDto(101L, OrderStatus.PAID, 25_000L, LocalDateTime.of(2026, 10, 12, 10, 0))));

        AdminMemberDetailDto detail = memberGetMemberDetailUseCase.getMemberDetail(3L);

        assertThat(detail.memberId()).isEqualTo(3L);
        assertThat(detail.role()).isEqualTo(MemberRole.USER);
        assertThat(detail.sellerStatus()).isEqualTo(SellerStatus.PENDING);
        assertThat(detail.wallet().balance()).isEqualTo(50_000L);
        assertThat(detail.wallet().heldAmount()).isZero();
        assertThat(detail.recentOrders()).singleElement().satisfies(order -> {
            assertThat(order.orderId()).isEqualTo(101L);
            assertThat(order.status()).isEqualTo(OrderStatus.PAID);
            assertThat(order.totalAmount()).isEqualTo(25_000L);
        });
    }

    @Test
    @DisplayName("판매자 신청 이력이 없으면 판매자 상태는 null, 주문이 없으면 빈 목록")
    void getMemberDetail_noSeller() {
        given(memberSupport.getMember(3L)).willReturn(member);
        given(memberGetSellerUseCase.findSellerByMemberId(3L)).willReturn(Optional.empty());
        given(memberPaymentPort.getWallet(3L)).willReturn(new WalletSummaryDto(3L, 0L, 0L));
        given(memberOrderPort.getRecentOrders(3L, 5)).willReturn(List.of());

        AdminMemberDetailDto detail = memberGetMemberDetailUseCase.getMemberDetail(3L);
        assertThat(detail.sellerStatus()).isNull();
        assertThat(detail.recentOrders()).isEmpty();
    }

    @Test
    @DisplayName("결제를 조회하지 못하면 지갑만 null로 비우고, 회원 정보와 최근 주문은 그대로 돌려준다")
    void getMemberDetail_paymentUnavailable() {
        given(memberSupport.getMember(3L)).willReturn(member);
        given(memberGetSellerUseCase.findSellerByMemberId(3L)).willReturn(Optional.empty());
        given(memberPaymentPort.getWallet(3L)).willThrow(new MemberPortUnavailableException("시간 초과", null));
        given(memberOrderPort.getRecentOrders(3L, 5)).willReturn(List.of());

        AdminMemberDetailDto detail = memberGetMemberDetailUseCase.getMemberDetail(3L);

        assertThat(detail.email()).isEqualTo("user@palette.com");
        assertThat(detail.wallet()).isNull();
        assertThat(detail.recentOrders()).isEmpty();
    }

    @Test
    @DisplayName("지갑이 없으면(404 WALLET_NOT_FOUND) 지갑만 null로 비우고, 회원 정보와 최근 주문은 그대로 돌려준다")
    void getMemberDetail_walletNotFound() {
        given(memberSupport.getMember(3L)).willReturn(member);
        given(memberGetSellerUseCase.findSellerByMemberId(3L)).willReturn(Optional.empty());
        given(memberPaymentPort.getWallet(3L)).willThrow(new DomainException(WALLET_NOT_FOUND));
        given(memberOrderPort.getRecentOrders(3L, 5)).willReturn(List.of());

        AdminMemberDetailDto detail = memberGetMemberDetailUseCase.getMemberDetail(3L);

        assertThat(detail.email()).isEqualTo("user@palette.com");
        assertThat(detail.wallet()).isNull();
        assertThat(detail.recentOrders()).isEmpty();
    }

    @Test
    @DisplayName("지갑 없음이 아닌 결제 오류 응답은 그대로 던진다")
    void getMemberDetail_paymentOtherError() {
        given(memberSupport.getMember(3L)).willReturn(member);
        given(memberGetSellerUseCase.findSellerByMemberId(3L)).willReturn(Optional.empty());
        given(memberPaymentPort.getWallet(3L)).willThrow(new DomainException(PAYMENT_BAD_REQUEST));

        assertThatThrownBy(() -> memberGetMemberDetailUseCase.getMemberDetail(3L))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo("400-INVALID_REQUEST");
    }

    @Test
    @DisplayName("주문을 조회하지 못하면 최근 주문만 null로 비운다 (주문이 없는 빈 목록과 구분)")
    void getMemberDetail_orderUnavailable() {
        given(memberSupport.getMember(3L)).willReturn(member);
        given(memberGetSellerUseCase.findSellerByMemberId(3L)).willReturn(Optional.empty());
        given(memberPaymentPort.getWallet(3L)).willReturn(new WalletSummaryDto(3L, 50_000L, 0L));
        given(memberOrderPort.getRecentOrders(3L, 5)).willThrow(new MemberPortUnavailableException("시간 초과", null));

        AdminMemberDetailDto detail = memberGetMemberDetailUseCase.getMemberDetail(3L);

        assertThat(detail.wallet().balance()).isEqualTo(50_000L);
        assertThat(detail.recentOrders()).isNull();
    }

    @Test
    @DisplayName("회원이 없으면 404 MEMBER_NOT_FOUND이고 다른 컨텍스트는 호출하지 않는다")
    void getMemberDetail_notFound() {
        given(memberSupport.getMember(99L)).willThrow(new DomainException(MemberErrorCode.MEMBER_NOT_FOUND));

        assertThatThrownBy(() -> memberGetMemberDetailUseCase.getMemberDetail(99L))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND.resultCode());

        verify(memberPaymentPort, never()).getWallet(anyLong());
    }

    private record RemoteErrorCode(int status, String name, String msg) implements ErrorCode {
    }
}

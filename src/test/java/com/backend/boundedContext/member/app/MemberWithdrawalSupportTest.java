package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.app.dto.WithdrawalCheckDto;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.WithdrawalBlockReason;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.payment.dto.WalletSummaryDto;
import com.backend.shared.payment.out.PaymentApi;
import com.backend.shared.settlement.out.SettlementApi;

@ExtendWith(MockitoExtension.class)
class MemberWithdrawalSupportTest {

    private static final Long MEMBER_ID = 3L;
    private static final Long SELLER_ID = 10L;

    @Mock
    private SellerRepository sellerRepository;

    @Mock
    private OrderApi orderApi;

    @Mock
    private SettlementApi settlementApi;

    @Mock
    private PaymentApi paymentApi;

    @InjectMocks
    private MemberWithdrawalSupport memberWithdrawalSupport;

    @Test
    @DisplayName("판매자가 아니고 진행 중인 주문·예치금이 없으면 탈퇴할 수 있다 (정산은 조회하지 않음)")
    void buyer_withdrawable() {
        givenBuyer(false, 0);

        WithdrawalCheckDto check = memberWithdrawalSupport.check(MEMBER_ID);

        assertThat(check.withdrawable()).isTrue();
        assertThat(check.reasons()).isEmpty();
        assertThat(check.unsettledAmount()).isZero();
        verify(settlementApi, never()).getUnsettledAmount(anyLong());
    }

    @Test
    @DisplayName("진행 중인 주문과 예치금이 남아 있으면 두 이유를 모두 돌려준다")
    void buyer_blocked() {
        givenBuyer(true, 30_000);

        WithdrawalCheckDto check = memberWithdrawalSupport.check(MEMBER_ID);

        assertThat(check.withdrawable()).isFalse();
        assertThat(check.hasActiveOrders()).isTrue();
        assertThat(check.balance()).isEqualTo(30_000);
        assertThat(check.reasons()).containsExactly(WithdrawalBlockReason.ACTIVE_ORDERS, WithdrawalBlockReason.BALANCE_REMAINING);
    }

    @Test
    @DisplayName("판매 중인 판매자는 판매자 철회가 먼저이고, 정산받지 않은 금액도 판매자 ID로 조회한다")
    void activeSeller_blocked() {
        givenSeller(SellerStatus.APPROVED, 12_000);

        WithdrawalCheckDto check = memberWithdrawalSupport.check(MEMBER_ID);

        assertThat(check.unsettledAmount()).isEqualTo(12_000);
        assertThat(check.reasons()).containsExactly(WithdrawalBlockReason.UNSETTLED_AMOUNT, WithdrawalBlockReason.SELLER_ACTIVE);
    }

    @Test
    @DisplayName("판매자 철회를 마쳤고 정산받지 않은 금액이 없으면 탈퇴할 수 있다")
    void withdrawnSeller_withdrawable() {
        givenSeller(SellerStatus.WITHDRAWN, 0);

        assertThat(memberWithdrawalSupport.check(MEMBER_ID).withdrawable()).isTrue();
    }

    private void givenBuyer(boolean hasActiveOrders, long balance) {
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(orderApi.hasActiveOrders(MEMBER_ID)).willReturn(hasActiveOrders);
        given(paymentApi.getWallet(MEMBER_ID)).willReturn(new WalletSummaryDto(MEMBER_ID, balance, 0));
    }

    private void givenSeller(SellerStatus status, long unsettledAmount) {
        Seller seller = Seller.apply(Member.seller("seller@palette.test", "encoded", "판매자"),
                "팔레트 뷰티", "1234567890", "홍길동", "01012345678", "국민은행", "12345601123456", "홍길동");
        ReflectionTestUtils.setField(seller, "id", SELLER_ID);
        ReflectionTestUtils.setField(seller, "status", status);

        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(seller));
        given(orderApi.hasActiveOrders(MEMBER_ID)).willReturn(false);
        given(settlementApi.getUnsettledAmount(SELLER_ID)).willReturn(unsettledAmount);
        given(paymentApi.getWallet(MEMBER_ID)).willReturn(new WalletSummaryDto(MEMBER_ID, 0, 0));
    }
}

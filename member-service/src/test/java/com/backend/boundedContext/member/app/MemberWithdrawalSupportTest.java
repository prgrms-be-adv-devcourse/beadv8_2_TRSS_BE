package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.WithdrawalBlockReason;
import com.backend.boundedContext.member.out.MemberOrderPort;
import com.backend.boundedContext.member.out.MemberPaymentPort;
import com.backend.boundedContext.member.out.MemberPortUnavailableException;
import com.backend.boundedContext.member.out.MemberSettlementPort;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.ErrorCode;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.payment.dto.WalletSummaryDto;

@ExtendWith(MockitoExtension.class)
class MemberWithdrawalSupportTest {

    private static final Long MEMBER_ID = 3L;
    private static final Long SELLER_ID = 10L;
    // 결제가 지갑 없음으로 응답했을 때 MemberInternalApiClient가 만드는 것과 같은 resultCode(404-WALLET_NOT_FOUND)
    private static final ErrorCode WALLET_NOT_FOUND = new RemoteErrorCode(404, "WALLET_NOT_FOUND", "지갑을 찾을 수 없습니다.");

    @Mock
    private SellerRepository sellerRepository;

    @Mock
    private MemberOrderPort memberOrderPort;

    @Mock
    private MemberSettlementPort memberSettlementPort;

    @Mock
    private MemberPaymentPort memberPaymentPort;

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
        verify(memberSettlementPort, never()).getUnsettledAmount(anyLong());
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
    @DisplayName("제재된 판매자라도 판매자 상품이 든 진행 중인 주문이 있으면 탈퇴할 수 없다 (판매자 상태와 무관하게 확인)")
    void sanctionedSeller_activeSales_blocked() {
        givenSeller(SellerStatus.SANCTIONED, 0);
        given(memberOrderPort.hasActiveSales(SELLER_ID)).willReturn(true);

        WithdrawalCheckDto check = memberWithdrawalSupport.check(MEMBER_ID);

        assertThat(check.hasActiveSales()).isTrue();
        assertThat(check.reasons()).containsExactly(WithdrawalBlockReason.ACTIVE_SALES);
    }

    @Test
    @DisplayName("결과가 정해지지 않은 충전 건이 있으면 잔액이 0이어도 탈퇴할 수 없다 (판매자가 아니면 판매는 조회하지 않음)")
    void buyer_pendingTopups_blocked() {
        givenBuyer(false, 0);
        given(memberPaymentPort.hasPendingTopups(MEMBER_ID)).willReturn(true);

        WithdrawalCheckDto check = memberWithdrawalSupport.check(MEMBER_ID);

        assertThat(check.hasPendingTopups()).isTrue();
        assertThat(check.reasons()).containsExactly(WithdrawalBlockReason.PENDING_TOPUPS);
        verify(memberOrderPort, never()).hasActiveSales(anyLong());
    }

    @Test
    @DisplayName("판매자 철회를 마쳤고 정산받지 않은 금액이 없으면 탈퇴할 수 있다")
    void withdrawnSeller_withdrawable() {
        givenSeller(SellerStatus.WITHDRAWN, 0);

        assertThat(memberWithdrawalSupport.check(MEMBER_ID).withdrawable()).isTrue();
    }

    @Test
    @DisplayName("지갑이 아직 없으면(404 WALLET_NOT_FOUND) 잔액 0으로 보고 탈퇴할 수 있다")
    void walletNotFound_balanceZero() {
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(memberOrderPort.hasActiveOrders(MEMBER_ID)).willReturn(false);
        given(memberPaymentPort.getWallet(MEMBER_ID)).willThrow(new DomainException(WALLET_NOT_FOUND));

        WithdrawalCheckDto check = memberWithdrawalSupport.check(MEMBER_ID);

        assertThat(check.balance()).isZero();
        assertThat(check.withdrawable()).isTrue();
    }

    @Test
    @DisplayName("지갑 조회가 지갑 없음이 아닌 오류로 실패하면 그 오류를 그대로 던진다")
    void walletOtherError_propagates() {
        DomainException walletError = new DomainException(MemberErrorCode.MEMBER_NOT_FOUND);
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(memberOrderPort.hasActiveOrders(MEMBER_ID)).willReturn(false);
        given(memberPaymentPort.getWallet(MEMBER_ID)).willThrow(walletError);

        assertThatThrownBy(() -> memberWithdrawalSupport.check(MEMBER_ID)).isSameAs(walletError);
    }

    @Test
    @DisplayName("주문을 조회하지 못하면 통과시키지 않고 503 WITHDRAWAL_CHECK_UNAVAILABLE")
    void orderUnavailable_rejected() {
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(memberOrderPort.hasActiveOrders(MEMBER_ID)).willThrow(unavailable());

        assertUnavailable();
    }

    @Test
    @DisplayName("판매자의 정산을 조회하지 못하면 503 WITHDRAWAL_CHECK_UNAVAILABLE")
    void settlementUnavailable_rejected() {
        Seller seller = seller(SellerStatus.WITHDRAWN);
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(seller));
        given(memberOrderPort.hasActiveOrders(MEMBER_ID)).willReturn(false);
        given(memberSettlementPort.getUnsettledAmount(SELLER_ID)).willThrow(unavailable());

        assertUnavailable();
    }

    @Test
    @DisplayName("지갑이나 충전 건을 조회하지 못하면 503 WITHDRAWAL_CHECK_UNAVAILABLE")
    void paymentUnavailable_rejected() {
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(memberOrderPort.hasActiveOrders(MEMBER_ID)).willReturn(false);
        given(memberPaymentPort.getWallet(MEMBER_ID)).willThrow(unavailable());

        assertUnavailable();
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> memberWithdrawalSupport.check(MEMBER_ID))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getResultCode())
                        .isEqualTo(MemberErrorCode.WITHDRAWAL_CHECK_UNAVAILABLE.resultCode()));
    }

    private static MemberPortUnavailableException unavailable() {
        return new MemberPortUnavailableException("시간 초과", null);
    }

    private void givenBuyer(boolean hasActiveOrders, long balance) {
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(memberOrderPort.hasActiveOrders(MEMBER_ID)).willReturn(hasActiveOrders);
        given(memberPaymentPort.getWallet(MEMBER_ID)).willReturn(new WalletSummaryDto(MEMBER_ID, balance, 0));
    }

    private void givenSeller(SellerStatus status, long unsettledAmount) {
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(seller(status)));
        given(memberOrderPort.hasActiveOrders(MEMBER_ID)).willReturn(false);
        given(memberSettlementPort.getUnsettledAmount(SELLER_ID)).willReturn(unsettledAmount);
        given(memberPaymentPort.getWallet(MEMBER_ID)).willReturn(new WalletSummaryDto(MEMBER_ID, 0, 0));
    }

    private static Seller seller(SellerStatus status) {
        Seller seller = Seller.apply(Member.signUp("seller@palette.test", "encoded", "판매자"),
                "팔레트 뷰티", "1234567890", "홍길동", "01012345678", "국민은행", "12345601123456", "홍길동");
        ReflectionTestUtils.setField(seller, "id", SELLER_ID);
        ReflectionTestUtils.setField(seller, "status", status);
        return seller;
    }

    private record RemoteErrorCode(int status, String name, String msg) implements ErrorCode {
    }
}

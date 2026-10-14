package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.settlement.out.SettlementApi;

@ExtendWith(MockitoExtension.class)
class MemberApproveWithdrawalUseCaseTest {

    private static final Long SELLER_ID = 5L;

    @Mock
    private SellerSupport sellerSupport;

    @Mock
    private OrderApi orderApi;

    @Mock
    private SettlementApi settlementApi;

    @InjectMocks
    private MemberApproveWithdrawalUseCase memberApproveWithdrawalUseCase;

    @Test
    @DisplayName("진행 중인 판매·미정산 금액이 없으면 WITHDRAWN이 되고 사업자번호를 비운 뒤 이력을 저장한다")
    void approveWithdrawal_success() {
        Seller seller = withdrawRequested();
        given(sellerSupport.getSeller(SELLER_ID)).willReturn(seller);
        given(orderApi.hasActiveSales(SELLER_ID)).willReturn(false);
        given(settlementApi.getUnsettledAmount(SELLER_ID)).willReturn(0L);

        memberApproveWithdrawalUseCase.approveWithdrawal(SELLER_ID);

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.WITHDRAWN);
        assertThat(seller.getBusinessNumber()).isNull();
        verify(sellerSupport).saveHistory(any(), any());
    }

    @Test
    @DisplayName("진행 중인 판매 주문이 있으면 409 ACTIVE_SALES_EXIST이고 상태는 그대로다")
    void approveWithdrawal_activeSales() {
        Seller seller = withdrawRequested();
        given(sellerSupport.getSeller(SELLER_ID)).willReturn(seller);
        given(orderApi.hasActiveSales(SELLER_ID)).willReturn(true);

        assertError(MemberErrorCode.ACTIVE_SALES_EXIST);

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.WITHDRAW_REQUESTED);
        verify(sellerSupport, never()).saveHistory(any(), any());
    }

    @Test
    @DisplayName("미정산 금액이 있으면 409 UNSETTLED_AMOUNT_EXISTS이고 상태는 그대로다")
    void approveWithdrawal_unsettledAmount() {
        Seller seller = withdrawRequested();
        given(sellerSupport.getSeller(SELLER_ID)).willReturn(seller);
        given(orderApi.hasActiveSales(SELLER_ID)).willReturn(false);
        given(settlementApi.getUnsettledAmount(SELLER_ID)).willReturn(15_000L);

        assertError(MemberErrorCode.UNSETTLED_AMOUNT_EXISTS);

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.WITHDRAW_REQUESTED);
        verify(sellerSupport, never()).saveHistory(any(), any());
    }

    @Test
    @DisplayName("철회 심사 중이 아니면 주문·정산을 확인하지 않고 409 SELLER_STATE_INVALID")
    void approveWithdrawal_notRequested() {
        Seller seller = seller();
        seller.approve();
        given(sellerSupport.getSeller(SELLER_ID)).willReturn(seller);

        assertError(MemberErrorCode.SELLER_STATE_INVALID);

        verifyNoInteractions(orderApi, settlementApi);
    }

    private void assertError(MemberErrorCode errorCode) {
        assertThatThrownBy(() -> memberApproveWithdrawalUseCase.approveWithdrawal(SELLER_ID))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(errorCode.resultCode()));
    }

    private static Seller withdrawRequested() {
        Seller seller = seller();
        seller.approve();
        seller.requestWithdrawal("폐업 예정");
        return seller;
    }

    private static Seller seller() {
        return Seller.apply(Member.signUp("seller@palette.com", "encoded-password", "판매자"),
                "팔레트 뷰티", "1234567890", "홍길동", "010-1234-5678", "국민은행", "123456-01-123456", "홍길동");
    }
}

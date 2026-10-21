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
import com.backend.shared.product.out.ProductApi;

@ExtendWith(MockitoExtension.class)
class MemberRejectWithdrawalUseCaseTest {

    private static final Long SELLER_ID = 5L;

    @Mock
    private SellerSupport sellerSupport;

    @Mock
    private ProductApi productApi;

    @InjectMocks
    private MemberRejectWithdrawalUseCase memberRejectWithdrawalUseCase;

    @Test
    @DisplayName("철회 심사 중이면 APPROVED로 돌아가고, 철회로 중지된 상품의 판매 재개를 요청한 뒤 이력을 저장한다")
    void rejectWithdrawal_success() {
        Seller seller = approved();
        seller.requestWithdrawal("폐업 예정");
        given(sellerSupport.getSeller(SELLER_ID)).willReturn(seller);

        memberRejectWithdrawalUseCase.rejectWithdrawal(SELLER_ID, " 진행 중인 반품 처리 후 다시 요청해 주세요 ");

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.APPROVED);
        verify(productApi).resumeAllBySeller(SELLER_ID);
        verify(sellerSupport).saveHistory(any(), any());
    }

    @Test
    @DisplayName("철회 심사 중이 아니면 409 SELLER_STATE_INVALID이고 판매 재개를 요청하지 않는다")
    void rejectWithdrawal_notRequested() {
        given(sellerSupport.getSeller(SELLER_ID)).willReturn(approved());

        assertThatThrownBy(() -> memberRejectWithdrawalUseCase.rejectWithdrawal(SELLER_ID, "거절"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(MemberErrorCode.SELLER_STATE_INVALID.resultCode()));

        verifyNoInteractions(productApi);
        verify(sellerSupport, never()).saveHistory(any(), any());
    }

    private static Seller approved() {
        Seller seller = Seller.apply(Member.signUp("seller@palette.com", "encoded-password", "판매자"),
                "팔레트 뷰티", "1234567890", "홍길동", "010-1234-5678", "국민은행", "123456-01-123456", "홍길동");
        seller.approve();
        return seller;
    }
}

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
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.event.SellerUnsanctionedEvent;

@ExtendWith(MockitoExtension.class)
class MemberUnsanctionSellerUseCaseTest {

    private static final Long SELLER_ID = 5L;

    @Mock
    private SellerSupport sellerSupport;

    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private MemberUnsanctionSellerUseCase memberUnsanctionSellerUseCase;

    @Test
    @DisplayName("제재 중이면 APPROVED가 되고, 사유가 담긴 해지 이벤트를 발행한 뒤 이력을 저장한다")
    void unsanction() {
        Seller seller = sanctioned();
        given(sellerSupport.getSeller(SELLER_ID)).willReturn(seller);

        memberUnsanctionSellerUseCase.unsanction(SELLER_ID, " 소명 확인 ");

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.APPROVED);
        verify(eventPublisher).publish(new SellerUnsanctionedEvent(SELLER_ID, "소명 확인"));
        verify(sellerSupport).saveHistory(any(), any());
    }

    @Test
    @DisplayName("제재 중이 아니면 409 SELLER_STATE_INVALID이고 이벤트를 발행하지 않는다")
    void unsanction_notSanctioned() {
        given(sellerSupport.getSeller(SELLER_ID)).willReturn(approved());

        assertStateInvalid();
    }

    @Test
    @DisplayName("회원 탈퇴한 판매자는 409 SELLER_STATE_INVALID이고 SANCTIONED 그대로다")
    void unsanction_withdrawnMember() {
        Seller seller = sanctioned();
        seller.getMember().withdraw();
        given(sellerSupport.getSeller(SELLER_ID)).willReturn(seller);

        assertStateInvalid();
        assertThat(seller.getStatus()).isEqualTo(SellerStatus.SANCTIONED);
    }

    private void assertStateInvalid() {
        assertThatThrownBy(() -> memberUnsanctionSellerUseCase.unsanction(SELLER_ID, "소명 확인"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(MemberErrorCode.SELLER_STATE_INVALID.resultCode()));

        verifyNoInteractions(eventPublisher);
        verify(sellerSupport, never()).saveHistory(any(), any());
    }

    private static Seller sanctioned() {
        Seller seller = approved();
        seller.sanction("위조 상품 판매");
        return seller;
    }

    private static Seller approved() {
        Seller seller = Seller.apply(Member.signUp("seller@palette.com", "encoded-password", "판매자"),
                "팔레트 뷰티", "1234567890", "홍길동", "010-1234-5678", "국민은행", "123456-01-123456", "홍길동");
        seller.approve();
        return seller;
    }
}

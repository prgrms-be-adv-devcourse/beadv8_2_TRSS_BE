package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;

@ExtendWith(MockitoExtension.class)
class MemberGetSellerUseCaseTest {

    private static final Long MEMBER_ID = 2L;

    @Mock
    private SellerRepository sellerRepository;

    @InjectMocks
    private MemberGetSellerUseCase memberGetSellerUseCase;

    @Test
    @DisplayName("getApprovedSellerByMemberId: APPROVED 판매자면 판매자 정보를 돌려준다")
    void getApprovedSellerByMemberId_approved() {
        Seller seller = seller(s -> { s.approve(); return s; });
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(seller));

        SellerDto result = memberGetSellerUseCase.getApprovedSellerByMemberId(MEMBER_ID);

        assertThat(result.status()).isEqualTo(SellerStatus.APPROVED);
        assertThat(result.businessName()).isEqualTo("팔레트 뷰티");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notApprovedSellers")
    @DisplayName("getApprovedSellerByMemberId: APPROVED가 아니면 403 SELLER_NOT_APPROVED")
    void getApprovedSellerByMemberId_notApproved(SellerStatus expectedStatus, UnaryOperator<Seller> toStatus) {
        Seller seller = seller(toStatus);
        assertThat(seller.getStatus()).isEqualTo(expectedStatus);
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(seller));

        assertThatThrownBy(() -> memberGetSellerUseCase.getApprovedSellerByMemberId(MEMBER_ID))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("403-SELLER_NOT_APPROVED");
    }

    @Test
    @DisplayName("getApprovedSellerByMemberId: 판매자 신청 내역이 없어도 403 SELLER_NOT_APPROVED")
    void getApprovedSellerByMemberId_notApplied() {
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> memberGetSellerUseCase.getApprovedSellerByMemberId(MEMBER_ID))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("403-SELLER_NOT_APPROVED");
    }

    static Stream<Arguments> notApprovedSellers() {
        return Stream.of(
                Arguments.of(SellerStatus.PENDING, (UnaryOperator<Seller>) s -> s),
                Arguments.of(SellerStatus.REJECTED, (UnaryOperator<Seller>) s -> { s.reject("서류 미비"); return s; }),
                Arguments.of(SellerStatus.WITHDRAW_REQUESTED, (UnaryOperator<Seller>) s -> {
                    s.approve();
                    s.requestWithdrawal("폐업");
                    return s;
                }),
                Arguments.of(SellerStatus.WITHDRAWN, (UnaryOperator<Seller>) s -> {
                    s.approve();
                    s.requestWithdrawal("폐업");
                    s.approveWithdrawal();
                    return s;
                }),
                Arguments.of(SellerStatus.SANCTIONED, (UnaryOperator<Seller>) s -> {
                    s.approve();
                    s.sanction("정책 위반");
                    return s;
                })
        );
    }

    // 신청(PENDING)한 판매자를 원하는 상태까지 바꾼다
    private static Seller seller(UnaryOperator<Seller> toStatus) {
        Member member = Member.signUp("seller@palette.com", "encoded-password", "판매자");
        Seller seller = Seller.apply(member, "팔레트 뷰티", "1234567890", "홍길동",
                "010-1234-5678", "국민은행", "123456-01-123456", "홍길동");
        return toStatus.apply(seller);
    }
}

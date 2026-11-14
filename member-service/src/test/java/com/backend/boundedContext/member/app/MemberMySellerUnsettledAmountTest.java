package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.in.dto.SellerUpdateRequest;
import com.backend.boundedContext.member.out.MemberPortUnavailableException;
import com.backend.boundedContext.member.out.MemberSettlementPort;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;

/**
 * 내 판매자 정보 조회·수정의 미지급 정산 금액. 화면 표시용이라 정산을 조회하지 못하면 그 값만 비우고 응답한다
 */
@ExtendWith(MockitoExtension.class)
class MemberMySellerUnsettledAmountTest {

    private static final Long MEMBER_ID = 3L;
    private static final Long SELLER_ID = 5L;

    @Mock
    private SellerRepository sellerRepository;

    @Mock
    private SellerStatusHistoryRepository sellerStatusHistoryRepository;

    @Mock
    private MemberSettlementPort memberSettlementPort;

    @Test
    @DisplayName("내 판매자 정보 조회: 미지급 정산 금액을 함께 준다")
    void getMySeller_withUnsettledAmount() {
        givenSeller();
        given(memberSettlementPort.getUnsettledAmount(SELLER_ID)).willReturn(12_000L);

        MySellerResponse response = getMySellerUseCase().getMySeller(MEMBER_ID);

        assertThat(response.unsettledAmount()).isEqualTo(12_000L);
    }

    @Test
    @DisplayName("내 판매자 정보 조회: 정산을 조회하지 못하면 미지급 금액만 null로 비우고 나머지는 응답한다")
    void getMySeller_settlementUnavailable() {
        givenSeller();
        given(memberSettlementPort.getUnsettledAmount(SELLER_ID)).willThrow(unavailable());

        MySellerResponse response = getMySellerUseCase().getMySeller(MEMBER_ID);

        assertThat(response.sellerId()).isEqualTo(SELLER_ID);
        assertThat(response.businessName()).isEqualTo("팔레트 뷰티");
        assertThat(response.unsettledAmount()).isNull();
    }

    @Test
    @DisplayName("내 판매자 정보 수정: 정산을 조회하지 못해도 수정은 반영되고 미지급 금액만 비운다")
    void update_settlementUnavailable() {
        Seller seller = givenSeller();
        given(memberSettlementPort.getUnsettledAmount(SELLER_ID)).willThrow(unavailable());

        MySellerResponse response = updateSellerUseCase().update(MEMBER_ID,
                new SellerUpdateRequest("팔레트 코스메틱", "010-9876-5432", null, null, null));

        assertThat(seller.getBusinessName()).isEqualTo("팔레트 코스메틱");
        assertThat(response.businessName()).isEqualTo("팔레트 코스메틱");
        assertThat(response.unsettledAmount()).isNull();
    }

    private Seller givenSeller() {
        Seller seller = Seller.apply(Member.signUp("seller@palette.com", "encoded-password", "판매자"),
                "팔레트 뷰티", "1234567890", "홍길동", "010-1234-5678", "국민은행", "123456-01-123456", "홍길동");
        seller.approve();
        ReflectionTestUtils.setField(seller, "id", SELLER_ID);
        given(sellerRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(seller));
        given(sellerStatusHistoryRepository.findFirstBySellerIdOrderByIdDesc(SELLER_ID)).willReturn(Optional.empty());
        return seller;
    }

    private MemberGetMySellerUseCase getMySellerUseCase() {
        return new MemberGetMySellerUseCase(sellerRepository, sellerStatusHistoryRepository, memberSettlementPort);
    }

    private MemberUpdateSellerUseCase updateSellerUseCase() {
        return new MemberUpdateSellerUseCase(sellerRepository, sellerStatusHistoryRepository, memberSettlementPort);
    }

    private static MemberPortUnavailableException unavailable() {
        return new MemberPortUnavailableException("시간 초과", null);
    }
}

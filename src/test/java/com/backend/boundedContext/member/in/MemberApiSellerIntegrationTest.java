package com.backend.boundedContext.member.in;

import com.backend.boundedContext.member.app.MemberSellerFacade;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 다른 컨텍스트가 쓰는 MemberApi의 판매자 조회 계약을 확인한다. 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest
class MemberApiSellerIntegrationTest {

    @Autowired
    private MemberApi memberApi;

    @Autowired
    private MemberSellerFacade memberSellerFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private SellerStatusHistoryRepository sellerStatusHistoryRepository;

    private final List<Long> memberIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        memberIds.forEach(memberId ->
                sellerRepository.findByMemberId(memberId).ifPresent(seller -> {
                    sellerStatusHistoryRepository.deleteAll(sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(seller.getId()));
                    sellerRepository.delete(seller);
                }));
        memberRepository.deleteAllById(memberIds);
    }

    @Test
    @DisplayName("findSellerByMemberId: 신청한 회원은 판매자 정보가 나온다")
    void findSellerByMemberId_applied() {
        Long memberId = createMember();
        Long sellerId = apply(memberId);

        SellerDto seller = memberApi.findSellerByMemberId(memberId).orElseThrow();

        assertThat(seller.sellerId()).isEqualTo(sellerId);
        assertThat(seller.memberId()).isEqualTo(memberId);
        assertThat(seller.businessName()).isEqualTo("팔레트 뷰티");
        assertThat(seller.status()).isEqualTo(SellerStatus.PENDING);
        assertThat(seller.canSell()).isFalse();   // 승인 전이라 판매 불가
    }

    @Test
    @DisplayName("findSellerByMemberId: 신청한 적이 없으면 empty")
    void findSellerByMemberId_notApplied() {
        Long memberId = createMember();

        assertThat(memberApi.findSellerByMemberId(memberId)).isEmpty();
    }

    @Test
    @DisplayName("getApprovedSellerByMemberId: 승인된 판매자는 판매자 정보가 나온다")
    void getApprovedSellerByMemberId_approved() {
        Long memberId = createMember();
        Long sellerId = apply(memberId);
        approve(memberId);

        SellerDto seller = memberApi.getApprovedSellerByMemberId(memberId);

        assertThat(seller.sellerId()).isEqualTo(sellerId);
        assertThat(seller.memberId()).isEqualTo(memberId);
        assertThat(seller.status()).isEqualTo(SellerStatus.APPROVED);
    }

    @Test
    @DisplayName("getApprovedSellerByMemberId: 승인 전(PENDING)이면 403 SELLER_NOT_APPROVED")
    void getApprovedSellerByMemberId_pending() {
        Long memberId = createMember();
        apply(memberId);

        assertThatThrownBy(() -> memberApi.getApprovedSellerByMemberId(memberId))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(MemberErrorCode.SELLER_NOT_APPROVED.resultCode()));
    }

    @Test
    @DisplayName("getApprovedSellerByMemberId: 신청한 적이 없으면 403 SELLER_NOT_APPROVED")
    void getApprovedSellerByMemberId_notApplied() {
        Long memberId = createMember();

        assertThatThrownBy(() -> memberApi.getApprovedSellerByMemberId(memberId))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(MemberErrorCode.SELLER_NOT_APPROVED.resultCode()));
    }

    @Test
    @DisplayName("getSeller: 판매자 ID로 조회한다")
    void getSeller_exists() {
        Long memberId = createMember();
        Long sellerId = apply(memberId);

        SellerDto seller = memberApi.getSeller(sellerId);

        assertThat(seller.sellerId()).isEqualTo(sellerId);
        assertThat(seller.memberId()).isEqualTo(memberId);
    }

    @Test
    @DisplayName("getSeller: 없는 판매자면 404 SELLER_NOT_FOUND")
    void getSeller_notFound() {
        assertThatThrownBy(() -> memberApi.getSeller(Long.MAX_VALUE))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(MemberErrorCode.SELLER_NOT_FOUND.resultCode()));
    }

    @Test
    @DisplayName("getSellers: 여러 판매자를 한 번에 조회하고, 없는 ID는 결과에서 빠진다")
    void getSellers_skipsMissingIds() {
        Long sellerA = apply(createMember());
        Long sellerB = apply(createMember());

        List<SellerDto> sellers = memberApi.getSellers(List.of(sellerA, sellerB, Long.MAX_VALUE));

        assertThat(sellers).extracting(SellerDto::sellerId).containsExactlyInAnyOrder(sellerA, sellerB);
    }

    @Test
    @DisplayName("getSellers: 빈 목록을 주면 빈 목록")
    void getSellers_empty() {
        assertThat(memberApi.getSellers(List.of())).isEmpty();
    }

    private Long createMember() {
        String email = "member-api-it-" + UUID.randomUUID() + "@palette.com";
        Long id = memberRepository.save(Member.signUp(email, "encoded-password", "판매자")).getId();
        memberIds.add(id);
        return id;
    }

    // 관리자 승인 기능 대신 도메인 메서드로 APPROVED로 바꾼다
    private void approve(Long memberId) {
        Seller seller = sellerRepository.findByMemberId(memberId).orElseThrow();
        sellerStatusHistoryRepository.save(seller.approve());
        sellerRepository.save(seller);
    }

    private Long apply(Long memberId) {
        String businessNumber = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        return memberSellerFacade.applySeller(memberId, new SellerApplyRequest("팔레트 뷰티", businessNumber, "홍길동",
                "010-1234-5678", "국민은행", "123456-01-123456", "홍길동")).sellerId();
    }
}

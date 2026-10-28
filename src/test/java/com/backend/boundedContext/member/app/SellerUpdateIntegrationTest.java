package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.in.dto.SellerUpdateRequest;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판매자 정보 수정 (S3). 로컬 Docker PostgreSQL·Redis가 필요하다.
 * 관리자 승인 API는 아직 없어서, 테스트에서 도메인 메서드로 승인한다.
 */
@SpringBootTest
class SellerUpdateIntegrationTest {

    @Autowired
    private MemberSellerFacade memberSellerFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private SellerStatusHistoryRepository sellerStatusHistoryRepository;

    private Long memberId;

    @BeforeEach
    void setUp() {
        String email = "seller-update-it-" + UUID.randomUUID() + "@palette.com";
        memberId = memberRepository.save(Member.signUp(email, "encoded-password", "판매자")).getId();
        String businessNumber = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        memberSellerFacade.applySeller(memberId, new SellerApplyRequest("팔레트 뷰티", businessNumber, "홍길동",
                "010-1234-5678", "국민은행", "123456-01-123456", "홍길동"));
    }

    @AfterEach
    void cleanUp() {
        sellerRepository.findByMemberId(memberId).ifPresent(seller -> {
            sellerStatusHistoryRepository.deleteAll(sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(seller.getId()));
            sellerRepository.delete(seller);
        });
        memberRepository.deleteById(memberId);
    }

    @Test
    @DisplayName("판매 중이면 보낸 값만 바뀌고, 계좌번호는 끝 4자리만 응답한다")
    void update_approved() {
        approve();

        MySellerResponse response = memberSellerFacade.updateSeller(memberId,
                new SellerUpdateRequest("팔레트 뷰티 2호점", null, "신한은행", "110-123-456789", "김철수"));

        assertThat(response.businessName()).isEqualTo("팔레트 뷰티 2호점");
        assertThat(response.phoneNumber()).isEqualTo("01012345678");
        assertThat(response.bankName()).isEqualTo("신한은행");
        assertThat(response.accountNumber()).isEqualTo("********6789");
        assertThat(response.accountHolder()).isEqualTo("김철수");

        Seller saved = sellerRepository.findByMemberId(memberId).orElseThrow();
        assertThat(saved.getAccountNumber()).isEqualTo("110123456789");
    }

    @Test
    @DisplayName("정보 수정은 이력을 남기지 않는다")
    void update_noHistory() {
        approve();
        Long sellerId = sellerRepository.findByMemberId(memberId).orElseThrow().getId();
        int before = sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(sellerId).size();

        memberSellerFacade.updateSeller(memberId, new SellerUpdateRequest("팔레트 뷰티 2호점", null, null, null, null));

        assertThat(sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(sellerId)).hasSize(before);
    }

    @Test
    @DisplayName("정산 계좌를 일부만 보내면 400 SELLER_ACCOUNT_INCOMPLETE이고, 같이 보낸 상호도 바뀌지 않는다")
    void update_partialAccount() {
        approve();

        assertThatThrownBy(() -> memberSellerFacade.updateSeller(memberId,
                new SellerUpdateRequest("팔레트 뷰티 2호점", null, null, "110-123-456789", null)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(MemberErrorCode.SELLER_ACCOUNT_INCOMPLETE.resultCode()));

        Seller saved = sellerRepository.findByMemberId(memberId).orElseThrow();
        assertThat(saved.getBusinessName()).isEqualTo("팔레트 뷰티");
        assertThat(saved.getAccountNumber()).isEqualTo("12345601123456");
    }

    @Test
    @DisplayName("심사 중(PENDING)이면 403 SELLER_NOT_APPROVED")
    void update_pending() {
        assertThatThrownBy(() -> memberSellerFacade.updateSeller(memberId,
                new SellerUpdateRequest("팔레트 뷰티 2호점", null, null, null, null)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(MemberErrorCode.SELLER_NOT_APPROVED.resultCode()));
    }

    @Test
    @DisplayName("판매자 신청 내역이 없으면 404 SELLER_NOT_FOUND")
    void update_notSeller() {
        String email = "seller-update-it-" + UUID.randomUUID() + "@palette.com";
        Long other = memberRepository.save(Member.signUp(email, "encoded-password", "구매자")).getId();

        try {
            assertThatThrownBy(() -> memberSellerFacade.updateSeller(other,
                    new SellerUpdateRequest("팔레트 뷰티 2호점", null, null, null, null)))
                    .isInstanceOfSatisfying(DomainException.class,
                            e -> assertThat(e.getResultCode()).isEqualTo(MemberErrorCode.SELLER_NOT_FOUND.resultCode()));
        } finally {
            memberRepository.deleteById(other);
        }
    }

    // 관리자 승인 API 대신 도메인 메서드로 승인한다
    private void approve() {
        Seller seller = sellerRepository.findByMemberId(memberId).orElseThrow();
        SellerStatusHistory history = seller.approve();
        sellerRepository.saveAndFlush(seller);
        sellerStatusHistoryRepository.save(history);
    }
}

package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.ErrorCode;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.SellerStatus;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * 관리자 입점 승인·반려 (S4·S5). 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest
class AdminSellerReviewIntegrationTest {

    @Autowired
    private MemberSellerFacade memberSellerFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private SellerStatusHistoryRepository sellerStatusHistoryRepository;

    private final List<Long> memberIds = new ArrayList<>();

    private Long memberId;
    private Long sellerId;
    private String businessNumber;

    @BeforeEach
    void setUp() {
        memberId = signUp();
        businessNumber = randomBusinessNumber();
        sellerId = apply(memberId, businessNumber);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : memberIds) {
            sellerRepository.findByMemberId(id).ifPresent(seller -> {
                sellerStatusHistoryRepository.deleteAll(sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(seller.getId()));
                sellerRepository.delete(seller);
            });
            memberRepository.deleteById(id);
        }
    }

    @Test
    @DisplayName("승인: APPROVED가 되고 회원 role이 SELLER가 되며, 판매 기능 검사를 통과한다")
    void approve() {
        AdminSellerDetailDto response = memberSellerFacade.approveSeller(sellerId);

        assertThat(response.status()).isEqualTo(SellerStatus.APPROVED);
        assertThat(response.histories()).extracting(AdminSellerDetailDto.History::toStatus)
                .containsExactly(SellerStatus.PENDING, SellerStatus.APPROVED);

        assertThat(memberRepository.findById(memberId).orElseThrow().getRole()).isEqualTo(MemberRole.SELLER);
        assertThat(memberSellerFacade.getApprovedSellerByMemberId(memberId).sellerId()).isEqualTo(sellerId);
    }

    @Test
    @DisplayName("승인: 심사 대기가 아니면 409 SELLER_STATE_INVALID이고 이력이 늘지 않는다")
    void approve_notPending() {
        memberSellerFacade.approveSeller(sellerId);
        int before = historyCount();

        assertError(() -> memberSellerFacade.approveSeller(sellerId), MemberErrorCode.SELLER_STATE_INVALID);

        assertThat(historyCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("승인·반려: 판매자가 없으면 404 SELLER_NOT_FOUND")
    void notFound() {
        assertError(() -> memberSellerFacade.approveSeller(Long.MAX_VALUE), MemberErrorCode.SELLER_NOT_FOUND);
        assertError(() -> memberSellerFacade.rejectSeller(Long.MAX_VALUE, "사유"), MemberErrorCode.SELLER_NOT_FOUND);
    }

    @Test
    @DisplayName("반려: REJECTED가 되고, 사유와 신청서 스냅샷이 이력에 남으며 사업자번호는 비워진다. role은 그대로 USER")
    void reject() {
        AdminSellerDetailDto response = memberSellerFacade.rejectSeller(sellerId, " 사업자등록증 확인 불가 ");

        assertThat(response.status()).isEqualTo(SellerStatus.REJECTED);
        assertThat(response.businessNumber()).isNull();

        Seller saved = sellerRepository.findById(sellerId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SellerStatus.REJECTED);
        assertThat(saved.getBusinessNumber()).isNull();

        SellerStatusHistory latest = sellerStatusHistoryRepository.findFirstBySellerIdOrderByIdDesc(sellerId).orElseThrow();
        assertThat(latest.getFromStatus()).isEqualTo(SellerStatus.PENDING);
        assertThat(latest.getToStatus()).isEqualTo(SellerStatus.REJECTED);
        assertThat(latest.getReason()).isEqualTo("사업자등록증 확인 불가");
        assertThat(latest.getBusinessNumber()).isEqualTo(businessNumber);
        assertThat(latest.getBusinessName()).isEqualTo("팔레트 뷰티");
        assertThat(latest.getAccountLast4()).isEqualTo("3456");

        assertThat(memberRepository.findById(memberId).orElseThrow().getRole()).isEqualTo(MemberRole.USER);
    }

    @Test
    @DisplayName("반려: 비워진 사업자번호로 다른 회원이 신청할 수 있다")
    void reject_businessNumberReusable() {
        memberSellerFacade.rejectSeller(sellerId, "본인 사업자가 아님");

        Long otherSellerId = apply(signUp(), businessNumber);

        assertThat(sellerRepository.findById(otherSellerId).orElseThrow().getBusinessNumber()).isEqualTo(businessNumber);
    }

    @Test
    @DisplayName("반려: 심사 대기가 아니면 409 SELLER_STATE_INVALID")
    void reject_notPending() {
        memberSellerFacade.approveSeller(sellerId);

        assertError(() -> memberSellerFacade.rejectSeller(sellerId, "사유"), MemberErrorCode.SELLER_STATE_INVALID);
    }

    private int historyCount() {
        return sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(sellerId).size();
    }

    private Long signUp() {
        String email = "admin-seller-review-it-" + UUID.randomUUID() + "@palette.com";
        Long id = memberRepository.save(Member.signUp(email, "encoded-password", "판매자")).getId();
        memberIds.add(id);
        return id;
    }

    private Long apply(Long memberId, String businessNumber) {
        return memberSellerFacade.applySeller(memberId, new SellerApplyRequest("팔레트 뷰티", businessNumber, "홍길동",
                "010-1234-5678", "국민은행", "123456-01-123456", "홍길동")).sellerId();
    }

    private static String randomBusinessNumber() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
    }

    private static void assertError(ThrowingCallable action, ErrorCode errorCode) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(errorCode.resultCode()));
    }
}

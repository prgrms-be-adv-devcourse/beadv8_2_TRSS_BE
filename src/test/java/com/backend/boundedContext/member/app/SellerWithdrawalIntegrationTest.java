package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.ErrorCode;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.settlement.out.SettlementApi;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 판매자 철회 요청 (S7). 로컬 Docker PostgreSQL·Redis가 필요하다.
 * 관리자 승인 API는 아직 없어서 도메인 메서드로 승인하고, 미정산 금액은 SettlementApi를 mock으로 바꾼다.
 */
@SpringBootTest
class SellerWithdrawalIntegrationTest {

    @Autowired
    private MemberSellerFacade memberSellerFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private SellerStatusHistoryRepository sellerStatusHistoryRepository;

    @MockitoBean
    private SettlementApi settlementApi;

    private Long memberId;

    @BeforeEach
    void setUp() {
        String email = "seller-withdrawal-it-" + UUID.randomUUID() + "@palette.com";
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
    @DisplayName("판매 중이고 미정산 금액이 없으면 WITHDRAW_REQUESTED가 되고, 사유가 이력과 응답에 남는다")
    void requestWithdrawal_success() {
        approve();

        MySellerResponse response = memberSellerFacade.requestWithdrawal(memberId, " 폐업 예정 ");

        assertThat(response.status()).isEqualTo(SellerStatus.WITHDRAW_REQUESTED);
        assertThat(response.reason()).isEqualTo("폐업 예정");
        assertThat(response.approvedAt()).isNotNull();
        assertThat(response.unsettledAmount()).isZero();

        Seller saved = sellerRepository.findByMemberId(memberId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SellerStatus.WITHDRAW_REQUESTED);

        SellerStatusHistory latest = sellerStatusHistoryRepository.findFirstBySellerIdOrderByIdDesc(saved.getId()).orElseThrow();
        assertThat(latest.getFromStatus()).isEqualTo(SellerStatus.APPROVED);
        assertThat(latest.getToStatus()).isEqualTo(SellerStatus.WITHDRAW_REQUESTED);
        assertThat(latest.getReason()).isEqualTo("폐업 예정");
    }

    @Test
    @DisplayName("미정산 금액이 있으면 409 UNSETTLED_AMOUNT_EXISTS이고 상태와 이력은 그대로다")
    void requestWithdrawal_unsettledAmount() {
        approve();
        Long sellerId = sellerRepository.findByMemberId(memberId).orElseThrow().getId();
        given(settlementApi.getUnsettledAmount(sellerId)).willReturn(15_000L);
        List<SellerStatusHistory> before = sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(sellerId);

        assertError(() -> memberSellerFacade.requestWithdrawal(memberId, "폐업 예정"), MemberErrorCode.UNSETTLED_AMOUNT_EXISTS);

        assertThat(sellerRepository.findById(sellerId).orElseThrow().getStatus()).isEqualTo(SellerStatus.APPROVED);
        assertThat(sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(sellerId)).hasSameSizeAs(before);
    }

    @Test
    @DisplayName("판매 중(APPROVED)이 아니면 409 SELLER_STATE_INVALID")
    void requestWithdrawal_notApproved() {
        assertError(() -> memberSellerFacade.requestWithdrawal(memberId, "폐업 예정"), MemberErrorCode.SELLER_STATE_INVALID);
    }

    @Test
    @DisplayName("이미 철회를 요청했으면 다시 요청할 수 없다 (409 SELLER_STATE_INVALID)")
    void requestWithdrawal_twice() {
        approve();
        memberSellerFacade.requestWithdrawal(memberId, "폐업 예정");

        assertError(() -> memberSellerFacade.requestWithdrawal(memberId, "폐업 예정"), MemberErrorCode.SELLER_STATE_INVALID);
    }

    @Test
    @DisplayName("판매자 신청 내역이 없으면 404 SELLER_NOT_FOUND")
    void requestWithdrawal_notSeller() {
        String email = "seller-withdrawal-it-" + UUID.randomUUID() + "@palette.com";
        Long other = memberRepository.save(Member.signUp(email, "encoded-password", "구매자")).getId();

        try {
            assertError(() -> memberSellerFacade.requestWithdrawal(other, "폐업 예정"), MemberErrorCode.SELLER_NOT_FOUND);
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

    private static void assertError(ThrowingCallable action, ErrorCode errorCode) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(errorCode.resultCode()));
    }
}

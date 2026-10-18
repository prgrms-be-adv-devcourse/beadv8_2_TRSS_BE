package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.ErrorCode;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.settlement.out.SettlementApi;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 관리자 철회 승인·거절 (S8·S9). 로컬 Docker PostgreSQL·Redis가 필요하다.
 * 진행 중인 판매와 미정산 금액은 OrderApi·SettlementApi를 mock으로 바꿔 정한다 (기본값: 없음).
 */
@SpringBootTest
class AdminSellerWithdrawalIntegrationTest {

    @Autowired
    private MemberSellerFacade memberSellerFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private SellerStatusHistoryRepository sellerStatusHistoryRepository;

    @MockitoBean
    private OrderApi orderApi;

    @MockitoBean
    private SettlementApi settlementApi;

    private Long memberId;
    private Long sellerId;
    private String businessNumber;

    // 승인된 판매자가 철회를 요청한 상태에서 시작
    @BeforeEach
    void setUp() {
        String email = "admin-seller-withdrawal-it-" + UUID.randomUUID() + "@palette.com";
        memberId = memberRepository.save(Member.signUp(email, "encoded-password", "판매자")).getId();
        businessNumber = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        sellerId = memberSellerFacade.applySeller(memberId, new SellerApplyRequest("팔레트 뷰티", businessNumber, "홍길동",
                "010-1234-5678", "국민은행", "123456-01-123456", "홍길동")).sellerId();
        memberSellerFacade.approveSeller(sellerId);
        memberSellerFacade.requestWithdrawal(memberId, "폐업 예정");
    }

    @AfterEach
    void cleanUp() {
        sellerRepository.findById(sellerId).ifPresent(seller -> {
            sellerStatusHistoryRepository.deleteAll(sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(sellerId));
            sellerRepository.delete(seller);
        });
        memberRepository.deleteById(memberId);
    }

    @Test
    @DisplayName("철회 승인: WITHDRAWN이 되고 신청서 스냅샷이 이력에 남으며 사업자번호는 비워진다. role은 USER 그대로, 판매는 막힌다")
    void approveWithdrawal() {
        AdminSellerDetailDto response = memberSellerFacade.approveWithdrawal(sellerId);

        assertThat(response.status()).isEqualTo(SellerStatus.WITHDRAWN);
        assertThat(response.businessNumber()).isNull();
        assertThat(sellerRepository.findById(sellerId).orElseThrow().getBusinessNumber()).isNull();

        SellerStatusHistory latest = sellerStatusHistoryRepository.findFirstBySellerIdOrderByIdDesc(sellerId).orElseThrow();
        assertThat(latest.getFromStatus()).isEqualTo(SellerStatus.WITHDRAW_REQUESTED);
        assertThat(latest.getToStatus()).isEqualTo(SellerStatus.WITHDRAWN);
        assertThat(latest.getBusinessNumber()).isEqualTo(businessNumber);
        assertThat(latest.getAccountLast4()).isEqualTo("3456");

        assertThat(memberRepository.findById(memberId).orElseThrow().getRole()).isEqualTo(MemberRole.USER);
        assertError(() -> memberSellerFacade.getApprovedSellerByMemberId(memberId), MemberErrorCode.SELLER_NOT_APPROVED);
    }

    @Test
    @DisplayName("철회 승인 뒤에는 같은 회원이 같은 사업자번호로 다시 신청할 수 있다")
    void approveWithdrawal_thenReapply() {
        memberSellerFacade.approveWithdrawal(sellerId);

        memberSellerFacade.applySeller(memberId, new SellerApplyRequest("팔레트 뷰티", businessNumber, "홍길동",
                "010-1234-5678", "국민은행", "123456-01-123456", "홍길동"));

        assertThat(sellerRepository.findById(sellerId).orElseThrow().getStatus()).isEqualTo(SellerStatus.PENDING);
    }

    @Test
    @DisplayName("철회 승인: 진행 중인 판매 주문이 있으면 409 ACTIVE_SALES_EXIST이고 상태와 이력은 그대로다")
    void approveWithdrawal_activeSales() {
        given(orderApi.hasActiveSales(sellerId)).willReturn(true);
        int before = historyCount();

        assertError(() -> memberSellerFacade.approveWithdrawal(sellerId), MemberErrorCode.ACTIVE_SALES_EXIST);

        assertThat(sellerRepository.findById(sellerId).orElseThrow().getStatus()).isEqualTo(SellerStatus.WITHDRAW_REQUESTED);
        assertThat(historyCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("철회 승인: 미정산 금액이 있으면 409 UNSETTLED_AMOUNT_EXISTS")
    void approveWithdrawal_unsettledAmount() {
        given(settlementApi.getUnsettledAmount(sellerId)).willReturn(15_000L);

        assertError(() -> memberSellerFacade.approveWithdrawal(sellerId), MemberErrorCode.UNSETTLED_AMOUNT_EXISTS);

        assertThat(sellerRepository.findById(sellerId).orElseThrow().getStatus()).isEqualTo(SellerStatus.WITHDRAW_REQUESTED);
    }

    @Test
    @DisplayName("철회 거절: APPROVED로 돌아가고 사유가 이력에 남으며, 다시 판매할 수 있다")
    void rejectWithdrawal() {
        AdminSellerDetailDto response = memberSellerFacade.rejectWithdrawal(sellerId, " 진행 중인 반품 처리 후 다시 요청해 주세요 ");

        assertThat(response.status()).isEqualTo(SellerStatus.APPROVED);
        assertThat(response.businessNumber()).isEqualTo(businessNumber);

        SellerStatusHistory latest = sellerStatusHistoryRepository.findFirstBySellerIdOrderByIdDesc(sellerId).orElseThrow();
        assertThat(latest.getFromStatus()).isEqualTo(SellerStatus.WITHDRAW_REQUESTED);
        assertThat(latest.getToStatus()).isEqualTo(SellerStatus.APPROVED);
        assertThat(latest.getReason()).isEqualTo("진행 중인 반품 처리 후 다시 요청해 주세요");

        assertThat(memberSellerFacade.getApprovedSellerByMemberId(memberId).sellerId()).isEqualTo(sellerId);
    }

    @Test
    @DisplayName("철회 승인·거절: 철회 심사 중이 아니면 409 SELLER_STATE_INVALID")
    void notRequested() {
        memberSellerFacade.rejectWithdrawal(sellerId, "거절");   // 다시 APPROVED

        assertError(() -> memberSellerFacade.approveWithdrawal(sellerId), MemberErrorCode.SELLER_STATE_INVALID);
        assertError(() -> memberSellerFacade.rejectWithdrawal(sellerId, "거절"), MemberErrorCode.SELLER_STATE_INVALID);
    }

    private int historyCount() {
        return sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(sellerId).size();
    }

    private static void assertError(ThrowingCallable action, ErrorCode errorCode) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(errorCode.resultCode()));
    }
}

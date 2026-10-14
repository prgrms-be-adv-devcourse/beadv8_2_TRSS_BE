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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 관리자 제재 (S10). 로컬 Docker PostgreSQL·Redis가 필요하다.
 * 정산 보류는 SettlementApi를 mock으로 바꿔 호출됐는지만 확인한다.
 */
@SpringBootTest
class AdminSellerSanctionIntegrationTest {

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
    private Long sellerId;
    private String businessNumber;

    @BeforeEach
    void setUp() {
        String email = "admin-seller-sanction-it-" + UUID.randomUUID() + "@palette.com";
        memberId = memberRepository.save(Member.signUp(email, "encoded-password", "판매자")).getId();
        businessNumber = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        sellerId = memberSellerFacade.applySeller(memberId, applyRequest()).sellerId();
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
    @DisplayName("제재: SANCTIONED가 되고 사유가 이력에 남으며 정산 보류를 요청한다. 사업자번호와 SELLER role은 남고, 판매는 막힌다")
    void sanction() {
        memberSellerFacade.approveSeller(sellerId);

        AdminSellerDetailDto response = memberSellerFacade.sanctionSeller(sellerId, " 위조 상품 판매 ");

        assertThat(response.status()).isEqualTo(SellerStatus.SANCTIONED);
        assertThat(response.businessNumber()).isEqualTo(businessNumber);

        SellerStatusHistory latest = sellerStatusHistoryRepository.findFirstBySellerIdOrderByIdDesc(sellerId).orElseThrow();
        assertThat(latest.getFromStatus()).isEqualTo(SellerStatus.APPROVED);
        assertThat(latest.getToStatus()).isEqualTo(SellerStatus.SANCTIONED);
        assertThat(latest.getReason()).isEqualTo("위조 상품 판매");

        verify(settlementApi).holdSeller(sellerId, "위조 상품 판매");
        assertThat(memberRepository.findById(memberId).orElseThrow().getRole()).isEqualTo(MemberRole.SELLER);
        assertError(() -> memberSellerFacade.getApprovedSellerByMemberId(memberId), MemberErrorCode.SELLER_NOT_APPROVED);
    }

    @Test
    @DisplayName("제재: 철회 심사 중인 판매자도 바로 제재할 수 있다")
    void sanction_withdrawRequested() {
        memberSellerFacade.approveSeller(sellerId);
        memberSellerFacade.requestWithdrawal(memberId, "폐업 예정");

        AdminSellerDetailDto response = memberSellerFacade.sanctionSeller(sellerId, "위조 상품 판매");

        assertThat(response.status()).isEqualTo(SellerStatus.SANCTIONED);
        assertThat(response.histories()).last().extracting(AdminSellerDetailDto.History::fromStatus)
                .isEqualTo(SellerStatus.WITHDRAW_REQUESTED);
    }

    @Test
    @DisplayName("제재 뒤에는 다시 신청할 수 없다 (403 SELLER_SANCTIONED)")
    void sanction_thenReapply() {
        memberSellerFacade.approveSeller(sellerId);
        memberSellerFacade.sanctionSeller(sellerId, "위조 상품 판매");

        assertError(() -> memberSellerFacade.applySeller(memberId, applyRequest()), MemberErrorCode.SELLER_SANCTIONED);
    }

    @Test
    @DisplayName("제재: 심사 대기 판매자는 제재할 수 없다 (409 SELLER_STATE_INVALID), 정산 보류도 요청하지 않는다")
    void sanction_pending() {
        assertError(() -> memberSellerFacade.sanctionSeller(sellerId, "위조 상품 판매"), MemberErrorCode.SELLER_STATE_INVALID);

        assertThat(sellerRepository.findById(sellerId).orElseThrow().getStatus()).isEqualTo(SellerStatus.PENDING);
        verify(settlementApi, never()).holdSeller(any(), any());
    }

    private SellerApplyRequest applyRequest() {
        return new SellerApplyRequest("팔레트 뷰티", businessNumber, "홍길동",
                "010-1234-5678", "국민은행", "123456-01-123456", "홍길동");
    }

    private static void assertError(ThrowingCallable action, ErrorCode errorCode) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(errorCode.resultCode()));
    }
}

package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.MemberSettlementPort;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerStatus;
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

/**
 * 내 판매자 정보 조회. 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest
class SellerGetMyIntegrationTest {

    @Autowired
    private MemberSellerFacade memberSellerFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private SellerStatusHistoryRepository sellerStatusHistoryRepository;

    // 정산 내부 API를 HTTP로 부르므로, 통합 테스트에서는 회원의 정산 Port를 목으로 둔다 (미지급 금액 기본값 0)
    @MockitoBean
    private MemberSettlementPort memberSettlementPort;

    private Long memberId;

    @BeforeEach
    void setUp() {
        String email = "seller-me-it-" + UUID.randomUUID() + "@palette.com";
        memberId = memberRepository.save(Member.signUp(email, "encoded-password", "판매자")).getId();
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
    @DisplayName("신청한 회원은 PENDING 상태와 끝 4자리만 보이는 계좌번호를 조회한다")
    void getMySeller_afterApply() {
        memberSellerFacade.applySeller(memberId, new SellerApplyRequest("팔레트 뷰티", randomBusinessNumber(), "홍길동",
                "010-1234-5678", "국민은행", "123456-01-123456", "홍길동"));

        MySellerResponse response = memberSellerFacade.getMySeller(memberId);

        assertThat(response.status()).isEqualTo(SellerStatus.PENDING);
        assertThat(response.accountNumber()).isEqualTo("**********3456");
        assertThat(response.phoneNumber()).isEqualTo("01012345678");
        assertThat(response.approvedAt()).isNull();
        assertThat(response.unsettledAmount()).isZero();
    }

    @Test
    @DisplayName("신청 내역이 없으면 404 SELLER_NOT_FOUND")
    void getMySeller_notApplied() {
        assertThatThrownBy(() -> memberSellerFacade.getMySeller(memberId))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(MemberErrorCode.SELLER_NOT_FOUND.resultCode()));
    }

    private static String randomBusinessNumber() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
    }
}

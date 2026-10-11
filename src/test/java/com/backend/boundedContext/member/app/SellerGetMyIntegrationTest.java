package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerStatus;
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
 * 내 판매자 정보 조회. 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest
class SellerGetMyIntegrationTest {

    @Autowired
    private MemberFacade memberFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SellerRepository sellerRepository;

    private Long memberId;

    @BeforeEach
    void setUp() {
        String email = "seller-me-it-" + UUID.randomUUID() + "@palette.com";
        memberId = memberRepository.save(Member.signUp(email, "encoded-password", "판매자")).getId();
    }

    @AfterEach
    void cleanUp() {
        sellerRepository.findFirstByMember_IdOrderByIdDesc(memberId).ifPresent(sellerRepository::delete);
        memberRepository.deleteById(memberId);
    }

    @Test
    @DisplayName("신청한 회원은 PENDING 상태와 끝 4자리만 보이는 계좌번호를 조회한다")
    void getMySeller_afterApply() {
        memberFacade.applySeller(memberId, new SellerApplyRequest("팔레트 뷰티", randomBusinessNumber(), "홍길동",
                "010-1234-5678", "국민은행", "123456-01-123456", "홍길동"));

        MySellerResponse response = memberFacade.getMySeller(memberId);

        assertThat(response.status()).isEqualTo(SellerStatus.PENDING);
        assertThat(response.accountNumber()).isEqualTo("**********3456");
        assertThat(response.phoneNumber()).isEqualTo("01012345678");
    }

    @Test
    @DisplayName("신청 내역이 없으면 404 SELLER_NOT_FOUND")
    void getMySeller_notApplied() {
        assertThatThrownBy(() -> memberFacade.getMySeller(memberId))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(MemberErrorCode.SELLER_NOT_FOUND.resultCode()));
    }

    private static String randomBusinessNumber() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
    }
}

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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 회원 1 : 판매자 1 구조에서 재신청·상태 이력·동시 변경을 확인한다. 로컬 Docker PostgreSQL·Redis가 필요하다.
 * 관리자 반려·철회 승인·제재 API는 아직 없어서, 테스트에서 도메인 메서드로 상태를 바꾼다.
 */
@SpringBootTest
class SellerReapplyIntegrationTest {

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
        memberIds.forEach(memberId -> sellerRepository.findByMemberId(memberId).ifPresent(seller -> {
            sellerStatusHistoryRepository.deleteAll(sellerStatusHistoryRepository.findAllBySeller_IdOrderByIdAsc(seller.getId()));
            sellerRepository.delete(seller);
        }));
        memberRepository.deleteAllById(memberIds);
    }

    @Test
    @DisplayName("반려 뒤 재신청하면 같은 판매자가 PENDING이 되고, 반려된 신청서는 이력에 남는다")
    void reapplyAfterReject() {
        Long memberId = createMember();
        String firstNumber = randomBusinessNumber();
        Long sellerId = memberSellerFacade.applySeller(memberId, request(firstNumber)).sellerId();
        change(memberId, seller -> seller.reject("사업자번호 불일치"));

        String secondNumber = randomBusinessNumber();
        Long reappliedId = memberSellerFacade.applySeller(memberId, request(secondNumber)).sellerId();

        Seller seller = sellerRepository.findByMemberId(memberId).orElseThrow();
        assertThat(reappliedId).isEqualTo(sellerId);
        assertThat(seller.getStatus()).isEqualTo(SellerStatus.PENDING);
        assertThat(seller.getBusinessNumber()).isEqualTo(secondNumber);

        List<SellerStatusHistory> histories = sellerStatusHistoryRepository.findAllBySeller_IdOrderByIdAsc(sellerId);
        assertThat(histories)
                .extracting(SellerStatusHistory::getFromStatus, SellerStatusHistory::getToStatus, SellerStatusHistory::getReason)
                .containsExactly(
                        tuple(null, SellerStatus.PENDING, null),
                        tuple(SellerStatus.PENDING, SellerStatus.REJECTED, "사업자번호 불일치"),
                        tuple(SellerStatus.REJECTED, SellerStatus.PENDING, null));
        assertThat(histories.get(1).getBusinessNumber()).isEqualTo(firstNumber);
        assertThat(histories.get(1).getAccountLast4()).isEqualTo("3456");
    }

    @Test
    @DisplayName("반려되면 내 판매자 조회에 반려 사유가 보이고 사업자번호는 비어 있다")
    void getMySellerAfterReject() {
        Long memberId = createMember();
        memberSellerFacade.applySeller(memberId, request(randomBusinessNumber()));
        change(memberId, seller -> seller.reject("사업자번호 불일치"));

        MySellerResponse response = memberSellerFacade.getMySeller(memberId);

        assertThat(response.status()).isEqualTo(SellerStatus.REJECTED);
        assertThat(response.reason()).isEqualTo("사업자번호 불일치");
        assertThat(response.businessNumber()).isNull();
        assertThat(response.statusChangedAt()).isNotNull();
    }

    @Test
    @DisplayName("본인 철회 뒤 재신청하면 같은 판매자가 PENDING이 된다")
    void reapplyAfterSelfWithdrawal() {
        Long memberId = createMember();
        Long sellerId = memberSellerFacade.applySeller(memberId, request(randomBusinessNumber())).sellerId();
        change(memberId, Seller::approve);
        change(memberId, seller -> seller.requestWithdrawal("폐업 예정"));
        change(memberId, Seller::approveWithdrawal);

        Long reappliedId = memberSellerFacade.applySeller(memberId, request(randomBusinessNumber())).sellerId();

        assertThat(reappliedId).isEqualTo(sellerId);
        assertThat(sellerRepository.findById(sellerId).orElseThrow().getStatus()).isEqualTo(SellerStatus.PENDING);
    }

    @Test
    @DisplayName("반려된 사업자번호는 다른 회원이 신청할 수 있다")
    void rejectedBusinessNumberIsReusable() {
        String businessNumber = randomBusinessNumber();
        Long rejectedMember = createMember();
        memberSellerFacade.applySeller(rejectedMember, request(businessNumber));
        change(rejectedMember, seller -> seller.reject("사업자번호 불일치"));

        Long owner = createMember();
        memberSellerFacade.applySeller(owner, request(businessNumber));

        assertThat(sellerRepository.findByMemberId(owner).orElseThrow().getBusinessNumber()).isEqualTo(businessNumber);
    }

    @Test
    @DisplayName("심사 중인 사업자번호로 다른 회원이 신청하면 409 BUSINESS_NUMBER_DUPLICATED")
    void pendingBusinessNumberIsTaken() {
        String businessNumber = randomBusinessNumber();
        memberSellerFacade.applySeller(createMember(), request(businessNumber));

        Long other = createMember();

        assertError(() -> memberSellerFacade.applySeller(other, request(businessNumber)), MemberErrorCode.BUSINESS_NUMBER_DUPLICATED);
    }

    @Test
    @DisplayName("심사 중에 다시 신청하면 409 SELLER_ALREADY_APPLIED")
    void reapplyWhilePending() {
        Long memberId = createMember();
        memberSellerFacade.applySeller(memberId, request(randomBusinessNumber()));

        assertError(() -> memberSellerFacade.applySeller(memberId, request(randomBusinessNumber())), MemberErrorCode.SELLER_ALREADY_APPLIED);
    }

    @Test
    @DisplayName("제재된 회원이 다시 신청하면 403 SELLER_SANCTIONED")
    void reapplyAfterSanction() {
        Long memberId = createMember();
        memberSellerFacade.applySeller(memberId, request(randomBusinessNumber()));
        change(memberId, Seller::approve);
        change(memberId, seller -> seller.sanction("허위 상품"));

        assertError(() -> memberSellerFacade.applySeller(memberId, request(randomBusinessNumber())), MemberErrorCode.SELLER_SANCTIONED);
    }

    @Test
    @DisplayName("제재된 사업자번호로 다른 회원이 신청하면 409 BUSINESS_NUMBER_DUPLICATED")
    void sanctionedBusinessNumberIsBlocked() {
        String businessNumber = randomBusinessNumber();
        Long sanctioned = createMember();
        memberSellerFacade.applySeller(sanctioned, request(businessNumber));
        change(sanctioned, Seller::approve);
        change(sanctioned, seller -> seller.sanction("허위 상품"));

        Long other = createMember();

        assertError(() -> memberSellerFacade.applySeller(other, request(businessNumber)), MemberErrorCode.BUSINESS_NUMBER_DUPLICATED);
    }

    @Test
    @DisplayName("같은 판매자를 동시에 바꾸면 늦게 저장한 쪽이 실패한다 (@Version)")
    void concurrentChange() {
        Long memberId = createMember();
        memberSellerFacade.applySeller(memberId, request(randomBusinessNumber()));
        change(memberId, Seller::approve);

        // 두 요청이 같은 APPROVED 상태를 읽음
        Seller byMember = sellerRepository.findByMemberId(memberId).orElseThrow();
        Seller byAdmin = sellerRepository.findByMemberId(memberId).orElseThrow();

        byAdmin.sanction("허위 상품");
        sellerRepository.saveAndFlush(byAdmin);

        byMember.requestWithdrawal("폐업 예정");
        assertThatThrownBy(() -> sellerRepository.saveAndFlush(byMember))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(sellerRepository.findByMemberId(memberId).orElseThrow().getStatus())
                .isEqualTo(SellerStatus.SANCTIONED);
    }

    // 관리자 API 대신 도메인 메서드로 상태를 바꾸고 판매자·이력을 저장한다
    private void change(Long memberId, Function<Seller, SellerStatusHistory> action) {
        Seller seller = sellerRepository.findByMemberId(memberId).orElseThrow();
        SellerStatusHistory history = action.apply(seller);
        sellerRepository.saveAndFlush(seller);
        sellerStatusHistoryRepository.save(history);
    }

    private Long createMember() {
        String email = "seller-reapply-it-" + UUID.randomUUID() + "@palette.com";
        Long id = memberRepository.save(Member.signUp(email, "encoded-password", "판매자")).getId();
        memberIds.add(id);
        return id;
    }

    private static SellerApplyRequest request(String businessNumber) {
        return new SellerApplyRequest("팔레트 뷰티", businessNumber, "홍길동", "010-1234-5678",
                "국민은행", "123456-01-123456", "홍길동");
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

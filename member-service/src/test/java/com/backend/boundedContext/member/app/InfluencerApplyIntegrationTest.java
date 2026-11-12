package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.InfluencerStatus;
import com.backend.boundedContext.member.domain.InfluencerStatusHistory;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.in.dto.InfluencerApplyRequest;
import com.backend.boundedContext.member.in.dto.InfluencerProfileResponse;
import com.backend.boundedContext.member.in.dto.MyInfluencerResponse;
import com.backend.boundedContext.member.out.InfluencerRepository;
import com.backend.boundedContext.member.out.InfluencerStatusHistoryRepository;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.global.exception.DomainException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 인플루언서 신청·재신청·내 정보·검색·프로필. 로컬 Docker PostgreSQL·Redis가 필요하다.
 * 관리자 심사 API는 아직 없어서, 테스트에서 도메인 메서드로 상태를 바꾼다.
 */
@SpringBootTest
class InfluencerApplyIntegrationTest {

    @Autowired
    private MemberInfluencerFacade memberInfluencerFacade;

    @Autowired
    private MemberFacade memberFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private InfluencerRepository influencerRepository;

    @Autowired
    private InfluencerStatusHistoryRepository influencerStatusHistoryRepository;

    @Autowired
    private SellerRepository sellerRepository;

    private final List<Long> memberIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        memberIds.forEach(memberId -> influencerRepository.findByMemberId(memberId).ifPresent(influencer -> {
            influencerStatusHistoryRepository.deleteAll(influencerStatusHistoryRepository.findAllByInfluencerIdOrderByIdAsc(influencer.getId()));
            influencerRepository.delete(influencer);
        }));
        memberIds.forEach(memberId -> sellerRepository.findByMemberId(memberId).ifPresent(sellerRepository::delete));
        memberRepository.deleteAllById(memberIds);
    }

    @Test
    @DisplayName("처음 신청하면 PENDING이 되고, 첫 이력이 남고, 내 정보와 /users/me에서 상태가 보인다")
    void applyFirst() {
        Long memberId = createMember();

        MyInfluencerResponse response = memberInfluencerFacade.applyInfluencer(memberId, request("데일리 뷰티 민지"));

        assertThat(response.status()).isEqualTo(InfluencerStatus.PENDING);
        assertThat(response.decidedAt()).isNull();
        assertThat(response.reason()).isNull();
        assertThat(influencerStatusHistoryRepository.findAllByInfluencerIdOrderByIdAsc(response.influencerId()))
                .extracting(InfluencerStatusHistory::getToStatus).containsExactly(InfluencerStatus.PENDING);
        assertThat(memberFacade.getMyInfo(memberId).influencerStatus()).isEqualTo(InfluencerStatus.PENDING);
    }

    @Test
    @DisplayName("심사 중에 다시 신청하면 409 ALREADY_APPLIED")
    void applyTwice() {
        Long memberId = createMember();
        memberInfluencerFacade.applyInfluencer(memberId, request("데일리 뷰티 민지"));

        assertErrorCode(() -> memberInfluencerFacade.applyInfluencer(memberId, request("다른 이름")),
                MemberErrorCode.INFLUENCER_ALREADY_APPLIED);
    }

    @Test
    @DisplayName("반려되면 내 정보에 반려 사유와 반려일이 보이고, 재신청하면 같은 인플루언서가 PENDING으로 돌아간다")
    void rejectThenReapply() {
        Long memberId = createMember();
        Long influencerId = memberInfluencerFacade.applyInfluencer(memberId, request("데일리 뷰티 민지")).influencerId();
        change(memberId, influencer -> influencer.reject("본인 확인이 어려워요"));

        MyInfluencerResponse rejected = memberInfluencerFacade.getMyInfluencer(memberId);
        assertThat(rejected.status()).isEqualTo(InfluencerStatus.REJECTED);
        assertThat(rejected.reason()).isEqualTo("본인 확인이 어려워요");
        assertThat(rejected.decidedAt()).isNotNull();

        MyInfluencerResponse reapplied = memberInfluencerFacade.applyInfluencer(memberId, request("새 활동명"));
        assertThat(reapplied.influencerId()).isEqualTo(influencerId);
        assertThat(reapplied.status()).isEqualTo(InfluencerStatus.PENDING);
        assertThat(reapplied.activityName()).isEqualTo("새 활동명");
        assertThat(reapplied.decidedAt()).isNull();
        assertThat(reapplied.reason()).isNull();
    }

    @Test
    @DisplayName("자격이 해제된 회원은 다시 신청할 수 없다")
    void reapplyAfterRevoke() {
        Long memberId = createMember();
        memberInfluencerFacade.applyInfluencer(memberId, request("데일리 뷰티 민지"));
        change(memberId, Influencer::approve);
        change(memberId, influencer -> influencer.revoke("쿠폰 무단 배포"));

        assertErrorCode(() -> memberInfluencerFacade.applyInfluencer(memberId, request("다시")),
                MemberErrorCode.INFLUENCER_REAPPLY_NOT_ALLOWED);
    }

    @Test
    @DisplayName("신청한 적이 없으면 내 정보는 404, /users/me의 인플루언서 상태는 null")
    void notApplied() {
        Long memberId = createMember();

        assertErrorCode(() -> memberInfluencerFacade.getMyInfluencer(memberId), MemberErrorCode.INFLUENCER_NOT_FOUND);
        assertThat(memberFacade.getMyInfo(memberId).influencerStatus()).isNull();
    }

    @Test
    @DisplayName("검색과 프로필은 승인된 인플루언서만 보여 준다")
    void searchAndProfileApprovedOnly() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long approvedMember = createMember();
        Long approvedId = memberInfluencerFacade.applyInfluencer(approvedMember, request("승인 " + tag)).influencerId();
        change(approvedMember, Influencer::approve);
        Long pendingId = memberInfluencerFacade.applyInfluencer(createMember(), request("심사중 " + tag)).influencerId();

        assertThat(memberInfluencerFacade.searchInfluencers(createApprovedSeller(), tag, PageRequest.of(0, 20)).items())
                .extracting(InfluencerProfileResponse::influencerId).containsExactly(approvedId);
        assertThat(memberInfluencerFacade.getInfluencerProfile(approvedId).activityName()).isEqualTo("승인 " + tag);
        assertErrorCode(() -> memberInfluencerFacade.getInfluencerProfile(pendingId), MemberErrorCode.INFLUENCER_NOT_FOUND);
    }

    @Test
    @DisplayName("검색은 판매 중인 판매자만 할 수 있다 (구매자는 403)")
    void searchSellerOnly() {
        Long buyer = createMember();

        assertErrorCode(() -> memberInfluencerFacade.searchInfluencers(buyer, null, PageRequest.of(0, 20)),
                MemberErrorCode.SELLER_NOT_APPROVED);
    }

    private Long createApprovedSeller() {
        Long memberId = createMember();
        String businessNumber = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        Seller seller = Seller.apply(memberRepository.getReferenceById(memberId), "팔레트 뷰티", businessNumber,
                "홍길동", "010-1234-5678", "국민은행", "123456-01-123456", "홍길동");
        seller.approve();
        sellerRepository.saveAndFlush(seller);
        return memberId;
    }

    private void change(Long memberId, Function<Influencer, InfluencerStatusHistory> action) {
        Influencer influencer = influencerRepository.findByMemberId(memberId).orElseThrow();
        InfluencerStatusHistory history = action.apply(influencer);
        influencerRepository.saveAndFlush(influencer);
        influencerStatusHistoryRepository.save(history);
    }

    private Long createMember() {
        String email = "influencer-apply-it-" + UUID.randomUUID() + "@palette.com";
        Long id = memberRepository.save(Member.signUp(email, "encoded-password", "인플루언서")).getId();
        memberIds.add(id);
        return id;
    }

    private static InfluencerApplyRequest request(String activityName) {
        return new InfluencerApplyRequest(activityName, "https://instagram.com/daily.beauty", "스킨케어 루틴을 올려요.");
    }

    private static void assertErrorCode(ThrowingCallable call, MemberErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo(code.resultCode());
    }
}

package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.app.dto.AdminInfluencerDetailDto;
import com.backend.boundedContext.member.app.dto.AdminInfluencerDto;
import com.backend.boundedContext.member.app.dto.AdminInfluencerListDto;
import com.backend.boundedContext.member.domain.InfluencerStatus;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.in.dto.InfluencerApplyRequest;
import com.backend.boundedContext.member.out.InfluencerRepository;
import com.backend.boundedContext.member.out.InfluencerStatusHistoryRepository;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.global.outbox.OutboxEventStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관리자 인플루언서 목록·상세·승인·반려·해제. 로컬 Docker PostgreSQL·Redis가 필요하다.
 * 협약 종료는 해제 이벤트를 받은 쿠폰(market)이 하므로, 해제 이벤트가 outbox에 남는지만 확인한다.
 */
@SpringBootTest
class AdminInfluencerReviewIntegrationTest {

    @Autowired
    private MemberInfluencerFacade memberInfluencerFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private InfluencerRepository influencerRepository;

    @Autowired
    private InfluencerStatusHistoryRepository influencerStatusHistoryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> influencerIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll(influencerOutbox());
        influencerIds.forEach(id -> influencerStatusHistoryRepository.deleteAll(influencerStatusHistoryRepository.findAllByInfluencerIdOrderByIdAsc(id)));
        influencerRepository.deleteAllById(influencerIds);
        memberRepository.deleteAllById(memberIds);
    }

    @Test
    @DisplayName("목록: 심사 대기가 먼저 오고, 항목에 이메일이 있고, 탭별 건수는 상태 필터와 관계없이 전체 기준")
    void list() {
        Long approvedId = apply("approved");
        memberInfluencerFacade.approveInfluencer(approvedId);
        Long pendingId = apply("pending");

        AdminInfluencerListDto all = memberInfluencerFacade.getInfluencersForAdmin(null, PageRequest.of(0, 100));
        List<Long> ids = all.influencers().items().stream().map(AdminInfluencerDto::influencerId).toList();
        assertThat(ids.indexOf(pendingId)).isLessThan(ids.indexOf(approvedId));
        assertThat(all.influencers().items()).filteredOn(item -> item.influencerId().equals(pendingId))
                .singleElement().satisfies(item -> assertThat(item.email()).startsWith("influencer-admin-it-pending"));

        AdminInfluencerListDto approvedTab = memberInfluencerFacade.getInfluencersForAdmin(InfluencerStatus.APPROVED, PageRequest.of(0, 100));
        assertThat(approvedTab.influencers().items()).allMatch(item -> item.status() == InfluencerStatus.APPROVED);
        assertThat(approvedTab.counts().all()).isEqualTo(all.counts().all());
        assertThat(approvedTab.counts().pending()).isPositive();
    }

    @Test
    @DisplayName("반려: REJECTED가 되고, 상세의 사유·결정 시각과 오래된 순 이력이 보인다")
    void reject() {
        Long influencerId = apply("reject");

        AdminInfluencerDetailDto detail = memberInfluencerFacade.rejectInfluencer(influencerId, " 본인 확인이 어려워요 ");

        assertThat(detail.status()).isEqualTo(InfluencerStatus.REJECTED);
        assertThat(detail.reason()).isEqualTo("본인 확인이 어려워요");
        assertThat(detail.decidedAt()).isNotNull();
        assertThat(detail.histories()).extracting(AdminInfluencerDetailDto.History::toStatus)
                .containsExactly(InfluencerStatus.PENDING, InfluencerStatus.REJECTED);
        assertThat(memberInfluencerFacade.getInfluencerDetail(influencerId).histories()).hasSize(2);
    }

    @Test
    @DisplayName("해제: 승인된 인플루언서가 REVOKED가 되고 해제 이벤트가 outbox에 PENDING으로 남는다")
    void revoke() {
        Long influencerId = apply("revoke");
        memberInfluencerFacade.approveInfluencer(influencerId);

        AdminInfluencerDetailDto detail = memberInfluencerFacade.revokeInfluencer(influencerId, "쿠폰 무단 배포");

        assertThat(detail.status()).isEqualTo(InfluencerStatus.REVOKED);
        assertThat(influencerOutbox()).singleElement().satisfies(outbox -> {
            assertThat(outbox.getEventType()).isEqualTo("InfluencerRevokedEvent");
            assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
            assertThat(outbox.getPayload()).contains("쿠폰 무단 배포");
        });
    }

    @Test
    @DisplayName("상태가 맞지 않으면 409 STATE_INVALID (심사 중 해제, 승인 두 번), 없는 ID는 404")
    void stateInvalid() {
        Long influencerId = apply("invalid");

        assertErrorCode(() -> memberInfluencerFacade.revokeInfluencer(influencerId, "사유"), MemberErrorCode.INFLUENCER_STATE_INVALID);
        memberInfluencerFacade.approveInfluencer(influencerId);
        assertErrorCode(() -> memberInfluencerFacade.approveInfluencer(influencerId), MemberErrorCode.INFLUENCER_STATE_INVALID);
        assertErrorCode(() -> memberInfluencerFacade.getInfluencerDetail(Long.MAX_VALUE), MemberErrorCode.INFLUENCER_NOT_FOUND);
    }

    private Long apply(String label) {
        String email = "influencer-admin-it-" + label + "-" + UUID.randomUUID() + "@palette.com";
        Long memberId = memberRepository.save(Member.signUp(email, "encoded-password", "인플루언서")).getId();
        memberIds.add(memberId);
        Long influencerId = memberInfluencerFacade.applyInfluencer(memberId,
                new InfluencerApplyRequest("활동명 " + label, "https://instagram.com/" + label, "소개")).influencerId();
        influencerIds.add(influencerId);
        return influencerId;
    }

    private List<OutboxEvent> influencerOutbox() {
        return outboxEventRepository.findAll().stream()
                .filter(outbox -> outbox.getAggregateType().equals("INFLUENCER") && influencerIds.contains(outbox.getAggregateId()))
                .toList();
    }

    private static void assertErrorCode(ThrowingCallable call, MemberErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo(code.resultCode());
    }
}

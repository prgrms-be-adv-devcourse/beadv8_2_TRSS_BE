package com.backend.boundedContext.recommend.app;

import com.backend.ai.client.MemberInfluencerApiClient;
import com.backend.boundedContext.recommend.app.dto.CreatorRecommendationsDto;
import com.backend.boundedContext.recommend.app.dto.CreatorRecommendationsDto.Item;
import com.backend.boundedContext.recommend.domain.IngredientTag;
import com.backend.boundedContext.recommend.domain.RecommendErrorCode;
import com.backend.boundedContext.recommend.domain.SkinConcern;
import com.backend.boundedContext.recommend.domain.SkinType;
import com.backend.boundedContext.recommend.out.BeautyProfileRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.InfluencerDto;
import com.backend.shared.member.dto.InfluencerStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.backend.boundedContext.recommend.domain.IngredientTag.*;
import static com.backend.boundedContext.recommend.domain.SkinConcern.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/** 회원 서비스는 mock으로 바꾼다. 설문은 실제 DB에 저장한다 */
@SpringBootTest
class RecommendGetCreatorsIntegrationTest {

    private static final Long ME = 1L;

    @Autowired
    private RecommendFacade recommendFacade;

    @Autowired
    private BeautyProfileRepository beautyProfileRepository;

    @MockitoBean
    private MemberInfluencerApiClient memberInfluencerApiClient;

    // 회원 서비스의 인플루언서 (memberId → 정보). getInfluencers는 여기서 찾아 돌려준다
    private final List<InfluencerDto> influencers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        beautyProfileRepository.deleteAll();
    }

    @Test
    @DisplayName("점수 합(타입 1 + 겹친 고민 + 겹친 선호 성분) 큰 순서. 이유 문구는 일치 항목·선호 성분")
    void orderByScore() {
        survey(ME, SkinType.OILY, List.of(TROUBLE, PORE), List.of(NIACINAMIDE, CICA));
        creator(10L, 100L, "A", SkinType.OILY, List.of(TROUBLE, PORE), List.of(NIACINAMIDE));   // 1 + 2 + 1 = 4
        creator(11L, 101L, "B", SkinType.OILY, List.of(SEBUM), List.of(NIACINAMIDE));          // 1 + 0 + 1 = 2
        creator(12L, 102L, "C", SkinType.COMBINATION, List.of(TROUBLE), List.of());            // 0 + 1 + 0 = 1

        List<Item> items = creators();

        assertThat(items).extracting(Item::activityName).containsExactly("A", "B", "C");
        assertThat(items.getFirst().reason()).isEqualTo("지성 · 트러블 · 모공 일치 · 나이아신아마이드 선호");
        assertThat(items.get(2).reason()).isEqualTo("트러블 일치");
        assertThat(items.getFirst().matchedSkinType()).isTrue();
    }

    @Test
    @DisplayName("일반 회원, 나 자신, 승인되지 않은 인플루언서, 겹치는 게 없는 사람은 빠진다")
    void excluded() {
        given(memberInfluencerApiClient.findApprovedByMemberId(ME)).willReturn(Optional.of(influencer(9L, ME, "나", InfluencerStatus.APPROVED)));
        survey(ME, SkinType.OILY, List.of(TROUBLE), List.of());
        survey(20L, SkinType.OILY, List.of(TROUBLE), List.of());                               // 일반 회원
        creator(10L, 100L, "승인", SkinType.OILY, List.of(TROUBLE), List.of());
        creator(11L, 101L, "반려", SkinType.OILY, List.of(TROUBLE), List.of());
        influencers.set(influencers.size() - 1, influencer(101L, 11L, "반려", InfluencerStatus.REJECTED));
        creator(12L, 102L, "안 겹침", SkinType.DRY, List.of(DRYNESS), List.of());

        assertThat(creators()).extracting(Item::activityName).containsExactly("승인");
    }

    @Test
    @DisplayName("'잘 모르겠어요'끼리는 피부 타입 일치로 보지 않는다")
    void unknownSkinType() {
        survey(ME, SkinType.UNKNOWN, List.of(), List.of(CICA));
        creator(10L, 100L, "A", SkinType.UNKNOWN, List.of(), List.of(CICA));

        Item item = creators().getFirst();
        assertThat(item.matchedSkinType()).isFalse();
        assertThat(item.reason()).isEqualTo("시카(병풀) 선호");
    }

    @Test
    @DisplayName("설문 저장 때 회원 서비스가 응답하지 않아도 저장되고, 이전 인플루언서 기록은 그대로 둔다")
    void memberDownOnSave() {
        creator(10L, 100L, "A", SkinType.OILY, List.of(TROUBLE), List.of());
        given(memberInfluencerApiClient.findApprovedByMemberId(10L)).willThrow(new ResourceAccessException("연결 실패"));

        recommendFacade.saveSurvey(10L, SkinType.DRY, List.of(), List.of(), List.of(), List.of());

        assertThat(beautyProfileRepository.findByMemberId(10L).orElseThrow().getInfluencerId()).isEqualTo(100L);
        assertThat(beautyProfileRepository.findByMemberId(10L).orElseThrow().getSkinType()).isEqualTo(SkinType.DRY);
    }

    @Test
    @DisplayName("설문을 안 했으면 SURVEY_NOT_FOUND, 추천 때 회원 서비스가 실패하면 502")
    void errors() {
        assertThatThrownBy(() -> recommendFacade.getCreators(ME, 10))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(RecommendErrorCode.SURVEY_NOT_FOUND.resultCode());

        survey(ME, SkinType.OILY, List.of(TROUBLE), List.of());
        creator(10L, 100L, "A", SkinType.OILY, List.of(TROUBLE), List.of());
        given(memberInfluencerApiClient.getInfluencers(any())).willThrow(new DomainException(com.backend.global.exception.GlobalErrorCode.EXTERNAL_SERVICE_ERROR));
        assertThatThrownBy(() -> recommendFacade.getCreators(ME, 10))
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo("502-EXTERNAL_SERVICE_ERROR");
    }

    private List<Item> creators() {
        given(memberInfluencerApiClient.getInfluencers(any())).willAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return influencers.stream().filter(i -> ids.contains(i.influencerId())).toList();
        });
        CreatorRecommendationsDto result = recommendFacade.getCreators(ME, 10);
        return result.items();
    }

    // 승인된 인플루언서의 설문 (저장할 때 회원 서비스가 승인으로 답한다)
    private void creator(Long memberId, Long influencerId, String name, SkinType type, List<SkinConcern> concerns, List<IngredientTag> preferred) {
        InfluencerDto influencer = influencer(influencerId, memberId, name, InfluencerStatus.APPROVED);
        influencers.add(influencer);
        given(memberInfluencerApiClient.findApprovedByMemberId(memberId)).willReturn(Optional.of(influencer));
        survey(memberId, type, concerns, preferred);
    }

    private void survey(Long memberId, SkinType type, List<SkinConcern> concerns, List<IngredientTag> preferred) {
        recommendFacade.saveSurvey(memberId, type, concerns, preferred, List.of(), List.of());
    }

    private static InfluencerDto influencer(Long influencerId, Long memberId, String name, InfluencerStatus status) {
        return new InfluencerDto(influencerId, memberId, name, status);
    }
}

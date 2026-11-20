package com.backend.boundedContext.recommend.app;

import com.backend.ai.client.MemberInfluencerApiClient;
import com.backend.boundedContext.recommend.app.dto.CreatorRecommendationsDto;
import com.backend.boundedContext.recommend.app.dto.CreatorRecommendationsDto.Item;
import com.backend.boundedContext.recommend.domain.BeautyProfile;
import com.backend.boundedContext.recommend.domain.IngredientTag;
import com.backend.boundedContext.recommend.domain.RecommendErrorCode;
import com.backend.boundedContext.recommend.domain.SkinConcern;
import com.backend.boundedContext.recommend.domain.SkinType;
import com.backend.boundedContext.recommend.out.BeautyProfileRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.InfluencerDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecommendGetCreatorsUseCase {
    private final BeautyProfileRepository beautyProfileRepository;
    private final MemberInfluencerApiClient memberInfluencerApiClient;

    /**
     * 나와 피부가 비슷한 크리에이터. 후보는 설문을 한 인플루언서이고, 승인 여부는 요청마다 회원 서비스에 다시 묻는다.
     * 점수 = 피부 타입 같음(1) + 겹친 고민 수 + 겹친 선호 성분 수. 0점은 빼고, 같으면 설문을 최근에 고친 순.
     * 설문을 안 했으면 404, 회원 서비스가 응답하지 않으면 502.
     * shortcut: 후보 설문을 전부 읽어 앱에서 점수를 낸다. 인플루언서가 수천 명이 되면 SQL로 옮긴다.
     */
    public CreatorRecommendationsDto getCreators(Long memberId, int size) {
        BeautyProfile me = beautyProfileRepository.findByMemberId(memberId)
                .orElseThrow(() -> new DomainException(RecommendErrorCode.SURVEY_NOT_FOUND));
        List<BeautyProfile> candidates = beautyProfileRepository.findByInfluencerIdIsNotNullAndMemberIdNot(memberId);
        if (candidates.isEmpty()) return new CreatorRecommendationsDto(List.of());

        Map<Long, InfluencerDto> approved = memberInfluencerApiClient
                .getInfluencers(candidates.stream().map(BeautyProfile::getInfluencerId).toList()).stream()
                .filter(InfluencerDto::approved)
                .collect(Collectors.toMap(InfluencerDto::influencerId, Function.identity(), (a, b) -> a));

        record Scored(BeautyProfile profile, InfluencerDto influencer, Item item, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (BeautyProfile candidate : candidates) {
            InfluencerDto influencer = approved.get(candidate.getInfluencerId());
            if (influencer == null) continue;   // 승인되지 않았거나 회원 서비스에 없음
            boolean sameType = me.getSkinType() != SkinType.UNKNOWN && me.getSkinType() == candidate.getSkinType();
            Set<SkinConcern> concerns = intersect(me.getConcerns(), candidate.getConcerns(), SkinConcern.class);
            Set<IngredientTag> ingredients = intersect(me.getPreferredIngredients(), candidate.getPreferredIngredients(), IngredientTag.class);
            int score = (sameType ? 1 : 0) + concerns.size() + ingredients.size();
            if (score == 0) continue;
            scored.add(new Scored(candidate, influencer, new Item(influencer.influencerId(), influencer.memberId(),
                    influencer.activityName(), candidate.getSkinType(), candidate.getConcerns(),
                    sameType, concerns, ingredients, reason(sameType ? candidate.getSkinType() : null, concerns, ingredients)), score));
        }

        List<Item> items = scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparing(s -> s.profile().getUpdatedAt(), Comparator.reverseOrder())
                        .thenComparing(s -> s.profile().getMemberId()))
                .limit(size)
                .map(Scored::item)
                .toList();
        return new CreatorRecommendationsDto(items);
    }

    // "지성·트러블·모공 일치·나이아신아마이드 선호". 순서는 코드표 순서
    static String reason(SkinType matchedType, Set<SkinConcern> concerns, Set<IngredientTag> ingredients) {
        List<String> matched = new ArrayList<>();
        if (matchedType != null) matched.add(matchedType.getLabel());
        concerns.forEach(concern -> matched.add(concern.getLabel()));
        List<String> parts = new ArrayList<>();
        if (!matched.isEmpty()) parts.add(String.join(" · ", matched) + " 일치");
        ingredients.forEach(tag -> parts.add(tag.getLabel() + " 선호"));
        return String.join(" · ", parts);
    }

    private static <E extends Enum<E>> Set<E> intersect(Set<E> a, Set<E> b, Class<E> type) {
        EnumSet<E> result = EnumSet.noneOf(type);
        result.addAll(a);
        result.retainAll(b);
        return result;
    }
}

package com.backend.boundedContext.recommend.app;

import com.backend.boundedContext.recommend.app.dto.BeautyProfileDto;
import com.backend.boundedContext.recommend.domain.BeautyCategory;
import com.backend.boundedContext.recommend.domain.BeautyProfile;
import com.backend.boundedContext.recommend.domain.IngredientTag;
import com.backend.boundedContext.recommend.domain.SkinConcern;
import com.backend.boundedContext.recommend.domain.SkinType;
import com.backend.boundedContext.recommend.out.BeautyProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RecommendSaveSurveyUseCase {
    private final BeautyProfileRepository beautyProfileRepository;

    // 처음이면 만들고, 있으면 덮어쓴다 같은 회원이 동시에 처음 저장하면 유니크 제약에 걸려 409-DATA_CONFLICT
    public BeautyProfileDto save(Long memberId, SkinType skinType, List<SkinConcern> concerns,
                                 List<IngredientTag> preferredIngredients, List<IngredientTag> avoidedIngredients,
                                 List<BeautyCategory> interestCategories) {
        BeautyProfile profile = beautyProfileRepository.findByMemberId(memberId)
                .map(found -> {
                    found.update(skinType, concerns, preferredIngredients, avoidedIngredients, interestCategories);
                    return found;
                })
                .orElseGet(() -> beautyProfileRepository.save(BeautyProfile.create(memberId, skinType, concerns,
                        preferredIngredients, avoidedIngredients, interestCategories)));
        // updatedAt을 응답에 담으려고 바로 반영함(Auditing은 flush 때 채움)
        beautyProfileRepository.flush();
        return BeautyProfileDto.from(profile);
    }
}

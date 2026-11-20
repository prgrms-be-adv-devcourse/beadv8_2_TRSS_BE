package com.backend.boundedContext.recommend.app;

import com.backend.boundedContext.recommend.app.dto.ProductRecommendationsDto;
import com.backend.boundedContext.recommend.app.dto.ProductRecommendationsDto.Basis;
import com.backend.boundedContext.recommend.app.dto.ProductRecommendationsDto.Item;
import com.backend.boundedContext.recommend.domain.BeautyCategory;
import com.backend.boundedContext.recommend.domain.BeautyProfile;
import com.backend.boundedContext.recommend.domain.IngredientTag;
import com.backend.boundedContext.recommend.domain.RecommendPolicy;
import com.backend.boundedContext.recommend.out.BeautyProfileRepository;
import com.backend.boundedContext.recommend.out.RecommendVectorRepository;
import com.backend.boundedContext.recommend.out.RecommendVectorRepository.RecommendedRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RecommendGetProductsUseCase {
    private final BeautyProfileRepository beautyProfileRepository;
    private final RecommendVectorRepository recommendVectorRepository;
    private final RecommendPolicy recommendPolicy;

    /** 설문이 있으면 맞춤 추천, 없거나 비회원(memberId null)이면 판매 중 뷰티 상품 최신순 */
    public ProductRecommendationsDto getProducts(Long memberId, int size) {
        BeautyProfile profile = memberId == null ? null : beautyProfileRepository.findByMemberId(memberId).orElse(null);
        if (profile == null) {
            List<Item> items = recommendVectorRepository.findLatest(size).stream()
                    .map(row -> new Item(row.productId(), row.name(), row.price(), row.thumbnailUrl(), row.categoryName(),
                            null, Set.of()))
                    .toList();
            return new ProductRecommendationsDto(false, null, items);
        }

        Set<IngredientTag> preferred = profile.getPreferredIngredients();
        Set<IngredientTag> avoided = profile.getAvoidedIngredients();
        Set<BeautyCategory> interests = profile.getInterestCategories();
        List<RecommendedRow> rows = recommendVectorRepository.findRecommended(memberId, codes(preferred), codes(avoided),
                codes(interests), recommendPolicy.getPreferredIngredientWeight(), recommendPolicy.getInterestCategoryWeight(), size);

        List<Item> items = rows.stream().map(row -> {
            Set<IngredientTag> matched = EnumSet.noneOf(IngredientTag.class);
            row.ingredientTags().stream().map(IngredientTag::valueOf).filter(preferred::contains).forEach(matched::add);
            BeautyCategory category = row.beautyCategory() == null ? null : BeautyCategory.valueOf(row.beautyCategory());
            BeautyCategory matchedCategory = interests.contains(category) ? category : null;
            return new Item(row.productId(), row.name(), row.price(), row.thumbnailUrl(), row.categoryName(),
                    RecommendReasonFormatter.format(matched, matchedCategory, avoided), matched);
        }).toList();
        return new ProductRecommendationsDto(true, new Basis(profile.getSkinType(), profile.getConcerns(), avoided), items);
    }

    private static List<String> codes(Collection<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).toList();
    }
}

package com.backend.boundedContext.recommend.app;

import com.backend.boundedContext.recommend.domain.RecommendProduct;
import com.backend.boundedContext.recommend.out.RecommendProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecommendRetagUseCase {
    private final RecommendProductRepository recommendProductRepository;

    /**
     * 모든 추천 상품의 전성분을 지금 사전으로 다시 읽는다. 태그가 바뀐 상품 수를 돌려준다.
     * 사전에 동의어를 더해도 이걸 하지 않으면 기존 상품에서 피하는 성분이 빠지지 않는다.
     * shortcut: 전체를 한 번에 읽는다. 상품이 수만 개가 되면 나눠 읽는다.
     */
    public int retagAll() {
        int changed = 0;
        for (RecommendProduct product : recommendProductRepository.findAll()) {
            if (product.retag()) changed++;
        }
        return changed;
    }
}

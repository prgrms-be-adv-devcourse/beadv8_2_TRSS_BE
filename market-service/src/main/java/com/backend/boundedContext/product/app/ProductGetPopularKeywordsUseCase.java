package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.PopularKeywordDto;
import com.backend.boundedContext.product.domain.ProductPolicy;
import com.backend.boundedContext.product.out.keyword.PopularKeywordPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 인기 검색어 순위. 최근 windowHours시간의 검색을 최근일수록 크게(반감기 halfLifeHours) 더한 점수로 순위를 내고,
 * 한 시간 전 기준 순위와 비교해 변동을 붙인다. 감쇠 덕분에 겹치는 시간이 많아도 최근 한 시간의 변화가 순위에 드러난다.
 * 합친 결과는 cacheSeconds 동안 캐시해서 요청마다 Redis에서 합치지 않는다.
 * Redis 조회가 실패하면 빈 목록을 돌려주고 그것도 캐시한다 (Redis 장애 동안 요청마다 제한 시간을 기다리지 않게).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductGetPopularKeywordsUseCase {
    private final PopularKeywordPort popularKeywordPort;
    private final ProductPolicy productPolicy;

    /** 캐시한 순위와 만료 시각. 여러 요청이 동시에 만료를 보면 각자 다시 합칠 수 있지만, 결과가 같아 괜찮다 */
    private volatile Cached cached;

    public List<PopularKeywordDto> getPopularKeywords(LocalDateTime now) {
        Cached current = cached;
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.keywords();
        }
        List<PopularKeywordDto> keywords = load(now);
        cached = new Cached(keywords, now.plusSeconds(productPolicy.getPopularKeywordCacheSeconds()));
        return keywords;
    }

    private List<PopularKeywordDto> load(LocalDateTime now) {
        int hours = productPolicy.getPopularKeywordWindowHours();
        double halfLife = productPolicy.getPopularKeywordHalfLifeHours();
        double minScore = productPolicy.getPopularKeywordMinScore();
        int limit = productPolicy.getPopularKeywordLimit();
        try {
            List<String> ranking = popularKeywordPort.findTop(now, hours, halfLife, minScore, limit);
            List<String> previous = popularKeywordPort.findTop(now.minusHours(1), hours, halfLife, minScore, limit);
            return withRankChange(ranking, previous);
        } catch (Exception e) {
            log.warn("인기 검색어를 읽지 못해 빈 목록을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    /** 지금 순위에 한 시간 전 순위와의 차이를 붙인다. 한 시간 전 순위에 없던 검색어는 null */
    static List<PopularKeywordDto> withRankChange(List<String> ranking, List<String> previous) {
        List<PopularKeywordDto> result = new ArrayList<>(ranking.size());
        for (int i = 0; i < ranking.size(); i++) {
            int rank = i + 1;
            int previousIndex = previous.indexOf(ranking.get(i));
            Integer rankChange = previousIndex < 0 ? null : (previousIndex + 1) - rank;
            result.add(new PopularKeywordDto(rank, ranking.get(i), rankChange));
        }
        return List.copyOf(result);
    }

    private record Cached(List<PopularKeywordDto> keywords, LocalDateTime expiresAt) {
    }
}

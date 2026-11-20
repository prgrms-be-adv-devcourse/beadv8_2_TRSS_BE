package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.ProductPolicy;
import com.backend.boundedContext.product.out.keyword.PopularKeywordPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 구매자 검색어를 인기 검색어로 센다.
 * 띄어쓰기·대소문자만 다른 검색어("수분크림", "수분 크림", "SUN크림", "sun 크림")는 한 검색어로 센다.
 * 띄어쓰기를 모두 없앤 소문자 값으로 세고, 순위에는 마지막으로 검색한 형태(공백 정리, 소문자)를 보여 준다.
 */
@Service
@RequiredArgsConstructor
public class ProductPopularKeywordSupport {
    private final PopularKeywordPort popularKeywordPort;
    private final ProductPolicy productPolicy;

    /**
     * 상품 검색 한 번을 센다. 검색어가 있는 첫 페이지 검색이고 결과가 있을 때만 센다.
     * 다음 페이지(더 보기)는 같은 검색을 이어 보는 것이라 세지 않고, 결과가 0건인 검색어는 순위에 올리지 않는다.
     */
    public void recordSearch(String keyword, int pageNumber, long totalElements, LocalDateTime now) {
        if (pageNumber != 0 || totalElements == 0) return;
        String normalized = normalize(keyword);
        if (normalized == null || normalized.length() > productPolicy.getPopularKeywordMaxLength()) return;
        popularKeywordPort.increase(countKey(normalized), normalized, now);
    }

    /** 세는 key: 정규화한 검색어에서 띄어쓰기를 모두 없앤다 */
    static String countKey(String normalized) {
        return normalized.replace(" ", "");
    }

    /** 앞뒤 공백을 지우고, 이어진 공백을 하나로, 영문은 소문자로 바꾼다. 비어 있으면 null */
    static String normalize(String keyword) {
        if (keyword == null) return null;
        String normalized = keyword.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}

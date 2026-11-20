package com.backend.boundedContext.product.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProductSortTest {

    @DisplayName("정렬을 보내지 않으면 검색어가 있을 때 관련도순, 없을 때 최신순이고, 검색어 없는 관련도순은 최신순이다")
    @ParameterizedTest(name = "{0}, 검색어 {1} → {2}")
    @CsvSource(nullValues = "null", value = {
            "null, true, RELEVANCE",
            "null, false, LATEST",
            "RELEVANCE, true, RELEVANCE",
            "RELEVANCE, false, LATEST",
            "PRICE_ASC, true, PRICE_ASC",
            "PRICE_DESC, false, PRICE_DESC",
            "LATEST, true, LATEST"
    })
    void resolve(ProductSort requested, boolean hasKeyword, ProductSort expected) {
        assertThat(ProductSort.resolve(requested, hasKeyword)).isEqualTo(expected);
    }
}

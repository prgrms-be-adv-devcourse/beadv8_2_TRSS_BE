package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 상품 컨텍스트 개발용 초기 데이터. 이미 데이터가 있으면 아무것도 하지 않는다.
 */
@Configuration
@RequiredArgsConstructor
public class ProductDataInit {
    private final ProductFacade productFacade;

    /** 기획서 1장의 카테고리. 넣은 순서가 정렬 순서가 된다. */
    private static final Map<String, List<String>> BASE_CATEGORIES = new LinkedHashMap<>();

    static {
        BASE_CATEGORIES.put("뷰티", List.of("메이크업", "바디케어", "선케어", "마스크팩", "클렌징", "스킨케어", "헤어케어", "건강식품"));
        BASE_CATEGORIES.put("의류", List.of("상의", "아우터", "바지", "원피스/스커트", "신발", "가방", "모자", "소품"));
    }

    @Bean
    @Order(2)
    public ApplicationRunner productDataInitApplicationRunner() {
        return args -> productFacade.createBaseCategories(BASE_CATEGORIES);
    }
}

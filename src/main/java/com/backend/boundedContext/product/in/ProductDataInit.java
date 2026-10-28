package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.ProductImageFile;
import com.backend.boundedContext.product.app.dto.ProductSampleDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 상품 컨텍스트 개발용 초기 데이터. 이미 데이터가 있으면 아무것도 하지 않는다.
 * 카테고리는 항상 넣고, 샘플 상품은 회원 시드의 승인된 판매자(2번 회원)가 있을 때만 넣는다.
 * 샘플 상품의 대표 이미지는 모두 {@value #SAMPLE_THUMBNAIL}을 쓴다 (상품마다 파일은 따로 저장된다).
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ProductDataInit {
    private final ProductFacade productFacade;

    /** MemberDataInit이 승인된 판매자로 만드는 시드 회원 ID (seller@palette.test) */
    private static final long SEED_SELLER_MEMBER_ID = 2L;

    private static final String SAMPLE_THUMBNAIL = "sample/product-sample.png";

    /** 기획서 1장의 카테고리. 넣은 순서가 정렬 순서가 된다. */
    private static final Map<String, List<String>> BASE_CATEGORIES = new LinkedHashMap<>();

    static {
        BASE_CATEGORIES.put("뷰티", List.of("메이크업", "바디케어", "선케어", "마스크팩", "클렌징", "스킨케어", "헤어케어", "건강식품"));
        BASE_CATEGORIES.put("의류", List.of("상의", "아우터", "바지", "원피스/스커트", "신발", "가방", "모자", "소품"));
    }

    /** 소분류마다 2개. 재고가 5개 이하인 상품을 섞어서 품절·재고 부족 흐름을 확인할 수 있게 한다. */
    private static final List<ProductSampleDto> SAMPLE_PRODUCTS = List.of(
            sample("뷰티", "메이크업", "벨벳 립 틴트", 15_000, 50),
            sample("뷰티", "메이크업", "롱래스팅 쿠션 파운데이션", 32_000, 3),
            sample("뷰티", "바디케어", "시어버터 바디로션", 18_000, 40),
            sample("뷰티", "바디케어", "릴렉싱 바디워시", 12_000, 60),
            sample("뷰티", "선케어", "데일리 선크림 SPF50+", 21_000, 80),
            sample("뷰티", "선케어", "톤업 선스틱", 17_000, 2),
            sample("뷰티", "마스크팩", "수분 시트 마스크 10매", 15_000, 100),
            sample("뷰티", "마스크팩", "진정 클레이 마스크", 22_000, 30),
            sample("뷰티", "클렌징", "약산성 폼 클렌저", 13_000, 70),
            sample("뷰티", "클렌징", "딥 클렌징 오일", 24_000, 5),
            sample("뷰티", "스킨케어", "히알루론 수분 크림", 28_000, 45),
            sample("뷰티", "스킨케어", "비타민 C 세럼", 35_000, 1),
            sample("뷰티", "헤어케어", "단백질 헤어 트리트먼트", 19_000, 35),
            sample("뷰티", "헤어케어", "두피 쿨링 샴푸", 16_000, 55),
            sample("뷰티", "건강식품", "멀티비타민 60정", 29_000, 40),
            sample("뷰티", "건강식품", "콜라겐 스틱 30포", 39_000, 4),
            sample("의류", "상의", "오버핏 코튼 티셔츠", 19_000, 90),
            sample("의류", "상의", "스트라이프 셔츠", 39_000, 25),
            sample("의류", "아우터", "울 블렌드 코트", 159_000, 8),
            sample("의류", "아우터", "경량 패딩 점퍼", 89_000, 3),
            sample("의류", "바지", "와이드 데님 팬츠", 49_000, 40),
            sample("의류", "바지", "밴딩 슬랙스", 39_000, 35),
            sample("의류", "원피스/스커트", "플리츠 롱스커트", 45_000, 20),
            sample("의류", "원피스/스커트", "셔츠 원피스", 59_000, 2),
            sample("의류", "신발", "캔버스 스니커즈", 59_000, 30),
            sample("의류", "신발", "레더 로퍼", 89_000, 5),
            sample("의류", "가방", "나일론 백팩", 69_000, 25),
            sample("의류", "가방", "미니 크로스백", 45_000, 15),
            sample("의류", "모자", "볼캡", 25_000, 60),
            sample("의류", "모자", "버킷햇", 29_000, 1),
            sample("의류", "소품", "울 머플러", 35_000, 20),
            sample("의류", "소품", "가죽 카드지갑", 29_000, 40)
    );

    @Bean
    @Order(2)   // 회원 시드(@Order(1)) 다음에 실행해야 시드 판매자가 있다
    public ApplicationRunner productDataInitApplicationRunner() {
        return args -> {
            productFacade.createBaseCategories(BASE_CATEGORIES);

            int created = productFacade.createSampleProducts(SEED_SELLER_MEMBER_ID, SAMPLE_PRODUCTS, sampleThumbnail());
            if (created > 0) log.info("샘플 상품 {}개 등록 (판매자 회원 ID {})", created, SEED_SELLER_MEMBER_ID);
        };
    }

    private static ProductImageFile sampleThumbnail() throws IOException {
        try (InputStream in = new ClassPathResource(SAMPLE_THUMBNAIL).getInputStream()) {
            return new ProductImageFile(in.readAllBytes());
        }
    }

    private static ProductSampleDto sample(String parentCategoryName, String categoryName, String name, long price, int stock) {
        return new ProductSampleDto(parentCategoryName, categoryName, name, price, stock);
    }
}

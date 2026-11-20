package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductDiscount;
import com.backend.boundedContext.product.domain.ProductImage;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductDiscountRepository;
import com.backend.boundedContext.product.out.ProductImageRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.wishlist.WishlistRepository;
import com.backend.boundedContext.review.domain.Review;
import com.backend.boundedContext.review.out.ReviewRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

/**
 * 상품 읽기 측정 테스트의 공통 준비. 측정은 여러 스레드가 실제로 커밋된 데이터를 읽어야 해서 {@code @Transactional}을 붙이지 않고,
 * 만든 데이터는 끝나면 SQL로 지운다. 로컬 docker DB·Redis만 쓴다.
 *
 * <p>측정 대상 상품(상세): 옵션 2개, 진행 중 할인, 대표 이미지 1개·상세 이미지 3개, 리뷰 3개, 찜 3개.
 * 검색 대상: 같은 검색어가 들어간 상품 {@value #SEARCH_PRODUCTS}개.
 * 결과는 {@code [측정]}으로 시작하는 줄로 로그에 남긴다. 단언하지 않는다.
 */
abstract class ProductReadMeasureSupport {
    static final Logger log = LoggerFactory.getLogger("measure");

    static final long SELLER_ID = 9_100_001L;
    static final long LOGIN_MEMBER_ID = 9_100_101L;
    static final int SEARCH_PRODUCTS = 30;
    static final int WARMUP = 20;
    static final int ROUNDS = 200;

    @Autowired ProductFacade productFacade;
    @Autowired ProductRepository productRepository;
    @Autowired ProductOptionRepository productOptionRepository;
    @Autowired OptionStockRepository optionStockRepository;
    @Autowired ProductDiscountRepository productDiscountRepository;
    @Autowired ProductImageRepository productImageRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired ReviewRepository reviewRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired NamedParameterJdbcTemplate jdbcTemplate;

    @MockitoBean
    MemberApi memberApi;

    /** 다른 상품 통합 테스트와 mock 조합을 맞춘다 */
    @MockitoBean
    OrderApi orderApi;

    /** 검색어. 실행마다 달라 로컬 DB의 다른 상품과 섞이지 않는다 */
    final String keyword = "msr" + UUID.randomUUID().toString().substring(0, 8);
    final List<Long> productIds = new ArrayList<>();
    Long detailProductId;

    @BeforeEach
    void prepare() {
        givenSellerOk();
        transactionTemplate.executeWithoutResult(status -> {
            Category skincare = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow();
            LocalDateTime now = LocalDateTime.now();

            Product detail = productRepository.save(Product.create(SELLER_ID, skincare, "측정 상세 크림 " + keyword,
                    "측정용 상품 설명", 30_000, List.of("용량")));
            detailProductId = detail.getId();
            productIds.add(detailProductId);
            ProductOption small = productOptionRepository.save(ProductOption.create(detail, List.of("50ml"), 0));
            ProductOption large = productOptionRepository.save(ProductOption.create(detail, List.of("100ml"), 5_000));
            optionStockRepository.save(OptionStock.init(small, 30));
            optionStockRepository.save(OptionStock.init(large, 30));

            // 진행 중 할인: 시작 시각은 지금 이후여야 만들 수 있어서 만든 뒤 하루 전으로 옮긴다
            ProductDiscount discount = ProductDiscount.create(detail, 24_000, now.plusHours(1), now.plusDays(7), now);
            ReflectionTestUtils.setField(discount, "startsAt", now.minusDays(1));
            productDiscountRepository.save(discount);

            productImageRepository.save(ProductImage.thumbnail(detail, "measure/thumbnail.png"));
            for (int i = 0; i < 3; i++) {
                productImageRepository.save(ProductImage.detail(detail, "measure/detail-" + i + ".png", i));
            }
            for (int i = 0; i < 3; i++) {
                long memberId = 9_100_200L + i;
                reviewRepository.save(Review.create(ThreadLocalRandom.current().nextLong(1L << 40), memberId, detailProductId,
                        small.getId(), "50ml", "리뷰어", 5, "측정용 리뷰 내용입니다"));
                wishlistRepository.insertIfAbsent(memberId, detailProductId, now);
            }

            for (int i = 1; i <= SEARCH_PRODUCTS; i++) {
                Product product = productRepository.save(Product.create(SELLER_ID, skincare,
                        "측정 크림 " + keyword + " " + i, "설명", 20_000 + i));
                ProductOption option = productOptionRepository.save(ProductOption.createDefault(product));
                optionStockRepository.save(OptionStock.init(option, 10));
                productIds.add(product.getId());
            }
        });
    }

    @AfterEach
    void cleanUp() {
        if (productIds.isEmpty()) return;
        Map<String, Object> ids = Map.of("ids", productIds);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("delete from wishlist where product_id in (:ids)", ids);
            jdbcTemplate.update("delete from review where product_id in (:ids)", ids);
            jdbcTemplate.update("delete from product_discount where product_id in (:ids)", ids);
            jdbcTemplate.update("delete from product_image where product_id in (:ids)", ids);
            jdbcTemplate.update("delete from stock_history where option_id in (select id from product_option where product_id in (:ids))", ids);
            jdbcTemplate.update("delete from option_stock where option_id in (select id from product_option where product_id in (:ids))", ids);
            jdbcTemplate.update("delete from product_option where product_id in (:ids)", ids);
            jdbcTemplate.update("delete from product where id in (:ids)", ids);
        });
    }

    void givenSellerOk() {
        given(memberApi.getSellers(anyCollection()))
                .willReturn(List.of(new SellerDto(SELLER_ID, 1L, "측정 상점", SellerStatus.APPROVED)));
    }

    void givenSellerSlow(long millis) {
        given(memberApi.getSellers(anyCollection())).willAnswer(invocation -> {
            Thread.sleep(millis);
            return List.of(new SellerDto(SELLER_ID, 1L, "측정 상점", SellerStatus.APPROVED));
        });
    }

    /** 실제 회원 클라이언트처럼 응답을 받지 못했을 때의 예외를 던진다 */
    void givenSellerFails() {
        given(memberApi.getSellers(anyCollection())).willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));
    }

    PageResponse<ProductSummaryDto> search() {
        return productFacade.searchProducts(keyword, null, ProductSort.LATEST, true, false, PageRequest.of(0, 20));
    }

    /** 걸린 시간(밀리초, 소수 첫째 자리까지) */
    static double timed(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return (System.nanoTime() - start) / 100_000 / 10.0;
    }

    /** 워밍업 뒤 rounds번 잰 시간(밀리초)을 정렬해 돌려준다 */
    static List<Double> latencies(Runnable action, int warmup, int rounds) {
        for (int i = 0; i < warmup; i++) action.run();
        List<Double> result = new ArrayList<>(rounds);
        for (int i = 0; i < rounds; i++) result.add(timed(action));
        result.sort(null);
        return result;
    }

    static double percentile(List<Double> sorted, int percent) {
        int index = (int) Math.ceil(sorted.size() * percent / 100.0) - 1;
        return sorted.get(Math.max(0, index));
    }

    static String summary(List<Double> sorted) {
        return "p50 %.1fms, p95 %.1fms, 최대 %.1fms (%d번)".formatted(
                percentile(sorted, 50), percentile(sorted, 95), sorted.getLast(), sorted.size());
    }

    /** 성공하면 결과를, 실패하면 예외 종류와 메시지를 적는다 */
    static String outcome(Supplier<String> action) {
        try {
            return "성공, " + action.get();
        } catch (RuntimeException e) {
            String code = e instanceof DomainException domain ? domain.getResultCode() : e.getMessage();
            return "실패, " + e.getClass().getSimpleName() + " " + code;
        }
    }

    static void record(String item, Object value) {
        log.info("[측정] | {} | {} |", item, value);
    }
}

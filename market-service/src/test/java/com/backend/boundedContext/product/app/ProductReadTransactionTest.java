package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductDetailDto;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

/**
 * 검색·상세가 회원 서비스(상호명)를 DB 트랜잭션 밖에서 부르는지, 상세 쿼리 수가 늘지 않았는지 확인한다.
 * 퍼사드가 트랜잭션을 잡지 않는 것과 지연 로딩 쿼리를 보려고 이 테스트는 {@code @Transactional}을 붙이지 않고, 만든 데이터는 끝나면 지운다.
 * (테스트 트랜잭션 안이면 영속성 컨텍스트에 이미 있는 엔티티는 지연 로딩 쿼리 없이 읽혀 쿼리 수가 실제보다 적게 나온다)
 */
@SpringBootTest
class ProductReadTransactionTest {
    private static final String KEY = "zqx트랜잭션";
    private static final long SELLER_ID = 301L;
    private static final long MEMBER_ID = 9_100_301L;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private OptionStockRepository optionStockRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @MockitoBean
    private MemberApi memberApi;

    /** 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트를 같이 쓴다 */
    @MockitoBean
    private OrderApi orderApi;

    private final List<Long> productIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (productIds.isEmpty()) return;
        Map<String, Object> ids = Map.of("ids", productIds);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("delete from wishlist where product_id in (:ids)", ids);
            jdbcTemplate.update("delete from stock_history where option_id in (select id from product_option where product_id in (:ids))", ids);
            jdbcTemplate.update("delete from option_stock where option_id in (select id from product_option where product_id in (:ids))", ids);
            jdbcTemplate.update("delete from product_option where product_id in (:ids)", ids);
            jdbcTemplate.update("delete from product where id in (:ids)", ids);
        });
    }

    @Test
    @DisplayName("검색: 상호명은 DB 트랜잭션이 끝난 뒤에 회원 서비스에서 읽는다")
    void searchReadsBusinessNameOutsideTransaction() {
        createProduct(KEY + " 크림");
        List<Boolean> transactionActive = new ArrayList<>();
        given(memberApi.getSellers(anyCollection())).willAnswer(invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(new SellerDto(SELLER_ID, 1L, "팔레트 상점", SellerStatus.APPROVED));
        });

        PageResponse<ProductSummaryDto> result = productFacade.searchProducts(KEY, null, ProductSort.LATEST, true, false,
                PageRequest.of(0, 20));

        assertThat(result.items()).singleElement()
                .extracting(ProductSummaryDto::businessName).isEqualTo("팔레트 상점");
        assertThat(transactionActive).containsExactly(false);
    }

    @Test
    @DisplayName("상세: 상호명은 DB 트랜잭션이 끝난 뒤에 회원 서비스에서 읽는다")
    void detailReadsBusinessNameOutsideTransaction() {
        Long productId = createProduct(KEY + " 크림");
        List<Boolean> transactionActive = new ArrayList<>();
        given(memberApi.getSellers(anyCollection())).willAnswer(invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(new SellerDto(SELLER_ID, 1L, "팔레트 상점", SellerStatus.APPROVED));
        });

        assertThat(productFacade.getProductDetail(productId).businessName()).isEqualTo("팔레트 상점");
        assertThat(transactionActive).containsExactly(false);
    }

    @Test
    @DisplayName("상세: 회원 서비스가 실패해도 상세는 성공하고, 상호명만 비운다")
    void detailWithoutBusinessNameWhenMemberFails() {
        Long productId = createProduct(KEY + " 크림");
        given(memberApi.getSellers(anyCollection())).willThrow(new IllegalStateException("member down"));

        ProductDetailDto detail = productFacade.getProductDetail(productId);

        assertThat(detail.productId()).isEqualTo(productId);
        assertThat(detail.sellerId()).isEqualTo(SELLER_ID);
        assertThat(detail.businessName()).isNull();
    }

    /**
     * 상품(소분류·대분류 함께), 옵션 재고, 진행 중 할인, 리뷰 통계, 이미지(대표·상세 함께), 찜 수. 로그인이면 찜 여부가 더해진다.
     * 카테고리 지연 로딩이나 이미지 조회가 다시 늘면 여기서 걸린다.
     */
    @Test
    @DisplayName("상세 쿼리 수: 비로그인 6번, 로그인 7번")
    void detailQueryCount() {
        Long productId = createProduct(KEY + " 크림");
        given(memberApi.getSellers(anyCollection()))
                .willReturn(List.of(new SellerDto(SELLER_ID, 1L, "팔레트 상점", SellerStatus.APPROVED)));

        assertThat(ThreadQueryCounter.record(() -> productFacade.getProductDetail(productId))).hasSize(6);
        assertThat(ThreadQueryCounter.record(() -> productFacade.getProductDetail(productId, MEMBER_ID))).hasSize(7);
    }

    private Long createProduct(String name) {
        return transactionTemplate.execute(status -> {
            Category skincare = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow();
            Product product = productRepository.save(Product.create(SELLER_ID, skincare, name, "설명", 10_000));
            ProductOption option = productOptionRepository.save(ProductOption.createDefault(product));
            optionStockRepository.save(OptionStock.init(option, 10));
            productIds.add(product.getId());
            return product.getId();
        });
    }
}

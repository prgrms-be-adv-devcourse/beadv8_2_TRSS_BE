package com.backend.boundedContext.product.app;

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
 * 검색이 회원 서비스(상호명)를 DB 트랜잭션 밖에서 부르는지 확인한다.
 * 검색 퍼사드가 트랜잭션을 잡지 않는 것을 보려고 이 테스트는 {@code @Transactional}을 붙이지 않고, 만든 데이터는 끝나면 지운다.
 */
@SpringBootTest
class ProductSearchTransactionTest {
    private static final String KEY = "zqx트랜잭션";
    private static final long SELLER_ID = 301L;

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

    private void createProduct(String name) {
        transactionTemplate.executeWithoutResult(status -> {
            Category skincare = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow();
            Product product = productRepository.save(Product.create(SELLER_ID, skincare, name, "설명", 10_000));
            ProductOption option = productOptionRepository.save(ProductOption.createDefault(product));
            optionStockRepository.save(OptionStock.init(option, 10));
            productIds.add(product.getId());
        });
    }
}

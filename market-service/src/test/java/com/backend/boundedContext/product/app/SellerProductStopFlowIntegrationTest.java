package com.backend.boundedContext.product.app;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.out.CartRepository;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductStoppedBy;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.StockHistoryRepository;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

/**
 * 판매자 철회·제재로 회원 서비스가 상품 중지·재개를 호출했을 때, 상품 판매 중지 → 결제 대기 주문 취소 → 재고 복원까지 이어지는지 확인한다.
 * 상품·주문은 실제로 돌리고 회원 서비스만 목으로 둔다. 회원 쪽 호출 여부는 회원 서비스의 유스케이스 테스트가,
 * 서비스 간 HTTP 연결은 E2E가 확인한다.
 * 이벤트 리스너(AFTER_COMMIT)가 섞여도 실제 흐름과 같게 돌도록 @Transactional을 붙이지 않고, 만든 데이터는 @AfterEach에서 지운다.
 */
@SpringBootTest
class SellerProductStopFlowIntegrationTest {

    private static final int STOCK = 10;
    private static final int ORDER_QUANTITY = 3;
    private static final long PRICE = 10_000L;

    @Autowired
    private ProductApi productApi;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private MemberApi memberApi;

    private Long sellerId;
    private Long buyerId;
    private Long productId;
    private Long optionId;

    @BeforeEach
    void setUp() {
        // 회원 데이터는 다른 서비스라 겹치지 않는 임의 ID를 쓴다
        sellerId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        buyerId = sellerId + 1;
        given(memberApi.getSellers(anyCollection()))
                .willReturn(List.of(new SellerDto(sellerId, sellerId + 2, "팔레트 뷰티", SellerStatus.APPROVED)));

        transactionTemplate.executeWithoutResult(status -> {
            Product product = productRepository.save(Product.create(
                    sellerId, categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow(), "판매 중지 흐름 테스트 크림", "설명", PRICE));
            productId = product.getId();
            optionId = productOptionRepository.save(ProductOption.createDefault(product, STOCK)).getId();
        });
    }

    @AfterEach
    void cleanUp() {
        transactionTemplate.executeWithoutResult(status -> {
            orderRepository.deleteAll(orderRepository.findByBuyerId(buyerId, Pageable.unpaged()));
            cartRepository.findByMemberId(buyerId).ifPresent(cartRepository::delete);
            stockHistoryRepository.deleteAll(stockHistoryRepository.findByOptionId(optionId, Pageable.unpaged()).getContent());
            productOptionRepository.deleteById(optionId);
            productRepository.deleteById(productId);
        });
    }

    @Test
    @DisplayName("판매 중지: 판매 중 상품이 중지되고, 그 상품이 든 결제 대기 주문이 취소되며 재고가 복원된다")
    void stop_stopsSalesAndCancelsPendingOrders() {
        Long orderId = createPendingOrder();
        assertThat(stock()).isEqualTo(STOCK - ORDER_QUANTITY);

        productApi.stopAllBySeller(sellerId, "위조 상품 판매");

        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.getStatus()).isEqualTo(ProductSaleStatus.STOPPED);
        assertThat(product.getStoppedBy()).isEqualTo(ProductStoppedBy.SELLER_WITHDRAWN);
        assertThat(product.getStoppedReason()).isEqualTo("위조 상품 판매");

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(order.getCancelType()).isEqualTo(OrderCancelType.PRODUCT_STOPPED);

        assertThat(stock()).isEqualTo(STOCK);
    }

    @Test
    @DisplayName("판매 재개: 판매자 철회로 중지된 상품이 다시 판매 중이 되고, 취소된 주문은 되살아나지 않는다")
    void resume_resumesSales() {
        Long orderId = createPendingOrder();
        productApi.stopAllBySeller(sellerId, "폐업 예정");

        productApi.resumeAllBySeller(sellerId);

        assertThat(productRepository.findById(productId).orElseThrow().getStatus()).isEqualTo(ProductSaleStatus.ON_SALE);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(stock()).isEqualTo(STOCK);
    }

    private Long createPendingOrder() {
        orderFacade.addCartItem(buyerId, optionId, ORDER_QUANTITY);
        Long cartItemId = orderFacade.getCart(buyerId).items().getFirst().itemId();
        return orderFacade.createOrder(buyerId, null, List.of(cartItemId), PRICE * ORDER_QUANTITY).orderId();
    }

    private int stock() {
        return productOptionRepository.findById(optionId).orElseThrow().getStock();
    }
}

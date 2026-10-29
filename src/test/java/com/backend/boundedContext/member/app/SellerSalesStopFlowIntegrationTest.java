package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.in.dto.SellerApplyRequest;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
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
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판매자 제재·철회가 상품 판매 중지 → 결제 대기 주문 취소 → 재고 복원까지 이어지는지 목 없이 확인한다.
 * 회원 → 상품 → 주문 → 상품으로 모듈 경계를 실제로 넘으므로, 연결이 끊기면 이 테스트가 실패한다.
 * 이벤트 리스너(AFTER_COMMIT)가 섞여도 실제 흐름과 같게 돌도록 @Transactional을 붙이지 않고, 만든 데이터는 @AfterEach에서 지운다.
 * 로컬 Docker PostgreSQL·Redis가 필요하다.
 */
@SpringBootTest
class SellerSalesStopFlowIntegrationTest {

    private static final int STOCK = 10;
    private static final int ORDER_QUANTITY = 3;
    private static final long PRICE = 10_000L;

    @Autowired
    private MemberSellerFacade memberSellerFacade;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private SellerStatusHistoryRepository sellerStatusHistoryRepository;

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

    private Long sellerMemberId;
    private Long buyerId;
    private Long sellerId;
    private Long productId;
    private Long optionId;

    @BeforeEach
    void setUp() {
        sellerMemberId = saveMember("판매자");
        buyerId = saveMember("구매자");
        sellerId = memberSellerFacade.applySeller(sellerMemberId, applyRequest()).sellerId();
        memberSellerFacade.approveSeller(sellerId);

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
            sellerStatusHistoryRepository.deleteAll(sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(sellerId));
            sellerRepository.deleteById(sellerId);
            memberRepository.deleteAllById(List.of(sellerMemberId, buyerId));
        });
    }

    @Test
    @DisplayName("제재: 판매 중 상품이 중지되고, 그 상품이 든 결제 대기 주문이 취소되며 재고가 복원된다")
    void sanction_stopsSalesAndCancelsPendingOrders() {
        Long orderId = createPendingOrder();
        assertThat(stock()).isEqualTo(STOCK - ORDER_QUANTITY);

        memberSellerFacade.sanctionSeller(sellerId, "위조 상품 판매");

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
    @DisplayName("철회 요청: 상품 중지·결제 대기 주문 취소·재고 복원이 일어나고, 철회 거절 시 상품이 다시 판매 중이 된다")
    void withdrawal_requestStopsSales_rejectResumes() {
        Long orderId = createPendingOrder();

        memberSellerFacade.requestWithdrawal(sellerMemberId, "폐업 예정");

        assertThat(productRepository.findById(productId).orElseThrow().getStatus()).isEqualTo(ProductSaleStatus.STOPPED);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(stock()).isEqualTo(STOCK);

        memberSellerFacade.rejectWithdrawal(sellerId, "진행 중인 판매 확인 필요");

        assertThat(productRepository.findById(productId).orElseThrow().getStatus()).isEqualTo(ProductSaleStatus.ON_SALE);
    }

    private Long createPendingOrder() {
        orderFacade.addCartItem(buyerId, optionId, ORDER_QUANTITY);
        Long cartItemId = orderFacade.getCart(buyerId).items().getFirst().itemId();
        return orderFacade.createOrder(buyerId, null, List.of(cartItemId), PRICE * ORDER_QUANTITY).orderId();
    }

    private int stock() {
        return productOptionRepository.findById(optionId).orElseThrow().getStock();
    }

    private Long saveMember(String name) {
        String email = "seller-sales-stop-it-" + UUID.randomUUID() + "@palette.com";
        return memberRepository.save(Member.signUp(email, "encoded-password", name)).getId();
    }

    private SellerApplyRequest applyRequest() {
        String businessNumber = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        return new SellerApplyRequest("팔레트 뷰티", businessNumber, "홍길동",
                "010-1234-5678", "국민은행", "123456-01-123456", "홍길동");
    }
}

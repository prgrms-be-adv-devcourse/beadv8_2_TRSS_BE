package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.ReturnFilter;
import com.backend.boundedContext.order.domain.ReturnItemDecision;
import com.backend.boundedContext.order.domain.ReturnReasonCode;
import com.backend.boundedContext.order.domain.ShippingAddress;
import com.backend.boundedContext.order.in.dto.AdminReturnSummaryResponse;
import com.backend.boundedContext.order.in.dto.ReturnResultResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.payment.out.PaymentApi;
import com.backend.shared.product.out.ProductApi;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 관리자 반품 목록·승인·거절을 실제 DB·실제 상품 재고·행 잠금으로 확인한다 (TRSS-222 관리자 반품).
 * - 승인: 고른 항목만 RETURN_RESTORE(수량 전체), restore_stock 저장, outbox OrderReturnedEvent 1건(refundAmount = pay_amount), 정산 후보(구매 확정 이벤트) 없음
 * - 삭제된 상품 복원 선택은 409 PRODUCT_DELETED로 주문·재고·outbox 모두 롤백, STOPPED 상품은 재고만 늘어남
 * - 거절: 기준일 전이면 PAID·outbox 없음, 지났으면 같은 트랜잭션에서 CONFIRMED(RETURN_REJECTED) + OrderConfirmedEvent(정산 후보)
 * - 동시성: 승인 ↔ 거절, 승인 ↔ 상품 삭제(양방향)
 * - 목록: 필터·정렬, 항목 N+1 없음
 * 회원·결제는 다른 서비스라 목으로 둔다. 상품 재고는 같은 서비스의 실제 ProductApi를 쓴다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
class OrderAdminReturnIntegrationTest {

    private static final long SELLER_MEMBER_ID = 7_700L;
    private static final int INITIAL_STOCK = 50;

    @Autowired private OrderFacade orderFacade;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductFacade productFacade;
    @Autowired private ProductApi productApi;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductOptionRepository productOptionRepository;
    @Autowired private OptionStockRepository optionStockRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @MockitoBean private PaymentApi paymentApi;
    @MockitoBean private MemberApi memberApi;

    private final long buyerId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
    private final long sellerId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();

    // 상품 2개, 상품마다 옵션 1개 (재고 50)
    private long productA, optionA, productB, optionB;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            var category = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow();
            Product a = productRepository.save(Product.create(sellerId, category, "반품 테스트 크림", "설명", 10_000));
            Product b = productRepository.save(Product.create(sellerId, category, "반품 테스트 토너", "설명", 5_000));
            productA = a.getId();
            productB = b.getId();
            optionA = saveOption(a).getId();
            optionB = saveOption(b).getId();
        });
        given(memberApi.getApprovedSellerByMemberId(SELLER_MEMBER_ID))
                .willReturn(new SellerDto(sellerId, SELLER_MEMBER_ID, "반품 테스트 상점", SellerStatus.APPROVED));
    }

    @AfterEach
    void cleanUp() {
        orderIds.forEach(id -> jdbcTemplate.update(
                "delete from outbox_event where aggregate_type = 'ORDER' and aggregate_id = ?", id));
        transactionTemplate.executeWithoutResult(status -> orderRepository.deleteAllById(orderIds));
        for (long optionId : List.of(optionA, optionB)) {
            jdbcTemplate.update("delete from stock_history where option_id = ?", optionId);
        }
        for (long productId : List.of(productA, productB)) {
            jdbcTemplate.update("delete from outbox_event where aggregate_type = 'PRODUCT' and aggregate_id = ?", productId);
        }
        transactionTemplate.executeWithoutResult(status -> {
            productOptionRepository.deleteById(optionA);
            productOptionRepository.deleteById(optionB);
            productRepository.deleteById(productA);
            productRepository.deleteById(productB);
        });
    }

    // ---------- 승인 ----------

    @Test
    @DisplayName("승인: 2개 중 1개만 복원 → RETURN_RESTORE 1건(수량 전체), restore_stock true/false 저장, OrderReturnedEvent 1건(refundAmount = pay_amount), 구매 확정 이벤트 없음")
    void approve_partialRestore() {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        long orderId = order.getId();
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK - 2);
        assertThat(stock(optionB)).isEqualTo(INITIAL_STOCK - 1);

        ReturnResultResponse response = orderFacade.approveReturn(orderId,
                List.of(new ReturnItemDecision(itemId(order, optionA), true), new ReturnItemDecision(itemId(order, optionB), false)));

        assertThat(response).isEqualTo(new ReturnResultResponse(orderId, OrderStatus.RETURNED, null));
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK);         // 2개 모두 복원
        assertThat(stock(optionB)).isEqualTo(INITIAL_STOCK - 1);     // 복원 안 함
        assertThat(jdbcTemplate.queryForList(
                "select option_id, change_qty, order_item_id from stock_history where order_id = ? and change_type = 'RETURN_RESTORE'", orderId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row).containsEntry("option_id", optionA);
                    assertThat(row).containsEntry("change_qty", 2);
                    assertThat(row).containsEntry("order_item_id", itemId(order, optionA));
                });
        assertThat(jdbcTemplate.queryForList(
                "select option_id, restore_stock from market_order_item where order_id = ? order by id", orderId))
                .extracting(row -> row.get("restore_stock"))
                .containsExactly(true, false);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, returned_at, pay_amount from market_order where id = ?", orderId);
        assertThat(row).containsEntry("status", "RETURNED");
        assertThat(row.get("returned_at")).isNotNull();
        assertThat(jdbcTemplate.queryForList(
                "select from_status from order_status_history where order_id = ? and to_status = 'RETURNED'", orderId))
                .singleElement().satisfies(h -> assertThat(h).containsEntry("from_status", "RETURN_REQUESTED"));

        // 반품 승인 이벤트 1건, 환불 금액 = 배송비 포함 결제 금액 전액
        assertThat(outbox(orderId, "OrderReturnedEvent")).singleElement().satisfies(payload -> {
            assertThat(payload).contains("\"refundAmount\": " + row.get("pay_amount"));
            assertThat(payload).contains("\"buyerId\": " + buyerId);
        });
        assertThat(((Number) row.get("pay_amount")).longValue()).isEqualTo(25_000 + 2_500);
        // 정산 후보는 구매 확정 이벤트에서만 만든다 → 반품 승인 주문은 후보 없음
        assertThat(outbox(orderId, "OrderConfirmedEvent")).isEmpty();
        // 승인 후에도 자동 확정 대상이 아니다
        assertThat(orderFacade.autoConfirmOrder(orderId, LocalDateTime.now().plusDays(30))).isFalse();
    }

    @Test
    @DisplayName("승인: 모두 복원하지 않으면 재고 이력 0건, 재고 그대로, 이벤트는 1건")
    void approve_noRestore() {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));

        orderFacade.approveReturn(order.getId(), decisions(order, false, false));

        assertThat(restoreHistoryCount(order.getId())).isZero();
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK - 2);
        assertThat(stock(optionB)).isEqualTo(INITIAL_STOCK - 1);
        assertThat(outbox(order.getId(), "OrderReturnedEvent")).hasSize(1);
    }

    @Test
    @DisplayName("승인: 삭제된 상품에 복원을 고르면 409 PRODUCT_DELETED, 주문 상태·restore_stock·재고·outbox 모두 롤백. 그 항목을 false로 다시 승인하면 성공")
    void approve_deletedProduct_rollsBack() {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        long orderId = order.getId();
        productFacade.deleteProduct(SELLER_MEMBER_ID, productB);

        assertThatThrownBy(() -> orderFacade.approveReturn(orderId, decisions(order, true, true)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode()).isEqualTo("409-PRODUCT_DELETED");

        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(jdbcTemplate.queryForList(
                "select restore_stock from market_order_item where order_id = ?", orderId))
                .extracting(r -> r.get("restore_stock")).containsOnlyNulls();
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK - 2);   // 앞 항목 복원도 롤백
        assertThat(restoreHistoryCount(orderId)).isZero();
        assertThat(outbox(orderId, "OrderReturnedEvent")).isEmpty();

        orderFacade.approveReturn(orderId, decisions(order, true, false));

        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURNED);
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK);
        assertThat(outbox(orderId, "OrderReturnedEvent")).hasSize(1);
    }

    @Test
    @DisplayName("승인: 판매 중지(STOPPED) 상품은 재고만 늘고 상품 상태는 STOPPED 그대로")
    void approve_stoppedProduct() {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        productFacade.stopProductByAdmin(productA, "허위 광고");

        orderFacade.approveReturn(order.getId(), decisions(order, true, false));

        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK);
        assertThat(productRepository.findById(productA).orElseThrow().getStatus()).isEqualTo(ProductSaleStatus.STOPPED);
    }

    @Test
    @DisplayName("승인: 이미 승인된 주문은 409 ORDER_STATE_INVALID, 재고·이벤트 추가 없음. 항목 불일치는 400이고 아무것도 바뀌지 않는다")
    void approve_twice_and_mismatch() {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        long orderId = order.getId();

        assertThatThrownBy(() -> orderFacade.approveReturn(orderId,
                List.of(new ReturnItemDecision(itemId(order, optionA), true))))
                .extracting(e -> ((DomainException) e).getResultCode()).isEqualTo(OrderErrorCode.RETURN_ITEMS_MISMATCH.resultCode());
        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURN_REQUESTED);

        orderFacade.approveReturn(orderId, decisions(order, true, false));
        assertThatThrownBy(() -> orderFacade.approveReturn(orderId, decisions(order, true, true)))
                .extracting(e -> ((DomainException) e).getResultCode()).isEqualTo(OrderErrorCode.ORDER_STATE_INVALID.resultCode());
        assertThatThrownBy(() -> orderFacade.rejectReturn(orderId, "늦은 거절"))
                .extracting(e -> ((DomainException) e).getResultCode()).isEqualTo(OrderErrorCode.ORDER_STATE_INVALID.resultCode());

        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK);
        assertThat(stock(optionB)).isEqualTo(INITIAL_STOCK - 1);
        assertThat(restoreHistoryCount(orderId)).isEqualTo(1);
        assertThat(outbox(orderId, "OrderReturnedEvent")).hasSize(1);
    }

    @Test
    @DisplayName("승인: 없는 주문은 404")
    void approve_notFound() {
        assertThatThrownBy(() -> orderFacade.approveReturn(Long.MAX_VALUE, List.of()))
                .extracting(e -> ((DomainException) e).getResultCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND.resultCode());
    }

    // ---------- 거절 ----------

    @Test
    @DisplayName("거절(배송 완료 3일): PAID, 거절 사유 저장, outbox 없음, 원래 기준일(배송 완료 + 7일)에 자동 확정 대상")
    void reject_beforeSevenDays() {
        LocalDateTime deliveredAt = LocalDateTime.now().minusDays(3);
        Order order = returnRequestedOrder(deliveredAt);
        long orderId = order.getId();

        ReturnResultResponse response = orderFacade.rejectReturn(orderId, "사용 흔적이 있어 반품할 수 없습니다");

        assertThat(response).isEqualTo(new ReturnResultResponse(orderId, OrderStatus.PAID, null));
        assertThat(jdbcTemplate.queryForMap(
                "select status, return_reject_reason, return_rejected_at, confirmed_at from market_order where id = ?", orderId))
                .containsEntry("status", "PAID")
                .containsEntry("return_reject_reason", "사용 흔적이 있어 반품할 수 없습니다")
                .containsEntry("confirmed_at", null);
        assertThat(outboxCount(orderId)).isZero();
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK - 2);   // 거절은 재고를 건드리지 않는다

        // 거절로 기간이 늘어나지 않는다: 배송 완료 + 7일 전에는 대상 아님, 지나면 대상
        assertThat(orderFacade.findAutoConfirmTargetIds(deliveredAt.plusDays(7).minusMinutes(1), orderId - 1, 100))
                .doesNotContain(orderId);
        assertThat(orderFacade.findAutoConfirmTargetIds(deliveredAt.plusDays(7).plusMinutes(1), orderId - 1, 100))
                .contains(orderId);
    }

    @Test
    @DisplayName("거절(배송 완료 8일): 같은 트랜잭션에서 CONFIRMED + RETURN_REJECTED, OrderConfirmedEvent 1건(정산 후보), 이력 RETURN_REQUESTED → PAID → CONFIRMED")
    void reject_afterSevenDays_confirmsImmediately() {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(8));
        long orderId = order.getId();

        ReturnResultResponse response = orderFacade.rejectReturn(orderId, "사용 흔적");

        assertThat(response).isEqualTo(new ReturnResultResponse(orderId, OrderStatus.CONFIRMED, ConfirmType.RETURN_REJECTED));
        assertThat(jdbcTemplate.queryForMap("select status, confirm_type from market_order where id = ?", orderId))
                .containsEntry("status", "CONFIRMED")
                .containsEntry("confirm_type", "RETURN_REJECTED");
        assertThat(jdbcTemplate.queryForList(
                "select from_status, to_status from order_status_history where order_id = ? order by id", orderId))
                .extracting(r -> r.get("from_status") + "->" + r.get("to_status"))
                .endsWith("PAID->RETURN_REQUESTED", "RETURN_REQUESTED->PAID", "PAID->CONFIRMED");
        // 정산 후보: 구매 확정 이벤트 1건(항목 2개, 항목 금액 기준이라 배송비 없음)
        assertThat(outbox(orderId, "OrderConfirmedEvent")).singleElement().satisfies(payload -> {
            assertThat(payload).contains("\"confirmType\": \"RETURN_REJECTED\"");
            assertThat(payload).contains("\"lineAmount\": 20000").contains("\"lineAmount\": 5000");
        });
        assertThat(outbox(orderId, "OrderReturnedEvent")).isEmpty();
    }

    @Test
    @DisplayName("거절 후 재요청은 409 RETURN_ALREADY_REQUESTED")
    void reject_thenRequestAgain() {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        orderFacade.rejectReturn(order.getId(), "사용 흔적");

        assertThatThrownBy(() -> orderFacade.requestReturn(buyerId, order.getId(), ReturnReasonCode.CHANGE_OF_MIND, null))
                .extracting(e -> ((DomainException) e).getResultCode()).isEqualTo(OrderErrorCode.RETURN_ALREADY_REQUESTED.resultCode());
    }

    // ---------- 목록 ----------

    @Test
    @DisplayName("목록: REQUESTED는 오래된 요청 순, RETURNED·REJECTED는 해당 주문만, ALL은 최근 요청 순. 항목은 페이지당 한 번에 읽는다(N+1 없음)")
    void list_filtersAndSort() {
        Order requested1 = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        Order returned = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        Order rejected = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        Order requested2 = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        Order notReturned = paidDeliveredOrder(LocalDateTime.now().minusDays(2));   // 반품 요청 없음 → 어느 목록에도 없다
        orderFacade.approveReturn(returned.getId(), decisions(returned, false, false));
        orderFacade.rejectReturn(rejected.getId(), "사용 흔적");

        assertThat(ours(ReturnFilter.REQUESTED)).containsExactly(requested1.getId(), requested2.getId());
        assertThat(ours(ReturnFilter.RETURNED)).containsExactly(returned.getId());
        assertThat(ours(ReturnFilter.REJECTED)).containsExactly(rejected.getId());
        assertThat(ours(ReturnFilter.ALL))
                .containsExactly(requested2.getId(), rejected.getId(), returned.getId(), requested1.getId())
                .doesNotContain(notReturned.getId());

        AdminReturnSummaryResponse returnedRow = page(ReturnFilter.RETURNED).items().stream()
                .filter(r -> r.orderId().equals(returned.getId())).findFirst().orElseThrow();
        assertThat(returnedRow.buyerId()).isEqualTo(buyerId);
        assertThat(returnedRow.payAmount()).isEqualTo(27_500);
        assertThat(returnedRow.shippingFee()).isEqualTo(2_500);
        assertThat(returnedRow.reasonCode()).isEqualTo(ReturnReasonCode.DEFECTIVE);
        assertThat(returnedRow.returnedAt()).isNotNull();
        assertThat(returnedRow.items()).extracting(AdminReturnSummaryResponse.Item::restoreStock).containsExactly(false, false);
        assertThat(returnedRow.items()).extracting(AdminReturnSummaryResponse.Item::quantity).containsExactly(2, 1);

        // 주문 4건 + 항목 8개를 읽어도 쿼리 수는 주문 수에 비례하지 않는다
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        page(ReturnFilter.ALL);
        long statements = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);
        assertThat(statements).isLessThanOrEqualTo(3);   // (count) + 주문 페이지 + 항목 일괄
    }

    // ---------- 동시성 ----------

    @Test
    @DisplayName("승인과 거절이 동시에 와도 하나만 반영된다 (뒤에 온 쪽 409 ORDER_STATE_INVALID, 결과와 재고·이벤트가 일치)")
    void approveAndReject_concurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        int approvedFirst = 0;
        int rejectedFirst = 0;
        try {
            for (int round = 0; round < 8; round++) {
                Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
                long orderId = order.getId();
                int stockBefore = stock(optionA);
                CountDownLatch start = new CountDownLatch(1);

                CompletableFuture<Boolean> approve = CompletableFuture.supplyAsync(() -> attempt(start,
                        () -> orderFacade.approveReturn(orderId, decisions(order, true, false))), executor);
                CompletableFuture<Boolean> reject = CompletableFuture.supplyAsync(() -> attempt(start,
                        () -> orderFacade.rejectReturn(orderId, "사용 흔적")), executor);
                start.countDown();

                boolean approved = approve.get(15, TimeUnit.SECONDS);
                boolean rejectedOk = reject.get(15, TimeUnit.SECONDS);

                assertThat(approved ^ rejectedOk).as("정확히 한쪽만 성공 (round %d)", round).isTrue();
                if (approved) {
                    approvedFirst++;
                    assertThat(status(orderId)).isEqualTo(OrderStatus.RETURNED);
                    assertThat(stock(optionA)).isEqualTo(stockBefore + 2);
                    assertThat(outbox(orderId, "OrderReturnedEvent")).hasSize(1);
                } else {
                    rejectedFirst++;
                    assertThat(status(orderId)).isEqualTo(OrderStatus.PAID);
                    assertThat(stock(optionA)).isEqualTo(stockBefore);
                    assertThat(outboxCount(orderId)).isZero();
                }
            }
        } finally {
            executor.shutdownNow();
        }
        System.out.printf("승인↔거절 경합: 승인 승 %d, 거절 승 %d%n", approvedFirst, rejectedFirst);
    }

    @Test
    @DisplayName("승인이 주문을 잠근 동안 온 거절은 기다렸다가 409 ORDER_STATE_INVALID (승인 결과 유지)")
    void approveHoldsOrderLock_rejectWaitsThenFails() throws Exception {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(8));   // 거절이 이겼다면 즉시 확정됐을 주문
        long orderId = order.getId();

        Object[] other = holdLockThenRun(
                () -> orderFacade.approveReturn(orderId, decisions(order, true, false)),
                () -> orderFacade.rejectReturn(orderId, "사용 흔적"));

        assertThat(other[0]).isEqualTo(OrderErrorCode.ORDER_STATE_INVALID.resultCode());
        assertThat((Long) other[1]).as("주문 잠금을 기다렸다").isGreaterThanOrEqualTo(500L);
        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURNED);
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK);
        assertThat(outbox(orderId, "OrderReturnedEvent")).hasSize(1);
        assertThat(outbox(orderId, "OrderConfirmedEvent")).isEmpty();
    }

    @Test
    @DisplayName("거절이 주문을 잠근 동안 온 승인은 기다렸다가 409 ORDER_STATE_INVALID (재고·반품 이벤트 없음)")
    void rejectHoldsOrderLock_approveWaitsThenFails() throws Exception {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        long orderId = order.getId();

        Object[] other = holdLockThenRun(
                () -> orderFacade.rejectReturn(orderId, "사용 흔적"),
                () -> orderFacade.approveReturn(orderId, decisions(order, true, true)));

        assertThat(other[0]).isEqualTo(OrderErrorCode.ORDER_STATE_INVALID.resultCode());
        assertThat((Long) other[1]).as("주문 잠금을 기다렸다").isGreaterThanOrEqualTo(500L);
        assertThat(status(orderId)).isEqualTo(OrderStatus.PAID);
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK - 2);
        assertThat(restoreHistoryCount(orderId)).isZero();
        assertThat(outboxCount(orderId)).isZero();
    }

    /**
     * first를 바깥 트랜잭션 안에서 실행하고 잠금을 쥔 채 0.8초 멈춘다. 그사이 다른 스레드에서 second를 실행한다.
     * second가 DomainException으로 끝나면 {resultCode, 걸린 ms}, 성공하면 {null, 걸린 ms}
     */
    private Object[] holdLockThenRun(Runnable first, Runnable second) throws Exception {
        CountDownLatch firstLocked = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Object[]> other = CompletableFuture.supplyAsync(() -> {
                awaitLatch(firstLocked);
                long started = System.nanoTime();
                try {
                    second.run();
                    return new Object[]{null, (System.nanoTime() - started) / 1_000_000};
                } catch (DomainException e) {
                    return new Object[]{e.getResultCode(), (System.nanoTime() - started) / 1_000_000};
                }
            }, executor);

            transactionTemplate.executeWithoutResult(status -> {
                first.run();
                firstLocked.countDown();
                sleep(800);
            });
            return other.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("상품 삭제가 상품을 잠근 동안 온 승인은 기다렸다가 삭제를 보고 409 PRODUCT_DELETED, 전체 롤백")
    void deleteHoldsLock_approveWaitsThenFails() throws Exception {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        long orderId = order.getId();
        CountDownLatch deleteLocked = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Object> approve = CompletableFuture.supplyAsync(() -> {
                awaitLatch(deleteLocked);
                long started = System.nanoTime();
                try {
                    orderFacade.approveReturn(orderId, decisions(order, true, true));
                    return "success";
                } catch (DomainException e) {
                    return new Object[]{e.getResultCode(), (System.nanoTime() - started) / 1_000_000};
                }
            }, executor);

            transactionTemplate.executeWithoutResult(status -> {
                productFacade.deleteProduct(SELLER_MEMBER_ID, productB);
                deleteLocked.countDown();
                sleep(800);
            });

            Object result = approve.get(15, TimeUnit.SECONDS);
            assertThat(result).isInstanceOf(Object[].class);
            Object[] failure = (Object[]) result;
            assertThat(failure[0]).isEqualTo("409-PRODUCT_DELETED");
            assertThat((Long) failure[1]).as("상품 잠금을 기다렸다").isGreaterThanOrEqualTo(500L);
        } finally {
            executor.shutdownNow();
        }
        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK - 2);
        assertThat(outbox(orderId, "OrderReturnedEvent")).isEmpty();
    }

    @Test
    @DisplayName("승인이 상품을 잠근 동안 온 상품 삭제는 승인 커밋까지 기다린다 → 승인은 재고 복원까지 성공, 이후 상품 삭제")
    void approveHoldsLock_deleteWaits() throws Exception {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        long orderId = order.getId();
        CountDownLatch approveLocked = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Long> delete = CompletableFuture.supplyAsync(() -> {
                awaitLatch(approveLocked);
                long started = System.nanoTime();
                productFacade.deleteProduct(SELLER_MEMBER_ID, productB);
                return (System.nanoTime() - started) / 1_000_000;
            }, executor);

            // Facade 트랜잭션이 바깥 트랜잭션에 참여하므로 잠금은 바깥이 커밋할 때까지 유지된다
            transactionTemplate.executeWithoutResult(status -> {
                orderFacade.approveReturn(orderId, decisions(order, true, true));
                approveLocked.countDown();
                sleep(800);
            });

            assertThat(delete.get(15, TimeUnit.SECONDS)).as("승인 커밋을 기다렸다").isGreaterThanOrEqualTo(500L);
        } finally {
            executor.shutdownNow();
        }
        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURNED);
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK);
        assertThat(stock(optionB)).isEqualTo(INITIAL_STOCK);
        assertThat(productRepository.findById(productB).orElseThrow().getStatus()).isEqualTo(ProductSaleStatus.DELETED);
        assertThat(outbox(orderId, "OrderReturnedEvent")).hasSize(1);
    }

    // ---------- 준비 ----------

    /** 결제·재고 차감·배송 완료된 주문 (상품 A 2개 + 상품 B 1개, 배송비 2,500) */
    private Order paidDeliveredOrder(LocalDateTime deliveredAt) {
        Order saved = transactionTemplate.execute(status -> {
            LocalDateTime paidAt = deliveredAt.minusDays(1);
            Order order = Order.create(buyerId, null, List.of(
                            new OrderItemSpec(null, productA, optionA, sellerId, "반품 테스트 크림", "기본", "반품 테스트 상점", 10_000, 2),
                            new OrderItemSpec(null, productB, optionB, sellerId, "반품 테스트 토너", "기본", "반품 테스트 상점", 5_000, 1)),
                    ShippingAddress.of("김반품수령", "01055556666", "06236", "서울 강남구 반품로 33", "303호", "문 앞"),
                    paidAt.minusMinutes(1), 15);
            order.applyShippingFee(2_500);
            orderRepository.saveAndFlush(order);
            productApi.deductStock(order.getItems().stream().map(OrderItem::toStockChangeDto).toList());
            order.pay(paidAt);
            order.markDelivered(deliveredAt);
            return order;
        });
        orderIds.add(saved.getId());
        return saved;
    }

    private Order returnRequestedOrder(LocalDateTime deliveredAt) {
        Order order = paidDeliveredOrder(deliveredAt);
        orderFacade.requestReturn(buyerId, order.getId(), ReturnReasonCode.DEFECTIVE, "깨짐");
        return order;
    }

    private ProductOption saveOption(Product product) {
        ProductOption option = productOptionRepository.save(ProductOption.createDefault(product));
        optionStockRepository.save(OptionStock.init(option, INITIAL_STOCK));
        return option;
    }

    private static long itemId(Order order, long optionId) {
        return order.getItems().stream().filter(i -> i.getOptionId() == optionId).findFirst().orElseThrow().getId();
    }

    private List<ReturnItemDecision> decisions(Order order, boolean restoreA, boolean restoreB) {
        return List.of(new ReturnItemDecision(itemId(order, optionA), restoreA),
                new ReturnItemDecision(itemId(order, optionB), restoreB));
    }

    private PageResponse<AdminReturnSummaryResponse> page(ReturnFilter filter) {
        return orderFacade.getAdminReturns(filter, PageRequest.of(0, 100));
    }

    private List<Long> ours(ReturnFilter filter) {
        return page(filter).items().stream().map(AdminReturnSummaryResponse::orderId).filter(orderIds::contains).toList();
    }

    private int stock(long optionId) {
        return optionStockRepository.findById(optionId).orElseThrow().getStock();
    }

    private OrderStatus status(long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private long restoreHistoryCount(long orderId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from stock_history where order_id = ? and change_type = 'RETURN_RESTORE'", Long.class, orderId);
        return count == null ? 0 : count;
    }

    // payload는 jsonb라 텍스트로 읽으면 "키": 값 형식(콜론 뒤 공백)이다
    private List<String> outbox(long orderId, String eventType) {
        return jdbcTemplate.queryForList(
                "select payload::text from outbox_event where aggregate_type = 'ORDER' and aggregate_id = ? and event_type = ?",
                String.class, orderId, eventType);
    }

    private int outboxCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from outbox_event where aggregate_type = 'ORDER' and aggregate_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    /** 출발 신호를 기다렸다가 실행. 성공이면 true, 409 ORDER_STATE_INVALID면 false, 그 밖의 예외는 그대로 */
    private static boolean attempt(CountDownLatch start, Runnable action) {
        awaitLatch(start);
        try {
            action.run();
            return true;
        } catch (DomainException e) {
            assertThat(e.getResultCode()).isEqualTo(OrderErrorCode.ORDER_STATE_INVALID.resultCode());
            return false;
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("출발 신호를 받지 못했습니다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

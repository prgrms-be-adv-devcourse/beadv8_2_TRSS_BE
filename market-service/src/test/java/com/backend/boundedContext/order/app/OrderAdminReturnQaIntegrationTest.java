package com.backend.boundedContext.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.ReturnItemDecision;
import com.backend.boundedContext.order.domain.ReturnReasonCode;
import com.backend.boundedContext.order.domain.ShippingAddress;
import com.backend.boundedContext.order.in.dto.AdminReturnSummaryResponse;
import com.backend.boundedContext.order.in.dto.ReturnResultResponse;
import com.backend.boundedContext.order.out.OrderCouponPort;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.OptionStockRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.payment.out.PaymentApi;
import com.backend.shared.product.out.ProductApi;

/**
 * [QA] 관리자 반품 승인·거절에서 개발 테스트가 다루지 않은 경로를 실제 DB·실제 재고·행 잠금으로 확인한다 (TRSS-222 관리자 반품).
 * - 경합(순서 고정, 실제 대기 확인): 승인 ↔ 자동 확정(양방향), 거절 즉시 확정 ↔ 자동 확정, 승인 ↔ 승인, 승인 ↔ 판매자 재고 조정(양방향)
 * - 롤백: STOCK_LIMIT_EXCEEDED·OPTION_DELETED에서 앞 항목 복원·주문 상태·restore_stock·outbox 모두 롤백
 * - 쿠폰: 승인·거절(즉시 확정 포함)이 쿠폰 포트를 부르지 않는다
 * - 이벤트 내용: OrderReturnedEvent의 orderId·buyerId·refundAmount(=pay_amount)·returnedAt(=returned_at)
 * - 거절 7일 경계(배송 완료 기준), 배송지 없는 주문(결제 시각 기준)
 * - V13 부분 인덱스: 정의·Flyway 이력·처리 대기 목록 쿼리가 인덱스를 쓸 수 있는지
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
class OrderAdminReturnQaIntegrationTest {

    private static final long SELLER_MEMBER_ID = 7_701L;
    private static final int INITIAL_STOCK = 50;
    private static final long HOLD_MILLIS = 800;
    private static final long BLOCK_CHECK_MILLIS = 500;

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

    @MockitoBean private PaymentApi paymentApi;
    @MockitoBean private MemberApi memberApi;
    @MockitoBean private OrderCouponPort orderCouponPort;

    private final long buyerId = ThreadLocalRandom.current().nextLong(6_000_000_000L, 7_000_000_000L);
    private final long sellerId = ThreadLocalRandom.current().nextLong(6_000_000_000L, 7_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();

    private long productA, optionA, productB, optionB;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            var category = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow();
            Product a = productRepository.save(Product.create(sellerId, category, "QA 반품 크림", "설명", 10_000));
            Product b = productRepository.save(Product.create(sellerId, category, "QA 반품 토너", "설명", 5_000));
            productA = a.getId();
            productB = b.getId();
            optionA = saveOption(a).getId();
            optionB = saveOption(b).getId();
        });
        given(memberApi.getApprovedSellerByMemberId(SELLER_MEMBER_ID))
                .willReturn(new SellerDto(sellerId, SELLER_MEMBER_ID, "QA 반품 상점", SellerStatus.APPROVED));
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

    // ---------- 경합: 승인 ↔ 자동 확정 ----------

    @Test
    @DisplayName("자동 확정이 주문을 잠근 동안 온 승인은 기다렸다가 성공한다 (반품 요청 주문은 자동 확정 대상 아님, 구매 확정 이벤트 없음)")
    void autoConfirmHoldsOrderLock_approveWaitsThenSucceeds() throws Exception {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(8));   // 기준일은 지났지만 RETURN_REQUESTED
        long orderId = order.getId();

        Outcome[] outcomes = holdLockThenRun(
                () -> orderFacade.autoConfirmOrder(orderId, LocalDateTime.now()),
                () -> orderFacade.approveReturn(orderId, decisions(order, true, false)));

        assertThat(outcomes[0].value()).isEqualTo(false);
        assertThat(outcomes[1].errorCode()).isNull();
        assertThat(outcomes[1].millis()).as("주문 잠금을 기다렸다").isGreaterThanOrEqualTo(BLOCK_CHECK_MILLIS);
        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURNED);
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK);
        assertThat(outbox(orderId, "OrderReturnedEvent")).hasSize(1);
        assertThat(outbox(orderId, "OrderConfirmedEvent")).isEmpty();
    }

    @Test
    @DisplayName("승인이 주문을 잠근 동안 온 자동 확정은 기다렸다가 RETURNED를 보고 건너뛴다 (구매 확정 이벤트 없음 = 정산 후보 없음)")
    void approveHoldsOrderLock_autoConfirmWaitsThenSkips() throws Exception {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(8));
        long orderId = order.getId();

        Outcome[] outcomes = holdLockThenRun(
                () -> orderFacade.approveReturn(orderId, decisions(order, false, false)),
                () -> orderFacade.autoConfirmOrder(orderId, LocalDateTime.now()));

        assertThat(outcomes[1].errorCode()).isNull();
        assertThat(outcomes[1].value()).isEqualTo(false);
        assertThat(outcomes[1].millis()).as("주문 잠금을 기다렸다").isGreaterThanOrEqualTo(BLOCK_CHECK_MILLIS);
        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURNED);
        assertThat(outbox(orderId, "OrderReturnedEvent")).hasSize(1);
        assertThat(outbox(orderId, "OrderConfirmedEvent")).isEmpty();
    }

    @Test
    @DisplayName("거절 즉시 확정이 주문을 잠근 동안 온 자동 확정은 기다렸다가 건너뛴다 → OrderConfirmedEvent는 RETURN_REJECTED 1건만")
    void rejectConfirmHoldsOrderLock_autoConfirmWaitsThenSkips() throws Exception {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(8));
        long orderId = order.getId();

        Outcome[] outcomes = holdLockThenRun(
                () -> orderFacade.rejectReturn(orderId, "사용 흔적"),
                () -> orderFacade.autoConfirmOrder(orderId, LocalDateTime.now()));

        assertThat(outcomes[0].value()).isEqualTo(new ReturnResultResponse(orderId, OrderStatus.CONFIRMED, ConfirmType.RETURN_REJECTED));
        assertThat(outcomes[1].value()).isEqualTo(false);
        assertThat(outcomes[1].millis()).as("주문 잠금을 기다렸다").isGreaterThanOrEqualTo(BLOCK_CHECK_MILLIS);
        assertThat(outbox(orderId, "OrderConfirmedEvent")).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"confirmType\": \"RETURN_REJECTED\""));
        assertThat(jdbcTemplate.queryForObject("select confirm_type from market_order where id = ?", String.class, orderId))
                .isEqualTo("RETURN_REJECTED");
    }

    // ---------- 경합: 승인 ↔ 승인 (관리자 2명) ----------

    @Test
    @DisplayName("승인이 주문을 잠근 동안 온 두 번째 승인은 기다렸다가 409 ORDER_STATE_INVALID — 재고 복원·반품 이벤트는 한 번만")
    void approveHoldsOrderLock_secondApproveWaitsThenFails() throws Exception {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        long orderId = order.getId();

        Outcome[] outcomes = holdLockThenRun(
                () -> orderFacade.approveReturn(orderId, decisions(order, true, true)),
                () -> orderFacade.approveReturn(orderId, decisions(order, true, true)));

        assertThat(outcomes[1].errorCode()).isEqualTo(OrderErrorCode.ORDER_STATE_INVALID.resultCode());
        assertThat(outcomes[1].millis()).as("주문 잠금을 기다렸다").isGreaterThanOrEqualTo(BLOCK_CHECK_MILLIS);
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK);
        assertThat(stock(optionB)).isEqualTo(INITIAL_STOCK);
        assertThat(restoreHistoryCount(orderId)).isEqualTo(2);
        assertThat(outbox(orderId, "OrderReturnedEvent")).hasSize(1);
    }

    // ---------- 경합: 승인 ↔ 판매자 재고 조정 ----------

    @Test
    @DisplayName("판매자 재고 조정이 재고를 잠근 동안 온 승인은 기다렸다가 성공하고, 두 변경이 모두 반영된다 (lost update 없음)")
    void adjustStockHoldsLock_approveWaitsThenSucceeds() throws Exception {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        long orderId = order.getId();

        Outcome[] outcomes = holdLockThenRun(
                () -> productFacade.adjustStock(SELLER_MEMBER_ID, productA, optionA, 10),
                () -> orderFacade.approveReturn(orderId, decisions(order, true, false)));

        assertThat(outcomes[1].errorCode()).isNull();
        assertThat(outcomes[1].millis()).as("재고 잠금을 기다렸다").isGreaterThanOrEqualTo(BLOCK_CHECK_MILLIS);
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK - 2 + 10 + 2);
        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURNED);
    }

    @Test
    @DisplayName("승인이 재고를 잠근 동안 온 판매자 재고 조정은 승인 커밋까지 기다리고, 두 변경이 모두 반영된다")
    void approveHoldsLock_adjustStockWaits() throws Exception {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        long orderId = order.getId();

        Outcome[] outcomes = holdLockThenRun(
                () -> orderFacade.approveReturn(orderId, decisions(order, true, false)),
                () -> productFacade.adjustStock(SELLER_MEMBER_ID, productA, optionA, -5));

        assertThat(outcomes[1].errorCode()).isNull();
        assertThat(outcomes[1].millis()).as("재고 잠금을 기다렸다").isGreaterThanOrEqualTo(BLOCK_CHECK_MILLIS);
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK - 2 + 2 - 5);
        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURNED);
    }

    // ---------- 롤백 ----------

    @Test
    @DisplayName("승인: 뒤 항목 복원이 재고 상한을 넘으면 400 STOCK_LIMIT_EXCEEDED, 앞 항목 복원·주문 상태·restore_stock·outbox 모두 롤백")
    void approve_stockLimitExceeded_rollsBackAll() {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        long orderId = order.getId();
        jdbcTemplate.update("update option_stock set stock = ? where option_id = ?", OptionStock.MAX_STOCK, optionB);

        assertThatThrownBy(() -> orderFacade.approveReturn(orderId, decisions(order, true, true)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode()).isEqualTo("400-STOCK_LIMIT_EXCEEDED");

        assertRolledBack(orderId);
        assertThat(stock(optionB)).isEqualTo(OptionStock.MAX_STOCK);
    }

    @Test
    @DisplayName("승인: 삭제된 옵션에 복원을 고르면 409 OPTION_DELETED, 전체 롤백. 그 항목을 false로 다시 승인하면 성공")
    void approve_deletedOption_rollsBackAll() {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        long orderId = order.getId();
        jdbcTemplate.update("update product_option set deleted_at = now() where id = ?", optionB);

        assertThatThrownBy(() -> orderFacade.approveReturn(orderId, decisions(order, true, true)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode()).isEqualTo("409-OPTION_DELETED");

        assertRolledBack(orderId);

        orderFacade.approveReturn(orderId, decisions(order, true, false));
        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURNED);
        assertThat(stock(optionA)).isEqualTo(INITIAL_STOCK);
        assertThat(stock(optionB)).isEqualTo(INITIAL_STOCK - 1);
    }

    private void assertRolledBack(long orderId) {
        assertThat(status(orderId)).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(jdbcTemplate.queryForObject("select returned_at from market_order where id = ?", Object.class, orderId)).isNull();
        assertThat(jdbcTemplate.queryForList("select restore_stock from market_order_item where order_id = ?", orderId))
                .extracting(r -> r.get("restore_stock")).containsOnlyNulls();
        assertThat(stock(optionA)).as("앞 항목 복원도 롤백").isEqualTo(INITIAL_STOCK - 2);
        assertThat(restoreHistoryCount(orderId)).isZero();
        assertThat(outboxCount(orderId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from order_status_history where order_id = ? and to_status = 'RETURNED'", Integer.class, orderId))
                .isZero();
    }

    // ---------- 쿠폰·이벤트 내용 ----------

    @Test
    @DisplayName("승인·거절(즉시 확정 포함)은 쿠폰 포트를 부르지 않는다 (반품해도 쿠폰은 돌려주지 않음)")
    void approveAndReject_doNotTouchCoupons() {
        Order toApprove = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        Order toReject = returnRequestedOrder(LocalDateTime.now().minusDays(8));

        orderFacade.approveReturn(toApprove.getId(), decisions(toApprove, true, true));
        orderFacade.rejectReturn(toReject.getId(), "사용 흔적");

        verifyNoInteractions(orderCouponPort);
    }

    @Test
    @DisplayName("OrderReturnedEvent: orderId·buyerId·refundAmount(= pay_amount, 배송비 포함)·returnedAt(= returned_at)이 DB 값과 같다")
    void returnedEvent_payloadMatchesOrderRow() {
        Order order = returnRequestedOrder(LocalDateTime.now().minusDays(2));
        long orderId = order.getId();

        orderFacade.approveReturn(orderId, decisions(order, false, true));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select (e.payload ->> 'orderId')::bigint                                  as event_order_id,
                       (e.payload ->> 'buyerId')::bigint                                  as event_buyer_id,
                       (e.payload ->> 'refundAmount')::bigint = o.pay_amount              as refund_matches,
                       o.pay_amount                                                       as pay_amount,
                       o.shipping_fee                                                     as shipping_fee,
                       abs(extract(epoch from ((e.payload ->> 'returnedAt')::timestamp - o.returned_at))) < 0.001
                                                                                          as returned_at_matches
                from outbox_event e
                join market_order o on o.id = e.aggregate_id
                where e.aggregate_type = 'ORDER' and e.aggregate_id = ? and e.event_type = 'OrderReturnedEvent'
                """, orderId);

        assertThat(((Number) row.get("event_order_id")).longValue()).isEqualTo(orderId);
        assertThat(((Number) row.get("event_buyer_id")).longValue()).isEqualTo(buyerId);
        assertThat(row.get("refund_matches")).isEqualTo(true);
        assertThat(((Number) row.get("pay_amount")).longValue()).isEqualTo(25_000 + 2_500);
        assertThat(((Number) row.get("shipping_fee")).longValue()).isEqualTo(2_500);
        assertThat(row.get("returned_at_matches")).isEqualTo(true);
    }

    // ---------- 거절 7일 경계 ----------

    @Test
    @DisplayName("거절: 배송 완료 후 정확히 7일이 막 지났으면 즉시 확정, 7일까지 1분 남았으면 PAID")
    void reject_sevenDayBoundary() {
        Order past = returnRequestedOrder(LocalDateTime.now().minusDays(7).minusSeconds(5));
        Order before = returnRequestedOrder(LocalDateTime.now().minusDays(7).plusMinutes(1));

        assertThat(orderFacade.rejectReturn(past.getId(), "사용 흔적"))
                .isEqualTo(new ReturnResultResponse(past.getId(), OrderStatus.CONFIRMED, ConfirmType.RETURN_REJECTED));
        assertThat(orderFacade.rejectReturn(before.getId(), "사용 흔적"))
                .isEqualTo(new ReturnResultResponse(before.getId(), OrderStatus.PAID, null));
        assertThat(outbox(past.getId(), "OrderConfirmedEvent")).hasSize(1);
        assertThat(outboxCount(before.getId())).isZero();
    }

    @Test
    @DisplayName("배송지 없는 주문: 결제 8일 뒤 거절하면 즉시 확정(RETURN_REJECTED), 결제 6일 뒤 거절하면 PAID이고 결제 + 7일에 자동 확정 대상")
    void reject_noShippingAddress_usesPaidAt() {
        LocalDateTime paid8 = LocalDateTime.now().minusDays(8);
        LocalDateTime paid6 = LocalDateTime.now().minusDays(6);
        Order old = noAddressReturnRequestedOrder(paid8);
        Order recent = noAddressReturnRequestedOrder(paid6);

        assertThat(orderFacade.rejectReturn(old.getId(), "사용 흔적"))
                .isEqualTo(new ReturnResultResponse(old.getId(), OrderStatus.CONFIRMED, ConfirmType.RETURN_REJECTED));
        assertThat(outbox(old.getId(), "OrderConfirmedEvent")).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"confirmType\": \"RETURN_REJECTED\""));

        assertThat(orderFacade.rejectReturn(recent.getId(), "사용 흔적"))
                .isEqualTo(new ReturnResultResponse(recent.getId(), OrderStatus.PAID, null));
        assertThat(outboxCount(recent.getId())).isZero();
        assertThat(orderFacade.autoConfirmOrder(recent.getId(), paid6.plusDays(7).minusMinutes(1))).isFalse();
        assertThat(orderFacade.autoConfirmOrder(recent.getId(), paid6.plusDays(7).plusMinutes(1))).isTrue();
        assertThat(jdbcTemplate.queryForObject("select confirm_type from market_order where id = ?", String.class, recent.getId()))
                .isEqualTo("AUTO");
    }

    // ---------- 목록 응답·V13 인덱스 ----------

    @Test
    @DisplayName("관리자 목록 응답 레코드에 배송지·수령인·연락처 필드가 없다")
    void adminSummary_hasNoAddressOrContactFields() {
        List<String> names = new ArrayList<>();
        Arrays.stream(AdminReturnSummaryResponse.class.getRecordComponents()).forEach(c -> names.add(c.getName()));
        Arrays.stream(AdminReturnSummaryResponse.Item.class.getRecordComponents()).forEach(c -> names.add(c.getName()));

        assertThat(names).noneMatch(n -> {
            String lower = n.toLowerCase();
            return lower.contains("address") || lower.contains("receiver") || lower.contains("phone")
                    || lower.contains("zip") || lower.contains("recipient") || lower.contains("contact");
        });
    }

    @Test
    @DisplayName("V13: 부분 인덱스(return_requested_at, id) where return_requested_at is not null이 있고, 처리 대기 목록 쿼리가 이 인덱스를 쓸 수 있다")
    void v13_partialIndexMatchesQuery() {
        assertThat(jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '13'", Boolean.class)).isTrue();
        String indexDef = jdbcTemplate.queryForObject(
                "select indexdef from pg_indexes where indexname = 'idx_market_order_return_requested'", String.class);
        assertThat(indexDef).contains("(return_requested_at, id)").contains("WHERE (return_requested_at IS NOT NULL)");

        // 전체·거절 필터(return_requested_at is not null [+ 조건], 요청 시각 최신순)는 부분 인덱스로 정렬까지 처리한다
        assertThat(explain("o.return_requested_at is not null", "o.return_requested_at desc, o.id desc"))
                .contains("idx_market_order_return_requested");
        assertThat(explain("o.return_requested_at is not null and o.return_rejected_at is not null",
                "o.return_requested_at desc, o.id desc"))
                .contains("idx_market_order_return_requested");
        // 처리 대기(REQUESTED)는 플래너가 부분 인덱스나 기존 (status, paying_started_at) 인덱스 중 하나를 고른다. 어느 쪽이든 seq scan은 아니다
        assertThat(explain("o.return_requested_at is not null and o.status = 'RETURN_REQUESTED'",
                "o.return_requested_at asc, o.id asc"))
                .contains("Index").doesNotContain("Seq Scan");
    }

    // 같은 연결에서 seq scan을 끄고 계획을 본다(테스트 DB는 행이 적어 기본 계획은 seq scan일 수 있음)
    private String explain(String where, String orderBy) {
        List<String> plan = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("set local enable_seqscan = off");
            return jdbcTemplate.queryForList(
                    "explain select o.id from market_order o where " + where + " order by " + orderBy + " limit 20",
                    String.class);
        });
        return String.join("\n", plan);
    }

    // ---------- 준비 ----------

    private Order paidDeliveredOrder(LocalDateTime deliveredAt) {
        Order saved = transactionTemplate.execute(status -> {
            LocalDateTime paidAt = deliveredAt.minusDays(1);
            Order order = Order.create(buyerId, null, specs(),
                    ShippingAddress.of("김큐에이", "01077778888", "06236", "서울 강남구 큐에이로 1", "101호", "문 앞"),
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

    /** 배송지 없는 주문(배송 완료 없음, 결제 시각이 기준) */
    private Order noAddressReturnRequestedOrder(LocalDateTime paidAt) {
        Order saved = transactionTemplate.execute(status -> {
            Order order = Order.create(buyerId, null, specs(), null, paidAt.minusMinutes(1), 15);
            orderRepository.saveAndFlush(order);
            productApi.deductStock(order.getItems().stream().map(OrderItem::toStockChangeDto).toList());
            order.pay(paidAt);
            return order;
        });
        orderIds.add(saved.getId());
        orderFacade.requestReturn(buyerId, saved.getId(), ReturnReasonCode.CHANGE_OF_MIND, null);
        return saved;
    }

    private List<OrderItemSpec> specs() {
        return List.of(
                new OrderItemSpec(null, productA, optionA, sellerId, "QA 반품 크림", "기본", "QA 반품 상점", 10_000, 2),
                new OrderItemSpec(null, productB, optionB, sellerId, "QA 반품 토너", "기본", "QA 반품 상점", 5_000, 1));
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

    // ---------- 잠금 순서 고정 도우미 ----------

    /** 결과: 성공이면 value, DomainException이면 errorCode. millis는 걸린 시간 */
    private record Outcome(Object value, String errorCode, long millis) {
    }

    /**
     * first를 바깥 트랜잭션에서 실행하고 잠금을 쥔 채 HOLD_MILLIS 동안 멈춘다. 그사이 다른 스레드에서 second를 실행한다.
     * second가 잠금을 기다렸다면 걸린 시간이 BLOCK_CHECK_MILLIS 이상이다.
     */
    private Outcome[] holdLockThenRun(Supplier<?> first, Supplier<?> second) throws Exception {
        CountDownLatch firstLocked = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Outcome> other = CompletableFuture.supplyAsync(() -> {
                awaitLatch(firstLocked);
                return run(second);
            }, executor);

            Outcome firstOutcome = transactionTemplate.execute(status -> {
                Outcome outcome = run(first);
                firstLocked.countDown();
                sleep(HOLD_MILLIS);
                return outcome;
            });
            return new Outcome[]{firstOutcome, other.get(15, TimeUnit.SECONDS)};
        } finally {
            executor.shutdownNow();
        }
    }

    private static Outcome run(Supplier<?> action) {
        long started = System.nanoTime();
        try {
            Object value = action.get();
            return new Outcome(value, null, (System.nanoTime() - started) / 1_000_000);
        } catch (DomainException e) {
            return new Outcome(null, e.getResultCode(), (System.nanoTime() - started) / 1_000_000);
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

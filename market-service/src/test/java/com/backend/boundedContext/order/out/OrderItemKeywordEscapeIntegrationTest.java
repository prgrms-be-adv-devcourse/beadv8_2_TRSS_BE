package com.backend.boundedContext.order.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.OrderItemSpec;

/**
 * 판매자 주문 항목 검색어의 %, _, 역슬래시가 와일드카드가 아니라 글자 그대로 매칭되는지 실제 DB(PostgreSQL)로 확인한다 (TRSS-307)
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
class OrderItemKeywordEscapeIntegrationTest {

    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    // 다른 테스트 데이터와 섞이지 않게 이 테스트만의 판매자
    private final long sellerId = ThreadLocalRandom.current().nextLong(7_000_000_000L, 8_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        for (String productName : List.of("할인100%세트", "할인1000세트", "a_b크림", "axb크림", "c\\d로션", "cd로션")) {
            Order order = Order.create(ThreadLocalRandom.current().nextLong(7_000_000_000L, 8_000_000_000L),
                    List.of(new OrderItemSpec(null, 10L, 100L, sellerId, productName, "기본", "상점", 10_000, 1)),
                    LocalDateTime.now(), 15);
            orderIds.add(transactionTemplate.execute(status -> orderRepository.save(order).getId()));
        }
    }

    @AfterEach
    void cleanUp() {
        transactionTemplate.executeWithoutResult(status -> orderRepository.deleteAllById(orderIds));
    }

    @ParameterizedTest(name = "[{0}] → {1}")
    @CsvSource(delimiter = '|', value = {
            "100%   | 할인100%세트",   // % 이스케이프 전에는 할인1000세트도 걸렸다
            "a_b    | a_b크림",        // _ 이스케이프 전에는 axb크림도 걸렸다
            "c\\d   | c\\d로션",       // 역슬래시도 글자 그대로
    })
    @DisplayName("검색어의 %, _, 역슬래시는 글자 그대로 매칭된다")
    void keywordMatchesLiterally(String keyword, String expectedProductName) {
        List<OrderItem> found = orderItemRepository.findAll(
                OrderItemSpecifications.sellerEquals(sellerId).and(OrderItemSpecifications.keywordContains(keyword)));

        assertThat(found).extracting(OrderItem::getProductName).containsExactly(expectedProductName);
    }
}

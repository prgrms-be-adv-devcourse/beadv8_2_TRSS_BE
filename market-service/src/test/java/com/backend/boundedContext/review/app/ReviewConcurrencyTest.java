package com.backend.boundedContext.review.app;

import com.backend.boundedContext.review.domain.Review;
import com.backend.boundedContext.review.out.ReviewRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.ReviewableOrderItemDto;
import com.backend.shared.order.out.OrderApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 같은 주문 항목에 동시에 여러 번 리뷰를 써도 한 건만 남는지 확인한다.
 * 각 요청이 따로 커밋돼야 하므로 @Transactional을 붙이지 않고, 만든 데이터는 @AfterEach에서 직접 지운다.
 */
@SpringBootTest
class ReviewConcurrencyTest {

    private static final long MEMBER_ID = 8_101L;
    private static final long ORDER_ITEM_ID = 970_001L;
    private static final long PRODUCT_ID = 980_101L;

    @Autowired
    private ReviewFacade reviewFacade;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private MemberApi memberApi;

    /** 다른 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다 */
    @MockitoBean
    private OrderApi orderApi;

    @BeforeEach
    void setUp() {
        given(orderApi.findConfirmedOrderItem(MEMBER_ID, ORDER_ITEM_ID)).willReturn(Optional.of(
                new ReviewableOrderItemDto(ORDER_ITEM_ID, PRODUCT_ID, 1L, "기본", LocalDateTime.now())));
        given(memberApi.getMember(MEMBER_ID)).willReturn(
                new MemberDto(MEMBER_ID, "review@palette.com", "김유진", MemberRole.USER, MemberStatus.ACTIVE));
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            reviewRepository.deleteAll(reviews());
            outboxEventRepository.deleteAll(reviewEvents());
        });
    }

    @Test
    @DisplayName("같은 주문 항목에 동시에 10번 써도 한 번만 성공하고 나머지는 409 REVIEW_ALREADY_EXISTS, 리뷰·작성 이벤트는 한 건만 남는다")
    void concurrentCreate() throws Exception {
        int requests = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            futures.add(executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return reviewFacade.createReview(MEMBER_ID, ORDER_ITEM_ID, 5, "동시에 쓴 리뷰 내용입니다.");
            }));
        }
        start.countDown();

        int succeeded = 0;
        List<String> failures = new ArrayList<>();
        for (Future<Long> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
                succeeded++;
            } catch (ExecutionException e) {
                failures.add(e.getCause() instanceof DomainException de ? de.getResultCode() : e.getCause().toString());
            }
        }
        executor.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(failures).hasSize(requests - 1).containsOnly("409-REVIEW_ALREADY_EXISTS");
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(reviews()).hasSize(1);
            assertThat(reviewEvents()).extracting(OutboxEvent::getEventType).containsExactly("ReviewCreatedEvent");
        });
    }

    private List<Review> reviews() {
        return reviewRepository.findAll().stream().filter(r -> r.getOrderItemId().equals(ORDER_ITEM_ID)).toList();
    }

    private List<OutboxEvent> reviewEvents() {
        return outboxEventRepository.findAll().stream()
                .filter(e -> "REVIEW".equals(e.getAggregateType()) && e.getAggregateId().equals(PRODUCT_ID))
                .toList();
    }
}

package com.backend.boundedContext.delivery.app;

import static com.backend.boundedContext.delivery.DeliveryFixtures.item;
import static com.backend.boundedContext.delivery.DeliveryFixtures.paidEvent;
import static com.backend.boundedContext.delivery.DeliveryFixtures.PAID_AT;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.delivery.FakeDeliveryTracker;
import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.admin.dto.AdminFailureItem;
import com.backend.shared.admin.dto.AdminFailureTargetType;
import com.backend.shared.admin.dto.AdminFailureType;
import com.backend.shared.order.dto.PaidOrderItemDto;

/**
 * 관리자 실패 거래(배송 추적 실패)를 실제 DB(로컬 Docker PostgreSQL, delivery 스키마)로 확인한다.
 * 15일 경계, DELIVERED·READY 제외, from·to 필터, 최신순 페이지, 공통 형식 변환.
 * 다른 테스트·로컬 데이터와 겹치지 않게 '지금'을 2001년으로 고정하고 송장 등록 시각도 그 무렵으로 옮겨 기간(2001년)으로 거른다.
 * 큰 주문 ID를 쓰고 끝나면 지운다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
@Import(FakeDeliveryTracker.Config.class)
class DeliveryTrackingFailuresIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2001, 3, 20, 12, 0);
    private static final LocalDate FROM = LocalDate.of(2001, 1, 1);
    private static final LocalDate TO = LocalDate.of(2001, 12, 31);
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    @Autowired private DeliveryFacade deliveryFacade;
    @Autowired private DeliveryGetTrackingFailuresUseCase useCase;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private final long base = ThreadLocalRandom.current().nextLong(6_000_000_000L, 7_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long orderId : orderIds) {
            jdbcTemplate.update("""
                    delete from outbox_event where aggregate_type = 'DELIVERY'
                    and aggregate_id in (select id from delivery where order_id = ?)""", orderId);
            jdbcTemplate.update("delete from delivery_item where delivery_id in (select id from delivery where order_id = ?)", orderId);
            jdbcTemplate.update("delete from delivery where order_id = ?", orderId);
        }
    }

    @Test
    @DisplayName("송장 등록 후 정확히 15일 지난 배송은 포함, 15일에서 1분 모자라거나 14일이면 제외")
    void boundary_15days() {
        long exactly15 = shipped(0, NOW.minusDays(15));
        long oneMinuteShort = shipped(1, NOW.minusDays(15).plusMinutes(1));
        long days14 = shipped(2, NOW.minusDays(14));

        List<Long> refIds = refIds(failures(FROM, TO, FIRST_PAGE, NOW));

        assertThat(refIds).contains(exactly15).doesNotContain(oneMinuteShort, days14);
    }

    @Test
    @DisplayName("배송 완료(DELIVERED)와 송장 등록 전(READY)은 15일이 지나도 제외")
    void deliveredAndReady_excluded() {
        long stalled = shipped(0, NOW.minusDays(20));
        long delivered = shipped(1, NOW.minusDays(20));
        deliveryFacade.completeManually(delivered, NOW.minusDays(19), "구매자 수령 확인");
        long ready = ready(2);

        List<Long> refIds = refIds(failures(FROM, TO, FIRST_PAGE, NOW));

        assertThat(refIds).containsExactly(stalled);
        assertThat(refIds).doesNotContain(delivered, ready);
    }

    @Test
    @DisplayName("from·to는 송장 등록 날짜로 거른다 (from 00:00 포함, to 23:59 포함, to 다음 날 00:00 제외)")
    void fromTo_byShippedDate() {
        long beforeFrom = shipped(0, LocalDateTime.of(2001, 2, 28, 23, 59));
        long fromStart = shipped(1, LocalDateTime.of(2001, 3, 1, 0, 0));
        long toEnd = shipped(2, LocalDateTime.of(2001, 3, 4, 23, 59, 59));
        long afterTo = shipped(3, LocalDateTime.of(2001, 3, 5, 0, 0));

        List<Long> refIds = refIds(failures(
                LocalDate.of(2001, 3, 1), LocalDate.of(2001, 3, 4), FIRST_PAGE, NOW));

        assertThat(refIds).containsExactly(toEnd, fromStart);
        assertThat(refIds).doesNotContain(beforeFrom, afterTo);
    }

    @Test
    @DisplayName("송장 등록 시각 최신순 페이지. 요청의 sort는 무시하고 totalElements는 전체 건수")
    void paging_latestFirst() {
        long oldest = shipped(0, LocalDateTime.of(2001, 2, 1, 9, 0));
        long middle = shipped(1, LocalDateTime.of(2001, 2, 2, 9, 0));
        long latest = shipped(2, LocalDateTime.of(2001, 2, 3, 9, 0));

        PageResponse<AdminFailureItem> page0 = failures(FROM, TO, PageRequest.of(0, 2, Sort.by("id")), NOW);
        PageResponse<AdminFailureItem> page1 = failures(FROM, TO, PageRequest.of(1, 2), NOW);

        assertThat(refIds(page0)).containsExactly(latest, middle);
        assertThat(refIds(page1)).containsExactly(oldest);
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("공통 형식: 판매자 대상(첫 항목 판매자, 상호명 외 N곳), 금액 null, 택배사 상태가 있으면 원인 STALLED_AFTER_SHIPPED, 물류 담당자 확인, 배송·주문 ID")
    void toAdminFailureItem() {
        LocalDateTime shippedAt = NOW.minusDays(16);
        long multiSeller = shipped(0, shippedAt);   // 판매자 1, 1, 2
        jdbcTemplate.update("update delivery set last_tracking_status = 'EXCEPTION' where id = ?", multiSeller);
        long singleSeller = shipped(1, shippedAt.minusHours(1), List.of(item(orderId(1) * 10, 9L, 1)));

        List<AdminFailureItem> items = failures(FROM, TO, FIRST_PAGE, NOW).items();

        AdminFailureItem first = items.getFirst();
        assertThat(first.type()).isEqualTo(AdminFailureType.DELIVERY_TRACKING_FAILED);
        assertThat(first.occurredAt()).isEqualTo(shippedAt);
        assertThat(first.targetType()).isEqualTo(AdminFailureTargetType.SELLER);
        assertThat(first.targetId()).isEqualTo(1L);
        assertThat(first.targetLabel()).isEqualTo("판매자1 외 1곳");
        assertThat(first.amount()).isNull();
        assertThat(first.reasonCode()).isEqualTo("STALLED_AFTER_SHIPPED");
        assertThat(first.reasonLabel()).isEqualTo("송장 등록 후 15일 지남 (택배사 상태: EXCEPTION)");
        assertThat(first.handlingCode()).isEqualTo("LOGISTICS_CHECK");
        assertThat(first.handlingLabel()).isEqualTo("물류 담당자 확인");
        assertThat(first.refId()).isEqualTo(multiSeller);
        assertThat(first.orderId()).isEqualTo(orderId(0));

        AdminFailureItem second = items.get(1);
        assertThat(second.refId()).isEqualTo(singleSeller);
        assertThat(second.targetId()).isEqualTo(9L);
        assertThat(second.targetLabel()).isEqualTo("판매자9");
        assertThat(second.reasonCode()).isEqualTo("TRACKING_NOT_FOUND");
        assertThat(second.reasonLabel()).isEqualTo("송장 등록 후 15일째 택배사 조회 결과 없음");
        assertThat(second.handlingCode()).isEqualTo("LOGISTICS_CHECK");
    }

    @Test
    @DisplayName("원인: 택배사 상태를 한 번도 받지 못했으면 TRACKING_NOT_FOUND, 받았으면 STALLED_AFTER_SHIPPED(상태를 문구에)")
    void reasonCode_byLastTrackingStatus() {
        long notFound = shipped(0, NOW.minusDays(20));
        long inTransit = shipped(1, NOW.minusDays(21));
        jdbcTemplate.update("update delivery set last_tracking_status = 'IN_TRANSIT' where id = ?", inTransit);

        List<AdminFailureItem> items = failures(FROM, TO, FIRST_PAGE, NOW).items();

        assertThat(items).extracting(AdminFailureItem::refId).containsExactly(notFound, inTransit);
        assertThat(items).extracting(AdminFailureItem::reasonCode).containsExactly("TRACKING_NOT_FOUND", "STALLED_AFTER_SHIPPED");
        assertThat(items).extracting(AdminFailureItem::reasonLabel).containsExactly(
                "송장 등록 후 15일째 택배사 조회 결과 없음", "송장 등록 후 15일 지남 (택배사 상태: IN_TRANSIT)");
    }

    @Test
    @DisplayName("Facade는 지금 시각 기준으로 거른다: 실제로 15일 넘게 SHIPPED인 배송이 보인다")
    void facade_usesNow() {
        long stalled = shipped(0, LocalDateTime.of(2001, 3, 1, 9, 0));

        List<Long> refIds = refIds(deliveryFacade.getTrackingFailures(FROM, TO, FIRST_PAGE));

        assertThat(refIds).containsExactly(stalled);
    }

    /** 지금을 NOW로 고정해 UseCase를 부른다. Facade처럼 읽기 트랜잭션 안에서(항목 지연 로딩) */
    private PageResponse<AdminFailureItem> failures(LocalDate from, LocalDate to, Pageable pageable, LocalDateTime now) {
        return transactionTemplate.execute(tx -> useCase.getTrackingFailures(from, to, pageable, now));
    }

    private long orderId(int n) {
        return base + n;
    }

    private long ready(int n) {
        return ready(n, paidEvent(orderId(n), orderId(n) * 10).items());
    }

    private long ready(int n, List<PaidOrderItemDto> items) {
        long orderId = orderId(n);
        orderIds.add(orderId);
        deliveryFacade.createFromPaidOrder(paidEvent(orderId, PAID_AT, items));
        return deliveryRepository.findByOrderId(orderId).orElseThrow().getId();
    }

    private long shipped(int n, LocalDateTime shippedAt) {
        return shipped(n, shippedAt, paidEvent(orderId(n), orderId(n) * 10).items());
    }

    /** READY 배송을 만들고 CJ 송장을 등록해 SHIPPED로 만든 뒤 송장 등록 시각을 옮긴다 */
    private long shipped(int n, LocalDateTime shippedAt, List<PaidOrderItemDto> items) {
        long id = ready(n, items);
        deliveryFacade.ship(id, Courier.CJ, base + String.format("%02d", n));
        jdbcTemplate.update("update delivery set shipped_at = ? where id = ?", shippedAt, id);
        return id;
    }

    private static List<Long> refIds(PageResponse<AdminFailureItem> page) {
        return page.items().stream().map(AdminFailureItem::refId).toList();
    }
}

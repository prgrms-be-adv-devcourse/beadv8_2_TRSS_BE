package com.backend.boundedContext.delivery.in;

import static com.backend.boundedContext.delivery.DeliveryFixtures.paidEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.backend.boundedContext.delivery.FakeDeliveryTracker;
import com.backend.boundedContext.delivery.app.DeliveryFacade;
import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.out.DeliveryRepository;

/**
 * Delivery Tracker 웹훅 콜백 (POST /webhooks/delivery-tracker/{token}). 보안 필터를 켠 채로 JWT 없이 부른다.
 * 콜백은 확인 요청 시각만 남기고 상태는 바꾸지 않는다. DB는 로컬 Docker PostgreSQL(delivery 스키마).
 * 토큰 값은 src/test/resources/application.properties의 custom.deliveryTracker.callbackToken
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
@AutoConfigureMockMvc
@Import(FakeDeliveryTracker.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class DeliveryTrackerWebhookIntegrationTest {

    private static final String TOKEN = "test-callback-token-0123456789abcdef";
    private static final String URL = "/webhooks/delivery-tracker/";

    @Autowired private MockMvc mockMvc;
    @Autowired private DeliveryFacade deliveryFacade;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private FakeDeliveryTracker tracker;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final long base = ThreadLocalRandom.current().nextLong(3_000_000_000L, 4_000_000_000L);
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
    @DisplayName("토큰이 틀리면 404, DB 변화 없음, 로그에 토큰 값이 없다")
    void wrongToken(CapturedOutput output) throws Exception {
        long id = shipped(0);
        String wrongToken = "wrong-token-value-9876543210-zyxwvut";

        callback(wrongToken, "kr.cjlogistics", tracking(0)).andExpect(status().isNotFound());

        assertThat(row(id).get("tracking_check_requested_at")).isNull();
        assertThat(output).contains("Delivery Tracker 콜백 토큰이 맞지 않습니다");
        assertThat(output).doesNotContain(wrongToken).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("토큰이 맞고 SHIPPED 송장이면 202, 확인 요청 시각을 남긴다. 콜백만으로 상태는 바뀌지 않는다(조회도 안 함)")
    void shipped_requestsCheck(CapturedOutput output) throws Exception {
        long id = shipped(0);

        callback(TOKEN, "kr.cjlogistics", tracking(0)).andExpect(status().isAccepted());

        Map<String, Object> row = row(id);
        assertThat(row.get("tracking_check_requested_at")).isNotNull();
        assertThat(row.get("status")).isEqualTo("SHIPPED");
        assertThat(row.get("delivered_at")).isNull();
        assertThat(tracker.trackCalls()).isEmpty();
        assertThat(output).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("송장번호에 하이픈·공백이 있어도 정규화해서 찾는다")
    void normalizedTrackingNumber() throws Exception {
        long id = shipped(0);
        String raw = tracking(0);
        String withHyphen = raw.substring(0, 4) + "-" + raw.substring(4);

        callback(TOKEN, "kr.cjlogistics", withHyphen).andExpect(status().isAccepted());

        assertThat(row(id).get("tracking_check_requested_at")).isNotNull();
    }

    @Test
    @DisplayName("같은 콜백이 두 번 오면 두 번째 콜백이 요청 시각을 now로 갱신한다 (11-08 결정 (b): 조회 중 콜백을 잃지 않게)")
    void twice_updatesToLatest() throws Exception {
        long id = shipped(0);

        callback(TOKEN, "kr.cjlogistics", tracking(0)).andExpect(status().isAccepted());
        // 첫 요청이 1시간 전에 남은 것으로 만든다
        LocalDateTime first = LocalDateTime.now().minusHours(1);
        jdbcTemplate.update("update delivery set tracking_check_requested_at = ? where id = ?", first, id);
        LocalDateTime beforeSecond = LocalDateTime.now();
        callback(TOKEN, "kr.cjlogistics", tracking(0)).andExpect(status().isAccepted());

        LocalDateTime requestedAt = ((Timestamp) row(id).get("tracking_check_requested_at")).toLocalDateTime();
        assertThat(requestedAt).isAfter(first).isAfterOrEqualTo(beforeSecond.truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("모르는 송장·모르는 carrierId·다른 택배사·READY 배송·빈 본문 → 202, 변화 없음")
    void unknown_noChange() throws Exception {
        long shippedId = shipped(0);
        long readyId = ready(1);

        callback(TOKEN, "kr.cjlogistics", "999999999999").andExpect(status().isAccepted());
        callback(TOKEN, "kr.unknown", tracking(0)).andExpect(status().isAccepted());
        callback(TOKEN, "kr.epost", tracking(0)).andExpect(status().isAccepted());
        callback(TOKEN, "kr.cjlogistics", "bad!").andExpect(status().isAccepted());
        mockMvc.perform(post(URL + TOKEN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post(URL + TOKEN)).andExpect(status().isAccepted());

        assertThat(row(shippedId).get("tracking_check_requested_at")).isNull();
        assertThat(row(readyId).get("tracking_check_requested_at")).isNull();
        assertThat(row(readyId).get("status")).isEqualTo("READY");
    }

    @Test
    @DisplayName("모르는 필드가 있어도 받는다")
    void unknownFields() throws Exception {
        long id = shipped(0);

        mockMvc.perform(post(URL + TOKEN).contentType(MediaType.APPLICATION_JSON).content("""
                        {"carrierId": "kr.cjlogistics", "trackingNumber": "%s", "extra": 1}
                        """.formatted(tracking(0))))
                .andExpect(status().isAccepted());

        assertThat(row(id).get("tracking_check_requested_at")).isNotNull();
    }

    private ResultActions callback(String token, String carrierId, String trackingNumber) throws Exception {
        return mockMvc.perform(post(URL + token).contentType(MediaType.APPLICATION_JSON).content("""
                {"carrierId": "%s", "trackingNumber": "%s"}
                """.formatted(carrierId, trackingNumber)));
    }

    private String tracking(int n) {
        return base + String.format("%02d", n);
    }

    private long ready(int n) {
        long orderId = base + n;
        orderIds.add(orderId);
        deliveryFacade.createFromPaidOrder(paidEvent(orderId, orderId * 10));
        return deliveryRepository.findByOrderId(orderId).orElseThrow().getId();
    }

    private long shipped(int n) {
        long id = ready(n);
        deliveryFacade.ship(id, Courier.CJ, tracking(n));
        return id;
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("select * from delivery where id = ?", id);
    }
}

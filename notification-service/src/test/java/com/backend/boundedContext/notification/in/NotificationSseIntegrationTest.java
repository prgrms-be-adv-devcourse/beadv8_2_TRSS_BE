package com.backend.boundedContext.notification.in;

import com.backend.boundedContext.notification.app.NotificationFacade;
import com.backend.boundedContext.notification.domain.Notification;
import com.backend.boundedContext.notification.out.NotificationEmitters;
import com.backend.boundedContext.notification.out.NotificationRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.security.JwtTokenProvider;
import com.backend.shared.member.dto.MemberRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 실제 서버(임의 포트)에 SSE로 연결해 응답 형식과 연결 저장소를 확인함
 * MockMvc는 비동기 응답을 끝까지 흘려보내지 않아 실제 HTTP 연결로 확인함.
 */
// heartbeat 스케줄러가 테스트 중간에 끼어들면 읽는 이벤트 순서가 바뀌므로 스케줄링을 끔
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "custom.scheduling.enabled=false")
class NotificationSseIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationEmitters notificationEmitters;
    @Autowired private NotificationFacade notificationFacade;
    @Autowired private TransactionTemplate transactionTemplate;

    // 다른 테스트와 겹치지 않는 회원 ID
    private final long memberId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
    private final long otherMemberId = memberId + 1;

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void cleanUp() {
        notificationRepository.deleteAll(notificationRepository.findAll().stream()
                .filter(n -> n.getMemberId().equals(memberId) || n.getMemberId().equals(otherMemberId)).toList());
    }

    @Test
    @DisplayName("구독하면 text/event-stream으로 연결되고 안 읽은 개수를 바로 받음")
    void subscribe_sendsUnreadCount() throws Exception {
        notificationRepository.save(Notification.orderPaid(memberId, 10L, "립스틱", 1));
        notificationRepository.save(Notification.deliveryCompleted(memberId, 10L, null));

        HttpResponse<InputStream> response = client.send(subscribeRequest(), HttpResponse.BodyHandlers.ofInputStream());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("text/event-stream"));
            assertThat(readEvent(reader)).containsExactly("event:unread-count", "data:2");
            assertThat(notificationEmitters.count(memberId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("토큰 없이 구독하면 401이고 연결이 저장되지 않음")
    void subscribe_unauthenticated() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/notifications/subscribe")).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(notificationEmitters.count(memberId)).isZero();
    }

    @Test
    @DisplayName("클라이언트가 연결을 끊으면 다음 전송 때 저장소에서 지워짐")
    void disconnect_removedOnNextSend() throws Exception {
        HttpResponse<InputStream> response = client.send(subscribeRequest(), HttpResponse.BodyHandlers.ofInputStream());
        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        readEvent(reader);
        reader.close();

        // 끊긴 것은 쓰기를 시도해야 알 수 있으므로, 지워질 때까지 계속 보냄
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            notificationEmitters.send(memberId, NotificationEmitters.UNREAD_COUNT, 0L);
            return notificationEmitters.count(memberId) == 0;
        });
    }

    @Test
    @DisplayName("알림이 저장되면 새 알림과 바뀐 안 읽은 개수를 차례로 받음")
    void created_sendsNotificationAndUnreadCount() throws Exception {
        try (BufferedReader reader = subscribe()) {
            readEvent(reader);   // 연결 직후 안 읽은 개수

            notificationFacade.createOrderPaid(memberId, 10L, "립스틱", 2);

            List<String> notification = readEvent(reader);
            assertThat(notification.getFirst()).isEqualTo("event:notification");
            assertThat(notification.get(1)).contains("\"type\":\"ORDER_PAID\"", "\"productName\":\"립스틱\"", "\"itemCount\":2", "\"read\":false");
            assertThat(readEvent(reader)).containsExactly("event:unread-count", "data:1");
        }
    }

    @Test
    @DisplayName("다른 회원의 알림과 롤백된 알림은 받지 않음")
    void created_onlyMineAndCommitted() throws Exception {
        try (BufferedReader reader = subscribe()) {
            readEvent(reader);

            notificationFacade.createOrderPaid(otherMemberId, 20L, "틴트", 1);
            transactionTemplate.executeWithoutResult(status -> {
                notificationFacade.createOrderPaid(memberId, 30L, "쿠션", 1);
                status.setRollbackOnly();
            });
            notificationFacade.createOrderPaid(memberId, 10L, "립스틱", 1);

            // 앞의 두 알림이 왔다면 이 알림보다 먼저 읽혔을 것임
            assertThat(readEvent(reader).get(1)).contains("\"productName\":\"립스틱\"");
        }
    }

    @Test
    @DisplayName("하나 읽음·모두 읽음이 커밋되면 바뀐 안 읽은 개수를 받고, 실패한 읽음 처리는 보내지 않음")
    void read_sendsUnreadCount() throws Exception {
        Notification first = notificationRepository.save(Notification.orderPaid(memberId, 10L, "립스틱", 1));
        notificationRepository.save(Notification.orderPaid(memberId, 11L, "틴트", 1));

        try (BufferedReader reader = subscribe()) {
            assertThat(readEvent(reader)).containsExactly("event:unread-count", "data:2");

            notificationFacade.read(memberId, first.getId());
            assertThat(readEvent(reader)).containsExactly("event:unread-count", "data:1");

            assertThatThrownBy(() -> notificationFacade.read(memberId, Long.MAX_VALUE)).isInstanceOf(DomainException.class);
            notificationFacade.readAll(memberId);
            // 없는 알림 읽음 처리가 보냈다면 data:1이 먼저 읽혔을 것임
            assertThat(readEvent(reader)).containsExactly("event:unread-count", "data:0");
        }
    }

    @Test
    @DisplayName("heartbeat는 SSE 주석으로 와서 이벤트로 처리되지 않음")
    void heartbeat_sentAsComment() throws Exception {
        try (BufferedReader reader = subscribe()) {
            readEvent(reader);

            notificationFacade.sendHeartbeat();

            assertThat(readEvent(reader)).containsExactly(":heartbeat");
        }
    }

    private BufferedReader subscribe() throws Exception {
        HttpResponse<InputStream> response = client.send(subscribeRequest(), HttpResponse.BodyHandlers.ofInputStream());
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    }

    private HttpRequest subscribeRequest() {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/notifications/subscribe"))
                .header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(memberId, MemberRole.USER))
                .header("Accept", "text/event-stream")
                .build();
    }

    // 빈 줄이 나올 때까지 읽어 이벤트 하나의 줄들을 돌려줌
    private static List<String> readEvent(BufferedReader reader) throws Exception {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }
}

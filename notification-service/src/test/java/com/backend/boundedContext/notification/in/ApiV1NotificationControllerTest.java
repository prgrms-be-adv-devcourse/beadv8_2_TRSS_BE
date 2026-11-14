package com.backend.boundedContext.notification.in;

import com.backend.boundedContext.notification.app.NotificationFacade;
import com.backend.boundedContext.notification.app.dto.NotificationListDto;
import com.backend.boundedContext.notification.domain.NotificationErrorCode;
import com.backend.boundedContext.notification.domain.NotificationType;
import com.backend.global.exception.DomainException;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1NotificationController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private NotificationFacade notificationFacade;

    @Test
    @DisplayName("알림 목록: 로그인 회원 기준으로 조회하고, size 기본값은 20")
    void getNotifications_defaults() throws Exception {
        given(notificationFacade.getNotifications(3L, null, null, 20))
                .willReturn(new NotificationListDto(List.of(), null, false));

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(3L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        verify(notificationFacade).getNotifications(3L, null, null, 20);
    }

    @Test
    @DisplayName("알림 목록: cursor·size를 그대로 넘김")
    void getNotifications_cursor() throws Exception {
        given(notificationFacade.getNotifications(3L, null, 50L, 10))
                .willReturn(new NotificationListDto(List.of(), null, false));

        mockMvc.perform(get("/api/v1/notifications").param("cursor", "50").param("size", "10")
                        .header("Authorization", bearer(3L)))
                .andExpect(status().isOk());

        verify(notificationFacade).getNotifications(3L, null, 50L, 10);
    }

    @Test
    @DisplayName("알림 목록: type을 넘기면 그 유형으로 조회함")
    void getNotifications_type() throws Exception {
        given(notificationFacade.getNotifications(3L, NotificationType.DELIVERY_STARTED, null, 20))
                .willReturn(new NotificationListDto(List.of(), null, false));

        mockMvc.perform(get("/api/v1/notifications").param("type", "DELIVERY_STARTED")
                        .header("Authorization", bearer(3L)))
                .andExpect(status().isOk());

        verify(notificationFacade).getNotifications(3L, NotificationType.DELIVERY_STARTED, null, 20);
    }

    @Test
    @DisplayName("알림 목록: 없는 type이면 400 INVALID_REQUEST")
    void getNotifications_unknownType() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").param("type", "REFUNDED").header("Authorization", bearer(3L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-INVALID_REQUEST"));

        verify(notificationFacade, never()).getNotifications(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("알림 목록: size가 100을 넘으면 400 VALIDATION_FAILED")
    void getNotifications_sizeTooLarge() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").param("size", "101").header("Authorization", bearer(3L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        verify(notificationFacade, never()).getNotifications(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("안 읽은 개수: 로그인 회원의 개수를 data로 응답함")
    void getUnreadCount() throws Exception {
        given(notificationFacade.getUnreadCount(3L)).willReturn(4L);

        mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(3L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data").value(4));
    }

    @Test
    @DisplayName("읽음 처리: 로그인 회원과 알림 id로 처리하고 200-1로 응답함")
    void read() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/7/read").header("Authorization", bearer(3L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));

        verify(notificationFacade).read(3L, 7L);
    }

    @Test
    @DisplayName("읽음 처리: 없거나 다른 회원의 알림이면 404 NOTIFICATION_NOT_FOUND")
    void read_notFound() throws Exception {
        willThrow(new DomainException(NotificationErrorCode.NOTIFICATION_NOT_FOUND))
                .given(notificationFacade).read(3L, 7L);

        mockMvc.perform(patch("/api/v1/notifications/7/read").header("Authorization", bearer(3L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultCode").value("404-NOTIFICATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("모두 읽음: 로그인 회원 기준으로 처리하고 200-1로 응답함")
    void readAll() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/read-all").header("Authorization", bearer(3L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));

        verify(notificationFacade).readAll(3L);
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isUnauthorized());

        verify(notificationFacade, never()).getUnreadCount(any());
    }

    private String bearer(Long memberId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(memberId, MemberRole.USER);
    }
}

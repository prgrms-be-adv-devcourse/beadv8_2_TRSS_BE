package com.backend.boundedContext.delivery.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.delivery.app.DeliveryFacade;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;

/**
 * [QA] 관리자 배송 API 보강: 시각 형식(KST LocalDateTime)·@PastOrPresent 경계·잘못된 경로/쿼리 값.
 */
@WebMvcTest(ApiV1AdminDeliveryController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1AdminDeliveryControllerQaTest {

    private static final String BASE = "/api/v1/admin/deliveries";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private DeliveryFacade deliveryFacade;

    @Test
    @DisplayName("deliveredAt이 방금(서버 시각 KST 기준 몇 초 전)이면 통과")
    void deliveredAt_justNow_ok() throws Exception {
        String justNow = LocalDateTime.now().minusSeconds(2).truncatedTo(ChronoUnit.SECONDS).toString();

        complete(justNow).andExpect(status().isOk());
        verify(deliveryFacade).completeManually(eq(3L), eq(LocalDateTime.parse(justNow)), anyString());
    }

    @Test
    @DisplayName("deliveredAt이 서버 시각보다 1분 미래면 400 (관리자 PC 시계가 빠르면 거절된다)")
    void deliveredAt_oneMinuteAhead_400() throws Exception {
        String ahead = LocalDateTime.now().plusMinutes(1).truncatedTo(ChronoUnit.SECONDS).toString();

        complete(ahead).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        verifyNoInteractions(deliveryFacade);
    }

    @Test
    @DisplayName("[시간대 주의] 'Z'가 붙은 UTC 문자열은 Z를 버리고 같은 숫자를 KST로 받는다 (UTC 06:30Z → KST 06:30, 9시간 이르게 기록)")
    void deliveredAt_utcZ_isReadAsLocal() throws Exception {
        complete("2026-11-10T06:30:00Z").andExpect(status().isOk());

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(deliveryFacade).completeManually(eq(3L), captor.capture(), anyString());
        // 의도(UTC 06:30 = KST 15:30)와 다르게 06:30으로 들어온다. 프론트는 오프셋 없는 KST 문자열을 보내야 한다
        assertThat(captor.getValue()).isEqualTo(LocalDateTime.of(2026, 11, 10, 6, 30));
    }

    @Test
    @DisplayName("deliveredAt 형식이 틀리면 400, Facade를 부르지 않는다")
    void deliveredAt_badFormat_400() throws Exception {
        complete("2026/11/10 15:30").andExpect(status().isBadRequest());
        verifyNoInteractions(deliveryFacade);
    }

    @Test
    @DisplayName("deliveryId가 숫자가 아니면 400, 없는 status 값이면 400")
    void badPathAndQuery_400() throws Exception {
        mockMvc.perform(post(BASE + "/abc/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveredAt\":\"2026-11-10T15:30:00\",\"reason\":\"확인\"}")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE).param("status", "LOST").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(deliveryFacade);
    }

    private org.springframework.test.web.servlet.ResultActions complete(String deliveredAt) throws Exception {
        return mockMvc.perform(post(BASE + "/3/complete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deliveredAt\":\"" + deliveredAt + "\",\"reason\":\"구매자 수령 확인\"}")
                .header("Authorization", bearer(MemberRole.ADMIN)));
    }

    private String bearer(MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(1L, role);
    }
}

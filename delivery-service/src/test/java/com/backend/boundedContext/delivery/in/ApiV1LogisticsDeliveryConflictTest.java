package com.backend.boundedContext.delivery.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
 * (QA 추가) 물류 API의 동시 충돌·요청 검증 응답.
 * - 송장 동시 등록으로 DB 유니크(uk_delivery_tracking)에 걸리면 409 DATA_CONFLICT (DeliveryConcurrencyIntegrationTest가 예외 종류를 실제 DB로 확인)
 * - 송장 수정(PATCH)도 등록과 같은 요청 검증을 한다
 */
@WebMvcTest(ApiV1LogisticsDeliveryController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1LogisticsDeliveryConflictTest {

    private static final String BASE = "/api/v1/logistics/deliveries";
    private static final String BODY = """
            {"courier":"CJ","trackingNumber":"123456789012"}""";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @MockitoBean private DeliveryFacade deliveryFacade;

    @Test
    @DisplayName("송장 등록이 DB 유니크에 걸리면 409 DATA_CONFLICT")
    void ship_dbUniqueViolation_409() throws Exception {
        given(deliveryFacade.ship(anyLong(), any(), anyString()))
                .willThrow(new DataIntegrityViolationException("uk_delivery_tracking"));

        mockMvc.perform(post(BASE + "/3/ship").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultCode").value("409-DATA_CONFLICT"));
    }

    @Test
    @DisplayName("송장 수정이 DB 유니크에 걸리면 409 DATA_CONFLICT")
    void changeTracking_dbUniqueViolation_409() throws Exception {
        given(deliveryFacade.changeTracking(anyLong(), any(), anyString()))
                .willThrow(new DataIntegrityViolationException("uk_delivery_tracking"));

        mockMvc.perform(patch(BASE + "/3/tracking").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultCode").value("409-DATA_CONFLICT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"trackingNumber\":\"123456789012\"}",
            "{\"courier\":\"CJ\"}",
            "{\"courier\":\"CJ\",\"trackingNumber\":\"  \"}",
            "{\"courier\":\"LOGEN\",\"trackingNumber\":\"123456789012\"}",
            "{\"courier\":\"CJ\",\"trackingNumber\":\"112-34-56-78-90-12-34-56-78-90-12-34-5678\"}"  // 원본 41자
    })
    @DisplayName("송장 수정: 요청 검증 실패는 400, Facade를 부르지 않는다")
    void changeTracking_invalidRequest(String body) throws Exception {
        mockMvc.perform(patch(BASE + "/3/tracking").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(deliveryFacade);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ready", "CANCELED", ""})
    @DisplayName("목록: 대소문자가 다르거나 없는 status는 400 (빈 값은 기본 READY)")
    void getDeliveries_statusParam(String value) throws Exception {
        if (value.isEmpty()) {
            given(deliveryFacade.getDeliveries(any(), any()))
                    .willReturn(new com.backend.global.page.PageResponse<>(java.util.List.of(), 0, 20, 0, 0));
            mockMvc.perform(get(BASE).param("status", value).header("Authorization", bearer()))
                    .andExpect(status().isOk());
            return;
        }
        mockMvc.perform(get(BASE).param("status", value).header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-INVALID_REQUEST"));
        verifyNoInteractions(deliveryFacade);
    }

    @Test
    @DisplayName("상세: 숫자가 아닌 배송 ID는 400")
    void getDelivery_nonNumericId() throws Exception {
        mockMvc.perform(get(BASE + "/abc").header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(deliveryFacade);
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.createAccessToken(1L, MemberRole.LOGISTICS);
    }
}

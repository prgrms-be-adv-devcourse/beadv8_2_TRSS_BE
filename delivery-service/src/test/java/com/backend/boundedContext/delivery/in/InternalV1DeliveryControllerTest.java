package com.backend.boundedContext.delivery.in;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.global.security.InternalApiKeyFilter;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.delivery.dto.DeliveryDto;
import com.backend.shared.delivery.dto.DeliveryStatus;
import com.backend.shared.delivery.out.DeliveryApi;
import com.backend.shared.member.dto.MemberRole;

/**
 * 배송 내부 API (market 주문 상세가 부른다). X-Internal-Key로만 부를 수 있다
 * 키 값은 src/test/resources/application.properties의 custom.internal.apiKey
 */
@WebMvcTest(InternalV1DeliveryController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class InternalV1DeliveryControllerTest {

    private static final String INTERNAL_KEY = "test-internal-api-key";
    private static final String URL = "/internal/v1/deliveries";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private DeliveryApi deliveryApi;

    @Test
    @DisplayName("내부 키가 맞으면 주문의 배송 정보를 data에 담아 돌려준다")
    void found() throws Exception {
        LocalDateTime shippedAt = LocalDateTime.of(2026, 11, 7, 9, 0);
        given(deliveryApi.findByOrderId(10L)).willReturn(Optional.of(
                new DeliveryDto(3L, DeliveryStatus.SHIPPED, "CJ대한통운", "123456789012", shippedAt, null)));

        mockMvc.perform(get(URL).param("orderId", "10").header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.deliveryId").value(3))
                .andExpect(jsonPath("$.data.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.carrierName").value("CJ대한통운"))
                .andExpect(jsonPath("$.data.trackingNumber").value("123456789012"))
                .andExpect(jsonPath("$.data.deliveredAt").doesNotExist());
    }

    @Test
    @DisplayName("배송 건이 없는 주문이면 200 + data null")
    void notFound_nullData() throws Exception {
        given(deliveryApi.findByOrderId(11L)).willReturn(Optional.empty());

        mockMvc.perform(get(URL).param("orderId", "11").header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("내부 키가 없으면 401 INTERNAL_API_KEY_INVALID")
    void noKey_unauthorized() throws Exception {
        mockMvc.perform(get(URL).param("orderId", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-INTERNAL_API_KEY_INVALID"));
        verifyNoInteractions(deliveryApi);
    }

    @Test
    @DisplayName("사용자 JWT(물류 담당자 포함)로는 부를 수 없다 401 INTERNAL_API_KEY_INVALID")
    void userToken_unauthorized() throws Exception {
        mockMvc.perform(get(URL).param("orderId", "10")
                        .header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(1L, MemberRole.LOGISTICS)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-INTERNAL_API_KEY_INVALID"));
        verifyNoInteractions(deliveryApi);
    }
}

package com.backend.boundedContext.payment.in;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.global.exception.DomainException;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;

@WebMvcTest(ApiV1TopupController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1TopupControllerTest {

    private static final String ORDER_ID = "TOPUP-123e4567-e89b-12d3-a456-426614174000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private PaymentFacade paymentFacade;

    @Test
    @DisplayName("충전 준비: 토큰의 회원 ID로 준비하고 충전 ID·주문번호·금액을 돌려준다")
    void prepareTopup_success() throws Exception {
        given(paymentFacade.prepareTopup(3L, 20_000)).willReturn(new TopupPrepareDto(5L, ORDER_ID, 20_000));

        prepare(3L, "{\"amount\":20000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.topupId").value(5))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.data.amount").value(20000));
    }

    @Test
    @DisplayName("충전 준비: 금액이 정책에 맞지 않으면 400 TOPUP_AMOUNT_INVALID")
    void prepareTopup_invalidAmount() throws Exception {
        given(paymentFacade.prepareTopup(3L, 15_000))
                .willThrow(new DomainException(PaymentErrorCode.TOPUP_AMOUNT_INVALID));

        prepare(3L, "{\"amount\":15000}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-TOPUP_AMOUNT_INVALID"));
    }

    @Test
    @DisplayName("충전 준비: 금액이 없으면 400 VALIDATION_FAILED")
    void prepareTopup_missingAmount() throws Exception {
        prepare(3L, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        verify(paymentFacade, never()).prepareTopup(anyLong(), anyLong());
    }

    @Test
    @DisplayName("충전 준비: 토큰이 없으면 401 UNAUTHORIZED")
    void prepareTopup_noToken() throws Exception {
        mockMvc.perform(post("/api/v1/topups").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":20000}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));
    }

    private ResultActions prepare(Long memberId, String body) throws Exception {
        String token = jwtTokenProvider.createAccessToken(memberId, MemberRole.USER);
        return mockMvc.perform(post("/api/v1/topups")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}

package com.backend.boundedContext.payment.in;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

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
import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.TopupStatus;
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

    private static final String CONFIRM_BODY =
            "{\"paymentKey\":\"pk_1\",\"orderId\":\"" + ORDER_ID + "\",\"amount\":20000}";

    @Test
    @DisplayName("충전 승인: 반영되면 충전 완료 메시지와 DONE·반영 후 잔액을 돌려준다")
    void confirmTopup_done() throws Exception {
        given(paymentFacade.confirmTopup(3L, "pk_1", ORDER_ID, 20_000))
                .willReturn(new TopupConfirmDto(5L, ORDER_ID, TopupStatus.DONE, 20_000, 25_000L));

        confirm(3L, CONFIRM_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.msg").value("충전이 완료되었습니다."))
                .andExpect(jsonPath("$.data.topupId").value(5))
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.balance").value(25000));
    }

    @Test
    @DisplayName("충전 승인: 결과 확인 중이면 200-1에 status PROCESSING, 잔액은 null")
    void confirmTopup_processing() throws Exception {
        given(paymentFacade.confirmTopup(3L, "pk_1", ORDER_ID, 20_000))
                .willReturn(TopupConfirmDto.processing(5L, ORDER_ID, 20_000));

        confirm(3L, CONFIRM_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.msg").value("충전 결과를 확인하고 있습니다."))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.balance").doesNotExist());
    }

    @Test
    @DisplayName("충전 승인: 카드 거절이면 400 TOPUP_PAYMENT_DECLINED와 토스 코드·메시지")
    void confirmTopup_declined() throws Exception {
        given(paymentFacade.confirmTopup(3L, "pk_1", ORDER_ID, 20_000))
                .willThrow(new DomainException(PaymentErrorCode.TOPUP_PAYMENT_DECLINED,
                        Map.of("code", "REJECT_CARD_PAYMENT", "message", "한도초과 혹은 잔액부족으로 결제에 실패했습니다.")));

        confirm(3L, CONFIRM_BODY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-TOPUP_PAYMENT_DECLINED"))
                .andExpect(jsonPath("$.data.code").value("REJECT_CARD_PAYMENT"))
                .andExpect(jsonPath("$.data.message").value("한도초과 혹은 잔액부족으로 결제에 실패했습니다."));
    }

    @Test
    @DisplayName("충전 승인: 결제키가 없으면 400 VALIDATION_FAILED")
    void confirmTopup_missingPaymentKey() throws Exception {
        confirm(3L, "{\"orderId\":\"" + ORDER_ID + "\",\"amount\":20000}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        verify(paymentFacade, never()).confirmTopup(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("충전 승인: 토큰이 없으면 401 UNAUTHORIZED")
    void confirmTopup_noToken() throws Exception {
        mockMvc.perform(post("/api/v1/topups/confirm").contentType(MediaType.APPLICATION_JSON).content(CONFIRM_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-UNAUTHORIZED"));
    }

    private ResultActions confirm(Long memberId, String body) throws Exception {
        String token = jwtTokenProvider.createAccessToken(memberId, MemberRole.USER);
        return mockMvc.perform(post("/api/v1/topups/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions prepare(Long memberId, String body) throws Exception {
        String token = jwtTokenProvider.createAccessToken(memberId, MemberRole.USER);
        return mockMvc.perform(post("/api/v1/topups")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}

package com.backend.boundedContext.order.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.ReturnReasonCode;
import com.backend.boundedContext.order.in.dto.OrderDetailResponse;
import com.backend.boundedContext.order.in.dto.RefundStatus;
import com.backend.boundedContext.order.in.dto.ReturnRequestResponse;
import com.backend.global.exception.DomainException;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.order.dto.OrderStatus;

/**
 * 반품 요청 API(POST /api/v1/orders/{orderId}/returns)의 HTTP 계약과 주문 상세의 반품 필드 JSON
 */
@WebMvcTest(ApiV1OrderController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1OrderControllerReturnTest {

    private static final long MEMBER_ID = 3L;
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 11, 12, 10, 30);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @MockitoBean private OrderFacade orderFacade;

    @Test
    @DisplayName("반품 요청: 200-1, 주문 id·RETURN_REQUESTED·요청 시각을 돌려주고 사유 코드·상세 사유를 Facade에 넘긴다")
    void request_200() throws Exception {
        given(orderFacade.requestReturn(MEMBER_ID, 9L, ReturnReasonCode.DEFECTIVE, "깨져서 왔어요"))
                .willReturn(new ReturnRequestResponse(9L, OrderStatus.RETURN_REQUESTED, REQUESTED_AT));

        mockMvc.perform(post("/api/v1/orders/9/returns").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"DEFECTIVE\",\"reason\":\"깨져서 왔어요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.orderId").value(9))
                .andExpect(jsonPath("$.data.status").value("RETURN_REQUESTED"))
                .andExpect(jsonPath("$.data.returnRequestedAt").value("2026-11-12T10:30:00"));
    }

    @Test
    @DisplayName("반품 요청: 상세 사유는 생략할 수 있다(null로 넘김)")
    void request_withoutReason() throws Exception {
        given(orderFacade.requestReturn(eq(MEMBER_ID), eq(9L), eq(ReturnReasonCode.CHANGE_OF_MIND), isNull()))
                .willReturn(new ReturnRequestResponse(9L, OrderStatus.RETURN_REQUESTED, REQUESTED_AT));

        mockMvc.perform(post("/api/v1/orders/9/returns").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"CHANGE_OF_MIND\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("반품 요청: 사유 코드가 없으면 400 VALIDATION_FAILED, Facade를 부르지 않는다")
    void request_noReasonCode_400() throws Exception {
        mockMvc.perform(post("/api/v1/orders/9/returns").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"그냥요\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("reasonCode"));

        verify(orderFacade, never()).requestReturn(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("반품 요청: 상세 사유가 300자를 넘으면 400 VALIDATION_FAILED (300자는 통과)")
    void request_reasonTooLong_400() throws Exception {
        mockMvc.perform(post("/api/v1/orders/9/returns").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"OTHER\",\"reason\":\"" + "가".repeat(301) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("reason"));
        verify(orderFacade, never()).requestReturn(anyLong(), anyLong(), any(), any());

        given(orderFacade.requestReturn(eq(MEMBER_ID), eq(9L), eq(ReturnReasonCode.OTHER), any()))
                .willReturn(new ReturnRequestResponse(9L, OrderStatus.RETURN_REQUESTED, REQUESTED_AT));
        mockMvc.perform(post("/api/v1/orders/9/returns").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"OTHER\",\"reason\":\"" + "가".repeat(300) + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("반품 요청: 없는 사유 코드는 400 INVALID_REQUEST")
    void request_unknownReasonCode_400() throws Exception {
        mockMvc.perform(post("/api/v1/orders/9/returns").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"BROKEN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-INVALID_REQUEST"));
    }

    @ParameterizedTest
    @EnumSource(value = OrderErrorCode.class,
            names = {"RETURN_ALREADY_REQUESTED", "ORDER_STATE_INVALID", "ORDER_NOT_DELIVERED", "RETURN_REASON_REQUIRED", "ORDER_NOT_FOUND"})
    @DisplayName("반품 요청: 도메인 오류는 코드의 HTTP 상태와 resultCode로 응답한다")
    void request_domainErrors(OrderErrorCode code) throws Exception {
        given(orderFacade.requestReturn(eq(MEMBER_ID), eq(9L), any(), any())).willThrow(new DomainException(code));

        mockMvc.perform(post("/api/v1/orders/9/returns").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"OTHER\"}"))
                .andExpect(status().is(code.status()))
                .andExpect(jsonPath("$.resultCode").value(code.resultCode()))
                .andExpect(jsonPath("$.msg").value(code.msg()));
    }

    @Test
    @DisplayName("반품 요청: 토큰이 없으면 401")
    void request_noToken_401() throws Exception {
        mockMvc.perform(post("/api/v1/orders/9/returns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"DEFECTIVE\"}"))
                .andExpect(status().isUnauthorized());
        verify(orderFacade, never()).requestReturn(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("주문 상세: 반품 필드와 refundStatus가 JSON에 나간다")
    void detail_returnFields() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 11, 12, 10, 0);
        given(orderFacade.getOrderDetail(MEMBER_ID, 9L)).willReturn(new OrderDetailResponse(
                9L, "ORD-20261101-0001", OrderStatus.RETURNED, 10_000, now, now, 0,
                now, null, null, List.of(), List.of(), null, now, null, false, 0, 0, 12_500, 2_500, false,
                REQUESTED_AT, ReturnReasonCode.OTHER, "색이 달라요", null, null, REQUESTED_AT.plusDays(1), RefundStatus.PENDING));

        mockMvc.perform(get("/api/v1/orders/9").header("Authorization", bearer(MemberRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.returnRequestedAt").value("2026-11-12T10:30:00"))
                .andExpect(jsonPath("$.data.returnReasonCode").value("OTHER"))
                .andExpect(jsonPath("$.data.returnReason").value("색이 달라요"))
                .andExpect(jsonPath("$.data.returnRejectedAt").doesNotExist())
                .andExpect(jsonPath("$.data.returnRejectReason").doesNotExist())
                .andExpect(jsonPath("$.data.returnedAt").value("2026-11-13T10:30:00"))
                .andExpect(jsonPath("$.data.refundStatus").value("PENDING"));
    }

    private String bearer(MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(MEMBER_ID, role);
    }
}

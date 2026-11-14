package com.backend.boundedContext.order.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

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
import com.backend.boundedContext.order.domain.ReturnReasonCode;
import com.backend.boundedContext.order.in.dto.ReturnRequestResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.order.dto.OrderStatus;

/**
 * [QA] 반품 요청 API 보강: USER가 아닌 역할(ADMIN·LOGISTICS)은 403, 본문 없음·사유 코드 null·잘못된 경로 변수.
 */
@WebMvcTest(ApiV1OrderController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1OrderControllerReturnQaTest {

    private static final long MEMBER_ID = 3L;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @MockitoBean private OrderFacade orderFacade;

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "LOGISTICS"})
    @DisplayName("USER가 아닌 역할(ADMIN·LOGISTICS)은 403, Facade를 부르지 않는다")
    void request_nonUserRole_403(MemberRole role) throws Exception {
        mockMvc.perform(post("/api/v1/orders/9/returns").header("Authorization", bearer(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"DEFECTIVE\"}"))
                .andExpect(status().isForbidden());
        verify(orderFacade, never()).requestReturn(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("사유 코드를 null로 보내면 400 VALIDATION_FAILED")
    void request_nullReasonCode_400() throws Exception {
        mockMvc.perform(post("/api/v1/orders/9/returns").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":null,\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        verify(orderFacade, never()).requestReturn(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("본문이 없으면 400 INVALID_REQUEST")
    void request_noBody_400() throws Exception {
        mockMvc.perform(post("/api/v1/orders/9/returns").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-INVALID_REQUEST"));
        verify(orderFacade, never()).requestReturn(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("주문 id가 숫자가 아니면 400 INVALID_REQUEST")
    void request_badOrderId_400() throws Exception {
        mockMvc.perform(post("/api/v1/orders/abc/returns").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"DEFECTIVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-INVALID_REQUEST"));
    }

    @Test
    @DisplayName("토큰의 회원 id로 요청한다(본문에 다른 값이 와도 무시), 공백뿐인 상세 사유도 그대로 Facade에 넘긴다")
    void request_usesTokenMember() throws Exception {
        given(orderFacade.requestReturn(eq(MEMBER_ID), eq(9L), eq(ReturnReasonCode.CHANGE_OF_MIND), eq("   ")))
                .willReturn(new ReturnRequestResponse(9L, OrderStatus.RETURN_REQUESTED, LocalDateTime.of(2026, 11, 12, 10, 0)));

        mockMvc.perform(post("/api/v1/orders/9/returns").header("Authorization", bearer(MemberRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"CHANGE_OF_MIND\",\"reason\":\"   \",\"buyerId\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETURN_REQUESTED"));
        verify(orderFacade, never()).requestReturn(eq(999L), anyLong(), any(), isNull());
    }

    private String bearer(MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(MEMBER_ID, role);
    }
}

package com.backend.boundedContext.order.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.ReturnFilter;
import com.backend.boundedContext.order.domain.ReturnItemDecision;
import com.backend.boundedContext.order.domain.ReturnReasonCode;
import com.backend.boundedContext.order.in.dto.AdminReturnSummaryResponse;
import com.backend.boundedContext.order.in.dto.ReturnResultResponse;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.OrderStatus;

/**
 * 관리자 반품 API(/api/v1/admin/returns)의 HTTP 계약: 권한, 요청 검증, 응답 JSON
 */
@WebMvcTest(ApiV1AdminReturnController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1AdminReturnControllerTest {

    private static final long ADMIN_ID = 1L;
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 12, 10, 30);
    private static final String APPROVE_BODY =
            "{\"items\":[{\"orderItemId\":11,\"restoreStock\":true},{\"orderItemId\":12,\"restoreStock\":false}]}";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @MockitoBean private OrderFacade orderFacade;

    // ---------- 목록 ----------

    @Test
    @DisplayName("목록: filter 기본값 REQUESTED, 200-1, PageResponse 형식이고 배송지·연락처 필드는 없다")
    void list_200() throws Exception {
        given(orderFacade.getAdminReturns(eq(ReturnFilter.REQUESTED), any())).willReturn(new PageResponse<>(
                List.of(summary()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/admin/returns").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].orderId").value(9))
                .andExpect(jsonPath("$.data.items[0].orderNumber").value("ORD-20261112-ABCDEFGH"))
                .andExpect(jsonPath("$.data.items[0].buyerId").value(3))
                .andExpect(jsonPath("$.data.items[0].status").value("RETURN_REQUESTED"))
                .andExpect(jsonPath("$.data.items[0].payAmount").value(27_500))
                .andExpect(jsonPath("$.data.items[0].shippingFee").value(2_500))
                .andExpect(jsonPath("$.data.items[0].deliveredAt").value("2026-11-12T10:30:00"))
                .andExpect(jsonPath("$.data.items[0].reasonCode").value("DEFECTIVE"))
                .andExpect(jsonPath("$.data.items[0].reason").value("깨짐"))
                .andExpect(jsonPath("$.data.items[0].returnedAt").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].items[0].orderItemId").value(11))
                .andExpect(jsonPath("$.data.items[0].items[0].sellerName").value("상점"))
                .andExpect(jsonPath("$.data.items[0].items[0].lineAmount").value(20_000))
                .andExpect(jsonPath("$.data.items[0].shippingAddress").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].receiverName").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].receiverPhone").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(ReturnFilter.class)
    @DisplayName("목록: filter 값을 그대로 넘긴다")
    void list_filter(ReturnFilter filter) throws Exception {
        given(orderFacade.getAdminReturns(eq(filter), any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/admin/returns").param("filter", filter.name())
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk());
        verify(orderFacade).getAdminReturns(eq(filter), any());
    }

    @Test
    @DisplayName("목록: size는 최대 100으로 잘린다(기존 관리자 목록 규칙)")
    void list_maxSize() throws Exception {
        given(orderFacade.getAdminReturns(any(), any())).willReturn(new PageResponse<>(List.of(), 0, 100, 0, 0));

        mockMvc.perform(get("/api/v1/admin/returns").param("page", "2").param("size", "500")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderFacade).getAdminReturns(eq(ReturnFilter.REQUESTED), pageable.capture());
        assertPage(pageable.getValue(), 2, 100);
    }

    @Test
    @DisplayName("목록: 없는 filter 값은 400 INVALID_REQUEST")
    void list_badFilter() throws Exception {
        mockMvc.perform(get("/api/v1/admin/returns").param("filter", "PENDING")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-INVALID_REQUEST"));
    }

    // ---------- 권한 ----------

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"USER", "LOGISTICS"})
    @DisplayName("ADMIN이 아니면 목록·승인·거절 모두 403, Facade를 부르지 않는다")
    void nonAdmin_403(MemberRole role) throws Exception {
        mockMvc.perform(get("/api/v1/admin/returns").header("Authorization", bearer(role)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/returns/9/approve").header("Authorization", bearer(role))
                        .contentType(MediaType.APPLICATION_JSON).content(APPROVE_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/returns/9/reject").header("Authorization", bearer(role))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"사유\"}"))
                .andExpect(status().isForbidden());

        verify(orderFacade, never()).getAdminReturns(any(), any());
        verify(orderFacade, never()).approveReturn(anyLong(), any());
        verify(orderFacade, never()).rejectReturn(anyLong(), anyString());
    }

    @Test
    @DisplayName("토큰이 없으면 401")
    void noToken_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/returns")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/returns/9/approve")
                        .contentType(MediaType.APPLICATION_JSON).content(APPROVE_BODY))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 승인 ----------

    @Test
    @DisplayName("승인: 200-1, 항목 결정을 순서대로 Facade에 넘기고 RETURNED·confirmType null을 돌려준다")
    void approve_200() throws Exception {
        given(orderFacade.approveReturn(9L, List.of(new ReturnItemDecision(11L, true), new ReturnItemDecision(12L, false))))
                .willReturn(new ReturnResultResponse(9L, OrderStatus.RETURNED, null));

        mockMvc.perform(post("/api/v1/admin/returns/9/approve").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(APPROVE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.orderId").value(9))
                .andExpect(jsonPath("$.data.status").value("RETURNED"))
                .andExpect(jsonPath("$.data.confirmType").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"items\":[]}",
            "{\"items\":[null]}",
            "{\"items\":[{\"restoreStock\":true}]}",
            "{\"items\":[{\"orderItemId\":11}]}",
            "{\"items\":[{\"orderItemId\":11,\"restoreStock\":null}]}"
    })
    @DisplayName("승인: items 없음·빈 목록·null 항목·값 누락은 400 VALIDATION_FAILED, Facade를 부르지 않는다")
    void approve_validation(String body) throws Exception {
        mockMvc.perform(post("/api/v1/admin/returns/9/approve").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        verify(orderFacade, never()).approveReturn(anyLong(), any());
    }

    @Test
    @DisplayName("승인: 도메인 오류는 상태 코드·resultCode 그대로 (400 RETURN_ITEMS_MISMATCH, 409 ORDER_STATE_INVALID, 409 PRODUCT_DELETED)")
    void approve_domainErrors() throws Exception {
        given(orderFacade.approveReturn(eq(9L), any())).willThrow(new DomainException(OrderErrorCode.RETURN_ITEMS_MISMATCH));
        given(orderFacade.approveReturn(eq(10L), any())).willThrow(new DomainException(OrderErrorCode.ORDER_STATE_INVALID));
        given(orderFacade.approveReturn(eq(11L), any())).willThrow(new DomainException("409-PRODUCT_DELETED", "삭제된 상품입니다.", null));

        approve(9L).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-RETURN_ITEMS_MISMATCH"))
                .andExpect(jsonPath("$.msg").value("주문 항목마다 재고 복원 여부를 한 번씩 정해 주세요."));
        approve(10L).andExpect(status().isConflict()).andExpect(jsonPath("$.resultCode").value("409-ORDER_STATE_INVALID"));
        approve(11L).andExpect(status().isConflict()).andExpect(jsonPath("$.resultCode").value("409-PRODUCT_DELETED"));
    }

    // ---------- 거절 ----------

    @Test
    @DisplayName("거절: 200-1, 즉시 확정이면 CONFIRMED·RETURN_REJECTED를 돌려준다")
    void reject_200() throws Exception {
        given(orderFacade.rejectReturn(9L, "사용 흔적이 있어요"))
                .willReturn(new ReturnResultResponse(9L, OrderStatus.CONFIRMED, ConfirmType.RETURN_REJECTED));

        mockMvc.perform(post("/api/v1/admin/returns/9/reject").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"사용 흔적이 있어요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmType").value("RETURN_REJECTED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reason\":null}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}"})
    @DisplayName("거절: 사유가 없거나 공백이면 400 VALIDATION_FAILED (사유 필수)")
    void reject_noReason(String body) throws Exception {
        mockMvc.perform(post("/api/v1/admin/returns/9/reject").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("reason"));
        verify(orderFacade, never()).rejectReturn(anyLong(), anyString());
    }

    @Test
    @DisplayName("거절: 사유 300자는 통과, 301자는 400")
    void reject_reasonLength() throws Exception {
        given(orderFacade.rejectReturn(eq(9L), anyString())).willReturn(new ReturnResultResponse(9L, OrderStatus.PAID, null));

        mockMvc.perform(post("/api/v1/admin/returns/9/reject").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + "가".repeat(300) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.confirmType").doesNotExist());
        mockMvc.perform(post("/api/v1/admin/returns/9/reject").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + "가".repeat(301) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("거절: 이미 처리된 주문은 409 ORDER_STATE_INVALID")
    void reject_409() throws Exception {
        given(orderFacade.rejectReturn(9L, "사유")).willThrow(new DomainException(OrderErrorCode.ORDER_STATE_INVALID));

        mockMvc.perform(post("/api/v1/admin/returns/9/reject").header("Authorization", bearer(MemberRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"사유\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultCode").value("409-ORDER_STATE_INVALID"));
    }

    // ---------- 준비 ----------

    private org.springframework.test.web.servlet.ResultActions approve(long orderId) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/returns/" + orderId + "/approve").header("Authorization", bearer(MemberRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(APPROVE_BODY));
    }

    private static AdminReturnSummaryResponse summary() {
        return new AdminReturnSummaryResponse(9L, "ORD-20261112-ABCDEFGH", 3L, OrderStatus.RETURN_REQUESTED,
                27_500, 2_500, AT, AT.plusDays(1), ReturnReasonCode.DEFECTIVE, "깨짐", null, null,
                List.of(new AdminReturnSummaryResponse.Item(11L, 10L, "립스틱", "기본", "상점", 2, 20_000, null)));
    }

    private static void assertPage(Pageable pageable, int page, int size) {
        org.assertj.core.api.Assertions.assertThat(pageable.getPageNumber()).isEqualTo(page);
        org.assertj.core.api.Assertions.assertThat(pageable.getPageSize()).isEqualTo(size);
    }

    private String bearer(MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(ADMIN_ID, role);
    }
}

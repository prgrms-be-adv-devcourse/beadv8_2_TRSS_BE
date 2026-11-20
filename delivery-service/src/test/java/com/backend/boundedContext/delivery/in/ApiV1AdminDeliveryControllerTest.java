package com.backend.boundedContext.delivery.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.backend.boundedContext.delivery.app.DeliveryFacade;
import com.backend.boundedContext.delivery.domain.DeliveryErrorCode;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.admin.dto.AdminFailureItem;
import com.backend.shared.admin.dto.AdminFailureTargetType;
import com.backend.shared.admin.dto.AdminFailureType;
import com.backend.shared.delivery.dto.DeliveryStatus;
import com.backend.shared.member.dto.MemberRole;

/**
 * 관리자 배송 API: 권한(ADMIN만), 요청 검증, 오류 응답 형식.
 * 수동 완료의 상태·이벤트 규칙은 DeliveryManualCompleteIntegrationTest에서 실제 DB로 확인한다
 */
@WebMvcTest(ApiV1AdminDeliveryController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1AdminDeliveryControllerTest {

    private static final String BASE = "/api/v1/admin/deliveries";
    private static final String COMPLETE_BODY = """
            {"deliveredAt":"2026-11-10T15:30:00","reason":"택배사 조회가 멈춰 구매자 수령을 확인함"}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private DeliveryFacade deliveryFacade;

    static Stream<Arguments> allEndpoints() {
        return Stream.of(
                Arguments.of("목록", get(BASE)),
                Arguments.of("실패 거래", get(BASE + "/failures")),
                Arguments.of("수동 완료", post(BASE + "/1/complete").contentType(MediaType.APPLICATION_JSON).content(COMPLETE_BODY)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEndpoints")
    @DisplayName("관리자(ADMIN)는 모든 경로 200")
    void admin_allowed(String name, MockHttpServletRequestBuilder request) throws Exception {
        given(deliveryFacade.getDeliveries(any(), any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(request.header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEndpoints")
    @DisplayName("로그인하지 않으면 모든 경로 401")
    void anonymous_unauthorized(String name, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(deliveryFacade);
    }

    static Stream<Arguments> nonAdminCases() {
        return allEndpoints().flatMap(endpoint -> Stream.of(MemberRole.USER, MemberRole.LOGISTICS)
                .map(role -> Arguments.of(endpoint.get()[0] + " - " + role, endpoint.get()[1], role)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonAdminCases")
    @DisplayName("구매자(USER)·물류 담당자(LOGISTICS)는 모든 경로 403 FORBIDDEN")
    void nonAdmin_forbidden(String name, MockHttpServletRequestBuilder request, MemberRole role) throws Exception {
        mockMvc.perform(request.header("Authorization", bearer(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
        verifyNoInteractions(deliveryFacade);
    }

    @Test
    @DisplayName("목록: status가 없으면 SHIPPED(수동 완료 대상)로, 있으면 그 상태로 조회한다")
    void getDeliveries_status() throws Exception {
        given(deliveryFacade.getDeliveries(any(), any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get(BASE).header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("배송 목록을 조회했습니다."));
        mockMvc.perform(get(BASE).param("status", "READY").header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk());

        verify(deliveryFacade).getDeliveries(eq(DeliveryStatus.SHIPPED), any(Pageable.class));
        verify(deliveryFacade).getDeliveries(eq(DeliveryStatus.READY), any(Pageable.class));
    }

    @Test
    @DisplayName("실패 거래: from·to를 날짜로 넘기고 공통 형식(AdminFailureItem) 페이지로 응답한다")
    void getTrackingFailures() throws Exception {
        AdminFailureItem item = new AdminFailureItem(AdminFailureType.DELIVERY_TRACKING_FAILED,
                LocalDateTime.of(2026, 11, 1, 9, 30), AdminFailureTargetType.SELLER, 7L, "팔레트상점", null,
                "STALLED_AFTER_SHIPPED", "송장 등록 후 15일 지남", "LOGISTICS_CHECK", "물류 담당자 확인", 31L, 142L);
        given(deliveryFacade.getTrackingFailures(any(), any(), any())).willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(get(BASE + "/failures").param("from", "2026-11-01").param("to", "2026-11-09")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("실패 거래(배송)를 조회했습니다."))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("DELIVERY_TRACKING_FAILED"))
                .andExpect(jsonPath("$.data.items[0].occurredAt").value("2026-11-01T09:30:00"))
                .andExpect(jsonPath("$.data.items[0].targetType").value("SELLER"))
                .andExpect(jsonPath("$.data.items[0].amount").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].refId").value(31))
                .andExpect(jsonPath("$.data.items[0].orderId").value(142));

        verify(deliveryFacade).getTrackingFailures(eq(LocalDate.of(2026, 11, 1)), eq(LocalDate.of(2026, 11, 9)), any(Pageable.class));
    }

    @Test
    @DisplayName("실패 거래: 날짜 형식이 틀리면 400, Facade를 부르지 않는다")
    void getTrackingFailures_invalidDate() throws Exception {
        mockMvc.perform(get(BASE + "/failures").param("from", "2026/11/01")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(deliveryFacade);
    }

    @Test
    @DisplayName("수동 완료: 배송 완료 시각·사유를 Facade에 넘기고 '배송을 완료 처리했습니다.'")
    void complete() throws Exception {
        mockMvc.perform(post(BASE + "/3/complete").contentType(MediaType.APPLICATION_JSON).content(COMPLETE_BODY)
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("배송을 완료 처리했습니다."));

        verify(deliveryFacade).completeManually(3L, LocalDateTime.of(2026, 11, 10, 15, 30), "택배사 조회가 멈춰 구매자 수령을 확인함");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"reason\":\"수령 확인\"}",                                            // 완료 시각 없음
            "{\"deliveredAt\":\"2999-01-01T00:00:00\",\"reason\":\"수령 확인\"}",     // 미래 시각
            "{\"deliveredAt\":\"2026-11-10T15:30:00\"}",                            // 사유 없음
            "{\"deliveredAt\":\"2026-11-10T15:30:00\",\"reason\":\"   \"}"           // 공백 사유
    })
    @DisplayName("수동 완료: 요청 검증 실패는 400, Facade를 부르지 않는다")
    void complete_invalidRequest(String body) throws Exception {
        mockMvc.perform(post(BASE + "/3/complete").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(deliveryFacade);
    }

    @Test
    @DisplayName("수동 완료: 사유가 300자면 통과, 301자면 400")
    void complete_reasonLength() throws Exception {
        mockMvc.perform(post(BASE + "/3/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveredAt\":\"2026-11-10T15:30:00\",\"reason\":\"" + "가".repeat(300) + "\"}")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isOk());
        verify(deliveryFacade).completeManually(eq(3L), any(), eq("가".repeat(300)));

        mockMvc.perform(post(BASE + "/3/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveredAt\":\"2026-11-10T15:30:00\",\"reason\":\"" + "가".repeat(301) + "\"}")
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryFacade);
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryErrorCode.class,
            names = {"DELIVERY_NOT_FOUND", "DELIVERY_STATE_INVALID", "DELIVERY_DELIVERED_AT_INVALID"})
    @DisplayName("수동 완료: Facade 오류를 오류 코드 그대로 응답한다")
    void complete_domainError(DeliveryErrorCode errorCode) throws Exception {
        given(deliveryFacade.completeManually(anyLong(), any(), anyString())).willThrow(new DomainException(errorCode));

        mockMvc.perform(post(BASE + "/3/complete").contentType(MediaType.APPLICATION_JSON).content(COMPLETE_BODY)
                        .header("Authorization", bearer(MemberRole.ADMIN)))
                .andExpect(status().is(errorCode.status()))
                .andExpect(jsonPath("$.resultCode").value(errorCode.resultCode()));
    }

    private String bearer(MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(1L, role);
    }
}

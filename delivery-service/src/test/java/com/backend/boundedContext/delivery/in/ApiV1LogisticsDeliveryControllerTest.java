package com.backend.boundedContext.delivery.in;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.backend.boundedContext.delivery.app.DeliveryFacade;
import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.domain.DeliveryErrorCode;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.delivery.dto.DeliveryStatus;
import com.backend.shared.member.dto.MemberRole;

/**
 * 물류 배송 API: 권한(LOGISTICS만), 요청 검증, 오류 응답 형식.
 * 비즈니스 규칙(정규화·중복·상태)은 DeliveryFacadeIntegrationTest에서 실제 DB로 확인한다
 */
@WebMvcTest(ApiV1LogisticsDeliveryController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1LogisticsDeliveryControllerTest {

    private static final String BASE = "/api/v1/logistics/deliveries";
    private static final String SHIP_BODY = """
            {"courier":"CJ","trackingNumber":"1234-5678-9012"}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private DeliveryFacade deliveryFacade;

    static Stream<Arguments> allEndpoints() {
        return Stream.of(
                Arguments.of("목록", get(BASE)),
                Arguments.of("상세", get(BASE + "/1")),
                Arguments.of("송장 등록", post(BASE + "/1/ship").contentType(MediaType.APPLICATION_JSON).content(SHIP_BODY)),
                Arguments.of("송장 수정", patch(BASE + "/1/tracking").contentType(MediaType.APPLICATION_JSON).content(SHIP_BODY)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEndpoints")
    @DisplayName("물류 담당자(LOGISTICS)는 네 경로 모두 200")
    void logistics_allowed(String name, MockHttpServletRequestBuilder request) throws Exception {
        given(deliveryFacade.getDeliveries(any(), any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(request.header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEndpoints")
    @DisplayName("로그인하지 않으면 네 경로 모두 401")
    void anonymous_unauthorized(String name, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(deliveryFacade);
    }

    static Stream<Arguments> nonLogisticsCases() {
        return allEndpoints().flatMap(endpoint -> Stream.of(MemberRole.USER, MemberRole.ADMIN)
                .map(role -> Arguments.of(endpoint.get()[0] + " - " + role, endpoint.get()[1], role)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonLogisticsCases")
    @DisplayName("구매자(USER)·관리자(ADMIN)는 네 경로 모두 403 FORBIDDEN")
    void nonLogistics_forbidden(String name, MockHttpServletRequestBuilder request, MemberRole role) throws Exception {
        mockMvc.perform(request.header("Authorization", bearer(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
        verifyNoInteractions(deliveryFacade);
    }

    @Test
    @DisplayName("목록: status가 없으면 READY로 조회한다")
    void getDeliveries_defaultReady() throws Exception {
        given(deliveryFacade.getDeliveries(any(), any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get(BASE).header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("배송 목록을 조회했습니다."));

        verify(deliveryFacade).getDeliveries(eq(DeliveryStatus.READY), any(Pageable.class));
    }

    @Test
    @DisplayName("목록: status 필터와 page·size를 넘기고, size는 최대 100으로 자른다")
    void getDeliveries_statusAndMaxSize() throws Exception {
        given(deliveryFacade.getDeliveries(any(), any())).willReturn(new PageResponse<>(List.of(), 1, 100, 0, 0));

        mockMvc.perform(get(BASE).param("status", "SHIPPED").param("page", "1").param("size", "500")
                        .header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(deliveryFacade).getDeliveries(eq(DeliveryStatus.SHIPPED), pageable.capture());
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("목록: 잘못된 status는 400")
    void getDeliveries_invalidStatus() throws Exception {
        mockMvc.perform(get(BASE).param("status", "SHIPPING").header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(deliveryFacade);
    }

    @Test
    @DisplayName("상세: 없는 배송이면 404 DELIVERY_NOT_FOUND")
    void getDelivery_notFound() throws Exception {
        given(deliveryFacade.getDelivery(9L)).willThrow(new DomainException(DeliveryErrorCode.DELIVERY_NOT_FOUND));

        mockMvc.perform(get(BASE + "/9").header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultCode").value("404-DELIVERY_NOT_FOUND"));
    }

    @Test
    @DisplayName("송장 등록: 택배사·송장번호(원래 입력값)를 Facade에 넘기고 '송장을 등록했습니다.'")
    void ship() throws Exception {
        mockMvc.perform(post(BASE + "/3/ship").contentType(MediaType.APPLICATION_JSON).content(SHIP_BODY)
                        .header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("송장을 등록했습니다."));

        verify(deliveryFacade).ship(3L, Courier.CJ, "1234-5678-9012");
    }

    @Test
    @DisplayName("송장 수정: '송장을 수정했습니다.'")
    void changeTracking() throws Exception {
        mockMvc.perform(patch(BASE + "/3/tracking").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courier":"POST","trackingNumber":"6896123456789"}""")
                        .header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("송장을 수정했습니다."));

        verify(deliveryFacade).changeTracking(3L, Courier.POST, "6896123456789");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"trackingNumber\":\"123456789012\"}",                         // 택배사 없음
            "{\"courier\":\"CJ\"}",                                         // 송장번호 없음
            "{\"courier\":\"CJ\",\"trackingNumber\":\"  \"}",               // 공백
            "{\"courier\":\"LOGEN\",\"trackingNumber\":\"123456789012\"}",  // 지원하지 않는 택배사
            "{\"courier\":\"CJ\",\"trackingNumber\":\"" + RAW_41 + "\"}"    // 원본 41자
    })
    @DisplayName("송장 등록: 요청 검증 실패는 400, Facade를 부르지 않는다")
    void ship_invalidRequest(String body) throws Exception {
        mockMvc.perform(post(BASE + "/3/ship").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(deliveryFacade);
    }

    // 하이픈 포함 원본 40자, 지우면 숫자 28자
    private static final String RAW_40 = "12-34-56-78-90-12-34-56-78-90-12-34-5678";
    private static final String RAW_41 = "1" + RAW_40;

    @Test
    @DisplayName("송장 등록: 원본이 30자를 넘어도(하이픈 포함 40자) 길이 검증을 통과해 Facade에 원본 그대로 넘긴다")
    void ship_rawLongerThan30_ok() throws Exception {
        org.assertj.core.api.Assertions.assertThat(RAW_40).hasSize(40);
        org.assertj.core.api.Assertions.assertThat(RAW_40.replace("-", "")).hasSize(28);

        mockMvc.perform(post(BASE + "/3/ship").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courier\":\"CJ\",\"trackingNumber\":\"" + RAW_40 + "\"}")
                        .header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("송장을 등록했습니다."));

        verify(deliveryFacade).ship(3L, Courier.CJ, RAW_40);
    }

    @Test
    @DisplayName("송장 수정: 공백·하이픈 포함 원본 32자(지우면 20자)는 통과, 원본 41자는 400")
    void changeTracking_rawLength() throws Exception {
        String raw32 = "1234 - 5678 - 9012 - 3456 - 7890";
        org.assertj.core.api.Assertions.assertThat(raw32).hasSize(32);
        org.assertj.core.api.Assertions.assertThat(RAW_41).hasSize(41);

        mockMvc.perform(patch(BASE + "/3/tracking").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courier\":\"POST\",\"trackingNumber\":\"" + raw32 + "\"}")
                        .header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isOk());
        verify(deliveryFacade).changeTracking(3L, Courier.POST, raw32);

        mockMvc.perform(patch(BASE + "/3/tracking").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courier\":\"POST\",\"trackingNumber\":\"" + RAW_41 + "\"}")
                        .header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryFacade);
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryErrorCode.class,
            names = {"DELIVERY_NOT_FOUND", "DELIVERY_STATE_INVALID", "DELIVERY_TRACKING_DUPLICATED", "DELIVERY_TRACKING_NUMBER_INVALID"})
    @DisplayName("송장 등록: Facade 오류를 오류 코드 그대로 응답한다")
    void ship_domainError(DeliveryErrorCode errorCode) throws Exception {
        given(deliveryFacade.ship(anyLong(), any(), anyString())).willThrow(new DomainException(errorCode));

        mockMvc.perform(post(BASE + "/3/ship").contentType(MediaType.APPLICATION_JSON).content(SHIP_BODY)
                        .header("Authorization", bearer(MemberRole.LOGISTICS)))
                .andExpect(status().is(errorCode.status()))
                .andExpect(jsonPath("$.resultCode").value(errorCode.resultCode()))
                .andExpect(jsonPath("$.msg", containsString(errorCode.msg())));
    }

    private String bearer(MemberRole role) {
        return "Bearer " + jwtTokenProvider.createAccessToken(1L, role);
    }
}

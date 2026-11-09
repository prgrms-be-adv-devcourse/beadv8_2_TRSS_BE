package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.boundedContext.coupon.app.dto.AdminCouponDetailDto;
import com.backend.boundedContext.coupon.app.dto.CartCouponCreateCommand;
import com.backend.boundedContext.coupon.domain.CartCouponTierSpec;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponIssuerType;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.global.page.PageResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

@WebMvcTest(ApiV1AdminCouponController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1AdminCouponControllerTest {

    private static final String VALID_BODY = """
            {
              "name": "11월 장바구니 쿠폰",
              "tiers": [
                {"minOrderAmount": 50000, "discountAmount": 5000},
                {"minOrderAmount": 30000, "discountAmount": 3000}
              ],
              "validFrom": "2026-11-08T00:00:00",
              "validUntil": "2026-11-09T18:00:00",
              "quantityLimited": true,
              "totalQuantity": 500
            }""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CouponFacade couponFacade;

    @Test
    @DisplayName("쿠폰 목록: 종류와 상태 조건을 그대로 넘기고 PageResponse로 돌려준다")
    void getCoupons() throws Exception {
        given(couponFacade.getCouponsForAdmin(eq(CouponType.CART), eq(CouponDisplayStatus.SCHEDULED), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        perform(MemberRole.ADMIN, get("/api/v1/admin/coupons?type=CART&status=SCHEDULED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    @DisplayName("쿠폰 목록: 조건을 보내지 않으면 null로 넘긴다")
    void getCoupons_noCondition() throws Exception {
        given(couponFacade.getCouponsForAdmin(isNull(), isNull(), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        perform(MemberRole.ADMIN, get("/api/v1/admin/coupons"))
                .andExpect(status().isOk());

        verify(couponFacade).getCouponsForAdmin(isNull(), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("쿠폰 목록: 없는 상태 값이면 400 INVALID_REQUEST")
    void getCoupons_invalidStatus() throws Exception {
        perform(MemberRole.ADMIN, get("/api/v1/admin/coupons?status=EXPIRED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-INVALID_REQUEST"));
    }

    @Test
    @DisplayName("관리자 쿠폰 API는 ADMIN이 아니면 403 FORBIDDEN, 토큰이 없으면 401")
    void notAdmin() throws Exception {
        perform(MemberRole.USER, get("/api/v1/admin/coupons"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
        perform(MemberRole.USER, post("/api/v1/admin/coupons/cart").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/coupons"))
                .andExpect(status().isUnauthorized());
        verify(couponFacade, never()).createCartCoupon(anyLong(), any());
    }

    @Test
    @DisplayName("쿠폰 상세: 쿠폰 정보와 사용 내역을 돌려준다")
    void getCoupon() throws Exception {
        AdminCouponDetailDto detail = new AdminCouponDetailDto(10L, CouponType.CART, CouponIssuerType.ADMIN, 1L,
                "11월 장바구니 쿠폰", CouponDisplayStatus.ACTIVE,
                LocalDateTime.of(2026, 11, 8, 0, 0), LocalDateTime.of(2026, 11, 9, 18, 0),
                false, null, null, List.of(), null, null, null, 3, 15_000,
                LocalDateTime.of(2026, 11, 1, 9, 0), new PageResponse<>(List.of(), 0, 20, 0, 0));
        given(couponFacade.getCouponForAdmin(eq(10L), any(Pageable.class))).willReturn(detail);

        perform(MemberRole.ADMIN, get("/api/v1/admin/coupons/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.couponId").value(10))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.usedCount").value(3))
                .andExpect(jsonPath("$.data.usages.items").isArray());
    }

    @Test
    @DisplayName("장바구니 쿠폰 등록: 로그인한 관리자 ID와 입력값을 넘기고 쿠폰 ID를 돌려준다")
    void createCartCoupon() throws Exception {
        given(couponFacade.createCartCoupon(eq(1L), any(CartCouponCreateCommand.class))).willReturn(10L);

        perform(MemberRole.ADMIN, post("/api/v1/admin/coupons/cart").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.couponId").value(10));

        ArgumentCaptor<CartCouponCreateCommand> captor = ArgumentCaptor.forClass(CartCouponCreateCommand.class);
        verify(couponFacade).createCartCoupon(eq(1L), captor.capture());
        CartCouponCreateCommand command = captor.getValue();
        assertThat(command.name()).isEqualTo("11월 장바구니 쿠폰");
        assertThat(command.tiers()).containsExactly(
                new CartCouponTierSpec(50_000, 5_000), new CartCouponTierSpec(30_000, 3_000));
        assertThat(command.validFrom()).isEqualTo(LocalDateTime.of(2026, 11, 8, 0, 0));
        assertThat(command.validUntil()).isEqualTo(LocalDateTime.of(2026, 11, 9, 18, 0));
        assertThat(command.quantityLimited()).isTrue();
        assertThat(command.totalQuantity()).isEqualTo(500);
    }

    @Test
    @DisplayName("장바구니 쿠폰 등록: 구간이 없거나 11개 이상, 금액이 0 이하, 기간이나 이름이 없으면 400 VALIDATION_FAILED")
    void createCartCoupon_invalid() throws Exception {
        String tiers11 = "[" + String.join(",", java.util.Collections.nCopies(11,
                "{\"minOrderAmount\": 30000, \"discountAmount\": 3000}")) + "]";
        List<String> bodies = List.of(
                VALID_BODY.replace("\"name\": \"11월 장바구니 쿠폰\"", "\"name\": \" \""),
                body("[]", "true", "500"),
                body(tiers11, "true", "500"),
                body("[{\"minOrderAmount\": 0, \"discountAmount\": 3000}]", "true", "500"),
                body("[{\"minOrderAmount\": 30000}]", "true", "500"),
                VALID_BODY.replace("\"validFrom\": \"2026-11-08T00:00:00\",", ""),
                body("[{\"minOrderAmount\": 30000, \"discountAmount\": 3000}]", "true", "0"));

        for (String body : bodies) {
            perform(MemberRole.ADMIN, post("/api/v1/admin/coupons/cart").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        }
        verify(couponFacade, never()).createCartCoupon(anyLong(), any());
    }

    @Test
    @DisplayName("장바구니 쿠폰 등록: 수량 제한인데 총 수량이 없거나, 제한이 없는데 총 수량을 보내면 400 VALIDATION_FAILED")
    void createCartCoupon_quantityInconsistent() throws Exception {
        String tiers = "[{\"minOrderAmount\": 30000, \"discountAmount\": 3000}]";

        for (String body : List.of(body(tiers, "true", "null"), body(tiers, "false", "100"))) {
            perform(MemberRole.ADMIN, post("/api/v1/admin/coupons/cart").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.data.fieldErrors[0].field").value("totalQuantityConsistent"));
        }
        verify(couponFacade, never()).createCartCoupon(anyLong(), any());
    }

    private String body(String tiers, String quantityLimited, String totalQuantity) {
        return """
                {
                  "name": "쿠폰",
                  "tiers": %s,
                  "validFrom": "2026-11-08T00:00:00",
                  "validUntil": "2026-11-09T18:00:00",
                  "quantityLimited": %s,
                  "totalQuantity": %s
                }""".formatted(tiers, quantityLimited, totalQuantity);
    }

    @Test
    @DisplayName("강제 중지: 사유를 넘긴다")
    void stopCoupon() throws Exception {
        perform(MemberRole.ADMIN, post("/api/v1/admin/coupons/10/stop").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reason":"부정 사용"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));

        verify(couponFacade).stopCouponByAdmin(10L, "부정 사용");
    }

    @Test
    @DisplayName("강제 중지: 사유가 없거나 공백이거나 300자를 넘으면 400 VALIDATION_FAILED")
    void stopCoupon_reasonInvalid() throws Exception {
        for (String body : List.of("{}", "{\"reason\":\"  \"}", "{\"reason\":\"" + "가".repeat(301) + "\"}")) {
            perform(MemberRole.ADMIN, post("/api/v1/admin/coupons/10/stop").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.data.fieldErrors[0].field").value("reason"));
        }
        verify(couponFacade, never()).stopCouponByAdmin(any(), any());
    }

    private ResultActions perform(MemberRole role, MockHttpServletRequestBuilder request) throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L, role);
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }
}

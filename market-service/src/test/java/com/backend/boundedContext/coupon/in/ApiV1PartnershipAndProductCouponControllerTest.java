package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.boundedContext.coupon.app.dto.ProductCouponIssueCommand;
import com.backend.boundedContext.coupon.app.dto.ProductCouponIssueResultDto;
import com.backend.boundedContext.coupon.app.dto.ProductCouponUpdateCommand;
import com.backend.boundedContext.coupon.app.dto.ProductCouponValidationDto;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.DiscountType;
import com.backend.boundedContext.coupon.domain.PartnershipStatus;
import com.backend.boundedContext.coupon.domain.ProductCouponDiscount;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.member.dto.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 협약과 상품 쿠폰 API (판매자, 인플루언서, 관리자, 구매자 코드 검증)
 */
@WebMvcTest({ApiV1SellerPartnershipController.class, ApiV1InfluencerPartnershipController.class,
        ApiV1SellerCouponController.class, ApiV1InfluencerCouponController.class,
        ApiV1AdminPartnershipController.class, ApiV1CouponController.class})
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1PartnershipAndProductCouponControllerTest {

    private static final String ISSUE_BODY = """
            {
              "partnershipId": 5,
              "productId": 40,
              "name": "뷰티언니 립스틱 쿠폰",
              "discountType": "RATE",
              "discountValue": 15,
              "maxDiscountAmount": 5000,
              "validFrom": "2026-11-08T00:00:00",
              "validUntil": "2026-11-09T18:00:00",
              "quantityLimited": false
            }""";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @MockitoBean private CouponFacade couponFacade;

    @Test
    @DisplayName("협약 요청: 인플루언서 ID와 메시지를 넘기고 협약 ID를 돌려준다")
    void requestPartnership() throws Exception {
        given(couponFacade.requestPartnership(1L, 30L, 20, "함께해요")).willReturn(5L);

        perform(MemberRole.USER, post("/api/v1/seller/partnerships").contentType(MediaType.APPLICATION_JSON)
                .content("{\"influencerId\": 30, \"commissionRate\": 20, \"message\": \"함께해요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partnershipId").value(5));
    }

    @Test
    @DisplayName("협약 요청: 인플루언서 ID가 없으면 400 VALIDATION_FAILED")
    void requestPartnership_invalid() throws Exception {
        perform(MemberRole.USER, post("/api/v1/seller/partnerships").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        verify(couponFacade, never()).requestPartnership(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("협약 요청: 수수료율이 없거나 15~30 밖이면 400 VALIDATION_FAILED")
    void requestPartnership_commissionRateInvalid() throws Exception {
        for (String body : new String[]{
                "{\"influencerId\": 30}",
                "{\"influencerId\": 30, \"commissionRate\": 14}",
                "{\"influencerId\": 30, \"commissionRate\": 31}"}) {
            perform(MemberRole.USER, post("/api/v1/seller/partnerships").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        }
        verify(couponFacade, never()).requestPartnership(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("협약 목록, 수락, 종료와 관리자 협약 조회는 조건과 ID를 그대로 넘긴다")
    void partnershipEndpoints() throws Exception {
        given(couponFacade.getSellerPartnerships(eq(1L), eq(PartnershipStatus.ACTIVE), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        given(couponFacade.getPartnershipsForAdmin(eq(20L), isNull(), eq(PartnershipStatus.ENDED), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        perform(MemberRole.USER, get("/api/v1/seller/partnerships?status=ACTIVE")).andExpect(status().isOk());
        perform(MemberRole.USER, post("/api/v1/influencer/partnerships/5/accept")).andExpect(status().isOk());
        perform(MemberRole.USER, post("/api/v1/influencer/partnerships/5/reject")).andExpect(status().isOk());
        perform(MemberRole.USER, post("/api/v1/seller/partnerships/5/end")).andExpect(status().isOk());
        perform(MemberRole.ADMIN, get("/api/v1/admin/partnerships?sellerId=20&status=ENDED")).andExpect(status().isOk());

        verify(couponFacade).acceptPartnership(1L, 5L);
        verify(couponFacade).rejectPartnership(1L, 5L);
        verify(couponFacade).endPartnershipBySeller(1L, 5L);
    }

    @Test
    @DisplayName("승인된 인플루언서가 아니면 인플루언서 API가 403-INFLUENCER_NOT_APPROVED")
    void influencerNotApproved() throws Exception {
        given(couponFacade.getInfluencerCoupons(eq(1L), isNull(), any(Pageable.class)))
                .willThrow(new DomainException(CouponErrorCode.INFLUENCER_NOT_APPROVED));

        perform(MemberRole.USER, get("/api/v1/influencer/coupons"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-INFLUENCER_NOT_APPROVED"));
    }

    @Test
    @DisplayName("상품 쿠폰 발행: 입력값을 할인 내용과 함께 넘기고 쿠폰 ID와 코드를 돌려준다")
    void issue() throws Exception {
        given(couponFacade.issueProductCoupon(eq(1L), any(ProductCouponIssueCommand.class)))
                .willReturn(new ProductCouponIssueResultDto(77L, "ABCDEFGH23"));

        perform(MemberRole.USER, post("/api/v1/seller/coupons").contentType(MediaType.APPLICATION_JSON).content(ISSUE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.couponId").value(77))
                .andExpect(jsonPath("$.data.code").value("ABCDEFGH23"));

        ArgumentCaptor<ProductCouponIssueCommand> captor = ArgumentCaptor.forClass(ProductCouponIssueCommand.class);
        verify(couponFacade).issueProductCoupon(eq(1L), captor.capture());
        ProductCouponIssueCommand command = captor.getValue();
        assertThat(command.partnershipId()).isEqualTo(5L);
        assertThat(command.productId()).isEqualTo(40L);
        assertThat(command.discount()).isEqualTo(new ProductCouponDiscount(DiscountType.RATE, 15, 5_000L));
        assertThat(command.validUntil()).isEqualTo(LocalDateTime.of(2026, 11, 9, 18, 0));
        assertThat(command.quantityLimited()).isFalse();
        assertThat(command.totalQuantity()).isNull();
    }

    @Test
    @DisplayName("상품 쿠폰 발행: 정률인데 최대 할인액이 없거나 100%를 넘으면, 정액인데 최대 할인액이 있으면 400 VALIDATION_FAILED")
    void issue_discountInvalid() throws Exception {
        List<String> bodies = List.of(
                ISSUE_BODY.replace("\"maxDiscountAmount\": 5000,", ""),
                ISSUE_BODY.replace("\"discountValue\": 15,", "\"discountValue\": 101,"),
                ISSUE_BODY.replace("\"discountType\": \"RATE\",", "\"discountType\": \"FIXED\","),
                ISSUE_BODY.replace("\"quantityLimited\": false", "\"quantityLimited\": true"));

        for (String body : bodies) {
            perform(MemberRole.USER, post("/api/v1/seller/coupons").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        }
        verify(couponFacade, never()).issueProductCoupon(anyLong(), any());
    }

    @Test
    @DisplayName("쿠폰 수정: 보낸 항목만 넘기고, 아무것도 보내지 않으면 400 VALIDATION_FAILED")
    void update() throws Exception {
        perform(MemberRole.USER, patch("/api/v1/seller/coupons/77").contentType(MediaType.APPLICATION_JSON)
                .content("{\"validUntil\": \"2026-12-01T00:00:00\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ProductCouponUpdateCommand> captor = ArgumentCaptor.forClass(ProductCouponUpdateCommand.class);
        verify(couponFacade).updateProductCoupon(eq(1L), eq(77L), captor.capture());
        assertThat(captor.getValue().validUntil()).isEqualTo(LocalDateTime.of(2026, 12, 1, 0, 0));
        assertThat(captor.getValue().hasFieldsOtherThanValidUntil()).isFalse();

        perform(MemberRole.USER, patch("/api/v1/seller/coupons/77").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        // 이름을 보냈다면 공백만으로는 바꿀 수 없다
        perform(MemberRole.USER, patch("/api/v1/seller/coupons/77").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        verify(couponFacade).updateProductCoupon(eq(1L), eq(77L), any());   // 앞의 정상 요청 1번뿐
    }

    @Test
    @DisplayName("판매자 쿠폰 목록, 상세 조회와 중지")
    void sellerCouponEndpoints() throws Exception {
        given(couponFacade.getSellerCoupons(eq(1L), eq(CouponDisplayStatus.SCHEDULED), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        perform(MemberRole.USER, get("/api/v1/seller/coupons?status=SCHEDULED")).andExpect(status().isOk());
        perform(MemberRole.USER, post("/api/v1/seller/coupons/77/stop")).andExpect(status().isOk());

        verify(couponFacade).stopCouponBySeller(1L, 77L);
    }

    @Test
    @DisplayName("코드 검증: 코드와 할인을 붙일 장바구니 항목을 넘기고, 항목이 없으면 400 VALIDATION_FAILED")
    void validate() throws Exception {
        given(couponFacade.validateProductCouponCode(1L, "abcdefgh23", 2L)).willReturn(
                new ProductCouponValidationDto(77L, "ABCDEFGH23", "쿠폰", 2L, 40L, 30_000, 3_000, LocalDateTime.of(2026, 11, 9, 18, 0)));

        perform(MemberRole.USER, post("/api/v1/coupons/validate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"abcdefgh23\", \"cartItemId\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expectedDiscountAmount").value(3000));

        perform(MemberRole.USER, post("/api/v1/coupons/validate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"abcdefgh23\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/seller/partnerships", "/api/v1/seller/coupons",
            "/api/v1/influencer/partnerships", "/api/v1/influencer/coupons"})
    @DisplayName("관리자는 판매자, 인플루언서 API를 쓸 수 없다 (403 FORBIDDEN)")
    void adminForbidden(String url) throws Exception {
        perform(MemberRole.ADMIN, get(url))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
    }

    @Test
    @DisplayName("관리자 협약 조회는 ADMIN만, 토큰이 없으면 401")
    void adminOnly() throws Exception {
        perform(MemberRole.USER, get("/api/v1/admin/partnerships")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/influencer/partnerships")).andExpect(status().isUnauthorized());
    }

    private ResultActions perform(MemberRole role, MockHttpServletRequestBuilder request) throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L, role);
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }
}

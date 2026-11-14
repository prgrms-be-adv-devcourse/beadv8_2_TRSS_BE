package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.OptionStockDto;
import com.backend.boundedContext.product.app.dto.ProductImageFile;
import com.backend.boundedContext.product.app.dto.ProductOptionSpec;
import com.backend.boundedContext.product.app.dto.ProductStopRequestDto;
import com.backend.boundedContext.product.domain.ProductStopRequestStatus;
import com.backend.boundedContext.product.app.dto.SellerProductFormDto;
import com.backend.boundedContext.product.app.dto.SellerProductCountsDto;
import com.backend.boundedContext.product.domain.ProductDisplayStatus;
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
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1SellerProductController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1SellerProductControllerTest {

    private static final String VALID_REQUEST = """
            {"name":"수분 크림","price":25000,"categoryId":5,"description":"촉촉한 크림","options":[{"values":[],"stock":30}]}""";
    private static final MockMultipartFile THUMBNAIL =
            new MockMultipartFile("thumbnail", "t.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1});

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ProductFacade productFacade;

    @Test
    @DisplayName("상품 등록: 토큰의 회원 ID로 등록하고 상품 ID를 돌려준다. 옵션 없는 상품은 optionNames 없이 값이 빈 조합 1개를 보낸다")
    void createProduct_success() throws Exception {
        given(productFacade.createProduct(eq(2L), eq(5L), eq("수분 크림"), eq("촉촉한 크림"), eq(25_000L), eq(List.of()), eq(List.of(ProductOptionSpec.single(30))), any(), any()))
                .willReturn(11L);

        create(MemberRole.USER, jsonPart(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.productId").value(11));
    }

    @Test
    @DisplayName("상품 등록: 옵션 재고가 0이거나 최대 재고를 넘으면 400 VALIDATION_FAILED")
    void createProduct_invalidStock() throws Exception {
        for (String stock : List.of("0", "1000001")) {
            create(MemberRole.USER, jsonPart(VALID_REQUEST.replace("\"stock\":30", "\"stock\":" + stock)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.data.fieldErrors[0].field").value("options[0].stock"));
        }

        verify(productFacade, never()).createProduct(any(), any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("상품 등록: 관리자면 403 FORBIDDEN (판매자 URL은 회원만 통과)")
    void createProduct_admin() throws Exception {
        create(MemberRole.ADMIN, jsonPart(VALID_REQUEST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
    }

    @Test
    @DisplayName("상품 등록: request 파트가 application/json이 아니면 400 INVALID_REQUEST")
    void createProduct_requestPartNotJson() throws Exception {
        MockMultipartFile plainPart = new MockMultipartFile(
                "request", "", MediaType.TEXT_PLAIN_VALUE, VALID_REQUEST.getBytes(StandardCharsets.UTF_8));

        create(MemberRole.USER, plainPart)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-INVALID_REQUEST"));
    }

    @Test
    @DisplayName("상품 등록: 대표 이미지 파트가 없거나 비어 있으면 null로 넘긴다 (필수 검사는 Facade)")
    void createProduct_noThumbnail() throws Exception {
        MockMultipartFile emptyThumbnail = new MockMultipartFile("thumbnail", "a.png", MediaType.IMAGE_PNG_VALUE, new byte[0]);

        create(MemberRole.USER, new MockMultipartFile[]{jsonPart(VALID_REQUEST)}).andExpect(status().isOk());
        create(MemberRole.USER, jsonPart(VALID_REQUEST), emptyThumbnail).andExpect(status().isOk());

        verify(productFacade, times(2)).createProduct(any(), any(), any(), any(), anyLong(), any(), any(), isNull(), eq(List.of()));
    }

    @Test
    @DisplayName("상품 등록: 이미지 파일 내용을 그대로 넘기고, 상세 이미지는 보낸 순서를 지키며 빈 파트는 뺀다")
    void createProduct_withDetailImages() throws Exception {
        MockMultipartFile first = new MockMultipartFile("detailImages", "1.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1});
        MockMultipartFile empty = new MockMultipartFile("detailImages", "e.png", MediaType.IMAGE_PNG_VALUE, new byte[0]);
        MockMultipartFile second = new MockMultipartFile("detailImages", "2.png", MediaType.IMAGE_PNG_VALUE, new byte[]{2});

        create(MemberRole.USER, jsonPart(VALID_REQUEST), THUMBNAIL, first, empty, second)
                .andExpect(status().isOk());

        ArgumentCaptor<ProductImageFile> thumbnail = ArgumentCaptor.captor();
        ArgumentCaptor<List<ProductImageFile>> details = ArgumentCaptor.captor();
        verify(productFacade).createProduct(eq(2L), eq(5L), any(), any(), anyLong(), any(), any(), thumbnail.capture(), details.capture());
        assertThat(thumbnail.getValue().content()).isEqualTo(THUMBNAIL.getBytes());
        assertThat(details.getValue()).extracting(ProductImageFile::content)
                .containsExactly(new byte[]{1}, new byte[]{2});
    }

    @Test
    @DisplayName("본인 상품 목록: 상태 필터·검색어·페이지를 넘기고, data가 바로 PageResponse 형식이다")
    void getSellerProducts() throws Exception {
        given(productFacade.getSellerProducts(eq(2L), eq(ProductDisplayStatus.SOLD_OUT), eq("크림"), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        getAsSeller("/api/v1/seller/products?status=SOLD_OUT&keyword=크림")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }

    @Test
    @DisplayName("본인 상품 탭별 개수: 검색어를 넘기고 개수를 돌려준다 (/{productId}로 잡히지 않는다)")
    void getSellerProductCounts() throws Exception {
        given(productFacade.getSellerProductCounts(2L, "크림")).willReturn(new SellerProductCountsDto(4, 2, 1, 1));

        getAsSeller("/api/v1/seller/products/counts?keyword=크림")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.all").value(4))
                .andExpect(jsonPath("$.data.onSale").value(2))
                .andExpect(jsonPath("$.data.soldOut").value(1))
                .andExpect(jsonPath("$.data.stopped").value(1));

        verify(productFacade, never()).getSellerProductForm(any(), any());
    }

    @Test
    @DisplayName("상품 수정 폼 조회: 이미지 자리(thumbnail, detailImages)를 포함해 돌려준다")
    void getSellerProductForm() throws Exception {
        given(productFacade.getSellerProductForm(2L, 11L)).willReturn(
                new SellerProductFormDto(11L, "수분 크림", 25_000, "설명", 1L, 5L, null, List.of(), List.of(), List.of(), List.of()));

        getAsSeller("/api/v1/seller/products/11")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentCategoryId").value(1))
                .andExpect(jsonPath("$.data.categoryId").value(5))
                .andExpect(jsonPath("$.data.thumbnail").isEmpty())
                .andExpect(jsonPath("$.data.detailImages").isArray());
    }

    @Test
    @DisplayName("상품 수정: 보낸 항목만 넘기고, 보내지 않은 항목은 null로 넘긴다")
    void modifyProduct() throws Exception {
        modify(MemberRole.USER, jsonPart("""
                {"name":"진정 크림","price":30000}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));

        verify(productFacade).modifyProduct(eq(2L), eq(11L), eq("진정 크림"), eq(30_000L), isNull(), isNull(),
                isNull(), isNull(), eq(List.of()));
    }

    @Test
    @DisplayName("상품 수정: 남길 상세 이미지 ID와 새 이미지 파일을 넘긴다")
    void modifyProduct_withImages() throws Exception {
        String token = jwtTokenProvider.createAccessToken(2L, MemberRole.USER);
        MockMultipartFile detail = new MockMultipartFile("detailImages", "d.png", MediaType.IMAGE_PNG_VALUE, new byte[]{3});

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/v1/seller/products/11")
                        .file(jsonPart("""
                                {"detailImageIds":[7,5]}"""))
                        .file(THUMBNAIL)
                        .file(detail)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        ArgumentCaptor<ProductImageFile> thumbnail = ArgumentCaptor.captor();
        ArgumentCaptor<List<ProductImageFile>> details = ArgumentCaptor.captor();
        verify(productFacade).modifyProduct(eq(2L), eq(11L), isNull(), isNull(), isNull(), isNull(),
                eq(List.of(7L, 5L)), thumbnail.capture(), details.capture());
        assertThat(thumbnail.getValue().content()).isEqualTo(THUMBNAIL.getBytes());
        assertThat(details.getValue()).extracting(ProductImageFile::content).containsExactly(new byte[]{3});
    }

    @Test
    @DisplayName("상품 수정: 상품명이 공백이거나 가격이 0 이하면 400 VALIDATION_FAILED")
    void modifyProduct_invalid() throws Exception {
        modify(MemberRole.USER, jsonPart("""
                {"name":"  "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        modify(MemberRole.USER, jsonPart("""
                {"price":0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("price"));

        verify(productFacade, never()).modifyProduct(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("재고 조정: 토큰의 회원 ID와 경로의 상품·옵션 ID로 조정하고 조정 후 재고를 돌려준다")
    void adjustStock_success() throws Exception {
        given(productFacade.adjustStock(2L, 11L, 21L, -3)).willReturn(new OptionStockDto(21L, 7));

        adjust(MemberRole.USER, """
                {"changeQty":-3}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.optionId").value(21))
                .andExpect(jsonPath("$.data.stock").value(7));
    }

    @Test
    @DisplayName("재고 조정: 증감량이 0이거나 없거나 절댓값이 최대 재고를 넘으면 400 VALIDATION_FAILED")
    void adjustStock_invalidChangeQty() throws Exception {
        adjust(MemberRole.USER, """
                {"changeQty":0}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        for (String changeQty : List.of("1000001", "-1000001")) {
            adjust(MemberRole.USER, "{\"changeQty\":" + changeQty + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.data.fieldErrors[0].field").value("changeQty"));
        }

        adjust(MemberRole.USER, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("changeQty"));

        verify(productFacade, never()).adjustStock(anyLong(), anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("재고 조정: 관리자면 403 FORBIDDEN (판매자 URL은 회원만 통과)")
    void adjustStock_admin() throws Exception {
        adjust(MemberRole.ADMIN, """
                {"changeQty":1}""")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
    }

    @Test
    @DisplayName("상품 삭제: 토큰의 회원 ID와 경로의 상품 ID로 삭제한다")
    void deleteProduct() throws Exception {
        String token = jwtTokenProvider.createAccessToken(2L, MemberRole.USER);

        mockMvc.perform(delete("/api/v1/seller/products/11").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));

        verify(productFacade).deleteProduct(2L, 11L);
    }

    @Test
    @DisplayName("판매 중지 요청: 사유를 넘기고 요청 정보를 돌려준다")
    void requestStop() throws Exception {
        given(productFacade.requestStop(2L, 11L, "단종")).willReturn(
                new ProductStopRequestDto(5L, 11L, ProductStopRequestStatus.PENDING, "단종", null));
        String token = jwtTokenProvider.createAccessToken(2L, MemberRole.USER);

        mockMvc.perform(post("/api/v1/seller/products/11/stop-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"단종"}""")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestId").value(5))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("판매 중지 요청: 사유가 없거나 공백이거나 300자를 넘으면 400 VALIDATION_FAILED")
    void requestStop_invalidReason() throws Exception {
        String token = jwtTokenProvider.createAccessToken(2L, MemberRole.USER);

        for (String body : List.of("{}", "{\"reason\":\"  \"}", "{\"reason\":\"" + "가".repeat(301) + "\"}")) {
            mockMvc.perform(post("/api/v1/seller/products/11/stop-requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.data.fieldErrors[0].field").value("reason"));
        }
        verify(productFacade, never()).requestStop(any(), any(), any());
    }

    @Test
    @DisplayName("재고 이력: 경로의 상품·옵션 ID와 페이지를 넘기고 PageResponse로 돌려준다")
    void getStockHistories() throws Exception {
        given(productFacade.getStockHistories(eq(2L), eq(11L), eq(21L), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        getAsSeller("/api/v1/seller/products/11/options/21/stock-history")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("상품 등록(옵션): optionNames와 options를 조합 스펙으로 넘긴다. 추가금을 보내지 않으면 0")
    void createProduct_withOptions() throws Exception {
        create(MemberRole.USER, jsonPart("""
                {"name":"티셔츠","price":20000,"categoryId":5,"description":"면 티셔츠",
                 "optionNames":["색상","사이즈"],
                 "options":[{"values":["흰색","M"],"additionalPrice":0,"stock":10},{"values":["흰색","L"],"stock":5}]}"""))
                .andExpect(status().isOk());

        verify(productFacade).createProduct(eq(2L), eq(5L), eq("티셔츠"), eq("면 티셔츠"), eq(20_000L),
                eq(List.of("색상", "사이즈")),
                eq(List.of(new ProductOptionSpec(List.of("흰색", "M"), 0, 10), new ProductOptionSpec(List.of("흰색", "L"), 0, 5))),
                any(), any());
    }

    @Test
    @DisplayName("상품 등록(옵션): options가 없거나 비었거나, 옵션 값이 14자를 넘거나, 추가금이 음수면 400 VALIDATION_FAILED")
    void createProduct_invalidOptions() throws Exception {
        List<String> invalids = List.of(
                """
                {"name":"티셔츠","price":20000,"categoryId":5,"description":"설명","stock":3}""",
                """
                {"name":"티셔츠","price":20000,"categoryId":5,"description":"설명","options":[]}""",
                """
                {"name":"티셔츠","price":20000,"categoryId":5,"description":"설명",
                 "optionNames":["색상"],"options":[{"values":["가가가가가가가가가가가가가가가"],"stock":1}]}""",
                """
                {"name":"티셔츠","price":20000,"categoryId":5,"description":"설명",
                 "optionNames":["색상"],"options":[{"values":["흰색"],"additionalPrice":-1,"stock":1}]}""");
        for (String json : invalids) {
            create(MemberRole.USER, jsonPart(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        }

        verify(productFacade, never()).createProduct(any(), any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("옵션 추가: 경로의 상품 ID와 조합 스펙을 넘기고 만든 옵션 ID를 돌려준다")
    void addOption() throws Exception {
        given(productFacade.addOption(2L, 11L, new ProductOptionSpec(List.of("검정", "M"), 1_000, 4))).willReturn(31L);

        sendAsSeller(post("/api/v1/seller/products/11/options"), """
                {"values":["검정","M"],"additionalPrice":1000,"stock":4}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.optionId").value(31));
    }

    @Test
    @DisplayName("옵션 수정: 추가금을 넘긴다. 추가금이 없거나 음수면 400 VALIDATION_FAILED")
    void modifyOption() throws Exception {
        sendAsSeller(patch("/api/v1/seller/products/11/options/21"), """
                {"additionalPrice":2500}""")
                .andExpect(status().isOk());
        verify(productFacade).modifyOption(2L, 11L, 21L, 2_500L);

        for (String json : List.of("{}", """
                {"additionalPrice":-1}""")) {
            sendAsSeller(patch("/api/v1/seller/products/11/options/21"), json)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("할인 등록: 할인가와 기간을 넘기고 만든 할인 ID를 돌려준다")
    void createDiscount() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 12, 1, 0, 0);
        given(productFacade.createDiscount(2L, 11L, 15_000L, start, start.plusDays(7))).willReturn(41L);

        sendAsSeller(post("/api/v1/seller/products/11/discounts"), """
                {"salePrice":15000,"startsAt":"2026-12-01T00:00:00","endsAt":"2026-12-08T00:00:00"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.discountId").value(41));
    }

    @Test
    @DisplayName("할인 등록은 세 값 모두 필수, 수정은 하나 이상. 할인가가 0 이하면 400 VALIDATION_FAILED")
    void discountValidation() throws Exception {
        for (String json : List.of(
                """
                {"startsAt":"2026-12-01T00:00:00","endsAt":"2026-12-08T00:00:00"}""",
                """
                {"salePrice":0,"startsAt":"2026-12-01T00:00:00","endsAt":"2026-12-08T00:00:00"}""",
                """
                {"salePrice":15000,"endsAt":"2026-12-08T00:00:00"}""",
                """
                {"salePrice":15000,"startsAt":"2026-12-01T00:00:00"}""")) {
            sendAsSeller(post("/api/v1/seller/products/11/discounts"), json)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        }

        // 수정은 바꿀 값만 보내지만, 하나도 없거나 할인가가 0 이하면 400
        for (String json : List.of("{}", "{\"salePrice\":0}")) {
            sendAsSeller(patch("/api/v1/seller/products/11/discounts/41"), json)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("할인 수정·삭제·종료: 경로의 상품·할인 ID로 처리한다")
    void modifyDeleteEndDiscount() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 12, 1, 0, 0);
        sendAsSeller(patch("/api/v1/seller/products/11/discounts/41"), """
                {"salePrice":14000,"startsAt":"2026-12-01T00:00:00","endsAt":"2026-12-08T00:00:00"}""")
                .andExpect(status().isOk());
        verify(productFacade).modifyDiscount(2L, 11L, 41L, 14_000L, start, start.plusDays(7));

        // 끝 시각만 보내면 나머지는 null로 넘긴다
        sendAsSeller(patch("/api/v1/seller/products/11/discounts/41"), "{\"endsAt\":\"2026-12-15T00:00:00\"}")
                .andExpect(status().isOk());
        verify(productFacade).modifyDiscount(2L, 11L, 41L, null, null, start.plusDays(14));

        String token = jwtTokenProvider.createAccessToken(2L, MemberRole.USER);
        mockMvc.perform(delete("/api/v1/seller/products/11/discounts/41").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        verify(productFacade).deleteDiscount(2L, 11L, 41L);

        mockMvc.perform(post("/api/v1/seller/products/11/discounts/41/end").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        verify(productFacade).endDiscount(2L, 11L, 41L);
    }

    @Test
    @DisplayName("옵션 삭제: 경로의 상품·옵션 ID로 지운다")
    void deleteOption() throws Exception {
        String token = jwtTokenProvider.createAccessToken(2L, MemberRole.USER);
        mockMvc.perform(delete("/api/v1/seller/products/11/options/21").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(productFacade).deleteOption(2L, 11L, 21L);
    }

    private ResultActions sendAsSeller(MockHttpServletRequestBuilder request, String json) throws Exception {
        String token = jwtTokenProvider.createAccessToken(2L, MemberRole.USER);
        return mockMvc.perform(request
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions adjust(MemberRole role, String json) throws Exception {
        String token = jwtTokenProvider.createAccessToken(2L, role);
        return mockMvc.perform(post("/api/v1/seller/products/11/options/21/stock-adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .header("Authorization", "Bearer " + token));
    }

    private MockMultipartFile jsonPart(String json) {
        return new MockMultipartFile("request", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8));
    }

    /** 대표 이미지를 붙여 등록한다 */
    private ResultActions create(MemberRole role, MockMultipartFile requestPart) throws Exception {
        return create(role, requestPart, THUMBNAIL);
    }

    private ResultActions create(MemberRole role, MockMultipartFile... parts) throws Exception {
        String token = jwtTokenProvider.createAccessToken(2L, role);
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/seller/products");
        for (MockMultipartFile part : parts) request.file(part);
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private ResultActions modify(MemberRole role, MockMultipartFile requestPart) throws Exception {
        String token = jwtTokenProvider.createAccessToken(2L, role);
        return mockMvc.perform(multipart(HttpMethod.PATCH, "/api/v1/seller/products/11")
                .file(requestPart)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions getAsSeller(String url) throws Exception {
        String token = jwtTokenProvider.createAccessToken(2L, MemberRole.USER);
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + token));
    }
}

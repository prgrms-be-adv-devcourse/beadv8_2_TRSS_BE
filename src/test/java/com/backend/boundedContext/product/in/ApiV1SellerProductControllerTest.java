package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.OptionStockDto;
import com.backend.boundedContext.product.app.dto.SellerProductFormDto;
import com.backend.boundedContext.product.app.dto.SellerProductListDto;
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

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1SellerProductController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1SellerProductControllerTest {

    private static final String VALID_REQUEST = """
            {"name":"수분 크림","price":25000,"categoryId":5,"description":"촉촉한 크림","stock":30}""";
    private static final MockMultipartFile THUMBNAIL =
            new MockMultipartFile("thumbnail", "t.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1});

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ProductFacade productFacade;

    @Test
    @DisplayName("상품 등록: 토큰의 회원 ID로 등록하고 상품 ID를 돌려준다")
    void createProduct_success() throws Exception {
        given(productFacade.createProduct(2L, 5L, "수분 크림", "촉촉한 크림", 25_000, 30)).willReturn(11L);

        create(MemberRole.SELLER, jsonPart(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.productId").value(11));
    }

    @Test
    @DisplayName("상품 등록: 재고가 0이면 400 VALIDATION_FAILED")
    void createProduct_zeroStock() throws Exception {
        create(MemberRole.SELLER, jsonPart(VALID_REQUEST.replace("\"stock\":30", "\"stock\":0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("stock"));

        verify(productFacade, never()).createProduct(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("상품 등록: SELLER가 아니면 403 FORBIDDEN")
    void createProduct_notSellerRole() throws Exception {
        create(MemberRole.USER, jsonPart(VALID_REQUEST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
    }

    @Test
    @DisplayName("상품 등록: request 파트가 application/json이 아니면 400 INVALID_REQUEST")
    void createProduct_requestPartNotJson() throws Exception {
        MockMultipartFile plainPart = new MockMultipartFile(
                "request", "", MediaType.TEXT_PLAIN_VALUE, VALID_REQUEST.getBytes(StandardCharsets.UTF_8));

        create(MemberRole.SELLER, plainPart)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-INVALID_REQUEST"));
    }

    @Test
    @DisplayName("상품 등록: 대표 이미지가 없으면 400 THUMBNAIL_REQUIRED")
    void createProduct_noThumbnail() throws Exception {
        create(MemberRole.SELLER, new MockMultipartFile[]{jsonPart(VALID_REQUEST)})
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-THUMBNAIL_REQUIRED"));

        MockMultipartFile emptyThumbnail = new MockMultipartFile("thumbnail", "a.png", MediaType.IMAGE_PNG_VALUE, new byte[0]);
        create(MemberRole.SELLER, jsonPart(VALID_REQUEST), emptyThumbnail)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-THUMBNAIL_REQUIRED"));

        verify(productFacade, never()).createProduct(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("상품 등록: 상세 이미지를 함께 보내도 등록된다")
    void createProduct_withDetailImages() throws Exception {
        given(productFacade.createProduct(2L, 5L, "수분 크림", "촉촉한 크림", 25_000, 30)).willReturn(11L);
        MockMultipartFile detail = new MockMultipartFile("detailImages", "d.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1});

        create(MemberRole.SELLER, jsonPart(VALID_REQUEST), THUMBNAIL, detail, detail)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(11));
    }

    @Test
    @DisplayName("본인 상품 목록: 상태 필터·검색어·페이지를 넘기고, 목록과 탭별 개수를 돌려준다")
    void getSellerProducts() throws Exception {
        given(productFacade.getSellerProducts(eq(2L), eq(ProductDisplayStatus.SOLD_OUT), eq("크림"), any(Pageable.class)))
                .willReturn(new SellerProductListDto(
                        new PageResponse<>(List.of(), 0, 20, 0, 0), new SellerProductListDto.Counts(4, 2, 1, 1)));

        getAsSeller("/api/v1/seller/products?status=SOLD_OUT&keyword=크림")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products.page").value(0))
                .andExpect(jsonPath("$.data.counts.all").value(4))
                .andExpect(jsonPath("$.data.counts.soldOut").value(1));
    }

    @Test
    @DisplayName("상품 수정 폼 조회: 이미지 자리(thumbnail, detailImages)를 포함해 돌려준다")
    void getSellerProductForm() throws Exception {
        given(productFacade.getSellerProductForm(2L, 11L)).willReturn(
                new SellerProductFormDto(11L, "수분 크림", 25_000, "설명", 1L, 5L, null, List.of()));

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
        modify(MemberRole.SELLER, jsonPart("""
                {"name":"진정 크림","price":30000}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"));

        verify(productFacade).modifyProduct(2L, 11L, "진정 크림", 30_000L, null, null);
    }

    @Test
    @DisplayName("상품 수정: 상품명이 공백이거나 가격이 0 이하면 400 VALIDATION_FAILED")
    void modifyProduct_invalid() throws Exception {
        modify(MemberRole.SELLER, jsonPart("""
                {"name":"  "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        modify(MemberRole.SELLER, jsonPart("""
                {"price":0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("price"));

        verify(productFacade, never()).modifyProduct(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("재고 조정: 토큰의 회원 ID와 경로의 상품·옵션 ID로 조정하고 조정 후 재고를 돌려준다")
    void adjustStock_success() throws Exception {
        given(productFacade.adjustStock(2L, 11L, 21L, -3)).willReturn(new OptionStockDto(21L, 7));

        adjust(MemberRole.SELLER, """
                {"changeQty":-3}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.optionId").value(21))
                .andExpect(jsonPath("$.data.stock").value(7));
    }

    @Test
    @DisplayName("재고 조정: 증감량이 0이거나 없으면 400 VALIDATION_FAILED")
    void adjustStock_invalidChangeQty() throws Exception {
        adjust(MemberRole.SELLER, """
                {"changeQty":0}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        adjust(MemberRole.SELLER, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("changeQty"));

        verify(productFacade, never()).adjustStock(anyLong(), anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("재고 조정: SELLER가 아니면 403 FORBIDDEN")
    void adjustStock_notSellerRole() throws Exception {
        adjust(MemberRole.USER, """
                {"changeQty":1}""")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-FORBIDDEN"));
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
        String token = jwtTokenProvider.createAccessToken(2L, MemberRole.SELLER);
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + token));
    }
}

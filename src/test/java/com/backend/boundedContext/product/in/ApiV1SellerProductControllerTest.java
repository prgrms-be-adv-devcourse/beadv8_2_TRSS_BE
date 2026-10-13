package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiV1SellerProductController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ApiV1SellerProductControllerTest {

    private static final String VALID_REQUEST = """
            {"name":"수분 크림","price":25000,"categoryId":5,"description":"촉촉한 크림","stock":30}""";

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

    private MockMultipartFile jsonPart(String json) {
        return new MockMultipartFile("request", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions create(MemberRole role, MockMultipartFile requestPart) throws Exception {
        String token = jwtTokenProvider.createAccessToken(2L, role);
        return mockMvc.perform(multipart("/api/v1/seller/products")
                .file(requestPart)
                .header("Authorization", "Bearer " + token));
    }
}

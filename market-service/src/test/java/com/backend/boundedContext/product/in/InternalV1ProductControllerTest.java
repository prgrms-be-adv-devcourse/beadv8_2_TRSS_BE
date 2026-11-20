package com.backend.boundedContext.product.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.global.security.InternalApiKeyFilter;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.ProductUnsaleableReason;

/**
 * 상품 내부 API (delivery 물류 목록·상세가 부른다). X-Internal-Key로만 부를 수 있다
 * 키 값은 src/test/resources/application.properties의 custom.internal.apiKey
 */
@WebMvcTest(InternalV1ProductController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class InternalV1ProductControllerTest {

    private static final String INTERNAL_KEY = "test-internal-api-key";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductFacade productFacade;

    @Test
    @DisplayName("옵션 정보를 data에 담아 돌려준다. 삭제된 상품의 옵션도 대표 이미지가 함께 온다")
    void getOptions() throws Exception {
        given(productFacade.getOptions(List.of(10L, 11L))).willReturn(List.of(
                option(10L, 1L, true, null, "https://img/1.jpg"),
                option(11L, 2L, false, ProductUnsaleableReason.PRODUCT_DELETED, "https://img/2.jpg")));

        mockMvc.perform(get("/internal/v1/products/options").header(InternalApiKeyFilter.HEADER, INTERNAL_KEY).param("optionIds", "10,11,10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].optionId").value(10))
                .andExpect(jsonPath("$.data[0].productId").value(1))
                .andExpect(jsonPath("$.data[0].thumbnailUrl").value("https://img/1.jpg"))
                .andExpect(jsonPath("$.data[1].saleable").value(false))
                .andExpect(jsonPath("$.data[1].thumbnailUrl").value("https://img/2.jpg"));
    }

    @Test
    @DisplayName("optionIds가 없으면 상품을 조회하지 않고 빈 목록을 돌려준다")
    void emptyIds() throws Exception {
        mockMvc.perform(get("/internal/v1/products/options").header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        then(productFacade).should(never()).getOptions(any());
    }

    @Test
    @DisplayName("optionIds가 200개를 넘으면 400-VALIDATION_FAILED")
    void tooManyIds() throws Exception {
        String ids = LongStream.rangeClosed(1, InternalV1ProductController.MAX_OPTION_IDS + 1)
                .mapToObj(String::valueOf).collect(Collectors.joining(","));

        mockMvc.perform(get("/internal/v1/products/options").header(InternalApiKeyFilter.HEADER, INTERNAL_KEY).param("optionIds", ids))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        then(productFacade).should(never()).getOptions(any());
    }

    @Test
    @DisplayName("optionIds가 정확히 200개면 조회한다")
    void maxIds() throws Exception {
        String ids = LongStream.rangeClosed(1, InternalV1ProductController.MAX_OPTION_IDS)
                .mapToObj(String::valueOf).collect(Collectors.joining(","));
        given(productFacade.getOptions(any())).willReturn(List.of());

        mockMvc.perform(get("/internal/v1/products/options").header(InternalApiKeyFilter.HEADER, INTERNAL_KEY).param("optionIds", ids))
                .andExpect(status().isOk());
    }

    private static OptionSnapshotDto option(Long optionId, Long productId, boolean saleable,
                                            ProductUnsaleableReason reason, String thumbnailUrl) {
        return new OptionSnapshotDto(optionId, productId, 3L, "상품", "기본", 10_000, 10_000, null, 5,
                saleable, reason, thumbnailUrl);
    }

    @Test
    @DisplayName("내부 키가 없으면 401-INTERNAL_API_KEY_INVALID")
    void noInternalKey() throws Exception {
        mockMvc.perform(get("/internal/v1/products/options").param("optionIds", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("401-INTERNAL_API_KEY_INVALID"));

        then(productFacade).should(never()).getOptions(any());
    }
}

package com.backend.boundedContext.payment.in;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.payment.out.hecto.HectoCrypto;
import com.backend.global.security.JwtAccessDeniedHandler;
import com.backend.global.security.JwtAuthenticationEntryPoint;
import com.backend.global.security.JwtTokenProvider;
import com.backend.global.security.SecurityConfig;
import com.backend.global.security.SecurityErrorResponseWriter;

@WebMvcTest(ApiV1HectoTopupController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
@TestPropertySource(properties = "custom.hecto.frontResultUrl=https://palette.example/wallet/topup-result")
class ApiV1HectoTopupControllerTest {

    private static final String ORDER_ID = "TOPUP-123e4567-e89b-12d3-a456-426614174000";
    private static final String TRD_NO = "STFP_PGCAtest0261114220111M1522521";
    private static final String RESULT_URL = "https://palette.example/wallet/topup-result";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HectoCrypto hectoCrypto;

    @Test
    @DisplayName("인증 완료(0061)면 토큰 없이 받아 프론트 결과 화면으로 승인 값(헥토 거래번호·주문번호·금액)을 넘긴다(303)")
    void next_authenticated() throws Exception {
        given(hectoCrypto.decryptAmount("enc")).willReturn(20_000L);

        mockMvc.perform(post("/api/v1/topups/hecto/next")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("outStatCd", "0061")
                        .param("outRsltCd", "0000")
                        .param("mchtTrdNo", ORDER_ID)
                        .param("trdNo", TRD_NO)
                        .param("trdAmt", "enc"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", RESULT_URL + "?paymentKey=" + TRD_NO + "&orderId=" + ORDER_ID + "&amount=20000"));
    }

    @Test
    @DisplayName("인증이 완료되지 않았으면 헥토 결과 코드·메시지를 실패 값으로 넘긴다")
    void next_notAuthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/topups/hecto/next")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("outStatCd", "0031")
                        .param("outRsltCd", "ST01")
                        .param("outRsltMsg", "카드 인증 실패")
                        .param("mchtTrdNo", ORDER_ID))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", RESULT_URL + "?orderId=" + ORDER_ID + "&code=ST01&message=%EC%B9%B4%EB%93%9C%20%EC%9D%B8%EC%A6%9D%20%EC%8B%A4%ED%8C%A8"));
    }

    @Test
    @DisplayName("결과 금액을 풀지 못하면 HECTO_INVALID_RESULT 실패 값으로 넘긴다")
    void next_invalidAmount() throws Exception {
        given(hectoCrypto.decryptAmount("broken")).willThrow(new IllegalStateException("복호화 실패"));

        mockMvc.perform(post("/api/v1/topups/hecto/next")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("outStatCd", "0061")
                        .param("mchtTrdNo", ORDER_ID)
                        .param("trdNo", TRD_NO)
                        .param("trdAmt", "broken"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", containsString("code=HECTO_INVALID_RESULT")));
    }

    @Test
    @DisplayName("결제창이 보낸 폼 POST처럼 헥토 출처(Origin)가 붙어 와도 CORS로 막지 않는다")
    void next_fromHectoOrigin() throws Exception {
        given(hectoCrypto.decryptAmount("enc")).willReturn(20_000L);

        mockMvc.perform(post("/api/v1/topups/hecto/next")
                        .header("Origin", "https://tbnpg.settlebank.co.kr")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("outStatCd", "0061")
                        .param("mchtTrdNo", ORDER_ID)
                        .param("trdNo", TRD_NO)
                        .param("trdAmt", "enc"))
                .andExpect(status().isSeeOther());
    }

    @Test
    @DisplayName("결제창 취소도 출처가 null(Origin: null)로 와도 막지 않는다")
    void cancel_nullOrigin() throws Exception {
        mockMvc.perform(post("/api/v1/topups/hecto/canc")
                        .header("Origin", "null")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("mchtTrdNo", ORDER_ID))
                .andExpect(status().isSeeOther());
    }

    @Test
    @DisplayName("결제창을 닫으면 USER_CANCEL 실패 값으로 넘긴다")
    void cancel() throws Exception {
        mockMvc.perform(post("/api/v1/topups/hecto/canc")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("mchtTrdNo", ORDER_ID))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", startsWith(RESULT_URL + "?orderId=" + ORDER_ID + "&code=USER_CANCEL&message=")));
    }

    @Test
    @DisplayName("결과 알림은 토큰 없이 받아 OK로 응답한다")
    void noti() throws Exception {
        mockMvc.perform(post("/api/v1/topups/hecto/noti")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("outStatCd", "0021")
                        .param("mchtTrdNo", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
    }
}

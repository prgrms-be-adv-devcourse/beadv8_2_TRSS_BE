package com.backend.boundedContext.delivery.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.delivery.FakeDeliveryTracker;
import com.backend.boundedContext.delivery.app.DeliveryFacade;

/**
 * [QA] 웹훅 콜백의 비정상 경로에서도 콜백 토큰이 로그에 남지 않는지 확인한다.
 * 토큰이 맞는 요청이 Facade에서 예외(DB 장애·잠금 대기 초과)를 만나면 GlobalExceptionHandler가
 * "Unhandled POST {URI}"·"PessimisticLockingFailure POST {URI}"로 요청 경로(토큰 포함)를 남긴다.
 * Grafana 알림 규칙은 "Unhandled" 메시지의 경로를 http_path 라벨로 뽑아 Slack에 보낸다(rules.yaml).
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
@AutoConfigureMockMvc
@Import(FakeDeliveryTracker.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class DeliveryTrackerWebhookLogSafetyTest {

    private static final String TOKEN = "test-callback-token-0123456789abcdef";
    private static final String URL = "/webhooks/delivery-tracker/";
    private static final String BODY = """
            {"carrierId": "kr.cjlogistics", "trackingNumber": "123456789012"}
            """;

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DeliveryFacade deliveryFacade;

    @Test
    @DisplayName("[QA] 토큰이 맞는 콜백 처리 중 DB 장애 → 로그(ERROR 포함)에 콜백 토큰이 남지 않는다")
    void dbFailure_tokenNotLogged(CapturedOutput output) throws Exception {
        given(deliveryFacade.requestTrackingCheck(anyString(), anyString()))
                .willThrow(new DataAccessResourceFailureException("connection refused"));

        mockMvc.perform(post(URL + TOKEN).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is5xxServerError());

        assertThat(appLogLinesWithToken(output)).isEmpty();
    }

    @Test
    @DisplayName("[QA] 토큰이 맞는 콜백 처리 중 잠금 대기 초과 → 로그에 콜백 토큰이 남지 않는다")
    void lockFailure_tokenNotLogged(CapturedOutput output) throws Exception {
        given(deliveryFacade.requestTrackingCheck(anyString(), anyString()))
                .willThrow(new PessimisticLockingFailureException("lock timeout"));

        mockMvc.perform(post(URL + TOKEN).contentType(MediaType.APPLICATION_JSON).content(BODY));

        assertThat(appLogLinesWithToken(output)).isEmpty();
    }

    @Test
    @DisplayName("[QA] 토큰이 맞고 본문 JSON이 깨짐 → 400, 로그에 토큰 없음, Facade 호출 없음")
    void malformedJson_tokenNotLogged(CapturedOutput output) throws Exception {
        mockMvc.perform(post(URL + TOKEN).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());

        assertThat(appLogLinesWithToken(output)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(deliveryFacade);
    }

    @Test
    @DisplayName("[QA] 토큰이 맞고 Content-Type이 text/plain·GET → 4xx, 로그에 토큰 없음")
    void wrongMediaTypeOrMethod_tokenNotLogged(CapturedOutput output) throws Exception {
        mockMvc.perform(post(URL + TOKEN).contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get(URL + TOKEN))
                .andExpect(status().is4xxClientError());

        assertThat(appLogLinesWithToken(output)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(deliveryFacade);
    }

    @Test
    @DisplayName("[QA] 토큰 앞부분만 맞음·뒤에 글자 더 붙음·대소문자 다름·빈 경로 → 404, Facade 호출 없음")
    void partialTokens_notFound() throws Exception {
        for (String token : new String[]{TOKEN.substring(0, TOKEN.length() - 1), TOKEN + "x", TOKEN.toUpperCase()}) {
            mockMvc.perform(post(URL + token).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is4xxClientError());

        org.mockito.Mockito.verifyNoInteractions(deliveryFacade);
    }

    /**
     * 애플리케이션 로그 줄(" --- [delivery-service] " 형식) 중 토큰이 든 줄.
     * MockMvc가 실패한 테스트에 찍는 요청 덤프(Request URI = ...)는 테스트 도구 출력이라 뺀다.
     */
    private static java.util.List<String> appLogLinesWithToken(CapturedOutput output) {
        return output.getAll().lines()
                .filter(line -> line.contains(" --- [delivery-service] "))
                .filter(line -> line.contains(TOKEN))
                .toList();
    }
}

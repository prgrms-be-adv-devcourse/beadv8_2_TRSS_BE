package com.backend.delivery.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

/**
 * 상품 내부 API 클라이언트. 실제 market 대신 MockRestServiceServer로 응답을 흉내 낸다 (market DeliveryApiClientTest 방식)
 */
class MarketProductApiClientTest {

    private static final String BASE_URL = "http://market:8082";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";
    private static final String PATH = BASE_URL + "/internal/v1/products/options";

    private MockRestServiceServer server;
    private MarketProductApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MarketProductApiClient(builder, BASE_URL, INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("옵션 ID별 대표 이미지를 돌려준다. 이미지가 없는 옵션은 빠지고, 요청에는 내부 API 키 헤더가 붙는다")
    void success() {
        server.expect(requestTo(Matchers.startsWith(PATH)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andExpect(queryParam("optionIds", "10", "11", "12"))
                .andRespond(withSuccess(rsData("""
                        [%s, %s]
                        """.formatted(option(10, "\"https://img/10.jpg\""), option(11, "null"))), MediaType.APPLICATION_JSON));

        Map<Long, String> result = client.getThumbnailUrls(List.of(10L, 11L, 12L, 10L));

        server.verify();
        assertThat(result).containsExactly(Map.entry(10L, "https://img/10.jpg"));
    }

    @Test
    @DisplayName("옵션이 200개를 넘으면 200개씩 나눠 부른다")
    void chunked() {
        server.expect(requestTo(Matchers.startsWith(PATH)))
                .andExpect(queryParam("optionIds", LongStream.rangeClosed(1, 200).mapToObj(String::valueOf).toArray(String[]::new)))
                .andRespond(withSuccess(rsData("[" + option(1, "\"https://img/1.jpg\"") + "]"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.startsWith(PATH)))
                .andExpect(queryParam("optionIds", "201"))
                .andRespond(withSuccess(rsData("[" + option(201, "\"https://img/201.jpg\"") + "]"), MediaType.APPLICATION_JSON));

        Map<Long, String> result = client.getThumbnailUrls(LongStream.rangeClosed(1, 201).boxed().toList());

        server.verify();
        assertThat(result).containsOnly(Map.entry(1L, "https://img/1.jpg"), Map.entry(201L, "https://img/201.jpg"));
    }

    @Test
    @DisplayName("market이 5xx로 응답하면 502 EXTERNAL_SERVICE_ERROR")
    void serverError() {
        server.expect(requestTo(Matchers.startsWith(PATH)))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.getThumbnailUrls(List.of(10L)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo("502-" + GlobalErrorCode.EXTERNAL_SERVICE_ERROR.name());
    }

    @Test
    @DisplayName("내부 키가 틀려 401이면 502 EXTERNAL_SERVICE_ERROR")
    void unauthorized() {
        server.expect(requestTo(Matchers.startsWith(PATH)))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"resultCode\":\"401-INTERNAL_API_KEY_INVALID\",\"msg\":\"내부 API 키가 없거나 올바르지 않습니다.\",\"data\":null}"));

        assertThatThrownBy(() -> client.getThumbnailUrls(List.of(10L)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("응답 시간 초과면 502 EXTERNAL_SERVICE_ERROR")
    void timeout() {
        server.expect(requestTo(Matchers.startsWith(PATH)))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.getThumbnailUrls(List.of(10L)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo("502-" + GlobalErrorCode.EXTERNAL_SERVICE_ERROR.name());
    }

    @Test
    @DisplayName("옵션 ID가 없으면 market을 부르지 않는다")
    void empty() {
        assertThat(client.getThumbnailUrls(List.of())).isEmpty();

        server.verify();
    }

    private static String rsData(String data) {
        return "{\"resultCode\":\"200-1\",\"msg\":\"옵션 정보를 조회했습니다.\",\"data\":" + data + "}";
    }

    private static String option(long optionId, String thumbnailUrl) {
        return """
                {"optionId": %d, "productId": %d, "sellerId": 3, "productName": "상품", "optionName": "기본",
                 "price": 10000, "listPrice": 10000, "discountRate": null, "stock": 5, "saleable": true,
                 "unsaleableReason": null, "thumbnailUrl": %s}
                """.formatted(optionId, optionId + 1000, thumbnailUrl);
    }
}

package com.backend.boundedContext.payment.out.toss;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 토스페이먼츠 결제 API 클라이언트. 호출은 DB 트랜잭션 밖에서 진행
 * - 토스가 오류로 응답하면 TossPaymentException (결과가 확정된 실패)
 * - 응답을 받지 못하면 TossTimeoutException (결과를 알 수 없음)
 *
 * 구조
 * - RestClient: 요청 조립, 객체 ↔ JSON 변환, 4xx·5xx 응답을 예외로 바꾸는 상위 계층
 * - SimpleClientHttpRequestFactory: 실제 HTTP 연결을 열고 데이터를 주고받는 하위 계층 (타임아웃은 여기서 설정)
 */
@Component
public class TossPaymentsClient {

    // baseUrl·인증 헤더·타임아웃이 모두 설정된 RestClient. 앱 시작 시 한 번 만들어 모든 호출에서 재사용
    private final RestClient restClient;

    /**
     * Spring이 빈을 만들 때 사용하는 생성자
     * 1. application.yaml의 custom.toss 값을 읽음
     * 2. 접속·응답 대기 제한 시간을 넣은 연결 부품(requestFactory)을 RestClient.builder에 끼움
     * 3. 나머지 설정(baseUrl, 인증 헤더)은 아래 생성자에 맡김
     */
    @Autowired
    public TossPaymentsClient(
            @Value("${custom.toss.baseUrl}") String baseUrl,
            @Value("${custom.toss.secretKey}") String secretKey,
            @Value("${custom.toss.connectTimeoutSeconds}") long connectTimeoutSeconds,
            @Value("${custom.toss.readTimeoutSeconds}") long readTimeoutSeconds
    ) {
        this(RestClient.builder().requestFactory(requestFactory(connectTimeoutSeconds, readTimeoutSeconds)), baseUrl, secretKey);
    }

    /**
     * RestClient 완성. 테스트에서 MockRestServiceServer를 연결한 builder를 넘길 수 있게 열어 둠
     * (MockRestServiceServer가 연결 부품을 가짜로 바꿔 끼워, 실제 네트워크 대신 정해 둔 응답을 돌려줌)
     * - baseUrl: 모든 요청 URI 앞에 붙음 (예: /v1/payments/confirm → https://api.tosspayments.com/v1/payments/confirm)
     * - defaultHeader: 모든 요청에 인증 헤더를 자동으로 넣음
     */
    TossPaymentsClient(RestClient.Builder builder, String baseUrl, String secretKey) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(secretKey))
                .build();
    }

    /**
     * 결제 승인. 결제창 인증을 마친 결제를 최종 승인하며, 성공하면 실제로 카드 결제가 이뤄짐
     */
    public TossPaymentResponse confirm(String paymentKey, String orderId, long amount) {
        // 요청 코드를 람다로 넘기고, 실제 실행과 예외 변환은 call()에서 진행
        return call(() -> restClient.post()
                .uri("/v1/payments/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ConfirmRequest(paymentKey, orderId, amount))   // 객체를 JSON 본문으로 변환
                .retrieve()                                              // 여기서 실제로 요청 전송
                .body(TossPaymentResponse.class));                       // 응답 JSON을 객체로 변환
    }

    /**
     * 주문번호로 결제 조회 (응답 시간 초과로 PROCESSING에 남은 충전 건의 최종 결과 확인용)
     */
    public TossPaymentResponse getPaymentByOrderId(String orderId) {
        return call(() -> restClient.get()
                .uri("/v1/payments/orders/{orderId}", orderId)       // {orderId} 자리에 주문번호가 들어감
                .retrieve()
                .body(TossPaymentResponse.class));
    }

    /**
     * 요청 실행과 예외 변환을 한곳에서 처리 (confirm·getPaymentByOrderId가 같은 try-catch를 반복하지 않게 함)
     * request는 아직 실행되지 않은 요청 코드이고, request.get()을 부를 때 실제로 실행됨
     * Spring HTTP 예외를 우리 예외로 바꿔, app 계층이 HTTP 라이브러리를 몰라도 되게 함
     */
    private TossPaymentResponse call(Supplier<TossPaymentResponse> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            // 토스가 4xx·5xx로 응답함 → 결과가 확정된 실패. 본문의 오류 코드·메시지를 꺼내 담음
            TossErrorResponse error = parseError(e);
            throw new TossPaymentException(e.getStatusCode().value(), error.code(), error.message(), e);
        } catch (ResourceAccessException e) {
            // 응답을 받지 못함 (접속 실패·응답 대기 시간 초과·연결 끊김) → 토스에서 처리됐는지 알 수 없음
            throw new TossTimeoutException(e);
        }
    }

    /**
     * 토스 오류 응답 본문({"code": ..., "message": ...})을 TossErrorResponse로 변환
     * 오류 처리 중 다시 예외가 나서 원래 원인이 묻히지 않게 하려는 것
     * 본문이 이 형식이 아니면(예: 장애로 HTML 페이지가 옴) 기본값을 돌려줌
     */
    private static TossErrorResponse parseError(RestClientResponseException e) {
        try {
            TossErrorResponse error = e.getResponseBodyAs(TossErrorResponse.class);
            if (error != null) return error;
        } catch (RuntimeException ignored) {
            // 본문이 토스 오류 형식이 아니면 아래 기본값 사용
        }
        return new TossErrorResponse("UNKNOWN", "토스 오류 응답을 해석할 수 없습니다.");
    }

    /**
     * 토스 인증 헤더 값. 시크릿 키 뒤에 ':'를 붙여 Base64 인코딩 (비밀번호 없이 사용자명만 쓰는 Basic 인증)
     * 예: "test_sk_abc" → "test_sk_abc:" → "Basic dGVzdF9za19hYmM6"
     */
    private static String basicAuth(String secretKey) {
        String encoded = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    /**
     * 실제 HTTP 연결을 담당하는 부품
     * - connectTimeout: 토스 서버에 접속하는 데 걸리는 제한 시간. 넘으면 요청이 도착하지 못했을 가능성이 큼
     * - readTimeout: 요청 후 응답 데이터를 기다리는 제한 시간. 넘으면 토스가 이미 승인했을 수 있음
     */
    private static SimpleClientHttpRequestFactory requestFactory(long connectTimeoutSeconds, long readTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        return factory;
    }

    // 승인 요청 본문. JSON으로 변환되어 전송됨
    private record ConfirmRequest(
            String paymentKey,
            String orderId,
            long amount
    ) {}
}

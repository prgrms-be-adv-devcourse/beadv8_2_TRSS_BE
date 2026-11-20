package com.backend.market.client;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.ErrorCode;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.rsData.RsData;
import com.backend.global.security.InternalApiKeyFilter;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.dto.WalletSummaryDto;
import com.backend.shared.payment.out.PaymentApi;

/**
 * {@link PaymentApi}를 결제 내부 API(/internal/v1/payments)로 호출하는 구현
 * - pay: POST /internal/v1/payments/pay
 * - cancelPayment: POST /internal/v1/payments/{idempotencyKey}/cancel
 * - getPaymentAttempts: GET /internal/v1/payments/orders/{orderId}/attempts
 *
 * 실패는 두 가지로 나눈다
 * - payment가 4xx RsData로 응답: 같은 resultCode·메시지의 DomainException (payment가 판단한 결과. 예: 404-WALLET_NOT_FOUND)
 * - payment가 401·403으로 응답(내부 키가 없거나 틀림 = 설정 오류): 503-INTERNAL_API_AUTH_FAILED + ERROR 로그.
 *   보안 필터에서 막혀 결제는 일어나지 않았다
 * - 접속 실패·응답 대기 시간 초과·5xx·응답을 읽을 수 없음: 502-EXTERNAL_SERVICE_ERROR (결과 불명. 결제는 됐을 수도 있다)
 * 결제 결과 반영 쪽(OrderPaymentSupport)은 이 구분으로 PAYMENT_FAILED와 PAYING 유지를 정한다
 *
 * 응답 대기 시간은 요청 종류별로 나눈다
 * - 결제 요청(pay·cancelPayment): readTimeoutMillis. 지갑 잠금을 기다릴 수 있어 길게 둔다
 * - 조회(getPaymentAttempts): queryReadTimeoutMillis. 주문 상세 요청 안에서 부르므로 짧게 둔다
 */
@Slf4j
@Component
public class PaymentApiClient implements PaymentApi {

    private final RestClient restClient;        // 결제 요청(pay·cancelPayment)
    private final RestClient queryRestClient;   // 조회(getPaymentAttempts). 응답 대기 시간이 짧다

    // restClientBuilder: 스프링이 넣어 주는 builder(호출 기록 http_client_requests·추적 traceId가 붙음). RestClient.builder()로 직접 만들면 빠진다
    // 결제 요청·조회는 응답 대기 시간이 달라 복사본(clone)을 하나 더 만든다(인자는 왼쪽부터 계산되어 원본을 바꾸기 전에 복사된다)
    @Autowired
    public PaymentApiClient(
            RestClient.Builder restClientBuilder,
            @Value("${custom.payment.baseUrl}") String baseUrl,
            @Value("${custom.internal.apiKey}") String internalApiKey,
            @Value("${custom.payment.connectTimeoutMillis}") long connectTimeoutMillis,
            @Value("${custom.payment.readTimeoutMillis}") long readTimeoutMillis,
            @Value("${custom.payment.queryReadTimeoutMillis}") long queryReadTimeoutMillis
    ) {
        this(restClientBuilder.clone().requestFactory(requestFactory(connectTimeoutMillis, readTimeoutMillis)),
                restClientBuilder.requestFactory(requestFactory(connectTimeoutMillis, queryReadTimeoutMillis)),
                baseUrl, internalApiKey);
    }

    /**
     * 테스트에서 MockRestServiceServer를 연결한 builder를 넘길 수 있게 열어 둔다. 결제 요청·조회가 같은 builder를 쓴다
     */
    PaymentApiClient(RestClient.Builder builder, String baseUrl, String internalApiKey) {
        this(builder, builder, baseUrl, internalApiKey);
    }

    /**
     * 모든 요청에 내부 API 키 헤더(X-Internal-Key)를 넣는다. 없으면 payment가 401-INTERNAL_API_KEY_INVALID로 막는다
     */
    private PaymentApiClient(RestClient.Builder commandBuilder, RestClient.Builder queryBuilder,
                             String baseUrl, String internalApiKey) {
        this.restClient = build(commandBuilder, baseUrl, internalApiKey);
        this.queryRestClient = build(queryBuilder, baseUrl, internalApiKey);
    }

    private static RestClient build(RestClient.Builder builder, String baseUrl, String internalApiKey) {
        return builder
                .baseUrl(baseUrl)
                .defaultHeader(InternalApiKeyFilter.HEADER, internalApiKey)
                .build();
    }

    // ── market이 쓰는 메서드 ──

    @Override
    public PayResultDto pay(PayCommand command) {
        PayResultDto result = call(() -> restClient.post()
                .uri("/internal/v1/payments/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<PayResultDto>>() {}));
        if (result == null || result.status() == null) {
            // 결과를 읽지 못했으므로 결제가 됐는지 알 수 없다
            throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR);
        }
        return result;
    }

    @Override
    public void cancelPayment(String idempotencyKey) {
        call(() -> restClient.post()
                .uri("/internal/v1/payments/{idempotencyKey}/cancel", idempotencyKey)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<Void>>() {}));
    }

    @Override
    public List<PaymentAttemptDto> getPaymentAttempts(Long orderId) {
        List<PaymentAttemptDto> attempts = call(() -> queryRestClient.get()
                .uri("/internal/v1/payments/orders/{orderId}/attempts", orderId)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<List<PaymentAttemptDto>>>() {}));
        return attempts == null ? List.of() : attempts;
    }

    // ── market이 쓰지 않는 메서드 ──

    @Override
    public WalletSummaryDto getWallet(Long memberId) {
        throw notUsedInMarket();
    }

    @Override
    public boolean hasPendingTopups(Long memberId) {
        throw notUsedInMarket();
    }

    @Override
    public TransferResultDto transfer(SettlementTransferCommand command) {
        throw notUsedInMarket();
    }

    @Override
    public Optional<TransferResultDto> findTransfer(String idempotencyKey) {
        throw notUsedInMarket();
    }

    private UnsupportedOperationException notUsedInMarket() {
        return new UnsupportedOperationException("market-service에서 쓰지 않는 PaymentApi 메서드");
    }

    /**
     * 요청을 실행하고 RsData의 data를 꺼낸다. 4xx RsData는 같은 오류로, 나머지 실패는 502(결과 불명)로 바꾼다
     */
    private <T> T call(Supplier<RsData<T>> request) {
        try {
            RsData<T> response = request.get();
            return response == null ? null : response.data();
        } catch (RestClientResponseException e) {
            throw toDomainException(e);
        } catch (RestClientException e) {
            // 접속 실패·응답 대기 시간 초과(ResourceAccessException)·응답 해석 실패
            throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
        }
    }

    // payment의 4xx RsData는 같은 resultCode·메시지로. 401·403은 내부 키 설정 오류라 503.
    // 5xx이거나 본문이 RsData가 아니면(예: 장애로 HTML) 502
    private static DomainException toDomainException(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 401 || status == 403) {
            // 사용자 잘못이 아니라 배포 설정(custom.internal.apiKey) 문제라 바로 알 수 있게 ERROR로 남긴다
            log.error("결제 내부 API 인증에 실패했습니다. 내부 API 키 설정을 확인하세요: status={}, body={}",
                    status, e.getResponseBodyAsString());
            return new DomainException(GlobalErrorCode.INTERNAL_API_AUTH_FAILED, e);
        }
        if (e.getStatusCode().is4xxClientError()) {
            try {
                RsData<?> error = e.getResponseBodyAs(RsData.class);
                if (error != null && error.resultCode() != null && error.resultCode().contains("-")) {
                    String[] parts = error.resultCode().split("-", 2);
                    return new DomainException(new RemoteErrorCode(Integer.parseInt(parts[0]), parts[1], error.msg()), e);
                }
            } catch (RuntimeException ignored) {
                // 본문을 읽을 수 없으면 아래에서 502로 본다
            }
        }
        return new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
    }

    // payment가 돌려준 오류 코드. resultCode가 "404-WALLET_NOT_FOUND"면 status 404, name WALLET_NOT_FOUND
    private record RemoteErrorCode(int status, String name, String msg) implements ErrorCode {
    }

    /**
     * 실제 HTTP 연결을 담당하는 부품
     * - connectTimeout: payment 서비스에 접속하는 데 걸리는 제한 시간
     * - readTimeout: 요청 후 응답을 기다리는 제한 시간. 결제 요청은 넘으면 결과 불명으로 보고 주문을 PAYING으로 둔다(재확인 스케줄러가 정리)
     *   조회는 넘으면 502로 보고 주문 상세는 결제 내역 없이(paymentUnavailable: true) 응답한다
     */
    private static SimpleClientHttpRequestFactory requestFactory(long connectTimeoutMillis, long readTimeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMillis));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        return factory;
    }
}

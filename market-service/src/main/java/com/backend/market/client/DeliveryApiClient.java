package com.backend.market.client;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.rsData.RsData;
import com.backend.global.security.InternalApiKeyFilter;
import com.backend.shared.delivery.dto.DeliveryDto;
import com.backend.shared.delivery.out.DeliveryApi;

/**
 * {@link DeliveryApi}를 배송 내부 API(GET /internal/v1/deliveries?orderId=)로 호출하는 구현
 */
@Component
public class DeliveryApiClient implements DeliveryApi {

    private final RestClient restClient;

    @Autowired
    public DeliveryApiClient(
            @Value("${custom.delivery.baseUrl}") String baseUrl,
            @Value("${custom.internal.apiKey}") String internalApiKey,
            @Value("${custom.delivery.connectTimeoutMillis}") long connectTimeoutMillis,
            @Value("${custom.delivery.readTimeoutMillis}") long readTimeoutMillis
    ) {
        this(RestClient.builder().requestFactory(requestFactory(connectTimeoutMillis, readTimeoutMillis)), baseUrl, internalApiKey);
    }

    /**
     * 테스트에서 MockRestServiceServer를 연결한 builder를 넘길 수 있게 열어 둔다
     * 모든 요청에 내부 API 키 헤더(X-Internal-Key)를 넣는다. 없으면 delivery가 401-INTERNAL_API_KEY_INVALID로 막는다
     */
    DeliveryApiClient(RestClient.Builder builder, String baseUrl, String internalApiKey) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(InternalApiKeyFilter.HEADER, internalApiKey)
                .build();
    }

    @Override
    public Optional<DeliveryDto> findByOrderId(Long orderId) {
        try {
            RsData<DeliveryDto> response = restClient.get()
                    .uri(uri -> uri.path("/internal/v1/deliveries").queryParam("orderId", orderId).build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<RsData<DeliveryDto>>() {});
            return Optional.ofNullable(response == null ? null : response.data());
        } catch (RestClientException e) {
            // 접속 실패·응답 대기 시간 초과·오류 응답(401·5xx)·응답 해석 실패
            throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
        }
    }

    /**
     * 실제 HTTP 연결을 담당하는 부품
     * - connectTimeout: delivery 서비스에 접속하는 데 걸리는 제한 시간
     * - readTimeout: 요청 후 응답을 기다리는 제한 시간. 주문 상세 요청 안에서 부르므로 짧게 둔다
     */
    private static SimpleClientHttpRequestFactory requestFactory(long connectTimeoutMillis, long readTimeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMillis));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        return factory;
    }
}

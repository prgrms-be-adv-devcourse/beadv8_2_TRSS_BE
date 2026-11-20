package com.backend.delivery.client;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.backend.boundedContext.delivery.out.ProductImagePort;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.rsData.RsData;
import com.backend.global.security.InternalApiKeyFilter;
import com.backend.shared.product.dto.OptionSnapshotDto;

/**
 * {@link ProductImagePort}를 상품 내부 API(GET /internal/v1/products/options?optionIds=)로 호출하는 구현
 */
@Component
public class MarketProductApiClient implements ProductImagePort {

    // market이 한 번에 받는 옵션 수(InternalV1ProductController.MAX_OPTION_IDS). 넘으면 나눠 부른다
    static final int MAX_OPTION_IDS_PER_CALL = 200;

    private final RestClient restClient;

    // restClientBuilder: 스프링이 넣어 주는 builder(호출 기록 http_client_requests·추적 traceId가 붙음). RestClient.builder()로 직접 만들면 빠진다
    @Autowired
    public MarketProductApiClient(
            RestClient.Builder restClientBuilder,
            @Value("${custom.market.baseUrl}") String baseUrl,
            @Value("${custom.internal.apiKey}") String internalApiKey,
            @Value("${custom.market.connectTimeoutMillis}") long connectTimeoutMillis,
            @Value("${custom.market.readTimeoutMillis}") long readTimeoutMillis
    ) {
        this(restClientBuilder.requestFactory(requestFactory(connectTimeoutMillis, readTimeoutMillis)), baseUrl, internalApiKey);
    }

    /**
     * 테스트에서 MockRestServiceServer를 연결한 builder를 넘길 수 있게 열어 둔다
     * 모든 요청에 내부 API 키 헤더(X-Internal-Key)를 넣는다. 없으면 market이 401-INTERNAL_API_KEY_INVALID로 막는다
     */
    MarketProductApiClient(RestClient.Builder builder, String baseUrl, String internalApiKey) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(InternalApiKeyFilter.HEADER, internalApiKey)
                .build();
    }

    @Override
    public Map<Long, String> getThumbnailUrls(Collection<Long> optionIds) {
        List<Long> ids = optionIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, String> thumbnails = new HashMap<>();
        for (int from = 0; from < ids.size(); from += MAX_OPTION_IDS_PER_CALL) {
            List<Long> chunk = ids.subList(from, Math.min(from + MAX_OPTION_IDS_PER_CALL, ids.size()));
            for (OptionSnapshotDto option : getOptions(chunk)) {
                if (option.thumbnailUrl() != null) {
                    thumbnails.put(option.optionId(), option.thumbnailUrl());
                }
            }
        }
        return thumbnails;
    }

    private List<OptionSnapshotDto> getOptions(List<Long> optionIds) {
        try {
            RsData<List<OptionSnapshotDto>> response = restClient.get()
                    .uri(uri -> uri.path("/internal/v1/products/options").queryParam("optionIds", optionIds).build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<RsData<List<OptionSnapshotDto>>>() {});
            return response == null || response.data() == null ? List.of() : response.data();
        } catch (RestClientException e) {
            // 접속 실패·응답 대기 시간 초과·오류 응답(401·4xx·5xx)·응답 해석 실패
            throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
        }
    }

    /**
     * 실제 HTTP 연결을 담당하는 부품
     * - connectTimeout: market 서비스에 접속하는 데 걸리는 제한 시간
     * - readTimeout: 요청 후 응답을 기다리는 제한 시간. 물류 목록·상세 요청 안에서 부르므로 짧게 둔다
     */
    private static SimpleClientHttpRequestFactory requestFactory(long connectTimeoutMillis, long readTimeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMillis));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        return factory;
    }
}

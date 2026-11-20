package com.backend.ai.client;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.rsData.RsData;
import com.backend.global.security.InternalApiKeyFilter;
import com.backend.shared.member.dto.InfluencerDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 회원 서비스의 인플루언서 내부 API(/internal/v1/influencers)를 부른다. 크리에이터 추천이 쓴다.
 * market의 MemberInfluencerApiClient와 같은 방식이다(공용 MemberApi에 넣지 않음).
 */
@Component
public class MemberInfluencerApiClient {

    private final RestClient restClient;

    // restClientBuilder: 스프링이 넣어 주는 builder(호출 기록 http_client_requests·추적 traceId가 붙음). RestClient.builder()로 직접 만들면 빠진다
    @Autowired
    public MemberInfluencerApiClient(
            RestClient.Builder restClientBuilder,
            @Value("${custom.member.baseUrl}") String baseUrl,
            @Value("${custom.internal.apiKey}") String internalApiKey,
            @Value("${custom.member.connectTimeoutMillis}") long connectTimeoutMillis,
            @Value("${custom.member.readTimeoutMillis}") long readTimeoutMillis
    ) {
        this(restClientBuilder.requestFactory(requestFactory(connectTimeoutMillis, readTimeoutMillis)), baseUrl, internalApiKey);
    }

    // 테스트에서 MockRestServiceServer를 연결한 builder를 넘길 수 있게 열어 둔다
    MemberInfluencerApiClient(RestClient.Builder builder, String baseUrl, String internalApiKey) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(InternalApiKeyFilter.HEADER, internalApiKey)
                .build();
    }

    /**
     * 회원의 승인된 인플루언서. 승인되지 않았거나(403 INFLUENCER_NOT_APPROVED) 신청한 적 없으면(404) empty.
     * 회원 서비스가 응답하지 않으면 RestClientException을 그대로 던진다(부르는 쪽이 정한다).
     */
    public Optional<InfluencerDto> findApprovedByMemberId(Long memberId) {
        try {
            RsData<InfluencerDto> response = restClient.get()
                    .uri("/internal/v1/influencers/by-member/{memberId}/approved", memberId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return Optional.ofNullable(response == null ? null : response.data());
        } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** 인플루언서 여러 명(상태와 상관없이). 없는 ID는 빠진다. 회원 서비스가 응답하지 않으면 502 */
    public List<InfluencerDto> getInfluencers(Collection<Long> influencerIds) {
        if (influencerIds.isEmpty()) return List.of();
        try {
            RsData<List<InfluencerDto>> response = restClient.get()
                    .uri(uri -> uri.path("/internal/v1/influencers").queryParam("ids", influencerIds).build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return response == null || response.data() == null ? List.of() : response.data();
        } catch (RestClientException e) {
            throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory(long connectTimeoutMillis, long readTimeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMillis));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        return factory;
    }
}

package com.backend.market.client;

import com.backend.global.exception.DomainException;
import com.backend.global.rsData.RsData;
import com.backend.global.security.InternalApiKeyFilter;
import com.backend.shared.member.dto.InfluencerDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 회원 서비스의 인플루언서 내부 API(/internal/v1/influencers)를 부른다. 쿠폰(협약)이 쓴다.
 * 공용 계약 {@link com.backend.shared.member.out.MemberApi}에 넣지 않고 market 전용으로 둔다 (넣으면 MemberApi를 구현한 다른 서비스도 고쳐야 한다).
 * 설정·오류 변환은 {@link MemberApiClient}와 같다: 회원 서비스의 오류는 같은 resultCode로, 응답을 못 받으면 502
 */
@Component
public class MemberInfluencerApiClient {

    private final RestClient restClient;

    @Autowired
    public MemberInfluencerApiClient(
            @Value("${custom.member.baseUrl}") String baseUrl,
            @Value("${custom.internal.apiKey}") String internalApiKey,
            @Value("${custom.member.connectTimeoutMillis}") long connectTimeoutMillis,
            @Value("${custom.member.readTimeoutMillis}") long readTimeoutMillis
    ) {
        this(RestClient.builder().requestFactory(MemberApiClient.requestFactory(connectTimeoutMillis, readTimeoutMillis)), baseUrl, internalApiKey);
    }

    // 테스트에서 MockRestServiceServer를 연결한 builder를 넘길 수 있게 열어 둔다
    MemberInfluencerApiClient(RestClient.Builder builder, String baseUrl, String internalApiKey) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(InternalApiKeyFilter.HEADER, internalApiKey)
                .build();
    }

    // 상태와 상관없이 조회. 없으면 empty
    public Optional<InfluencerDto> findInfluencer(Long influencerId) {
        try {
            return Optional.ofNullable(MemberApiClient.call(() -> restClient.get()
                    .uri("/internal/v1/influencers/{influencerId}", influencerId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<RsData<InfluencerDto>>() {})));
        } catch (DomainException e) {
            if (e.statusCode() == 404) return Optional.empty();
            throw e;
        }
    }

    // 상태와 상관없이 여러 명 조회. 없는 ID는 결과에서 빠진다
    public List<InfluencerDto> getInfluencers(Collection<Long> influencerIds) {
        if (influencerIds.isEmpty()) return List.of();
        return MemberApiClient.call(() -> restClient.get()
                .uri(uri -> uri.path("/internal/v1/influencers").queryParam("ids", influencerIds).build())
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<List<InfluencerDto>>>() {}));
    }

    // 회원의 승인된 인플루언서. 아니면 회원 서비스가 403-INFLUENCER_NOT_APPROVED로 응답한다
    public InfluencerDto getApprovedInfluencerByMemberId(Long memberId) {
        return MemberApiClient.call(() -> restClient.get()
                .uri("/internal/v1/influencers/by-member/{memberId}/approved", memberId)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<InfluencerDto>>() {}));
    }
}

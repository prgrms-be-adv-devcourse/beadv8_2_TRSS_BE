package com.backend.settlement.client;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.backend.global.rsData.RsData;
import com.backend.global.security.InternalApiKeyFilter;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.out.MemberApi;

/**
 * {@link MemberApi}를 회원 내부 API(/internal/v1/sellers)로 호출하는 구현 (member-service)
 *
 * 정산이 쓰는 것은 판매자 조회 세 가지다.
 * - getSeller: 지급 직전 판매자 상태(제재 여부)와 지급 대상 회원 ID
 * - getSellers: 판별, 확정의 제재 확인, 관리자 목록과 미리보기의 상호명
 * - findSellerByMemberId: 판매자 화면(로그인한 회원 → 판매자 ID)
 * 오류 처리는 {@link InternalApiCalls} 참고. 회원 서비스가 응답하지 않으면 502로, 지급이라면 그 정산서는 실패로 기록되고 재시도된다
 * 정산 코드는 SettlementMemberPort → SettlementMemberApiAdapter를 거쳐 이 구현을 부른다.
 */
@Component
public class MemberApiClient implements MemberApi {

    private final RestClient restClient;

    // restClientBuilder: 스프링이 넣어 주는 builder(호출 기록 http_client_requests·추적 traceId가 붙음). RestClient.builder()로 직접 만들면 빠진다
    @Autowired
    public MemberApiClient(
            RestClient.Builder restClientBuilder,
            @Value("${custom.member.baseUrl}") String baseUrl,
            @Value("${custom.internal.apiKey}") String internalApiKey,
            @Value("${custom.member.connectTimeoutMillis}") long connectTimeoutMillis,
            @Value("${custom.member.readTimeoutMillis}") long readTimeoutMillis
    ) {
        this(restClientBuilder.requestFactory(InternalApiCalls.requestFactory(connectTimeoutMillis, readTimeoutMillis)),
                baseUrl, internalApiKey);
    }

    /**
     * 테스트에서 MockRestServiceServer를 연결한 builder를 넘길 수 있게 열어 둔다
     * 모든 요청에 내부 API 키 헤더(X-Internal-Key)를 넣는다. 없으면 회원 서비스가 401로 막는다
     */
    MemberApiClient(RestClient.Builder builder, String baseUrl, String internalApiKey) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(InternalApiKeyFilter.HEADER, internalApiKey)
                .build();
    }

    // ── 정산이 쓰는 메서드 ──

    @Override
    public SellerDto getSeller(Long sellerId) {
        return InternalApiCalls.call(() -> restClient.get()
                .uri("/internal/v1/sellers/{sellerId}", sellerId)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<SellerDto>>() {}));
    }

    @Override
    public List<SellerDto> getSellers(Collection<Long> sellerIds) {
        if (sellerIds.isEmpty()) return List.of();
        List<SellerDto> sellers = InternalApiCalls.call(() -> restClient.get()
                .uri(uri -> uri.path("/internal/v1/sellers").queryParam("ids", sellerIds).build())
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<List<SellerDto>>>() {}));
        return sellers == null ? List.of() : sellers;
    }

    /** 판매자 신청 이력이 없으면 회원 서비스가 200 + data null로 응답한다 */
    @Override
    public Optional<SellerDto> findSellerByMemberId(Long memberId) {
        return Optional.ofNullable(InternalApiCalls.call(() -> restClient.get()
                .uri("/internal/v1/sellers/by-member/{memberId}", memberId)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<SellerDto>>() {})));
    }

    // ── 정산이 쓰지 않는 메서드 ──

    @Override
    public MemberDto getMember(Long memberId) {
        throw notUsedInSettlement();
    }

    @Override
    public void checkActiveMember(Long memberId) {
        throw notUsedInSettlement();
    }

    @Override
    public List<MemberDto> getMembers(Collection<Long> memberIds) {
        throw notUsedInSettlement();
    }

    @Override
    public Optional<MemberDto> findMemberByEmail(String email) {
        throw notUsedInSettlement();
    }

    @Override
    public SellerDto getApprovedSellerByMemberId(Long memberId) {
        throw notUsedInSettlement();
    }

    private UnsupportedOperationException notUsedInSettlement() {
        return new UnsupportedOperationException("settlement-service에서 쓰지 않는 MemberApi 메서드");
    }
}

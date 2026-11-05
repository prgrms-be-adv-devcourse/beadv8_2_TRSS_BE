package com.backend.boundedContext.member.out;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import com.backend.global.rsData.RsData;

/**
 * 정산 내부 API(/internal/v1/settlements)를 HTTP로 불러 MemberSettlementPort를 구현함
 * 주소만 바꾸면 루트 앱(정산 분리 전)과 settlement-service(분리 후) 어느 쪽이든 부를 수 있음
 */
@Component
public class MemberSettlementHttpAdapter implements MemberSettlementPort {

    private final MemberInternalApiClient client;

    @Autowired
    public MemberSettlementHttpAdapter(
            @Value("${custom.settlement.baseUrl}") String baseUrl,
            @Value("${custom.internal.apiKey}") String internalApiKey,
            @Value("${custom.settlement.connectTimeoutMillis}") long connectTimeoutMillis,
            @Value("${custom.settlement.readTimeoutMillis}") long readTimeoutMillis
    ) {
        this(MemberInternalApiClient.create("settlement", baseUrl, internalApiKey, connectTimeoutMillis, readTimeoutMillis));
    }

    // 테스트에서 MockRestServiceServer를 연결한 클라이언트를 넘길 수 있게 열어 둠
    MemberSettlementHttpAdapter(MemberInternalApiClient client) {
        this.client = client;
    }

    @Override
    public long getUnsettledAmount(Long sellerId) {
        Long amount = client.call(() -> client.restClient().get()
                .uri("/internal/v1/settlements/sellers/{sellerId}/unsettled-amount", sellerId)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<Long>>() {}));
        // 철회·탈퇴 판단에 쓰는 값이라 data가 비어 있으면 0으로 통과시키지 않고 확인하지 못한 것으로 봄
        if (amount == null) {
            throw new MemberPortUnavailableException("settlement 내부 API 응답에 data가 없습니다", null);
        }
        return amount;
    }
}

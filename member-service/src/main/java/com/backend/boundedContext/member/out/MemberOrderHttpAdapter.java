package com.backend.boundedContext.member.out;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import com.backend.global.rsData.RsData;
import com.backend.shared.order.dto.OrderSummaryDto;

/**
 * 주문 내부 API(market-service /internal/v1/orders)를 HTTP로 불러 MemberOrderPort를 구현함
 * 주문이 market-service로 옮겨 가서 같은 앱의 OrderApi로는 부를 수 없음
 */
@Component
public class MemberOrderHttpAdapter implements MemberOrderPort {

    private final MemberInternalApiClient client;

    @Autowired
    public MemberOrderHttpAdapter(
            @Value("${custom.market.baseUrl}") String baseUrl,
            @Value("${custom.internal.apiKey}") String internalApiKey,
            @Value("${custom.market.connectTimeoutMillis}") long connectTimeoutMillis,
            @Value("${custom.market.readTimeoutMillis}") long readTimeoutMillis
    ) {
        this(MemberInternalApiClient.create("order", baseUrl, internalApiKey, connectTimeoutMillis, readTimeoutMillis));
    }

    // 테스트에서 MockRestServiceServer를 연결한 클라이언트를 넘길 수 있게 열어 둠
    MemberOrderHttpAdapter(MemberInternalApiClient client) {
        this.client = client;
    }

    @Override
    public boolean hasActiveOrders(Long memberId) {
        return requireBoolean(client.call(() -> client.restClient().get()
                .uri(uri -> uri.path("/internal/v1/orders/active/exists").queryParam("buyerId", memberId).build())
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<Boolean>>() {})));
    }

    @Override
    public boolean hasActiveSales(Long sellerId) {
        return requireBoolean(client.call(() -> client.restClient().get()
                .uri(uri -> uri.path("/internal/v1/orders/active-sales/exists").queryParam("sellerId", sellerId).build())
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<Boolean>>() {})));
    }

    @Override
    public List<OrderSummaryDto> getRecentOrders(Long memberId, int limit) {
        List<OrderSummaryDto> orders = client.call(() -> client.restClient().get()
                .uri(uri -> uri.path("/internal/v1/orders/recent")
                        .queryParam("buyerId", memberId)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<List<OrderSummaryDto>>>() {}));
        return orders == null ? List.of() : orders;
    }

    // 탈퇴·철회 판단에 쓰는 값이라 data가 비어 있으면 false로 통과시키지 않고 확인하지 못한 것으로 봄
    private static boolean requireBoolean(Boolean value) {
        if (value == null) {
            throw new MemberPortUnavailableException("order 내부 API 응답에 data가 없습니다", null);
        }
        return value;
    }
}

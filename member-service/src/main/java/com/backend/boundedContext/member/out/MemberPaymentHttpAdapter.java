package com.backend.boundedContext.member.out;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import com.backend.global.rsData.RsData;
import com.backend.shared.payment.dto.WalletSummaryDto;

/**
 * 결제 내부 API(/internal/v1/payments)를 HTTP로 불러 MemberPaymentPort를 구현함
 * 회원이 member-service로 옮겨 가면 같은 앱의 PaymentApi를 부를 수 없어 HTTP로 부름. 주소만 바꾸면 루트 앱(결제 분리 전)과 payment-service(분리 후) 어느 쪽이든 부를 수 있음
 */
@Component
public class MemberPaymentHttpAdapter implements MemberPaymentPort {

    private final MemberInternalApiClient client;

    @Autowired
    public MemberPaymentHttpAdapter(
            @Value("${custom.payment.baseUrl}") String baseUrl,
            @Value("${custom.internal.apiKey}") String internalApiKey,
            @Value("${custom.payment.connectTimeoutMillis}") long connectTimeoutMillis,
            @Value("${custom.payment.readTimeoutMillis}") long readTimeoutMillis
    ) {
        this(MemberInternalApiClient.create("payment", baseUrl, internalApiKey, connectTimeoutMillis, readTimeoutMillis));
    }

    // 테스트에서 MockRestServiceServer를 연결한 클라이언트를 넘길 수 있게 열어 둠
    MemberPaymentHttpAdapter(MemberInternalApiClient client) {
        this.client = client;
    }

    // 지갑이 없으면 결제가 404-WALLET_NOT_FOUND로 응답해 같은 DomainException이 됨 (탈퇴 검사는 잔액 0으로 보고, 관리자 회원 상세는 지갑을 비움)
    @Override
    public WalletSummaryDto getWallet(Long memberId) {
        WalletSummaryDto wallet = client.call(() -> client.restClient().get()
                .uri("/internal/v1/payments/wallets/{memberId}", memberId)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<WalletSummaryDto>>() {}));
        if (wallet == null) {
            throw new MemberPortUnavailableException("payment 내부 API 응답에 data가 없습니다", null);
        }
        return wallet;
    }

    @Override
    public boolean hasPendingTopups(Long memberId) {
        Boolean pending = client.call(() -> client.restClient().get()
                .uri("/internal/v1/payments/wallets/{memberId}/pending-topups", memberId)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<Boolean>>() {}));
        // 탈퇴 판단에 쓰는 값이라 data가 비어 있으면 false로 통과시키지 않고 확인하지 못한 것으로 봄
        if (pending == null) {
            throw new MemberPortUnavailableException("payment 내부 API 응답에 data가 없습니다", null);
        }
        return pending;
    }
}

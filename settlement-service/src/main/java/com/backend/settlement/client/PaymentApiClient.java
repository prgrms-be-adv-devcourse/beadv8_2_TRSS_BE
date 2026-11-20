package com.backend.settlement.client;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.backend.global.exception.DomainException;
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
 * {@link PaymentApi}를 결제 내부 API(/internal/v1/payments/transfers)로 호출하는 구현 (payment-service)
 *
 * 정산이 쓰는 것은 두 가지다.
 * - transfer: 정산 지급. 결제가 자기 트랜잭션으로 커밋한 뒤 응답한다(정산 트랜잭션에 참여하지 않음)
 *   2xx인데 지급 결과(data)가 없으면 지급됐는지 알 수 없으므로 502로 바꿔 결과 모름으로 처리한다
 * - findTransfer: 같은 멱등 키(SETTLEMENT-{정산서 ID})의 지급 내역. 없으면 결제가 200 + data null로 응답 → Optional.empty
 * 오류 처리는 {@link InternalApiCalls} 참고.
 *   4xx(HOLDING_INSUFFICIENT, TRANSFER_AMOUNT_MISMATCH 등)는 결제가 거절한 것이라 정산서를 실패로 기록하고,
 *   5xx, 502(응답 없음, 시간 초과)는 결과 모름이라 정산서를 PROCESSING으로 두고 결제 원장으로 확인한다.
 * 정산 코드는 SettlementPaymentPort → SettlementPaymentApiAdapter를 거쳐 이 구현을 부른다.
 */
@Component
public class PaymentApiClient implements PaymentApi {

    private final RestClient restClient;

    // restClientBuilder: 스프링이 넣어 주는 builder(호출 기록 http_client_requests·추적 traceId가 붙음). RestClient.builder()로 직접 만들면 빠진다
    @Autowired
    public PaymentApiClient(
            RestClient.Builder restClientBuilder,
            @Value("${custom.payment.baseUrl}") String baseUrl,
            @Value("${custom.internal.apiKey}") String internalApiKey,
            @Value("${custom.payment.connectTimeoutMillis}") long connectTimeoutMillis,
            @Value("${custom.payment.readTimeoutMillis}") long readTimeoutMillis
    ) {
        this(restClientBuilder.requestFactory(InternalApiCalls.requestFactory(connectTimeoutMillis, readTimeoutMillis)),
                baseUrl, internalApiKey);
    }

    /** 테스트에서 MockRestServiceServer를 연결한 builder를 넘길 수 있게 열어 둔다 */
    PaymentApiClient(RestClient.Builder builder, String baseUrl, String internalApiKey) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(InternalApiKeyFilter.HEADER, internalApiKey)
                .build();
    }

    // ── 정산이 쓰는 메서드 ──

    @Override
    public TransferResultDto transfer(SettlementTransferCommand command) {
        TransferResultDto result = InternalApiCalls.call(() -> restClient.post()
                .uri("/internal/v1/payments/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<TransferResultDto>>() {}));
        if (result == null) {
            // 결과를 읽지 못했으므로 지급됐는지 알 수 없다. null을 돌려주면 결과 기록 단계에서 NPE가 나 결과 모름 이력이 남지 않는다
            throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR);
        }
        return result;
    }

    @Override
    public Optional<TransferResultDto> findTransfer(String idempotencyKey) {
        return Optional.ofNullable(InternalApiCalls.call(() -> restClient.get()
                .uri("/internal/v1/payments/transfers/{idempotencyKey}", idempotencyKey)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<TransferResultDto>>() {})));
    }

    // ── 정산이 쓰지 않는 메서드 ──

    @Override
    public WalletSummaryDto getWallet(Long memberId) {
        throw notUsedInSettlement();
    }

    @Override
    public boolean hasPendingTopups(Long memberId) {
        throw notUsedInSettlement();
    }

    @Override
    public PayResultDto pay(PayCommand command) {
        throw notUsedInSettlement();
    }

    @Override
    public void cancelPayment(String idempotencyKey) {
        throw notUsedInSettlement();
    }

    @Override
    public List<PaymentAttemptDto> getPaymentAttempts(Long orderId) {
        throw notUsedInSettlement();
    }

    private UnsupportedOperationException notUsedInSettlement() {
        return new UnsupportedOperationException("settlement-service에서 쓰지 않는 PaymentApi 메서드");
    }
}

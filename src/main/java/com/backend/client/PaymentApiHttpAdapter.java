package com.backend.client;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.rsData.RsData;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.dto.WalletSummaryDto;
import com.backend.shared.payment.out.PaymentApi;

/**
 * 결제 내부 API(/internal/v1/payments)를 HTTP로 불러 PaymentApi를 구현함 (결제 서비스 분리 후 루트 앱이 사용)
 * 회원·주문·정산 코드는 지금처럼 PaymentApi만 보므로, 이 어댑터로 바뀌어도 그쪽 코드는 그대로임
 * custom.paymentService.client가 http일 때만 빈으로 등록됨 (기본 local은 같은 앱의 PaymentApiAdapter)
 *
 * 임시 코드: 회원·주문·정산이 아직 루트 앱에 남아 있는 동안만 씀
 * 각 서비스가 분리되면 부르는 쪽이 자기 Port + HTTP 클라이언트를 만들어 결제를 부르므로(예: MemberPaymentPort),
 * 루트 앱에 결제를 부르는 코드가 남지 않으면 이 클래스와 테스트, custom.paymentService 설정을 삭제함
 *
 * 실패 처리
 * - 결제 서비스가 RsData 오류로 응답하면(예: 409-PAYMENT_ALREADY_PAID) 같은 resultCode의 DomainException으로 바꿈
 *   → 부른 쪽은 메서드를 직접 부를 때와 같은 예외를 받음
 * - 응답을 못 받았거나(접속 실패·시간 초과) 본문이 RsData가 아니면 502 EXTERNAL_SERVICE_ERROR
 *   pay·transfer에서 이 오류가 나면 결제 서비스에서 처리됐는지 알 수 없음 → 부른 쪽이 같은 멱등 키로 취소하거나 다시 부름
 */
@Component
@ConditionalOnProperty(name = "custom.paymentService.client", havingValue = "http")
public class PaymentApiHttpAdapter implements PaymentApi {

    private final PaymentHttpClient client;

    @Autowired
    public PaymentApiHttpAdapter(
            @Value("${custom.paymentService.baseUrl}") String baseUrl,
            @Value("${custom.paymentService.connectTimeoutSeconds}") long connectTimeoutSeconds,
            @Value("${custom.paymentService.readTimeoutSeconds}") long readTimeoutSeconds
    ) {
        this(RestClient.builder().requestFactory(requestFactory(connectTimeoutSeconds, readTimeoutSeconds)), baseUrl);
    }

    /**
     * 테스트에서 MockRestServiceServer를 연결한 builder를 넘길 수 있게 열어 둠
     * HttpServiceProxyFactory가 아래 PaymentHttpClient 인터페이스를 보고 실제 요청 코드를 만들어 줌
     */
    PaymentApiHttpAdapter(RestClient.Builder builder, String baseUrl) {
        RestClient restClient = builder.baseUrl(baseUrl).build();
        this.client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(PaymentHttpClient.class);
    }

    @Override
    public WalletSummaryDto getWallet(Long memberId) {
        return call(() -> client.getWallet(memberId));
    }

    @Override
    public boolean hasPendingTopups(Long memberId) {
        return call(() -> client.hasPendingTopups(memberId));
    }

    @Override
    public PayResultDto pay(PayCommand command) {
        return call(() -> client.pay(command));
    }

    @Override
    public void cancelPayment(String idempotencyKey) {
        call(() -> client.cancelPayment(idempotencyKey));
    }

    @Override
    public List<PaymentAttemptDto> getPaymentAttempts(Long orderId) {
        return call(() -> client.getPaymentAttempts(orderId));
    }

    @Override
    public TransferResultDto transfer(SettlementTransferCommand command) {
        return call(() -> client.transfer(command));
    }

    @Override
    public Optional<TransferResultDto> findTransfer(String idempotencyKey) {
        // 지급 내역이 없으면 200 + data null로 옴
        return Optional.ofNullable(call(() -> client.findTransfer(idempotencyKey)));
    }

    private static <T> T call(Supplier<RsData<T>> request) {
        try {
            return request.get().data();
        } catch (RestClientResponseException e) {
            throw convertPaymentServiceError(e);
        } catch (ResourceAccessException e) {
            // 결제 서비스에 접속 실패, 응답 대기 시간 초과인 경우
            throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
        }
    }

    // 결제 서비스가 보낸 RsData 오류는 같은 resultCode로, 해석할 수 없는 오류는 502로 바꿈
    private static DomainException convertPaymentServiceError(RestClientResponseException e) {
        PaymentServiceErrorResponse error = readErrorResponse(e);
        if (!hasResultCode(error)) {
            return new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
        }
        return new DomainException(error.resultCode(), error.msg(), error.data());
    }

    // 오류 응답 본문을 읽음. RsData 형식이 아니면(예: 게이트웨이 오류 HTML) null
    private static PaymentServiceErrorResponse readErrorResponse(RestClientResponseException e) {
        try {
            return e.getResponseBodyAs(PaymentServiceErrorResponse.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    // "409-PAYMENT_ALREADY_PAID"처럼 우리 형식의 resultCode가 들어 있는지
    private static boolean hasResultCode(PaymentServiceErrorResponse error) {
        return error != null && error.resultCode() != null && error.resultCode().contains("-");
    }

    /**
     * 실제 HTTP 연결 부품
     * - connectTimeout: 결제 서비스 접속 제한 시간
     * - readTimeout: 요청 후 응답 대기 제한 시간. 결제는 지갑 잠금을 기다릴 수 있어 회원 조회보다 길게 잡음
     */
    private static SimpleClientHttpRequestFactory requestFactory(long connectTimeoutSeconds, long readTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        return factory;
    }

    // 결제 내부 API 주소 목록 (InternalPaymentController와 같은 모양)
    @HttpExchange("/internal/v1/payments")
    interface PaymentHttpClient {

        @GetExchange("/wallets/{memberId}")
        RsData<WalletSummaryDto> getWallet(@PathVariable Long memberId);

        @GetExchange("/wallets/{memberId}/pending-topups")
        RsData<Boolean> hasPendingTopups(@PathVariable Long memberId);

        @PostExchange("/pay")
        RsData<PayResultDto> pay(@RequestBody PayCommand command);

        @PostExchange("/{idempotencyKey}/cancel")
        RsData<Void> cancelPayment(@PathVariable String idempotencyKey);

        @GetExchange("/orders/{orderId}/attempts")
        RsData<List<PaymentAttemptDto>> getPaymentAttempts(@PathVariable Long orderId);

        @PostExchange("/transfers")
        RsData<TransferResultDto> transfer(@RequestBody SettlementTransferCommand command);

        @GetExchange("/transfers/{idempotencyKey}")
        RsData<TransferResultDto> findTransfer(@PathVariable String idempotencyKey);
    }

    // 결제 서비스 오류 응답 본문 (RsData 모양)
    private record PaymentServiceErrorResponse(String resultCode, String msg, Object data) {}
}

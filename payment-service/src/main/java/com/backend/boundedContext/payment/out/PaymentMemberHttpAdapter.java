package com.backend.boundedContext.payment.out;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
import com.backend.shared.member.dto.MemberDto;

/**
 * 회원 내부 API(/internal/v1/members)를 HTTP로 불러 PaymentMemberPort를 구현함
 *
 * 실패 처리
 * - 회원 서비스가 RsData 오류로 응답하면(예: 401-UNAUTHORIZED) 같은 resultCode의 DomainException으로 바꿈
 *   → 결제 API를 부른 사용자도 분리 전과 같은 응답을 받음
 * - 응답을 못 받았거나(접속 실패·시간 초과) 본문이 RsData가 아니면 502 EXTERNAL_SERVICE_ERROR
 */
@Component
public class PaymentMemberHttpAdapter implements PaymentMemberPort {

    private final MemberHttpClient client;

    @Autowired
    public PaymentMemberHttpAdapter(
            @Value("${custom.payment.memberBaseUrl}") String baseUrl,
            @Value("${custom.payment.memberConnectTimeoutSeconds}") long connectTimeoutSeconds,
            @Value("${custom.payment.memberReadTimeoutSeconds}") long readTimeoutSeconds
    ) {
        this(RestClient.builder().requestFactory(requestFactory(connectTimeoutSeconds, readTimeoutSeconds)), baseUrl);
    }

    /**
     * 테스트에서 MockRestServiceServer를 연결한 builder를 넘길 수 있게 열어 둠
     * HttpServiceProxyFactory가 아래 MemberHttpClient 인터페이스를 보고 실제 요청 코드를 만들어 줌
     */
    PaymentMemberHttpAdapter(RestClient.Builder builder, String baseUrl) {
        RestClient restClient = builder.baseUrl(baseUrl).build();
        this.client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(MemberHttpClient.class);
    }

    @Override
    public void checkActiveMember(Long memberId) {
        call(() -> client.checkActiveMember(memberId));
    }

    @Override
    public List<MemberDto> getMembers(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) return List.of();   // 조회할 회원이 없으면 부르지 않음
        return call(() -> client.getMembers(memberIds));
    }

    @Override
    public Optional<MemberDto> findMemberByEmail(String email) {
        // 회원이 없으면 200 + data null로 옴
        return Optional.ofNullable(call(() -> client.findMemberByEmail(new EmailSearchRequest(email))));
    }

    private static <T> T call(Supplier<RsData<T>> request) {
        try {
            return request.get().data();
        } catch (RestClientResponseException e) {
            throw convertMemberServiceError(e);
        } catch (ResourceAccessException e) {
            // 회원 서비스에 접속 실패, 응답 대기 시간 초과인 경우
            throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
        }
    }

    // 회원 서비스가 보낸 RsData 오류는 같은 resultCode로, 해석할 수 없는 오류는 502로 바꿈
    private static DomainException convertMemberServiceError(RestClientResponseException e) {
        MemberServiceErrorResponse error = readErrorResponse(e);
        if (!hasResultCode(error)) {
            return new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
        }
        return new DomainException(error.resultCode(), error.msg(), error.data());
    }

    // 오류 응답 본문을 읽음. RsData 형식이 아니면(예: 게이트웨이 오류 HTML) null
    private static MemberServiceErrorResponse readErrorResponse(RestClientResponseException e) {
        try {
            return e.getResponseBodyAs(MemberServiceErrorResponse.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    // "401-UNAUTHORIZED"처럼 우리 형식의 resultCode가 들어 있는지
    private static boolean hasResultCode(MemberServiceErrorResponse error) {
        return error != null && error.resultCode() != null && error.resultCode().contains("-");
    }

    /**
     * 실제 HTTP 연결 부품. 회원 조회는 가벼운 요청이라 토스보다 짧게 잡음
     * - connectTimeout: 회원 서비스 접속 제한 시간
     * - readTimeout: 요청 후 응답 대기 제한 시간
     */
    private static SimpleClientHttpRequestFactory requestFactory(long connectTimeoutSeconds, long readTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        return factory;
    }

    // 회원 내부 API 주소 목록 (InternalMemberController와 같은 모양)
    @HttpExchange("/internal/v1/members")
    interface MemberHttpClient {

        @GetExchange("/{memberId}/active")
        RsData<Void> checkActiveMember(@PathVariable Long memberId);

        @GetExchange
        RsData<List<MemberDto>> getMembers(@RequestParam("ids") Collection<Long> ids);

        @PostExchange("/search-by-email")
        RsData<MemberDto> findMemberByEmail(@RequestBody EmailSearchRequest request);
    }

    // 이메일 검색 요청 본문 (회원 쪽 MemberEmailSearchRequest와 같은 모양)
    record EmailSearchRequest(String email) {}

    // 회원 서비스 오류 응답 본문 (RsData 모양)
    private record MemberServiceErrorResponse(String resultCode, String msg, Object data) {}
}

package com.backend.boundedContext.member.out;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.ErrorCode;
import com.backend.global.rsData.RsData;
import com.backend.global.security.InternalApiKeyFilter;

/**
 * 회원이 다른 모듈의 내부 API(/internal/**)를 HTTP로 부를 때 같이 쓰는 부분 (*HttpAdapter가 하나씩 가짐)
 * - 주소, 접속·응답 대기 제한 시간, 내부 키 헤더(X-Internal-Key)를 넣은 RestClient
 * - 응답 RsData의 data를 꺼내 돌려줌
 * - 상대가 4xx 오류로 응답하면 같은 resultCode·메시지의 DomainException (예: 404-WALLET_NOT_FOUND)
 * - 응답을 받지 못했거나(접속 실패·시간 초과) 상대가 5xx이거나 오류 본문을 읽을 수 없으면 MemberPortUnavailableException
 * market-service의 MemberApiClient와 같은 방식. 다른 서비스도 같은 처리가 필요하면 common으로 올릴 후보
 */
public class MemberInternalApiClient {

    // 예외 메시지에 넣는 대상 모듈 이름 (order, payment, settlement)
    private final String target;
    private final RestClient restClient;

    public static MemberInternalApiClient create(String target, String baseUrl, String internalApiKey,
                                                 long connectTimeoutMillis, long readTimeoutMillis) {
        return new MemberInternalApiClient(target,
                RestClient.builder().requestFactory(requestFactory(connectTimeoutMillis, readTimeoutMillis)),
                baseUrl, internalApiKey);
    }

    // 테스트에서 MockRestServiceServer를 연결한 builder를 넘길 수 있게 열어 둠
    MemberInternalApiClient(String target, RestClient.Builder builder, String baseUrl, String internalApiKey) {
        this.target = target;
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(InternalApiKeyFilter.HEADER, internalApiKey)
                .build();
    }

    // 요청을 만들 때 쓰는 RestClient. 실행은 call()로 감싸서 함
    public RestClient restClient() {
        return restClient;
    }

    // 요청을 실행하고 RsData의 data를 꺼냄. 실패는 DomainException(상대의 4xx) 또는 MemberPortUnavailableException으로 바꿈
    public <T> T call(Supplier<RsData<T>> request) {
        try {
            RsData<T> response = request.get();
            return response == null ? null : response.data();
        } catch (RestClientResponseException e) {
            throw toException(e);
        } catch (ResourceAccessException e) {
            // 응답을 받지 못함 (상대 서비스가 꺼져 있음·접속 실패·응답 대기 시간 초과)
            throw new MemberPortUnavailableException(target + " 내부 API에 접속하지 못했습니다", e);
        }
    }

    // 4xx는 상대가 판단한 결과라 같은 오류로 다시 던지고, 5xx나 읽을 수 없는 응답은 확인하지 못한 것으로 봄
    private RuntimeException toException(RestClientResponseException e) {
        if (e.getStatusCode().is4xxClientError()) {
            RemoteErrorCode errorCode = RemoteErrorCode.from(e);
            if (errorCode != null) {
                return new DomainException(errorCode, e);
            }
        }
        return new MemberPortUnavailableException(target + " 내부 API가 " + e.getStatusCode().value() + "로 응답했습니다", e);
    }

    /**
     * 실제 HTTP 연결을 담당하는 부품
     * - connectTimeout: 상대 서비스에 접속하는 데 걸리는 제한 시간
     * - readTimeout: 요청 후 응답을 기다리는 제한 시간. 사용자 요청 안에서 부르므로 짧게 둠
     */
    private static SimpleClientHttpRequestFactory requestFactory(long connectTimeoutMillis, long readTimeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMillis));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        return factory;
    }

    // 상대 서비스가 돌려준 오류 코드. resultCode가 "404-WALLET_NOT_FOUND"면 status 404, name WALLET_NOT_FOUND
    private record RemoteErrorCode(int status, String name, String msg) implements ErrorCode {

        // 본문이 RsData 형식이 아니면 null
        static RemoteErrorCode from(RestClientResponseException e) {
            try {
                RsData<?> error = e.getResponseBodyAs(RsData.class);
                if (error == null || error.resultCode() == null || !error.resultCode().contains("-")) {
                    return null;
                }
                String[] parts = error.resultCode().split("-", 2);
                return new RemoteErrorCode(Integer.parseInt(parts[0]), parts[1], error.msg());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}

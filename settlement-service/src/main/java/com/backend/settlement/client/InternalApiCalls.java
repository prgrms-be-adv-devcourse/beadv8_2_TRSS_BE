package com.backend.settlement.client;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.ErrorCode;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.rsData.RsData;

/**
 * 정산이 다른 서비스 내부 API(/internal/**)를 부를 때 쓰는 공통 처리 (회원, 결제 클라이언트가 함께 쓴다)
 *
 * 응답은 RsData로 오고 data를 꺼내 돌려준다. 예외는 정산 지급의 결과 구분(SettlementPayoutUseCase.isResultUnknown)에 맞춰 바꾼다.
 * - 상대가 4xx로 응답: 같은 resultCode, 메시지의 DomainException(4xx). "상대가 판단을 마친 거절"이라 돈이 움직이지 않았다
 * - 상대가 5xx로 응답: 같은 resultCode의 DomainException(5xx). 본문이 RsData가 아니면 502
 * - 응답을 받지 못함(접속 실패, 응답 대기 시간 초과), 응답을 해석할 수 없음: 502 EXTERNAL_SERVICE_ERROR
 * 5xx와 502는 지급에서 "결과 모름"으로 처리된다(정산서를 PROCESSING으로 두고 결제 원장으로 확인).
 */
final class InternalApiCalls {

    private InternalApiCalls() {
    }

    /** 요청을 실행하고 RsData의 data를 꺼낸다. Spring HTTP 예외는 DomainException으로 바꾼다 */
    static <T> T call(Supplier<RsData<T>> request) {
        try {
            RsData<T> response = request.get();
            return response == null ? null : response.data();
        } catch (RestClientResponseException e) {
            throw toDomainException(e);
        } catch (ResourceAccessException e) {
            // 응답을 받지 못함 (상대 서비스가 꺼져 있음, 접속 실패, 응답 대기 시간 초과)
            throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
        } catch (RestClientException e) {
            // 응답은 받았지만 본문을 해석할 수 없음
            throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
        }
    }

    /** 상대의 오류 응답(RsData)을 같은 resultCode, 메시지의 DomainException으로. 본문이 RsData가 아니면 502 */
    private static DomainException toDomainException(RestClientResponseException e) {
        try {
            RsData<?> error = e.getResponseBodyAs(RsData.class);
            if (error != null && error.resultCode() != null && error.resultCode().contains("-")) {
                String[] parts = error.resultCode().split("-", 2);
                return new DomainException(new RemoteErrorCode(Integer.parseInt(parts[0]), parts[1], error.msg()), e);
            }
        } catch (RuntimeException ignored) {
            // 본문이 RsData 형식이 아니면 아래 기본값
        }
        return new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
    }

    /**
     * 실제 HTTP 연결 부품
     * connectTimeout: 상대 서비스에 접속하는 제한 시간 / readTimeout: 요청 후 응답을 기다리는 제한 시간
     */
    static SimpleClientHttpRequestFactory requestFactory(long connectTimeoutMillis, long readTimeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMillis));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        return factory;
    }

    /** 상대가 돌려준 오류 코드. resultCode가 "409-HOLDING_INSUFFICIENT"면 status 409, name HOLDING_INSUFFICIENT */
    private record RemoteErrorCode(int status, String name, String msg) implements ErrorCode {
    }
}

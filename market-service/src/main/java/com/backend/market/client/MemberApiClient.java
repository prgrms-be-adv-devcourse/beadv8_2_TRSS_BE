package com.backend.market.client;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.ErrorCode;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.rsData.RsData;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.out.MemberApi;

/**
 * {@link MemberApi}를 회원 내부 API(/internal/v1/members·sellers)로 호출하는 구현.
 * 회원 서비스가 분리되기 전까지는 루트 모놀리스가 이 API를 제공한다. 분리되면 custom.member.baseUrl만 바꾼다.
 *
 * 응답은 RsData로 오고, data를 꺼내 돌려준다.
 * - 회원 서비스가 오류로 응답하면(예: 403-SELLER_NOT_APPROVED) 같은 resultCode·메시지로 DomainException을 던진다.
 *   사용자에게도 회원 서비스가 정한 오류가 그대로 전달된다
 * - 응답을 받지 못하거나(접속 실패·응답 대기 시간 초과) 오류 응답을 해석할 수 없으면 502 EXTERNAL_SERVICE_ERROR
 * 결과가 없을 수 있는 조회(Optional)는 회원 서비스가 200 + data null로 응답한다.
 */
@Component
public class MemberApiClient implements MemberApi {

    private final RestClient restClient;

    @Autowired
    public MemberApiClient(
            @Value("${custom.member.baseUrl}") String baseUrl,
            @Value("${custom.member.connectTimeoutMillis}") long connectTimeoutMillis,
            @Value("${custom.member.readTimeoutMillis}") long readTimeoutMillis
    ) {
        this(RestClient.builder().requestFactory(requestFactory(connectTimeoutMillis, readTimeoutMillis)), baseUrl);
    }

    /** 테스트에서 MockRestServiceServer를 연결한 builder를 넘길 수 있게 열어 둔다 */
    MemberApiClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public MemberDto getMember(Long memberId) {
        return call(() -> restClient.get()
                .uri("/internal/v1/members/{memberId}", memberId)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<MemberDto>>() {}));
    }

    @Override
    public void checkActiveMember(Long memberId) {
        call(() -> restClient.get()
                .uri("/internal/v1/members/{memberId}/active", memberId)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<Void>>() {}));
    }

    @Override
    public List<MemberDto> getMembers(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) return List.of();
        return call(() -> restClient.get()
                .uri(uri -> uri.path("/internal/v1/members").queryParam("ids", memberIds).build())
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<List<MemberDto>>>() {}));
    }

    @Override
    public Optional<MemberDto> findMemberByEmail(String email) {
        return Optional.ofNullable(call(() -> restClient.post()
                .uri("/internal/v1/members/search-by-email")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmailSearchRequest(email))
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<MemberDto>>() {})));
    }

    @Override
    public Optional<SellerDto> findSellerByMemberId(Long memberId) {
        return Optional.ofNullable(call(() -> restClient.get()
                .uri("/internal/v1/sellers/by-member/{memberId}", memberId)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<SellerDto>>() {})));
    }

    @Override
    public SellerDto getApprovedSellerByMemberId(Long memberId) {
        return call(() -> restClient.get()
                .uri("/internal/v1/sellers/by-member/{memberId}/approved", memberId)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<SellerDto>>() {}));
    }

    @Override
    public SellerDto getSeller(Long sellerId) {
        return call(() -> restClient.get()
                .uri("/internal/v1/sellers/{sellerId}", sellerId)
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<SellerDto>>() {}));
    }

    @Override
    public List<SellerDto> getSellers(Collection<Long> sellerIds) {
        if (sellerIds.isEmpty()) return List.of();
        return call(() -> restClient.get()
                .uri(uri -> uri.path("/internal/v1/sellers").queryParam("ids", sellerIds).build())
                .retrieve()
                .body(new ParameterizedTypeReference<RsData<List<SellerDto>>>() {}));
    }

    /**
     * 요청을 실행하고 RsData의 data를 꺼낸다. Spring HTTP 예외는 DomainException으로 바꾼다
     */
    private <T> T call(Supplier<RsData<T>> request) {
        try {
            RsData<T> response = request.get();
            return response == null ? null : response.data();
        } catch (RestClientResponseException e) {
            throw toDomainException(e);
        } catch (ResourceAccessException e) {
            // 응답을 받지 못함 (회원 서비스가 꺼져 있음·접속 실패·응답 대기 시간 초과)
            throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
        }
    }

    /**
     * 회원 서비스의 오류 응답(RsData)을 같은 resultCode·메시지의 DomainException으로 바꾼다.
     * 본문이 RsData 형식이 아니면(예: 장애로 HTML 페이지가 옴) 502로 본다
     */
    private static DomainException toDomainException(RestClientResponseException e) {
        try {
            RsData<?> error = e.getResponseBodyAs(RsData.class);
            if (error != null && error.resultCode() != null && error.resultCode().contains("-")) {
                String[] parts = error.resultCode().split("-", 2);
                return new DomainException(new RemoteErrorCode(Integer.parseInt(parts[0]), parts[1], error.msg()), e);
            }
        } catch (RuntimeException ignored) {
            // 본문이 RsData 형식이 아니면 아래 기본값 사용
        }
        return new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
    }

    /**
     * 실제 HTTP 연결을 담당하는 부품
     * - connectTimeout: 회원 서비스에 접속하는 데 걸리는 제한 시간
     * - readTimeout: 요청 후 응답을 기다리는 제한 시간. 사용자 요청 안에서 부르므로 짧게 둔다
     */
    private static SimpleClientHttpRequestFactory requestFactory(long connectTimeoutMillis, long readTimeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMillis));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        return factory;
    }

    /** 회원 서비스가 돌려준 오류 코드. resultCode가 "403-SELLER_NOT_APPROVED"면 status 403, name SELLER_NOT_APPROVED */
    private record RemoteErrorCode(int status, String name, String msg) implements ErrorCode {
    }

    private record EmailSearchRequest(String email) {
    }
}

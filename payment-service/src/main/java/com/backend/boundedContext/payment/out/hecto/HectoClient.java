package com.backend.boundedContext.payment.out.hecto;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 헥토파이낸셜 신용카드 API 클라이언트 (인증 분리 승인·취소·조회). 호출은 DB 트랜잭션 밖에서 진행
 * - 헥토는 성공·실패 모두 HTTP 200에 outStatCd(0021 성공, 0031 실패)로 알려 줌 → 실패 응답도 HectoResponse로 돌려주고, 판단은 어댑터가 함
 * - 응답을 받지 못했거나 믿을 수 없으면 HectoResultUnknownException (처리됐는지 알 수 없음)
 *
 * 요청은 params(공통 값)와 data(거래 값) 두 덩어리의 JSON이며, 금액은 암호화하고 위변조 확인용 해시(pktHash)를 붙임
 * 요청 일시(trdDt·trdTm)는 헥토가 해시 검증에 쓰므로 호출하는 쪽이 넘긴 시각을 그대로 씀
 */
@Component
public class HectoClient {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    // 헥토 문서의 고정값
    private static final String VERSION = "0A19";           // 전문 버전 (승인·취소)
    private static final String INQUIRY_VERSION = "0A1M";   // 전문 버전 (조회)
    private static final String METHOD_CARD = "CA";         // 결제수단: 신용카드
    private static final String BIZ_TYPE_APPROVE = "B2";    // 업무 구분: 승인
    private static final String BIZ_TYPE_CANCEL = "C0";     // 업무 구분: 취소
    private static final String ENC_CD = "23";              // 암호화 구분 코드
    private static final String CURRENCY_KRW = "KRW";       // 통화: 국내결제

    // 문서상 필수는 아니지만 테스트베드에서 보내 확인한 값. 서버에서 호출하므로 N(PC 또는 그 외)
    private static final String MOBILE_N = "N";

    // 우리가 정한 값: 조회 요청 자체에도 상점 주문번호가 필요해서, 충전 주문번호와 겹치지 않게 앞에 붙임
    private static final String INQUIRY_ORDER_ID_PREFIX = "INQ-";

    // 응답 JSON을 읽는 용도. 응답에 우리가 쓰지 않는 필드가 있어도 읽음
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final RestClient restClient;
    private final RestClient lookupRestClient;  // 조회 전용. 응답 대기 시간만 짧음 (토스 클라이언트와 같은 이유)
    private final HectoCrypto crypto;
    private final String mchtId;

    // restClientBuilder: 스프링이 넣어 주는 builder(헥토 호출 기록·추적이 붙음). RestClient.builder()로 직접 만들면 빠진다
    // 승인·취소와 조회는 응답 대기 시간이 달라 복사본(clone)을 하나 더 만듦 (토스 클라이언트와 같음)
    @Autowired
    public HectoClient(
            RestClient.Builder restClientBuilder,
            @Value("${custom.hecto.apiBaseUrl}") String baseUrl,
            @Value("${custom.hecto.mchtId}") String mchtId,
            @Value("${custom.hecto.connectTimeoutSeconds}") long connectTimeoutSeconds,
            @Value("${custom.hecto.readTimeoutSeconds}") long readTimeoutSeconds,
            @Value("${custom.hecto.lookupReadTimeoutSeconds}") long lookupReadTimeoutSeconds,
            HectoCrypto crypto
    ) {
        this(restClientBuilder.clone().requestFactory(requestFactory(Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(readTimeoutSeconds))),
                restClientBuilder.requestFactory(requestFactory(Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(lookupReadTimeoutSeconds))),
                baseUrl, mchtId, crypto);
    }

    // 테스트에서 MockRestServiceServer를 연결한 builder를 넘길 수 있게 열어 둠
    HectoClient(RestClient.Builder builder, String baseUrl, String mchtId, HectoCrypto crypto) {
        this(builder, builder, baseUrl, mchtId, crypto);
    }

    // 승인·취소용과 조회용 builder를 따로 받음. 테스트에서 응답 대기 시간을 다르게 한 실제 연결을 넘길 수 있게 열어 둠
    HectoClient(RestClient.Builder builder, RestClient.Builder lookupBuilder, String baseUrl, String mchtId, HectoCrypto crypto) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.lookupRestClient = lookupBuilder.baseUrl(baseUrl).build();
        this.mchtId = mchtId;
        this.crypto = crypto;
    }

    /**
     * 인증 분리 승인. 결제창 인증(0061)을 마친 거래를 인증 거래번호로 승인하며, 성공하면 실제로 카드 결제가 이뤄짐
     * 성공 응답은 해시를 검증해, 맞지 않으면 승인됐는지 알 수 없는 것으로 봄
     */
    public HectoResponse approve(String orderId, String authTrdNo, long amount, LocalDateTime requestAt) {
        String trdDt = requestAt.format(DATE);
        String trdTm = requestAt.format(TIME);
        String amountText = String.valueOf(amount);

        Map<String, String> params = Map.of(
                "mchtId", mchtId, "ver", VERSION, "method", METHOD_CARD, "bizType", BIZ_TYPE_APPROVE, "encCd", ENC_CD,
                "mchtTrdNo", orderId, "trdDt", trdDt, "trdTm", trdTm, "mobileYn", MOBILE_N);
        Map<String, String> data = Map.of(
                "pktHash", crypto.hash(trdDt, trdTm, mchtId, orderId, amountText),
                "trdAmt", crypto.encryptAmount(amount),
                "authTrdNo", authTrdNo);

        HectoEnvelope response = post(restClient, "/spay/APIService.do", params, data);
        Long approvedAmount = response.isSuccess() ? decrypt(response.data("trdAmt")) : null;

        if (response.isSuccess()) verifyApproveHash(response, approvedAmount);

        return response.toResponse(approvedAmount, null, null);
    }

    /**
     * 부분 취소. 취소 회차(cnclOrd)는 001부터 회차마다 달라야 하며, 성공하면 취소 후 남은 금액(blcAmt)을 알려 줌
     * 주의: 이미 성공한 회차를 다시 보내면 또 취소됨 (멱등하지 않음, 테스트베드 확인). 다시 요청하기 전에 조회로 취소 합계를 확인해야 함
     */
    public HectoResponse cancel(String orderId, String orgTrdNo, int cancelOrder, long cancelAmount, LocalDateTime requestAt) {
        String trdDt = requestAt.format(DATE);
        String trdTm = requestAt.format(TIME);

        Map<String, String> params = Map.of(
                "mchtId", mchtId, "ver", VERSION, "method", METHOD_CARD, "bizType", BIZ_TYPE_CANCEL, "encCd", ENC_CD,
                "mchtTrdNo", orderId, "trdDt", trdDt, "trdTm", trdTm, "mobileYn", MOBILE_N);
        Map<String, String> data = Map.of(
                "pktHash", crypto.hash(trdDt, trdTm, mchtId, orderId, String.valueOf(cancelAmount)),
                "orgTrdNo", orgTrdNo,
                "crcCd", CURRENCY_KRW,
                "cnclOrd", "%03d".formatted(cancelOrder),
                "cnclAmt", crypto.encryptAmount(cancelAmount));

        HectoEnvelope response = post(restClient, "/spay/APICancel.do", params, data);
        if (!response.isSuccess()) return response.toResponse(null, null, null);

        return response.toResponse(null, decrypt(response.data("cnclAmt")), decrypt(response.data("blcAmt")));
    }

    /**
     * 거래 조회. 충전 주문번호·원거래 일자(결제창 요청 일자)·금액으로 찾으며, 금액은 평문으로 주고받음
     * 결제창에서 인증하지 않은 주문은 실패(ST06 거래 없음)
     * - orgTrdNo: 헥토 거래번호. 있으면 같이 보내고, 없어도 조회됨
     */
    public HectoResponse inquiry(String orderId, String orgTrdNo, long amount, LocalDateTime requestedAt, LocalDateTime requestAt) {
        String trdDt = requestAt.format(DATE);
        String trdTm = requestAt.format(TIME);
        String inquiryOrderId = INQUIRY_ORDER_ID_PREFIX + UUID.randomUUID();
        String amountText = String.valueOf(amount);

        Map<String, String> params = Map.of(
                "mchtId", mchtId, "ver", INQUIRY_VERSION, "mchtTrdNo", inquiryOrderId, "trdDt", trdDt, "trdTm", trdTm);
        Map<String, String> data = Map.of(
                "pktHash", crypto.hash(trdDt, trdTm, mchtId, inquiryOrderId, amountText),
                "method", METHOD_CARD,
                "trdAmt", amountText,
                "orgTrdDt", requestedAt.format(DATE),
                "orgMchtTrdNo", orderId,
                "orgTrdNo", orgTrdNo != null ? orgTrdNo : "");

        // 조회는 응답 대기 시간이 짧은 조회 전용 RestClient를 씀
        HectoEnvelope response = post(lookupRestClient, "/spay/APITrdcheck.do", params, data);
        if (!response.isSuccess()) return response.toResponse(null, null, null);

        return response.toResponse(parse(response.data("trdAmt")), parse(response.data("cnclAmt")), null);
    }

    // 요청 실행. 응답을 못 받았거나 HTTP 오류·해석할 수 없는 응답이면 처리됐는지 알 수 없음
    // 헥토는 JSON 본문을 text/plain·*/* 같은 Content-Type으로 보내서(테스트베드 확인), 문자열로 받아 직접 JSON으로 읽음
    private HectoEnvelope post(RestClient client, String uri, Map<String, String> params, Map<String, String> data) {
        HectoEnvelope response;
        try {
            String body = client.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("params", params, "data", data))
                    .retrieve()
                    .body(String.class);
            response = body != null ? JSON.readValue(body, HectoEnvelope.class) : null;
        } catch (RestClientException | JacksonException e) {
            throw new HectoResultUnknownException(e);
        }
        if (response == null || response.params() == null || response.param("outStatCd") == null) {
            throw new HectoResultUnknownException("헥토 응답을 해석할 수 없습니다. uri=" + uri);
        }
        return response;
    }

    // 승인 응답 해시 = SHA-256(outStatCd + trdDt + trdTm + mchtId + mchtTrdNo + 승인 금액 + 해시 키). 응답이 헥토에서 온 그대로인지 확인
    private void verifyApproveHash(HectoEnvelope response, Long approvedAmount) {
        if (approvedAmount == null) {
            throw new HectoResultUnknownException("헥토 승인 응답에 금액이 없습니다. mchtTrdNo=" + response.param("mchtTrdNo"));
        }
        String expected = crypto.hash(response.param("outStatCd"), response.param("trdDt"), response.param("trdTm"),
                response.param("mchtId"), response.param("mchtTrdNo"), String.valueOf(approvedAmount));

        if (!expected.equalsIgnoreCase(String.valueOf(response.data("pktHash")))) {
            throw new HectoResultUnknownException("헥토 승인 응답의 해시가 맞지 않습니다. mchtTrdNo=" + response.param("mchtTrdNo"));
        }
    }

    // 성공 응답의 암호화 금액을 풀지 못하면 결과를 믿을 수 없어 알 수 없음으로 봄
    private Long decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return null;

        try {
            return crypto.decryptAmount(encrypted);
        } catch (RuntimeException e) {
            throw new HectoResultUnknownException(e);
        }
    }

    private static Long parse(String amount) {
        if (amount == null || amount.isBlank()) return null;

        try {
            return Long.parseLong(amount);
        } catch (NumberFormatException e) {
            throw new HectoResultUnknownException(e);
        }
    }

    // 토스 클라이언트와 같은 연결 설정 (HTTP/1.1, 접속·응답 대기 제한 시간)
    static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    // 헥토 응답 원문 ({"params": {...}, "data": {...}}). 값은 모두 문자열
    record HectoEnvelope(Map<String, String> params, Map<String, String> data) {

        String param(String name) {
            return params.get(name);
        }

        String data(String name) {
            return data != null ? data.get(name) : null;
        }

        boolean isSuccess() {
            return HectoResponse.SUCCESS.equals(param("outStatCd"));
        }

        HectoResponse toResponse(Long amount, Long canceledAmount, Long balanceAmount) {
            return new HectoResponse(param("outStatCd"), param("outRsltCd"), param("outRsltMsg"), param("mchtTrdNo"), param("trdNo"),
                    transactedAt(), amount, canceledAmount, balanceAmount);
        }

        private LocalDateTime transactedAt() {
            String trdDt = param("trdDt");
            String trdTm = param("trdTm");

            if (trdDt == null || trdTm == null) return null;

            try {
                return LocalDateTime.of(LocalDate.parse(trdDt, DATE), LocalTime.parse(trdTm, TIME));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}

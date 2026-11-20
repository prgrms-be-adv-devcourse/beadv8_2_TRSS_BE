package com.backend.boundedContext.payment.out.hecto;

import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.out.pg.PgCheckout;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;

/**
 * 헥토 신용카드 결제창 요청 값을 만듦. 금액 암호화·해시에 키가 필요해서 서버에서 만들고, 프론트는 받은 값 그대로 결제창에 보냄
 * 요청 일시(trdDt·trdTm)는 충전 건의 결제 요청 시각을 씀. 헥토 조회의 원거래 일자가 이 값과 같아야 조회됨
 *
 * 결제창 결과는 브라우저가 우리 서버로 POST함
 * - nextUrl: 인증을 마치면 (outStatCd 0061)
 * - cancUrl: 결제창을 닫거나 취소하면
 * - notiUrl: 헥토가 서버로 결과를 알림 (필수 값)
 */
@Component
public class HectoCheckout {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    // 헥토 문서의 고정값: 결제창 결제수단 신용카드 (승인·취소 API의 CA와 표기가 다름)
    private static final String METHOD_CARD = "card";

    // 결제창에 보이는 상점·상품 이름
    private static final String MERCHANT_NAME = "팔레트";
    private static final String MERCHANT_ENGLISH_NAME = "Palette";
    private static final String PRODUCT_NAME = "예치금 충전";

    // 결제창 결과를 받는 우리 서버 경로 (ApiV1HectoTopupController)
    static final String NEXT_PATH = "/api/v1/topups/hecto/next";
    static final String CANCEL_PATH = "/api/v1/topups/hecto/canc";
    static final String NOTI_PATH = "/api/v1/topups/hecto/noti";

    private final HectoCrypto crypto;
    private final String mchtId;
    private final String windowUrl;
    private final String callbackBaseUrl;

    public HectoCheckout(
            HectoCrypto crypto,
            @Value("${custom.hecto.mchtId}") String mchtId,
            @Value("${custom.hecto.windowUrl}") String windowUrl,
            @Value("${custom.hecto.callbackBaseUrl}") String callbackBaseUrl
    ) {
        this.crypto = crypto;
        this.mchtId = mchtId;
        this.windowUrl = windowUrl;
        this.callbackBaseUrl = callbackBaseUrl;
    }

    // 결제창 해시 = SHA-256(mchtId + method + mchtTrdNo + trdDt + trdTm + 금액 + 해시 키)
    public PgCheckout create(PgPaymentRef payment) {
        String trdDt = payment.requestedAt().format(DATE);
        String trdTm = payment.requestedAt().format(TIME);
        String amountText = String.valueOf(payment.amount());
        Map<String, String> params = Map.ofEntries(
                Map.entry("mchtId", mchtId),
                Map.entry("method", METHOD_CARD),
                Map.entry("trdDt", trdDt),
                Map.entry("trdTm", trdTm),
                Map.entry("mchtTrdNo", payment.orderId()),
                Map.entry("mchtName", MERCHANT_NAME),
                Map.entry("mchtEName", MERCHANT_ENGLISH_NAME),
                Map.entry("pmtPrdtNm", PRODUCT_NAME),
                Map.entry("trdAmt", crypto.encryptAmount(payment.amount())),
                Map.entry("notiUrl", callbackBaseUrl + NOTI_PATH),
                Map.entry("nextUrl", callbackBaseUrl + NEXT_PATH),
                Map.entry("cancUrl", callbackBaseUrl + CANCEL_PATH),
                Map.entry("pktHash", crypto.hash(mchtId, METHOD_CARD, payment.orderId(), trdDt, trdTm, amountText)));
        return new PgCheckout(windowUrl, params);
    }
}

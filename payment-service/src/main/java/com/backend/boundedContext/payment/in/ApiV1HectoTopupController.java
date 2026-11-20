package com.backend.boundedContext.payment.in;

import java.net.URI;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.backend.boundedContext.payment.out.hecto.HectoCrypto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 헥토 결제창 결과를 받는 엔드포인트. 프론트가 아니라 브라우저(결제창)·헥토 서버가 토큰 없이 호출함
 * 헥토는 결제창 결과를 브라우저 POST(nextUrl·cancUrl)로 보내서, 서버가 받아 프론트 충전 결과 화면으로 넘김(303)
 * 넘긴 뒤에는 토스와 같이 프론트가 승인·실패 기록 API를 호출하므로, 여기서는 충전 건을 바꾸지 않음
 * (결과 값은 브라우저를 거쳐 와서 믿지 않음. 승인 API가 회원·주문번호·금액을 확인하고 헥토 승인 응답으로 확정함)
 */
@Slf4j
@Tag(name = "충전 (헥토 결제창 결과)", description = "헥토 결제창·헥토 서버가 호출. 프론트에서 직접 호출하지 않음")
@RestController
@RequestMapping("/api/v1/topups/hecto")
public class ApiV1HectoTopupController {

    // 결제창 인증 완료 상태. 이 상태일 때만 승인을 요청할 수 있음
    private static final String AUTHENTICATED = "0061";

    // 결제창을 닫거나 취소했을 때 프론트에 넘기는 실패 코드 (헥토 어댑터가 사용자 취소로 판단하는 코드)
    private static final String USER_CANCEL = "USER_CANCEL";
    private static final String USER_CANCEL_MESSAGE = "사용자가 결제를 취소했습니다.";

    // 결과 금액을 풀지 못했을 때 프론트에 넘기는 실패 코드
    private static final String INVALID_RESULT = "HECTO_INVALID_RESULT";

    private final HectoCrypto hectoCrypto;
    private final String frontResultUrl;

    public ApiV1HectoTopupController(
            HectoCrypto hectoCrypto,
            @Value("${custom.hecto.frontResultUrl}") String frontResultUrl
    ) {
        this.hectoCrypto = hectoCrypto;
        this.frontResultUrl = frontResultUrl;
    }

    /**
     * 결제창 인증 결과(nextUrl). 인증 완료(0061)면 프론트에 승인 API 값(paymentKey=헥토 거래번호, orderId, amount)을 넘김
     * 그 외 상태면 실패 코드·메시지를 넘겨 프론트가 실패 기록 API를 호출하게 함
     */
    @Operation(summary = "헥토 결제창 인증 결과 (nextUrl)", description = "인증 완료면 프론트 충전 결과 화면으로 승인 값을, 아니면 실패 코드를 넘긴다(303)")
    @PostMapping("/next")
    public ResponseEntity<Void> next(@RequestParam Map<String, String> result) {
        String orderId = result.get("mchtTrdNo");

        if (!AUTHENTICATED.equals(result.get("outStatCd"))) {
            log.info("헥토 결제창 인증이 완료되지 않았습니다. orderId={}, outStatCd={}, outRsltCd={}", orderId, result.get("outStatCd"), result.get("outRsltCd"));
            return redirect("orderId", nullToEmpty(orderId), "code", nullToEmpty(result.get("outRsltCd")), "message", nullToEmpty(result.get("outRsltMsg")));
        }

        long amount;
        try {
            amount = hectoCrypto.decryptAmount(result.get("trdAmt"));
        } catch (RuntimeException e) {
            log.warn("헥토 결제창 결과의 금액을 풀지 못했습니다. orderId={}", orderId, e);
            return redirect("orderId", nullToEmpty(orderId), "code", INVALID_RESULT, "message", "결제 결과를 확인하지 못했습니다.");
        }
        return redirect("paymentKey", nullToEmpty(result.get("trdNo")), "orderId", nullToEmpty(orderId), "amount", String.valueOf(amount));
    }

    /**
     * 결제창 닫기·취소(cancUrl). 사용자 취소 코드로 프론트에 넘김
     * 충전 ID는 프론트가 결제창을 열기 전에 저장해 둔 값을 씀 (토스 사용자 취소와 같음)
     */
    @Operation(summary = "헥토 결제창 취소 (cancUrl)", description = "프론트 충전 결과 화면으로 USER_CANCEL 실패 코드를 넘긴다(303)")
    @PostMapping("/canc")
    public ResponseEntity<Void> cancel(@RequestParam Map<String, String> result) {
        String orderId = result.get("mchtTrdNo");
        log.info("헥토 결제창이 취소됐습니다. orderId={}", orderId);
        return redirect("orderId", nullToEmpty(orderId), "code", USER_CANCEL, "message", USER_CANCEL_MESSAGE);
    }

    /**
     * 결과 알림(notiUrl). 헥토 필수 값이라 받고 OK로 응답함
     * 승인은 승인 API 응답으로 확정하고, 결과를 모르면 대사가 조회로 확정하므로 알림은 기록만 함
     */
    @Operation(summary = "헥토 결과 알림 (notiUrl)", description = "헥토 서버가 호출한다. 기록만 하고 OK로 응답한다")
    @PostMapping(value = "/noti", produces = MediaType.TEXT_PLAIN_VALUE)
    public String noti(@RequestParam Map<String, String> result) {
        log.info("헥토 결과 알림을 받았습니다. orderId={}, trdNo={}, outStatCd={}, outRsltCd={}",
                result.get("mchtTrdNo"), result.get("trdNo"), result.get("outStatCd"), result.get("outRsltCd"));
        return "OK";
    }

    // 프론트 충전 결과 화면으로 303 (POST로 받은 요청을 GET으로 넘김). 이름·값을 번갈아 받아 순서대로 붙이고, 빈 값은 붙이지 않음
    private ResponseEntity<Void> redirect(String... nameValues) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(frontResultUrl);
        for (int i = 0; i < nameValues.length; i += 2) {
            if (!nameValues[i + 1].isEmpty()) builder.queryParam(nameValues[i], nameValues[i + 1]);
        }
        URI location = builder.encode().build().toUri();
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(location).build();
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}

package com.backend.boundedContext.payment.in;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.app.dto.TopupCancelDto;
import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.app.dto.TopupDto;
import com.backend.boundedContext.payment.app.dto.TopupFailDto;
import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.in.dto.TopupConfirmRequest;
import com.backend.boundedContext.payment.in.dto.TopupFailRequest;
import com.backend.boundedContext.payment.in.dto.TopupPrepareRequest;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "충전", description = "예치금 충전")
@RestController
@RequestMapping("/api/v1/topups")
@RequiredArgsConstructor
public class ApiV1TopupController {

    private final PaymentFacade paymentFacade;

    @Operation(summary = "충전 준비", description = """
            충전 금액을 저장하고 주문번호(TOPUP-…)를 발급한다. 결제할 PG는 서버가 고른다: 토스가 기본이고, 토스가 장애(사용 불가)면 헥토.
            응답의 pgProvider에 따라 결제창을 띄운다.
            - TOSS: 응답의 orderId·amount로 토스 결제창을 띄운다 (checkout은 null)
            - HECTO: 응답의 checkout.params를 checkout.url 결제창에 그대로 보낸다 (금액 암호화·해시는 서버가 만듦)""")
    @ApiResponse(responseCode = "200", description = "200-1 충전 준비 완료")
    @ApiResponse(responseCode = "400", description = "400-TOPUP_AMOUNT_INVALID 충전 금액이 정책에 맞지 않음")
    @ApiResponse(responseCode = "502", description = "502-EXTERNAL_SERVICE_ERROR PG 키 설정 오류로 결제창 요청 값을 만들지 못함")
    @ApiResponse(responseCode = "503", description = "503-TOPUP_PG_UNAVAILABLE 토스·헥토 모두 장애라 충전할 수 없음")
    @PostMapping
    public RsData<TopupPrepareDto> prepareTopup(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody TopupPrepareRequest request
    ) {
        return RsData.of("200-1", "충전이 준비되었습니다.", paymentFacade.prepareTopup(authMember.memberId(), request.amount()));
    }

    @Operation(summary = "충전 승인", description = """
            결제창 인증 성공 후 호출한다. 금액을 검증하고 충전 건의 PG 승인 API를 호출해 예치금에 반영한다.
            - 토스: 성공 리다이렉트의 paymentKey·orderId·amount
            - 헥토: 서버가 결제창 결과를 받아 충전 결과 화면으로 넘긴 paymentKey(헥토 거래번호)·orderId·amount
            data.status가 DONE이면 반영 완료, PROCESSING이면 결과 확인 중(PG 응답 지연 등)이며 잠시 후 충전 내역에서 확인한다.
            같은 요청(같은 orderId·paymentKey·amount)을 다시 보내면 PG를 다시 부르지 않고 처음 결과를 돌려준다 (새로고침·중복 클릭 대비).
            DONE이면 data.balance는 지금 지갑 잔액이라 처음 응답과 다를 수 있다.""")
    @ApiResponse(responseCode = "200", description = "200-1 충전 완료(data.status: DONE) 또는 결과 확인 중(data.status: PROCESSING)")
    @ApiResponse(responseCode = "400", description = "400-AMOUNT_MISMATCH 준비 금액과 다름 / 400-TOPUP_PAYMENT_DECLINED 카드 거절 등(data: PG 오류 코드·메시지) / 400-TOPUP_METHOD_NOT_ALLOWED 충전에 쓸 수 없는 결제 수단(예치금에 반영하지 않고 결제를 자동 취소)")
    @ApiResponse(responseCode = "404", description = "404-TOPUP_NOT_FOUND 충전 건 없음")
    @ApiResponse(responseCode = "409", description = "409-TOPUP_ALREADY_PROCESSED 결제키·금액이 처음 요청과 다름 / 실패·만료·취소된 충전 건 / 다른 충전 건에 쓰인 결제키")
    @ApiResponse(responseCode = "502", description = "502-EXTERNAL_SERVICE_ERROR PG 키 설정 오류 등")
    @PostMapping("/confirm")
    public RsData<TopupConfirmDto> confirmTopup(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody TopupConfirmRequest request
    ) {
        TopupConfirmDto result = paymentFacade.confirmTopup(authMember.memberId(), request.paymentKey(), request.orderId(), request.amount());
        String msg = result.status() == TopupStatus.DONE ? "충전이 완료되었습니다." : "충전 결과를 확인하고 있습니다.";
        return RsData.of("200-1", msg, result);
    }

    @Operation(summary = "충전 실패 기록", description = """
            결제창에서 실패·취소로 돌아왔을 때 호출한다. 돌아온 URL(또는 SDK 오류)의 code·message를 그대로 넘긴다.
            - 토스: 실패 리다이렉트, 또는 결제 요청(requestPayment)이 USER_CANCEL 오류로 끝났을 때
            - 헥토: 서버가 결제창 결과를 받아 충전 결과 화면으로 넘긴 code·message (결제창 닫기는 USER_CANCEL)
            사용자 취소(토스 PAY_PROCESS_CANCELED·USER_CANCEL, 헥토 USER_CANCEL)는 FAILED_USER_CANCEL, 그 외는 FAILED_PG_DECLINED로 기록한다.
            사용자 취소 시 orderId가 오지 않을 수 있으므로 충전 준비 응답의 topupId를 쓴다.""")
    @ApiResponse(responseCode = "200", description = "200-1 실패 기록 완료")
    @ApiResponse(responseCode = "404", description = "404-TOPUP_NOT_FOUND 충전 건 없음")
    @ApiResponse(responseCode = "409", description = "409-TOPUP_ALREADY_PROCESSED 결제창 진행 전(READY)이 아닌 충전 건")
    @PostMapping("/{topupId}/fail")
    public RsData<TopupFailDto> failTopup(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long topupId,
            @Valid @RequestBody TopupFailRequest request
    ) {
        return RsData.of("200-1", "충전 실패가 기록되었습니다.", paymentFacade.failTopup(authMember.memberId(), topupId, request.code(), request.message()));
    }

    @Operation(summary = "충전 취소", description = """
            충전 건에서 아직 쓰지 않은 금액(cancelableAmount) 전부를 결제한 PG로 취소한다. 일부를 쓴 충전 건은 남은 금액만 부분 취소된다.
            취소 금액은 요청 즉시 예치금 잔액에서 빠진다.
            data.status가 CANCELED(전부 취소)·DONE(일부 취소)이면 취소 완료, CANCELING이면 결과 확인 중이며 잠시 후 충전 내역에서 확인한다.
            결제 취소로 남은 금액이 다시 생기면 다시 취소할 수 있다.""")
    @ApiResponse(responseCode = "200", description = "200-1 취소 완료(data.status: CANCELED·DONE) 또는 결과 확인 중(data.status: CANCELING)")
    @ApiResponse(responseCode = "400", description = "400-TOPUP_CANCEL_DECLINED 취소 가능 기간 지남·부분 취소 불가 수단 등(data: PG 오류 코드·메시지). 잔액은 원래대로 돌아옴")
    @ApiResponse(responseCode = "404", description = "404-TOPUP_NOT_FOUND 충전 건이 없거나 남의 충전 건")
    @ApiResponse(responseCode = "409", description = "409-TOPUP_NOT_CANCELABLE 승인 완료(DONE)가 아니거나 취소할 남은 금액이 없음")
    @ApiResponse(responseCode = "502", description = "502-EXTERNAL_SERVICE_ERROR PG 인증 오류 등. 잔액은 원래대로 돌아옴")
    @PostMapping("/{topupId}/cancel")
    public RsData<TopupCancelDto> cancelTopup(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long topupId
    ) {
        TopupCancelDto result = paymentFacade.cancelTopup(authMember.memberId(), topupId);
        String msg = result.status() == TopupStatus.CANCELING ? "충전 취소 결과를 확인하고 있습니다." : "충전이 취소되었습니다.";
        return RsData.of("200-1", msg, result);
    }

    @Operation(summary = "내 충전 목록", description = "충전 준비 최신순. page(0부터)·size(기본 20, 최대 100), 정렬은 고정이라 sort는 무시한다")
    @GetMapping
    public RsData<PageResponse<TopupDto>> getTopups(
            @AuthenticationPrincipal AuthMember authMember,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "충전 목록을 조회했습니다.", paymentFacade.getTopups(authMember.memberId(), pageable));
    }

    @Operation(summary = "내 충전 건 상세", description = "승인 결과가 PROCESSING일 때 최종 상태를 다시 확인하거나, 새로고침·목록에서 진입할 때 사용한다")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "404", description = "404-TOPUP_NOT_FOUND 충전 건이 없거나 남의 충전 건")
    @GetMapping("/{topupId}")
    public RsData<TopupDto> getTopup(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long topupId
    ) {
        return RsData.of("200-1", "충전 내역을 조회했습니다.", paymentFacade.getTopup(authMember.memberId(), topupId));
    }
}

package com.backend.boundedContext.member.domain;

import com.backend.global.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum MemberErrorCode implements ErrorCode {

    // 회원 조회
    MEMBER_NOT_FOUND(404, "회원을 찾을 수 없습니다."),
    PASSWORD_MISMATCH(400, "비밀번호가 올바르지 않습니다."),          // 탈퇴 등 로그인 상태에서 본인 확인. 401은 프론트가 토큰 만료로 처리하므로 400
    WITHDRAWAL_NOT_ALLOWED(409, "탈퇴 조건을 충족하지 않습니다."),    // data.reasons에 남은 조건

    // 회원가입·이메일 인증
    EMAIL_DUPLICATED(409, "이미 가입된 이메일입니다."),
    CODE_INVALID(400, "인증코드가 올바르지 않습니다."),
    CODE_EXPIRED(400, "인증코드가 만료되었습니다. 인증코드를 다시 요청해 주세요."),
    CODE_ATTEMPTS_EXCEEDED(400, "인증 시도 횟수를 초과했습니다. 인증코드를 다시 요청해 주세요."),
    EMAIL_NOT_VERIFIED(400, "이메일 인증이 완료되지 않았습니다."),

    // 판매자
    SELLER_NOT_FOUND(404, "판매자 신청 내역이 없습니다."),
    SELLER_ALREADY_APPLIED(409, "이미 진행 중인 판매자 신청이 있습니다."),
    BUSINESS_NUMBER_DUPLICATED(409, "이미 다른 판매자가 사용 중인 사업자등록번호입니다."),
    SELLER_SANCTIONED(403, "제재된 판매자는 다시 신청할 수 없습니다."),
    SELLER_STATE_INVALID(409, "현재 판매자 상태에서는 처리할 수 없습니다."),
    SELLER_NOT_APPROVED(403, "승인된 판매자만 이용할 수 있습니다."),   // 판매자 기능은 APPROVED일 때만 가능하다 (정보 수정, 상품 등록 등에서 쓰인다)
    SELLER_ACCOUNT_INCOMPLETE(400, "정산 계좌는 은행·계좌번호·예금주를 함께 입력해야 합니다."),
    UNSETTLED_AMOUNT_EXISTS(409, "아직 지급되지 않은 정산 금액이 있어 철회할 수 없습니다."),     // 철회 요청·철회 승인
    ACTIVE_SALES_EXIST(409, "진행 중인 판매 주문이 있어 철회를 승인할 수 없습니다."),

    // 로그인
    LOGIN_FAILED(401, "이메일 또는 비밀번호가 올바르지 않습니다."),
    LOGIN_LOCKED(429, "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."),

    // 소셜 로그인
    KAKAO_AUTH_FAILED(400, "카카오 인증에 실패했습니다. 다시 시도해 주세요."),   // 인가 코드가 만료·재사용됐거나 redirect_uri가 다름
    SOCIAL_ACCOUNT_LINKED_TO_OTHER(409, "다른 회원에 이미 연결된 카카오 계정입니다."),
    SOCIAL_PROVIDER_ALREADY_LINKED(409, "이미 다른 카카오 계정이 연결돼 있습니다. 연결을 해제한 뒤 다시 연결해 주세요."),   // 같은 제공자 계정은 회원당 하나
    SOCIAL_NOT_LINKED(401, "연결된 계정이 없습니다. 이메일로 로그인한 뒤 마이페이지에서 카카오를 연결해 주세요.");   // 카카오로는 가입하지 않음(연결 전용)

    private final int status;
    private final String msg;
}

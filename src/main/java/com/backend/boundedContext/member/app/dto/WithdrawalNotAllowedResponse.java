package com.backend.boundedContext.member.app.dto;

import java.util.List;

import com.backend.boundedContext.member.domain.WithdrawalBlockReason;

// 409 WITHDRAWAL_NOT_ALLOWED 응답의 data: 탈퇴를 막는 이유
public record WithdrawalNotAllowedResponse(
        List<WithdrawalBlockReason> reasons
) {}

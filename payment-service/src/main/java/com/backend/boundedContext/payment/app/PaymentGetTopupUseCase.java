package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.TopupDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentGetTopupUseCase {

    private final TopupRepository topupRepository;

    // 내 충전 건 상세 (승인 결과가 PROCESSING일 때 재확인, 새로고침, 목록에서 진입할 때 사용)
    public TopupDto getTopup(Long memberId, Long topupId) {
        return topupRepository.findByIdAndMemberId(topupId, memberId)
                .map(TopupDto::from)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.TOPUP_NOT_FOUND));
    }
}

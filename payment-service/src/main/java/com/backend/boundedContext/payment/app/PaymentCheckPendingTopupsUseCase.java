package com.backend.boundedContext.payment.app;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.TopupRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentCheckPendingTopupsUseCase {

    // 결과가 아직 정해지지 않은 상태. READY는 결제창 진행 중, PROCESSING은 승인 중이거나 대사 스케줄러가 결과를 확인할 건
    private static final Set<TopupStatus> PENDING_STATUSES = EnumSet.of(TopupStatus.READY, TopupStatus.PROCESSING);

    private final TopupRepository topupRepository;

    // 이 회원에게 결과가 정해지지 않은 충전 건이 있는지 (있으면 나중에 예치금이 반영될 수 있음)
    public boolean hasPendingTopups(Long memberId) {
        return topupRepository.existsByMemberIdAndStatusIn(memberId, PENDING_STATUSES);
    }
}

package com.backend.boundedContext.payment.app;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupManualCheckReason;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.PaymentMemberPort;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.admin.dto.AdminFailureItem;
import com.backend.shared.admin.dto.AdminFailureTargetType;
import com.backend.shared.admin.dto.AdminFailureType;
import com.backend.shared.member.dto.MemberDto;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 실패 거래 화면의 충전 확인 지연(TOPUP_DELAYED) 행. 공통 형식(AdminFailureItem)으로 돌려줌
 * - 결과 확인 대기: PROCESSING·CANCELING·METHOD_CANCELING으로 결과 확인 대기 시간(topupReconcileDelay)보다 오래 남은 건
 *   오래 남은 건 경고 기준(topupStuckWarnAfter) 이하는 자동 확인 대기, 넘으면 확인 필요
 *   수동 확인으로 표시된 건은 대사가 더 보지 않으므로 시간과 관계없이 확인 필요이고, 원인 문구에 수동 확인 사유를 붙임
 * - 실패: 사람이 볼 실패(시스템 오류, 금액 불일치)만. 사용자 취소·만료·카드 거절·결제 수단 자동 취소는 정상 흐름이라 뺌
 * occurredAt은 지금 상태가 된 시각(updatedAt). 기간은 그 날짜로 거름(from, to 포함, 한국 시간)
 */
@Component
@RequiredArgsConstructor
public class PaymentGetTopupFailuresUseCase {

    static final Set<TopupStatus> PENDING_STATUSES = EnumSet.of(TopupStatus.PROCESSING, TopupStatus.CANCELING, TopupStatus.METHOD_CANCELING);
    static final Set<TopupStatus> FAILED_STATUSES = EnumSet.of(TopupStatus.FAILED_SYSTEM_ERROR, TopupStatus.FAILED_AMOUNT_MISMATCH);

    private static final LocalDateTime EARLIEST = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final LocalDateTime LATEST = LocalDateTime.of(9999, 1, 1, 0, 0);
    // 지금 상태가 된 시각 최신순 (같은 시각이면 ID 역순). 요청의 sort는 무시
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));

    private final TopupRepository topupRepository;
    private final PaymentMemberPort memberPort;
    private final PaymentPolicy paymentPolicy;

    public PageResponse<AdminFailureItem> getFailures(LocalDate from, LocalDate to, Pageable pageable, LocalDateTime now) {
        LocalDateTime fromAt = from == null ? EARLIEST : from.atStartOfDay();
        LocalDateTime toAt = to == null ? LATEST : to.plusDays(1).atStartOfDay();
        Pageable request = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST_FIRST);

        Page<Topup> topups = topupRepository.findDelayed(PENDING_STATUSES, now.minus(paymentPolicy.topupReconcileDelay()), FAILED_STATUSES, fromAt, toAt, request);

        // 회원 이메일은 페이지에 나온 충전 건의 회원만 모아 한 번에 조회
        Map<Long, String> emails = emailsOf(topups.getContent().stream().map(Topup::getMemberId).distinct().toList());
        LocalDateTime stuckCutoff = now.minus(paymentPolicy.topupStuckWarnAfter());
        
        return PageResponse.from(topups, topup -> toItem(topup, emails.get(topup.getMemberId()), stuckCutoff));
    }

    private Map<Long, String> emailsOf(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) return Map.of();
        return memberPort.getMembers(memberIds).stream()
                .filter(member -> member.email() != null)
                .collect(Collectors.toMap(MemberDto::id, MemberDto::email, (a, b) -> a));
    }

    // stuckCutoff 이전부터 결과 확인 대기로 남은 건은 대사가 오래 못 정한 것이라 확인 필요. 수동 확인으로 표시된 건도 확인 필요
    static AdminFailureItem toItem(Topup topup, String email, LocalDateTime stuckCutoff) {
        boolean autoRecheck = PENDING_STATUSES.contains(topup.getStatus()) && !topup.needsManualCheck() && !topup.getUpdatedAt().isBefore(stuckCutoff);
        return new AdminFailureItem(
                AdminFailureType.TOPUP_DELAYED,
                topup.getUpdatedAt(),
                AdminFailureTargetType.MEMBER,
                topup.getMemberId(),
                AdminFailureItem.maskEmail(email),
                topup.getAmount(),
                topup.getStatus().name(),
                reasonLabel(topup),
                autoRecheck ? "AUTO_RECHECK" : "CHECK_REQUIRED",
                autoRecheck ? "자동 확인 대기" : "확인 필요",
                topup.getId(),
                null);
    }

    // 실패는 원인이 여러 가지라(PG 키 설정 오류, 인증만 된 건 정리 등) 저장된 실패 사유를 붙임
    static String reasonLabel(Topup topup) {
        String label = switch (topup.getStatus()) {
            case PROCESSING -> "PG 승인 결과 확인 중";
            case CANCELING -> "PG 취소 결과 확인 중";
            case METHOD_CANCELING -> "허용하지 않은 결제 수단 취소 결과 확인 중";
            case FAILED_SYSTEM_ERROR -> "시스템 오류";
            case FAILED_AMOUNT_MISMATCH -> "금액 불일치";
            default -> topup.getStatus().name();
        };
        if (topup.needsManualCheck()) {
            return label + " (수동 확인: " + manualCheckLabel(topup.getManualCheckReason()) + ")";
        }
        boolean hasReason = topup.getStatus().isFailure() && topup.getFailReason() != null && !topup.getFailReason().isBlank();
        return hasReason ? label + " (" + topup.getFailReason() + ")" : label;
    }

    private static String manualCheckLabel(TopupManualCheckReason reason) {
        return switch (reason) {
            case CANCEL_AMOUNT_MISMATCH -> "PG 취소 금액이 기록과 다름";
            case PG_PAYMENT_NOT_FOUND -> "PG에 결제 없음";
            case METHOD_CANCEL_PARTIAL -> "일부만 취소됨";
            case METHOD_CANCEL_RETRY_EXHAUSTED -> "PG 취소 재시도 모두 실패";
        };
    }
}

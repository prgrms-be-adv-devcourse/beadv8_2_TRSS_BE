package com.backend.boundedContext.member.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.shared.member.dto.SellerStatus;

// 관리자 판매자 상세. 계좌번호는 관리자에게도 끝 4자리만 보여 준다
public record AdminSellerDetailDto(
        Long sellerId,
        Long memberId,
        String email,
        String name,
        SellerStatus status,
        String businessName,
        String businessNumber,          // 반려·본인 철회 뒤에는 비어 있다 (이력 스냅샷에 남음)
        String representativeName,
        String phoneNumber,
        String bankName,
        String accountNumber,
        String accountHolder,
        LocalDateTime appliedAt,
        List<History> histories         // 오래된 순
) {
    // 상태 이력 한 줄. 스냅샷은 반려·본인 철회로 바뀐 줄에만 있다
    public record History(
            SellerStatus fromStatus,    // 처음 신청이면 null
            SellerStatus toStatus,
            String reason,
            LocalDateTime changedAt,
            String businessName,
            String businessNumber,
            String representativeName,
            String accountLast4
    ) {
        static History from(SellerStatusHistory history) {
            return new History(
                    history.getFromStatus(),
                    history.getToStatus(),
                    history.getReason(),
                    history.getChangedAt(),
                    history.getBusinessName(),
                    history.getBusinessNumber(),
                    history.getRepresentativeName(),
                    history.getAccountLast4());
        }
    }

    public static AdminSellerDetailDto of(Seller seller, List<SellerStatusHistory> histories) {
        Member member = seller.getMember();

        return new AdminSellerDetailDto(
                seller.getId(),
                member.getId(),
                member.getEmail(),
                member.getName(),
                seller.getStatus(),
                seller.getBusinessName(),
                seller.getBusinessNumber(),
                seller.getRepresentativeName(),
                seller.getPhoneNumber(),
                seller.getBankName(),
                seller.getMaskedAccountNumber(),
                seller.getAccountHolder(),
                seller.getAppliedAt(),
                histories.stream().map(History::from).toList()
        );
    }
}

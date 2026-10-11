package com.backend.boundedContext.member.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "seller")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seller extends BaseIdAndTime {
    public static final String UQ_ONGOING_MEMBER = "uq_seller_ongoing_member";
    public static final String UQ_ONGOING_BUSINESS_NUMBER = "uq_seller_ongoing_business_number";

    // 진행 중인 신청 상태(심사 중·판매 중·철회 심사 중). 반려·철회 완료는 끝난 신청이다
    // 중복 검사 조회와 부분 유니크 인덱스 조건이 모두 이 목록을 사용한다
    public static final List<SellerStatus> ONGOING_STATUSES =
            List.of(SellerStatus.PENDING, SellerStatus.APPROVED, SellerStatus.WITHDRAW_REQUESTED);

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false, length = 100)
    private String businessName;

    // 숫자만 저장(10자리)
    @Column(nullable = false, length = 20)
    private String businessNumber;

    @Column(nullable = false, length = 50)
    private String representativeName;

    // 숫자만 저장
    @Column(nullable = false, length = 20)
    private String phoneNumber;

    @Column(nullable = false, length = 30)
    private String bankName;

    @Convert(converter = AccountNumberConverter.class)
    @Column(nullable = false)
    private String accountNumber;

    @Column(nullable = false, length = 50)
    private String accountHolder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SellerStatus status;

    private String rejectedReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private WithdrawType withdrawType;

    private String withdrawReason;

    @Column(nullable = false)
    private LocalDateTime appliedAt;

    private LocalDateTime approvedAt;

    private LocalDateTime withdrawRequestedAt;

    private LocalDateTime withdrawnAt;

    public static Seller apply(Member member, String businessName, String businessNumber,
                               String representativeName, String phoneNumber,
                               String bankName, String accountNumber, String accountHolder) {
        Seller seller = new Seller();
        seller.member = member;
        seller.businessName = businessName.strip();
        seller.businessNumber = digitsOnly(businessNumber);
        seller.representativeName = representativeName.strip();
        seller.phoneNumber = digitsOnly(phoneNumber);
        seller.bankName = bankName.strip();
        seller.accountNumber = digitsOnly(accountNumber);
        seller.accountHolder = accountHolder.strip();
        seller.status = SellerStatus.PENDING;
        seller.appliedAt = LocalDateTime.now();
        return seller;
    }

    public SellerDto toDto() {
        return new SellerDto(getId(), member.getId(), businessName, status);
    }

    // 계좌번호는 끝 4자리만 보여 준다 (예: **********3456)
    public String getMaskedAccountNumber() {
        int visible = Math.min(4, accountNumber.length());
        return "*".repeat(accountNumber.length() - visible) + accountNumber.substring(accountNumber.length() - visible);
    }

    public static String digitsOnly(String value) {
        return value.replaceAll("\\D", "");
    }
}

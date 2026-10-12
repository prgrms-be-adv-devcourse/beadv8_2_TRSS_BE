package com.backend.boundedContext.member.domain;

import com.backend.global.exception.DomainException;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 회원의 판매자 자격. 회원 1 : 판매자 1이고, 지금 상태만 담는다.
 * 상태가 바뀔 때마다 {@link SellerStatusHistory}를 한 줄 만들어 돌려주고, 저장은 호출한 쪽이 한다.
 */
@Entity
@Table(name = "seller")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seller extends BaseIdAndTime {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private Member member;

    @Column(nullable = false, length = 100)
    private String businessName;

    // 숫자만 저장(10자리). 반려·본인 철회 때 비워서 다른 회원이 쓸 수 있게 한다 (비운 번호는 이력 스냅샷에 남음)
    // 제재는 비우지 않아서 같은 번호로 다시 신청하면 UNIQUE에 막힌다
    @Column(unique = true, length = 20)
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

    // 가장 최근 신청 시각 (재신청하면 갱신)
    @Column(nullable = false)
    private LocalDateTime appliedAt;

    // 같은 행을 동시에 바꾸면 늦게 커밋한 쪽이 실패한다 (예: 철회 요청과 제재가 동시에 들어온 경우)
    @Version
    private Long version;

    public static Seller apply(Member member, String businessName, String businessNumber,
                               String representativeName, String phoneNumber,
                               String bankName, String accountNumber, String accountHolder) {
        Seller seller = new Seller();
        seller.member = member;
        seller.fillApplication(businessName, businessNumber, representativeName, phoneNumber,
                bankName, accountNumber, accountHolder);
        return seller;
    }

    // 반려·본인 철회 뒤 재신청: 같은 행의 신청 정보를 새 값으로 바꾸고 다시 PENDING
    public SellerStatusHistory reapply(String businessName, String businessNumber,
                                       String representativeName, String phoneNumber,
                                       String bankName, String accountNumber, String accountHolder) {
        if (!canReapply()) throw stateInvalid();

        SellerStatus fromStatus = status;
        fillApplication(businessName, businessNumber, representativeName, phoneNumber, bankName, accountNumber, accountHolder);
        return SellerStatusHistory.of(this, fromStatus, null);
    }

    public SellerStatusHistory approve() {
        return changeStatus(SellerStatus.PENDING, SellerStatus.APPROVED, null);
    }

    public SellerStatusHistory reject(String reason) {
        return endWithSnapshot(SellerStatus.PENDING, SellerStatus.REJECTED, reason);
    }

    public SellerStatusHistory requestWithdrawal(String reason) {
        return changeStatus(SellerStatus.APPROVED, SellerStatus.WITHDRAW_REQUESTED, reason);
    }

    public SellerStatusHistory approveWithdrawal() {
        return endWithSnapshot(SellerStatus.WITHDRAW_REQUESTED, SellerStatus.WITHDRAWN_SELF, null);
    }

    public SellerStatusHistory rejectWithdrawal(String reason) {
        return changeStatus(SellerStatus.WITHDRAW_REQUESTED, SellerStatus.APPROVED, reason);
    }

    // 판매 중이거나 철회 심사 중일 때 바로 제재
    public SellerStatusHistory sanction(String reason) {
        if (status != SellerStatus.APPROVED && status != SellerStatus.WITHDRAW_REQUESTED) throw stateInvalid();

        SellerStatus from = status;
        status = SellerStatus.WITHDRAWN_SANCTION;
        return SellerStatusHistory.of(this, from, reason);
    }

    // 판매 중인 판매자의 상호·연락처 수정. null은 그대로 둔다 (상태 변경이 아니라 이력은 남기지 않음)
    public void updateInfo(String businessName, String phoneNumber) {
        checkEditable();

        if (businessName != null) this.businessName = businessName.strip();
        if (phoneNumber != null) this.phoneNumber = digitsOnly(phoneNumber);
    }

    // 판매 중인 판매자의 정산 계좌 변경. 은행·계좌번호·예금주는 한 묶음이라 셋 다 있어야 한다
    public void changeAccount(String bankName, String accountNumber, String accountHolder) {
        checkEditable();
        if (bankName == null || accountNumber == null || accountHolder == null) {
            throw new DomainException(MemberErrorCode.SELLER_ACCOUNT_INCOMPLETE);
        }

        this.bankName = bankName.strip();
        this.accountNumber = digitsOnly(accountNumber);
        this.accountHolder = accountHolder.strip();
    }

    // 정보 수정은 판매 중(APPROVED)일 때만 가능
    private void checkEditable() {
        if (status != SellerStatus.APPROVED) throw new DomainException(MemberErrorCode.SELLER_NOT_EDITABLE);
    }

    // 반려됐거나 본인 철회한 판매자만 다시 신청할 수 있다 (제재는 불가)
    public boolean canReapply() {
        return status == SellerStatus.REJECTED || status == SellerStatus.WITHDRAWN_SELF;
    }

    public SellerDto toDto() {
        return new SellerDto(getId(), member.getId(), businessName, status);
    }

    // 계좌번호는 끝 4자리만 보여 준다 (예: **********3456)
    public String getMaskedAccountNumber() {
        String last4 = getAccountLast4();
        return "*".repeat(accountNumber.length() - last4.length()) + last4;
    }

    public String getAccountLast4() {
        return accountNumber.substring(Math.max(0, accountNumber.length() - 4));
    }

    public static String digitsOnly(String value) {
        return value.replaceAll("\\D", "");
    }

    private void fillApplication(String businessName, String businessNumber,
                                 String representativeName, String phoneNumber,
                                 String bankName, String accountNumber, String accountHolder) {
        this.businessName = businessName.strip();
        this.businessNumber = digitsOnly(businessNumber);
        this.representativeName = representativeName.strip();
        this.phoneNumber = digitsOnly(phoneNumber);
        this.bankName = bankName.strip();
        this.accountNumber = digitsOnly(accountNumber);
        this.accountHolder = accountHolder.strip();
        this.status = SellerStatus.PENDING;
        this.appliedAt = LocalDateTime.now();
    }

    private SellerStatusHistory changeStatus(SellerStatus expected, SellerStatus to, String reason) {
        if (status != expected) throw stateInvalid();

        status = to;
        return SellerStatusHistory.of(this, expected, reason);
    }

    // 재신청할 수 있는 상태로 끝날 때: 다음 재신청에 덮어써지기 전에 신청서를 이력에 남기고, 사업자번호를 비운다
    private SellerStatusHistory endWithSnapshot(SellerStatus expected, SellerStatus to, String reason) {
        if (status != expected) throw stateInvalid();

        status = to;
        SellerStatusHistory history = SellerStatusHistory.withSnapshot(this, expected, reason);
        businessNumber = null;
        return history;
    }

    private static DomainException stateInvalid() {
        return new DomainException(MemberErrorCode.SELLER_STATE_INVALID);
    }
}

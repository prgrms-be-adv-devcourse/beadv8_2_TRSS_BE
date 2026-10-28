package com.backend.boundedContext.member.domain;

import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판매자 상태 변경과 이력·스냅샷 규칙 (DB 없이)
 */
class SellerTest {

    @Test
    @DisplayName("신청하면 PENDING이고, 번호류는 숫자만 저장된다")
    void apply() {
        Seller seller = pending();

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.PENDING);
        assertThat(seller.getBusinessNumber()).isEqualTo("1234567890");
        assertThat(seller.getPhoneNumber()).isEqualTo("01012345678");
        assertThat(seller.getAccountNumber()).isEqualTo("12345601123456");
        assertThat(seller.getAppliedAt()).isNotNull();
    }

    @Test
    @DisplayName("회원 탈퇴 비식별화: 대표자명·연락처·정산 계좌를 대체 값으로 바꾸고, 상호·사업자번호는 남긴다")
    void anonymize() {
        Seller seller = pending();
        seller.reject("사업자번호 불일치");

        seller.anonymize();

        assertThat(seller.getRepresentativeName()).isEqualTo(Member.WITHDRAWN_NAME);
        assertThat(seller.getPhoneNumber()).isEqualTo("0");
        assertThat(seller.getAccountNumber()).isEqualTo("0");
        assertThat(seller.getAccountHolder()).isEqualTo(Member.WITHDRAWN_NAME);
        assertThat(seller.getBusinessName()).isEqualTo("팔레트 뷰티");
    }

    @Test
    @DisplayName("회원 탈퇴 비식별화: 이력 스냅샷의 대표자명·계좌 끝 4자리를 지운다")
    void anonymizeHistory() {
        SellerStatusHistory history = pending().reject("사업자번호 불일치");

        history.anonymize();

        assertThat(history.getRepresentativeName()).isNull();
        assertThat(history.getAccountLast4()).isNull();
        assertThat(history.getBusinessNumber()).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("처음 신청한 이력은 from이 없고 to가 PENDING")
    void appliedHistory() {
        SellerStatusHistory history = SellerStatusHistory.applied(pending());

        assertThat(history.getFromStatus()).isNull();
        assertThat(history.getToStatus()).isEqualTo(SellerStatus.PENDING);
    }

    @Test
    @DisplayName("승인: PENDING → APPROVED, 스냅샷은 남기지 않는다")
    void approve() {
        Seller seller = pending();

        SellerStatusHistory history = seller.approve();

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.APPROVED);
        assertTransition(history, SellerStatus.PENDING, SellerStatus.APPROVED);
        assertThat(history.getBusinessNumber()).isNull();
        assertThat(seller.getBusinessNumber()).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("반려: 신청서를 이력에 스냅샷으로 남기고 판매자의 사업자번호를 비운다")
    void reject() {
        Seller seller = pending();

        SellerStatusHistory history = seller.reject("사업자번호 불일치");

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.REJECTED);
        assertThat(seller.getBusinessNumber()).isNull();
        assertTransition(history, SellerStatus.PENDING, SellerStatus.REJECTED);
        assertThat(history.getReason()).isEqualTo("사업자번호 불일치");
        assertSnapshot(history);
    }

    @Test
    @DisplayName("철회 요청: APPROVED → WITHDRAW_REQUESTED, 사유가 이력에 남는다")
    void requestWithdrawal() {
        Seller seller = approved();

        SellerStatusHistory history = seller.requestWithdrawal("폐업 예정");

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.WITHDRAW_REQUESTED);
        assertTransition(history, SellerStatus.APPROVED, SellerStatus.WITHDRAW_REQUESTED);
        assertThat(history.getReason()).isEqualTo("폐업 예정");
    }

    @Test
    @DisplayName("철회 승인: WITHDRAWN, 스냅샷을 남기고 사업자번호를 비운다")
    void approveWithdrawal() {
        Seller seller = approved();
        seller.requestWithdrawal("폐업 예정");

        SellerStatusHistory history = seller.approveWithdrawal();

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.WITHDRAWN);
        assertThat(seller.getBusinessNumber()).isNull();
        assertTransition(history, SellerStatus.WITHDRAW_REQUESTED, SellerStatus.WITHDRAWN);
        assertSnapshot(history);
    }

    @Test
    @DisplayName("철회 거절: WITHDRAW_REQUESTED → APPROVED, 거절 사유가 이력에 남는다")
    void rejectWithdrawal() {
        Seller seller = approved();
        seller.requestWithdrawal("폐업 예정");

        SellerStatusHistory history = seller.rejectWithdrawal("진행 중인 주문 있음");

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.APPROVED);
        assertTransition(history, SellerStatus.WITHDRAW_REQUESTED, SellerStatus.APPROVED);
        assertThat(history.getReason()).isEqualTo("진행 중인 주문 있음");
    }

    @Test
    @DisplayName("승인일: 승인할 때 기록되고, 철회 요청·거절이 있어도 그대로다")
    void approvedAt() {
        Seller seller = pending();
        assertThat(seller.getApprovedAt()).isNull();

        seller.approve();
        LocalDateTime approvedAt = seller.getApprovedAt();
        seller.requestWithdrawal("폐업 예정");
        seller.rejectWithdrawal("진행 중인 주문 있음");

        assertThat(approvedAt).isNotNull();
        assertThat(seller.getApprovedAt()).isEqualTo(approvedAt);
    }

    @Test
    @DisplayName("승인일: 본인 철회 뒤 재신청하면 비워진다")
    void approvedAt_clearedOnReapply() {
        Seller seller = approved();
        seller.requestWithdrawal("폐업 예정");
        seller.approveWithdrawal();

        reapply(seller);

        assertThat(seller.getApprovedAt()).isNull();
    }

    @Test
    @DisplayName("제재: APPROVED·WITHDRAW_REQUESTED에서 SANCTIONED, 사업자번호는 남긴다")
    void sanction() {
        Seller fromApproved = approved();
        Seller fromRequested = approved();
        fromRequested.requestWithdrawal("폐업 예정");

        SellerStatusHistory history = fromApproved.sanction("허위 상품");
        fromRequested.sanction("허위 상품");

        assertThat(fromApproved.getStatus()).isEqualTo(SellerStatus.SANCTIONED);
        assertThat(fromRequested.getStatus()).isEqualTo(SellerStatus.SANCTIONED);
        assertThat(fromApproved.getBusinessNumber()).isEqualTo("1234567890");
        assertTransition(history, SellerStatus.APPROVED, SellerStatus.SANCTIONED);
        assertThat(history.getReason()).isEqualTo("허위 상품");
        assertThat(history.getBusinessNumber()).isNull();
    }

    @Test
    @DisplayName("반려 뒤 재신청: 같은 판매자의 신청 정보를 새 값으로 바꾸고 PENDING")
    void reapplyAfterReject() {
        Seller seller = pending();
        seller.reject("사업자번호 불일치");

        SellerStatusHistory history = seller.reapply("팔레트 의류", "987-65-43210", "김철수",
                "010-9999-8888", "신한은행", "110-123-456789", "김철수");

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.PENDING);
        assertThat(seller.getBusinessName()).isEqualTo("팔레트 의류");
        assertThat(seller.getBusinessNumber()).isEqualTo("9876543210");
        assertThat(seller.getAccountLast4()).isEqualTo("6789");
        assertTransition(history, SellerStatus.REJECTED, SellerStatus.PENDING);
    }

    @Test
    @DisplayName("본인 철회 뒤에도 재신청할 수 있다")
    void reapplyAfterSelfWithdrawal() {
        Seller seller = approved();
        seller.requestWithdrawal("폐업 예정");
        seller.approveWithdrawal();

        SellerStatusHistory history = seller.reapply("팔레트 뷰티", "123-45-67890", "홍길동",
                "010-1234-5678", "국민은행", "123456-01-123456", "홍길동");

        assertThat(seller.getStatus()).isEqualTo(SellerStatus.PENDING);
        assertTransition(history, SellerStatus.WITHDRAWN, SellerStatus.PENDING);
    }

    @Test
    @DisplayName("재신청은 반려·본인 철회 상태에서만 된다 (심사 중·판매 중·제재는 불가)")
    void reapplyNotAllowed() {
        Seller sanctioned = approved();
        sanctioned.sanction("허위 상품");

        assertStateInvalid(() -> reapply(pending()));
        assertStateInvalid(() -> reapply(approved()));
        assertStateInvalid(() -> reapply(sanctioned));
    }

    @Test
    @DisplayName("허용되지 않은 상태에서 바꾸면 409 SELLER_STATE_INVALID")
    void invalidTransitions() {
        assertStateInvalid(() -> approved().approve());
        assertStateInvalid(() -> approved().reject("사유"));
        assertStateInvalid(() -> pending().requestWithdrawal("사유"));
        assertStateInvalid(() -> approved().approveWithdrawal());
        assertStateInvalid(() -> approved().rejectWithdrawal("사유"));
        assertStateInvalid(() -> pending().sanction("사유"));
    }

    @Test
    @DisplayName("정보 수정: 보낸 값만 바꾸고, null은 그대로 둔다")
    void updateInfo_onlyGivenFields() {
        Seller seller = approved();

        seller.updateInfo(" 팔레트 뷰티 2호점 ", null);

        assertThat(seller.getBusinessName()).isEqualTo("팔레트 뷰티 2호점");
        assertThat(seller.getPhoneNumber()).isEqualTo("01012345678");
    }

    @Test
    @DisplayName("정보 수정: 연락처는 숫자만 저장되고, 정산 계좌는 그대로다")
    void updateInfo_phone() {
        Seller seller = approved();

        seller.updateInfo(null, "010-9876-5432");

        assertThat(seller.getPhoneNumber()).isEqualTo("01098765432");
        assertThat(seller.getBusinessName()).isEqualTo("팔레트 뷰티");
        assertThat(seller.getAccountNumber()).isEqualTo("12345601123456");
    }

    @Test
    @DisplayName("계좌 변경: 은행·계좌번호·예금주를 함께 바꾸고, 계좌번호는 숫자만 저장된다")
    void changeAccount() {
        Seller seller = approved();

        seller.changeAccount("신한은행", "110-123-456789", "김철수");

        assertThat(seller.getBankName()).isEqualTo("신한은행");
        assertThat(seller.getAccountNumber()).isEqualTo("110123456789");
        assertThat(seller.getAccountHolder()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("계좌 변경: 일부만 주면 400 SELLER_ACCOUNT_INCOMPLETE이고 계좌는 그대로다")
    void changeAccount_partial() {
        Seller seller = approved();

        assertError(() -> seller.changeAccount(null, "110-123-456789", null), MemberErrorCode.SELLER_ACCOUNT_INCOMPLETE);
        assertError(() -> seller.changeAccount("신한은행", null, "김철수"), MemberErrorCode.SELLER_ACCOUNT_INCOMPLETE);

        assertThat(seller.getBankName()).isEqualTo("국민은행");
        assertThat(seller.getAccountNumber()).isEqualTo("12345601123456");
        assertThat(seller.getAccountHolder()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("정보 수정·계좌 변경은 판매 중(APPROVED)일 때만 된다, 아니면 403 SELLER_NOT_APPROVED")
    void edit_onlyApproved() {
        Seller requested = approved();
        requested.requestWithdrawal("폐업 예정");

        assertError(() -> pending().updateInfo("상호", null), MemberErrorCode.SELLER_NOT_APPROVED);
        assertError(() -> requested.updateInfo("상호", null), MemberErrorCode.SELLER_NOT_APPROVED);
        assertError(() -> pending().changeAccount("신한은행", "110-123-456789", "김철수"), MemberErrorCode.SELLER_NOT_APPROVED);
    }

    @Test
    @DisplayName("계좌번호는 끝 4자리만 보여 준다")
    void maskedAccountNumber() {
        Seller seller = pending();

        assertThat(seller.getMaskedAccountNumber()).isEqualTo("**********3456");
        assertThat(seller.getAccountLast4()).isEqualTo("3456");
    }

    private static Seller pending() {
        return Seller.apply(Member.signUp("seller@palette.com", "encoded-password", "판매자"),
                " 팔레트 뷰티 ", "123-45-67890", "홍길동", "010-1234-5678",
                "국민은행", "123456-01-123456", "홍길동");
    }

    private static Seller approved() {
        Seller seller = pending();
        seller.approve();
        return seller;
    }

    private static void reapply(Seller seller) {
        seller.reapply("팔레트 뷰티", "123-45-67890", "홍길동",
                "010-1234-5678", "국민은행", "123456-01-123456", "홍길동");
    }

    private static void assertTransition(SellerStatusHistory history, SellerStatus from, SellerStatus to) {
        assertThat(history.getFromStatus()).isEqualTo(from);
        assertThat(history.getToStatus()).isEqualTo(to);
        assertThat(history.getChangedAt()).isNotNull();
    }

    // pending()으로 만든 신청서가 그대로 남았는지
    private static void assertSnapshot(SellerStatusHistory history) {
        assertThat(history.getBusinessName()).isEqualTo("팔레트 뷰티");
        assertThat(history.getBusinessNumber()).isEqualTo("1234567890");
        assertThat(history.getRepresentativeName()).isEqualTo("홍길동");
        assertThat(history.getAccountLast4()).isEqualTo("3456");
    }

    private static void assertStateInvalid(Executable action) {
        assertError(action, MemberErrorCode.SELLER_STATE_INVALID);
    }

    private static void assertError(Executable action, MemberErrorCode errorCode) {
        assertThatThrownBy(action::execute)
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(errorCode.resultCode()));
    }
}

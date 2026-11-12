package com.backend.boundedContext.member.domain;

import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 인플루언서 상태 변경과 이력 규칙 (DB 없이)
 */
class InfluencerTest {

    @Test
    @DisplayName("신청하면 PENDING이고, 입력값의 앞뒤 공백을 지우고, 결정 시각은 없다")
    void apply() {
        Influencer influencer = pending();

        assertThat(influencer.getStatus()).isEqualTo(InfluencerStatus.PENDING);
        assertThat(influencer.getActivityName()).isEqualTo("데일리 뷰티 민지");
        assertThat(influencer.getSnsUrl()).isEqualTo("https://instagram.com/daily.beauty");
        assertThat(influencer.getIntroduction()).isEqualTo("스킨케어 루틴을 올려요.");
        assertThat(influencer.getAppliedAt()).isNotNull();
        assertThat(influencer.getDecidedAt()).isNull();
    }

    @Test
    @DisplayName("처음 신청한 이력은 from이 없고 to가 PENDING")
    void appliedHistory() {
        InfluencerStatusHistory history = pending().recordApplied();

        assertThat(history.getFromStatus()).isNull();
        assertThat(history.getToStatus()).isEqualTo(InfluencerStatus.PENDING);
    }

    @Test
    @DisplayName("승인하면 APPROVED, 결정 시각이 생기고 이력은 PENDING → APPROVED")
    void approve() {
        Influencer influencer = pending();

        InfluencerStatusHistory history = influencer.approve();

        assertThat(influencer.isApproved()).isTrue();
        assertThat(influencer.getDecidedAt()).isNotNull();
        assertThat(history.getFromStatus()).isEqualTo(InfluencerStatus.PENDING);
        assertThat(history.getToStatus()).isEqualTo(InfluencerStatus.APPROVED);
        assertThat(history.getReason()).isNull();
    }

    @Test
    @DisplayName("반려하면 REJECTED, 사유는 이력에 남는다")
    void reject() {
        Influencer influencer = pending();

        InfluencerStatusHistory history = influencer.reject("본인 확인이 어려워요");

        assertThat(influencer.getStatus()).isEqualTo(InfluencerStatus.REJECTED);
        assertThat(influencer.getDecidedAt()).isNotNull();
        assertThat(history.getReason()).isEqualTo("본인 확인이 어려워요");
    }

    @Test
    @DisplayName("심사 중일 때만 승인·반려할 수 있다")
    void decideOnlyPending() {
        assertStateInvalid(() -> approved().approve());
        assertStateInvalid(() -> approved().reject("사유"));

        Influencer rejected = pending();
        rejected.reject("사유");
        assertStateInvalid(rejected::approve);
    }

    @Test
    @DisplayName("반려된 회원은 같은 행으로 재신청하면 PENDING으로 돌아가고 결정 시각이 비워진다")
    void reapplyAfterReject() {
        Influencer influencer = pending();
        influencer.reject("본인 확인이 어려워요");

        InfluencerStatusHistory history = influencer.applyAgain("새 활동명", "https://instagram.com/new", "새 소개");

        assertThat(influencer.getStatus()).isEqualTo(InfluencerStatus.PENDING);
        assertThat(influencer.getActivityName()).isEqualTo("새 활동명");
        assertThat(influencer.getDecidedAt()).isNull();
        assertThat(history.getFromStatus()).isEqualTo(InfluencerStatus.REJECTED);
        assertThat(history.getToStatus()).isEqualTo(InfluencerStatus.PENDING);
    }

    @Test
    @DisplayName("심사 중·승인된 회원이 다시 신청하면 409 ALREADY_APPLIED")
    void reapplyWhileOpen() {
        assertErrorCode(() -> pending().applyAgain("a", "https://a.com", "b"), MemberErrorCode.INFLUENCER_ALREADY_APPLIED);
        assertErrorCode(() -> approved().applyAgain("a", "https://a.com", "b"), MemberErrorCode.INFLUENCER_ALREADY_APPLIED);
    }

    private static Influencer pending() {
        return Influencer.apply(Member.signUp("influencer@palette.com", "encoded-password", "인플루언서"),
                " 데일리 뷰티 민지 ", " https://instagram.com/daily.beauty ", " 스킨케어 루틴을 올려요. ");
    }

    private static Influencer approved() {
        Influencer influencer = pending();
        influencer.approve();
        return influencer;
    }

    private static void assertStateInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertErrorCode(call, MemberErrorCode.INFLUENCER_STATE_INVALID);
    }

    private static void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, MemberErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo(code.resultCode());
    }
}

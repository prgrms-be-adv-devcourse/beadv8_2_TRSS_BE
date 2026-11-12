package com.backend.boundedContext.member.app;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.WithdrawalCheckDto;
import com.backend.boundedContext.member.app.dto.WithdrawalNotAllowedResponse;
import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.InfluencerStatus;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.MemberPolicy;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.out.InfluencerRepository;
import com.backend.boundedContext.member.out.InfluencerStatusHistoryRepository;
import com.backend.boundedContext.member.out.LoginAttemptStore;
import com.backend.boundedContext.member.out.MemberSocialAccountRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.event.InfluencerRevokedEvent;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberWithdrawUseCase {

    private final MemberSupport memberSupport;
    private final MemberWithdrawalSupport memberWithdrawalSupport;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptStore loginAttemptStore;
    private final MemberPolicy memberPolicy;
    private final SellerRepository sellerRepository;
    private final SellerStatusHistoryRepository sellerStatusHistoryRepository;
    private final MemberSocialAccountRepository memberSocialAccountRepository;
    private final InfluencerRepository influencerRepository;
    private final InfluencerStatusHistoryRepository influencerStatusHistoryRepository;
    private final EventPublisher eventPublisher;

    // 비밀번호로 본인 확인 → 탈퇴 조건 재확인 → 회원·판매자 비식별화, 인플루언서 자격 해제, 소셜 연결 정보 삭제
    public void withdraw(Long memberId, String password) {
        Member member = memberSupport.getLoginMember(memberId);
        checkPassword(member, password);

        WithdrawalCheckDto check = memberWithdrawalSupport.check(memberId);
        if (!check.withdrawable()) {
            throw new DomainException(MemberErrorCode.WITHDRAWAL_NOT_ALLOWED, new WithdrawalNotAllowedResponse(check.reasons()));
        }

        // 엔티티는 조회한 상태라 커밋 때 변경 감지로 저장됨
        sellerRepository.findByMemberId(memberId).ifPresent(seller -> {
            seller.anonymize();
            sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(seller.getId()).forEach(SellerStatusHistory::anonymize);
        });
        influencerRepository.findByMemberId(memberId)
                .filter(influencer -> influencer.getStatus() == InfluencerStatus.PENDING || influencer.isApproved())
                .ifPresent(this::revokeInfluencer);
        // 탈퇴한 회원의 카카오 계정으로는 로그인할 수 없게 하고, 같은 카카오 계정을 새 회원에게 다시 연결할 수 있게 지움
        // (카카오 쪽 연결은 끊지 않음. unlink API 연동은 이번 범위 밖)
        memberSocialAccountRepository.deleteAllByMemberId(memberId);
        member.withdraw();
    }

    // 심사 중이던 회원은 협약이 없어서, 승인된 인플루언서였을 때만 협약 종료 이벤트를 보낸다
    private void revokeInfluencer(Influencer influencer) {
        boolean wasApproved = influencer.isApproved();
        influencerStatusHistoryRepository.save(influencer.revokeByWithdrawal());
        if (wasApproved) {
            eventPublisher.publish(new InfluencerRevokedEvent(influencer.getId(), influencer.getMember().getId(), Influencer.WITHDRAWAL_REASON));
        }
    }

    // Access Token만 가진 사람이 비밀번호를 계속 넣어 볼 수 없도록, 로그인과 같은 이메일별 실패 횟수·잠금을 씀
    private void checkPassword(Member member, String password) {
        String email = member.getEmail();
        long attempt = loginAttemptStore.increaseAttempt(email, memberPolicy.loginLockTtl());
        if (attempt > memberPolicy.loginMaxAttempts()) {
            throw new DomainException(MemberErrorCode.LOGIN_LOCKED);
        }
        if (passwordEncoder.matches(password, member.getPassword())) {
            // 본인 확인이 끝났으므로 횟수를 지움 (탈퇴 조건 때문에 거절돼도 시도 횟수가 쌓이지 않게)
            loginAttemptStore.deleteAttempts(email);
            return;
        }

        if (attempt == memberPolicy.loginMaxAttempts()) {
            loginAttemptStore.lock(email, memberPolicy.loginLockTtl());
            throw new DomainException(MemberErrorCode.LOGIN_LOCKED);
        }
        throw new DomainException(MemberErrorCode.PASSWORD_MISMATCH);
    }
}

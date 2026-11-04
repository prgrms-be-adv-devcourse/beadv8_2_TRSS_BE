package com.backend.boundedContext.payment.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.backend.shared.member.dto.MemberDto;

/**
 * 결제 컨텍스트가 쓰는 회원 기능만 담은 Port
 * - 분리 전: PaymentMemberApiAdapter가 같은 앱의 MemberApi를 부름
 * - 분리 후: PaymentMemberHttpAdapter가 회원 내부 API(/internal/v1/members)를 부름
 * 어느 쪽을 쓸지는 custom.payment.memberClient(local | http)로 정함
 */
public interface PaymentMemberPort {

    // 로그인 상태인 회원인지 확인 (충전 준비·승인). 없거나 탈퇴했으면 401 UNAUTHORIZED
    void checkActiveMember(Long memberId);

    // 여러 회원 조회 (관리자 결제·충전 목록의 이메일 표시). 없는 ID는 결과에서 빠짐
    List<MemberDto> getMembers(Collection<Long> memberIds);

    // 이메일로 회원 조회 (관리자 검색). 없으면 empty
    Optional<MemberDto> findMemberByEmail(String email);
}

package com.backend.boundedContext.member.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.boundedContext.member.domain.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

    boolean existsByEmail(String email);

    Optional<Member> findByEmail(String email);
}

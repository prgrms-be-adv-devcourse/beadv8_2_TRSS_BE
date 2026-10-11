package com.backend.shared.member.dto;

public record MemberDto(
        Long id,
        String email,
        String name,
        MemberRole role,
        MemberStatus status
) {}

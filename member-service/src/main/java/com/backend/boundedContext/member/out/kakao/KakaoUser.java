package com.backend.boundedContext.member.out.kakao;

/**
 * 카카오 사용자 정보 중 우리가 쓰는 값
 * @param id 카카오 회원번호. 앱마다 정해지고 연결을 끊었다 다시 이어도 바뀌지 않음
 * @param profileImageUrl 프로필 이미지 주소(https). 동의하지 않았거나 기본 이미지면 null
 */
public record KakaoUser(String id, String profileImageUrl) {}

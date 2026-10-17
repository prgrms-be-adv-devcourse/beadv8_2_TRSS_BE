package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.out.RefreshTokenStore;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberLogoutUseCase {

    private final RefreshTokenStore refreshTokenStore;

    // Refresh Token 폐기 (Access Token은 저장하지 않으므로 만료될 때까지 유효)
    public void logout(String refreshToken) {
        refreshTokenStore.delete(refreshToken);
    }
}

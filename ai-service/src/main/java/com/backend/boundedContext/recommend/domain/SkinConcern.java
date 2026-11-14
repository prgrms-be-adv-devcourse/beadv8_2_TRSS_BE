package com.backend.boundedContext.recommend.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 설문 피부 고민*/
@Getter
@RequiredArgsConstructor
public enum SkinConcern {
    TROUBLE("트러블"),
    PORE("모공"),
    DEAD_SKIN("각질"),
    DRYNESS("건조함"),
    SENSITIVE_REDNESS("민감·홍조"),
    DULLNESS("칙칙함"),
    WRINKLE_ELASTICITY("주름·탄력"),
    PIGMENTATION("색소침착"),
    SEBUM("유분");

    private final String label;
}

package com.backend.boundedContext.coupon.app;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 상품 쿠폰 코드 생성
 *
 * <p>대문자와 숫자 10자리. 구독자가 손으로 옮겨 적다 헷갈리기 쉬운 0, O, 1, I는 뺀다(32가지 문자).
 * 경우의 수가 32^10(약 1,100조)이라 겹칠 일은 거의 없지만, 발행할 때 한 번 더 확인하고 DB 유니크 제약으로도 막는다.
 * 입력받은 코드는 {@link #normalize}로 앞뒤 공백을 지우고 대문자로 바꿔 찾는다(대소문자 구분 없음).
 */
@Component
public class CouponCodeGenerator {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 10;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    public static String normalize(String code) {
        return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
    }
}

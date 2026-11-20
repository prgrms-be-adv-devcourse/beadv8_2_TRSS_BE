package com.backend.boundedContext.review.app;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewSupportTest {

    @ParameterizedTest(name = "[{0}] → [{1}]")
    @CsvSource(delimiter = '|', value = {
            "김        | *",
            "김진      | 김*",
            "김유진    | 김*진",
            "남궁민희  | 남**희",
            "Kim Yujin | K*******n",
            "' 김유진 ' | 김*진",
            "'   '     | *",
    })
    void maskName(String name, String expected) {
        assertThat(ReviewSupport.maskName(name)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "null → *")
    @NullSource
    void maskNull(String name) {
        assertThat(ReviewSupport.maskName(name)).isEqualTo("*");
    }
}

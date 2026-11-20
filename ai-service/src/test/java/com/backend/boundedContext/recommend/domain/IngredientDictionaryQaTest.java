package com.backend.boundedContext.recommend.domain;

import com.backend.boundedContext.recommend.domain.IngredientDictionary.IngredientMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

import static com.backend.boundedContext.recommend.domain.IngredientDictionary.match;
import static com.backend.boundedContext.recommend.domain.IngredientTag.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA 보강(TRSS-300): 괄호 깊이 기반 split·normalize가 기존(정규식) 결과를 바꾸지 않는지, 괄호 안 이름으로 태그를 붙여 생기는 오탐 범위.
 */
class IngredientDictionaryQaTest {

    // 변경 전 구현(develop df3398f)의 정규식. 괄호가 없는 입력에서는 새 split과 결과가 같아야 한다
    private static final Pattern OLD_SEPARATOR = Pattern.compile("(?<!\\d),|,(?!\\d)|[，、\\n\\r]");
    private static final Pattern OLD_PARENTHESES = Pattern.compile("[(（][^)）]*[)）]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static String oldNormalize(String name) {
        String withoutParentheses = OLD_PARENTHESES.matcher(name).replaceAll("");
        return SPACES.matcher(withoutParentheses).replaceAll("").toLowerCase(Locale.ROOT);
    }

    // 정규식 split은 끝의 빈 조각을 버리지만, 빈 조각은 normalize 뒤 어차피 건너뛴다. 비지 않은 이름만 비교한다
    private static List<String> names(List<String> pieces, boolean old) {
        return pieces.stream().map(p -> old ? oldNormalize(p) : IngredientDictionary.normalize(p)).filter(s -> !s.isEmpty()).toList();
    }

    @Test
    @DisplayName("회귀: 괄호가 없는 입력(숫자 사이 쉼표·전각 쉼표·모점·줄바꿈·CR 포함)은 예전 정규식과 같은 이름 목록으로 나눈다 (무작위 2만 건)")
    void splitWithoutParenthesesSameAsBefore() {
        char[] alphabet = {'1', '2', '9', ',', '，', '、', '\n', '\r', ' ', '-', '가', '향', 'a', 'B', '\t'};
        Random random = new Random(300);
        for (int n = 0; n < 20_000; n++) {
            StringBuilder sb = new StringBuilder();
            int length = random.nextInt(16);
            for (int i = 0; i < length; i++) sb.append(alphabet[random.nextInt(alphabet.length)]);
            String text = sb.toString();

            List<String> before = names(Arrays.asList(OLD_SEPARATOR.split(text)), true);
            List<String> after = names(IngredientDictionary.split(text), false);
            assertThat(after).as("입력 [%s]", text.replace("\n", "\\n").replace("\r", "\\r")).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("회귀: 짝이 맞고 겹치지 않은 괄호의 normalize는 예전과 같다(괄호와 안쪽을 지움)")
    void normalizeBalancedSameAsBefore() {
        for (String name : List.of("정제수(Water)", "정제수 ( Water )", "향료（Fragrance）", "글리세린(식물유래)(99%)",
                "Butylene Glycol", "1,2-헥산다이올", "다이메티콘 (Dimethicone)", "(괄호만)", "앞(가)뒤(나)끝")) {
            assertThat(IngredientDictionary.normalize(name)).as(name).isEqualTo(oldNormalize(name));
        }
    }

    @Test
    @DisplayName("기존 케이스 유지: 1,2-헥산다이올, 전각 쉼표·괄호, 줄바꿈(CRLF 포함)")
    void legacyCases() {
        assertThat(match("정제수, 1,2-헥산다이올, 에탄올").unmatchedNames()).containsExactly("정제수", "1,2-헥산다이올");
        assertThat(match("정제수，향료（Fragrance），에탄올").tags()).containsExactlyInAnyOrder(FRAGRANCE, ALCOHOL);
        assertThat(match("정제수\r\n향료\r\n에탄올").tags()).containsExactlyInAnyOrder(FRAGRANCE, ALCOHOL);
        assertThat(match("정제수\r\n향료\r\n에탄올").unmatchedNames()).containsExactly("정제수");
        assertThat(match("정제수、향료").tags()).containsExactly(FRAGRANCE);
    }

    @ParameterizedTest
    @DisplayName("오탐 없음: 괄호 안 영문명·함량·설명 문구에는 태그를 붙이지 않는다")
    @ValueSource(strings = {
            "정제수(Water)", "글리세린(식물유래, 99%)", "글리세린(Glycerin, 식물유래)", "정제수(무향료)",
            "정제수(향료 무첨가)", "정제수(파라벤 프리)", "정제수(무알코올)", "정제수(실리콘프리)",
            "부틸렌글라이콜(1,3-BG)", "카보머(점증제)", "정제수(Aqua, Eau)"
    })
    void noFalsePositive(String text) {
        IngredientMatch result = match(text);
        assertThat(result.tags()).as(text).isEmpty();
        assertThat(result.known()).isTrue();
    }

    @Test
    @DisplayName("현재 동작(안전한 쪽 오탐): 괄호 안에 '무첨가: 파라벤, 알코올'처럼 사전 성분명이 쉼표로 따로 적히면 태그가 붙는다 — 피하는 회원 추천에서 빠질 뿐 반대(놓침)는 없음")
    void knownSafeSideFalsePositive() {
        assertThat(match("정제수(무첨가: 파라벤, 알코올)").tags()).containsExactlyInAnyOrder(PARABEN, ALCOHOL);   // "무첨가:파라벤"이 SUFFIX 파라벤에 걸림
        assertThat(match("정제수(무첨가: 향료, 에탄올)").tags()).containsExactlyInAnyOrder(ALCOHOL);           // "무첨가:향료"는 EXACT라 안 걸림
    }

    @Test
    @DisplayName("괄호가 앞에서 안 닫혀도 뒤 성분의 태그는 놓치지 않는다(미매칭 목록·known은 괄호 밖 이름만)")
    void unclosedEarlyStillTags() {
        IngredientMatch result = match("정제수(Water, 글리세린, 다이메티콘, 향료, 에탄올");

        assertThat(result.tags()).containsExactlyInAnyOrder(SILICONE, FRAGRANCE, ALCOHOL);
        assertThat(result.unmatchedNames()).containsExactly("정제수");
        assertThat(result.known()).isTrue();
    }

    @Test
    @DisplayName("괄호 안 줄바꿈에서도 나누지 않고 안쪽 성분 태그를 찾는다")
    void newlineInsideParentheses() {
        IngredientMatch result = match("정제수\n향료(리모넨\n리날룰)\n판테놀");

        assertThat(result.tags()).containsExactlyInAnyOrder(FRAGRANCE, PANTHENOL);
        assertThat(result.unmatchedNames()).containsExactly("정제수");
    }

    @Test
    @DisplayName("괄호 안에 괄호 밖과 다른 종류의 태그만 있어도 붙는다: 보습제(나이아신아마이드, 판테놀)")
    void innerDifferentTags() {
        assertThat(match("보습제(나이아신아마이드, 판테놀)").tags()).containsExactlyInAnyOrder(NIACINAMIDE, PANTHENOL);
    }

    @Test
    @DisplayName("괄호만 있는 조각은 known으로 치지 않는다: '(리모넨)'만 있으면 태그는 붙되 known=false — 피하는 회원에겐 어차피 빠짐")
    void onlyParenthesesPiece() {
        IngredientMatch result = match("(리모넨)");
        assertThat(result.tags()).containsExactly(FRAGRANCE);
        assertThat(result.known()).isFalse();
    }

    @Test
    @DisplayName("깊게 겹친 괄호·아주 긴 전성분도 예외 없이 빨리 끝난다")
    void pathologicalInput() {
        String deep = "(".repeat(5_000) + "리모넨" + ")".repeat(5_000);
        assertThat(match(deep).tags()).containsExactly(FRAGRANCE);

        String longText = String.join(", ", java.util.Collections.nCopies(5_000, "정제수(Water, 식물유래)"));
        long start = System.nanoTime();
        assertThat(match(longText).unmatchedNames()).hasSize(5_000);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
    }
}

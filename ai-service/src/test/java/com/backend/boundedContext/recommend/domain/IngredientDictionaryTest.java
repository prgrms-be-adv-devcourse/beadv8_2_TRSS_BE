package com.backend.boundedContext.recommend.domain;

import com.backend.boundedContext.recommend.domain.IngredientDictionary.IngredientMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.backend.boundedContext.recommend.domain.IngredientTag.*;
import static org.assertj.core.api.Assertions.assertThat;

class IngredientDictionaryTest {

    private static IngredientMatch match(String text) {
        return IngredientDictionary.match(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"에탄올", "변성알코올", "알코올"})
    @DisplayName("알코올: 에탄올·변성알코올은 ALCOHOL")
    void alcohol(String name) {
        assertThat(match(name).tags()).containsExactly(ALCOHOL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"세테아릴알코올", "세틸알코올", "스테아릴알코올"})
    @DisplayName("알코올: 보습용 지방 알코올은 ALCOHOL이 아님")
    void fattyAlcoholIsNotAlcohol(String name) {
        assertThat(match(name).tags()).isEmpty();
    }

    @Test
    @DisplayName("벤질알코올은 착향제라 FRAGRANCE이고 ALCOHOL이 아님")
    void benzylAlcohol() {
        assertThat(match("벤질알코올").tags()).containsExactly(FRAGRANCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"메틸파라벤", "메칠파라벤", "프로필파라벤", "부틸파라벤"})
    @DisplayName("파라벤: ~파라벤은 PARABEN")
    void paraben(String name) {
        assertThat(match(name).tags()).containsExactly(PARABEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"다이메티콘", "디메치콘", "아모다이메티콘", "페닐트라이메티콘", "사이클로메티콘",
            "사이클로펜타실록세인", "사이클로펜타실록산", "다이메티콘/비닐다이메티콘크로스폴리머"})
    @DisplayName("실리콘: 메티콘(구명칭 메치콘)·실록세인(구명칭 실록산)이 들어간 이름은 SILICONE")
    void silicone(String name) {
        assertThat(match(name).tags()).containsExactly(SILICONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "향료", "Fragrance", "Parfum",
            "아밀신남알", "벤질알코올", "신나밀알코올", "시트랄", "유제놀", "하이드록시시트로넬알",
            "아이소유제놀", "이소유제놀", "아밀신나밀알코올", "벤질살리실레이트", "신남알", "쿠마린",
            "제라니올", "아니스알코올", "아니스에탄올", "벤질신나메이트", "파네솔",
            "부틸페닐메틸프로피오날", "부틸페닐메칠프로피오날", "리날룰", "벤질벤조에이트",
            "시트로넬올", "시트로넬롤", "헥실신남알", "헥실신나몰", "리모넨",
            "메틸2-옥티노에이트", "메칠2-옥티노에이트", "알파-아이소메틸아이오논", "알파-이소메칠이오논",
            "참나무이끼추출물", "나무이끼추출물"})
    @DisplayName("향료: '향료'와 알레르기 유발 착향제 25종(KCIA 표준명·구명칭)은 FRAGRANCE")
    void fragranceAndAllergens(String name) {
        assertThat(match(name).tags()).contains(FRAGRANCE);
    }

    @Test
    @DisplayName("시카: 영문 괄호가 붙은 병풀추출물·마데카소사이드는 CICA")
    void cica() {
        assertThat(match("병풀추출물(Centella Asiatica Extract), 마데카소사이드").tags()).containsExactly(CICA);
    }

    @Test
    @DisplayName("오일: 호호바씨오일은 에센셜 오일이 아니고, 티트리잎오일은 TEA_TREE와 ESSENTIAL_OIL 둘 다")
    void oils() {
        assertThat(match("호호바씨오일").tags()).isEmpty();
        assertThat(match("티트리잎오일").tags()).containsExactlyInAnyOrder(TEA_TREE, ESSENTIAL_OIL);
        assertThat(match("베르가모트오일, 센티드제라늄꽃오일").tags()).containsExactly(ESSENTIAL_OIL);
    }

    @Test
    @DisplayName("레티놀: 레틴알(구명칭 레티날)·하이드록시피나콜론레티노에이트도 RETINOL")
    void retinoids() {
        assertThat(match("레틴알, 레티날, 하이드록시피나콜론레티노에이트").tags()).containsExactly(RETINOL);
    }

    @Test
    @DisplayName("펩타이드·세라마이드는 이름 일부로 찾음")
    void peptideAndCeramide() {
        assertThat(match("아세틸헥사펩타이드-8").tags()).containsExactly(PEPTIDE);
        assertThat(match("세라마이드엔피").tags()).containsExactly(CERAMIDE);
    }

    @Test
    @DisplayName("시트릭애씨드는 pH 조절제라 AHA_BHA가 아님")
    void citricAcid() {
        assertThat(match("시트릭애씨드").tags()).isEmpty();
        assertThat(match("살리실릭애씨드").tags()).containsExactly(AHA_BHA);
    }

    @Test
    @DisplayName("정규화: 공백·전각 쉼표·줄바꿈이 섞여도 성분을 나눠 찾고, 숫자 사이 쉼표(1,2-헥산다이올)는 나누지 않음")
    void normalize() {
        IngredientMatch result = match("정제수 ,  나이아신 아마이드，1,2-헥산다이올\n에탄올、 FRAGRANCE ");

        assertThat(result.tags()).containsExactlyInAnyOrder(NIACINAMIDE, ALCOHOL, FRAGRANCE);
        assertThat(result.unmatchedNames()).containsExactly("정제수", "1,2-헥산다이올");
        assertThat(result.known()).isTrue();
    }

    @Test
    @DisplayName("태그가 없는 성분만 있으면 태그는 비고 미매칭으로 남지만, 전성분은 아는 것(known)")
    void onlyUnmatched() {
        IngredientMatch result = match("정제수, 글리세린");

        assertThat(result.tags()).isEmpty();
        assertThat(result.unmatchedNames()).containsExactly("정제수", "글리세린");
        assertThat(result.known()).isTrue();
    }

    @Test
    @DisplayName("null·공백·쉼표만 있으면 전성분을 모르는 것(known=false)")
    void unknown() {
        assertThat(match(null).known()).isFalse();
        assertThat(match("   ").known()).isFalse();
        assertThat(match(" , ,\n").known()).isFalse();
    }
}

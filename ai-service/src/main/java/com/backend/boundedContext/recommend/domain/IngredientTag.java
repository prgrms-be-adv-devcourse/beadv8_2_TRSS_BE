package com.backend.boundedContext.recommend.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 성분 태그. 설문의 선호·피하는 성분과 상품 전성분에서 찾은 태그가 같은 코드를 쓴다.
 * side는 설문의 어느 칸에 올 수 있는지다(선호 칸에 FRAGRANCE를 넣을 수 없음).
 */
@Getter
@RequiredArgsConstructor
public enum IngredientTag {
    NIACINAMIDE("나이아신아마이드", Side.PREFERRED),
    CICA("시카(병풀)", Side.PREFERRED),
    VITAMIN_C("비타민C", Side.PREFERRED),
    HYALURONIC_ACID("히알루론산", Side.PREFERRED),
    CERAMIDE("세라마이드", Side.PREFERRED),
    RETINOL("레티놀", Side.PREFERRED),
    PANTHENOL("판테놀", Side.PREFERRED),
    TEA_TREE("티트리", Side.PREFERRED),
    PEPTIDE("펩타이드", Side.PREFERRED),
    AHA_BHA("AHA·BHA", Side.PREFERRED),
    FRAGRANCE("향료", Side.AVOIDED),   // 알레르기 유발 착향제 25종 포함 (IngredientDictionary)
    ALCOHOL("알코올(에탄올)", Side.AVOIDED),
    PARABEN("파라벤", Side.AVOIDED),
    ESSENTIAL_OIL("에센셜 오일", Side.AVOIDED),
    SILICONE("실리콘", Side.AVOIDED),
    MINERAL_OIL("미네랄 오일", Side.AVOIDED);

    private final String label;
    private final Side side;

    public enum Side { PREFERRED, AVOIDED }
}

-- 뷰티 상품 전성분(판매자가 붙여 넣은 원문). 뷰티 소분류 상품만 값이 있다.
-- 필수 여부는 앱이 검사한다. 기존 뷰티 상품은 null이라 NOT NULL을 걸 수 없고, 다음 수정 때 판매자가 넣는다.
-- 번호: develop에서 V15(#340 협약 수수료율)·V16(#350 할인 경계 인덱스)을 먼저 썼다. 머지 직전에 develop의 마지막 번호 + 1인지 다시 확인한다.
alter table product add column ingredients_text text;

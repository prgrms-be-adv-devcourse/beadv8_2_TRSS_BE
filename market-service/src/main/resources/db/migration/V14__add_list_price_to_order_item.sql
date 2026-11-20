-- 주문 항목 정가·할인율 (상품 기간 할인 #316 후속). 화면 표시용(취소선·할인율)이고 금액 계산은 지금처럼 unit_price만 쓴다
-- list_unit_price: 주문 시점 정가 단가 = 상품 가격 + 옵션 추가금. 할인이 없으면 unit_price와 같다
-- discount_rate: 주문 시점 할인율(%, 소수점 내림). 상품 가격(기본가) 기준이라 옵션 추가금과 무관하다. 할인 중이 아니었으면 null
alter table market_order_item
    add column list_unit_price bigint,
    add column discount_rate integer;

-- 이미 있는 주문 항목은 할인 기능 전이라 정가 = 주문 단가, 할인율 없음
update market_order_item set list_unit_price = unit_price where list_unit_price is null;

alter table market_order_item alter column list_unit_price set not null;

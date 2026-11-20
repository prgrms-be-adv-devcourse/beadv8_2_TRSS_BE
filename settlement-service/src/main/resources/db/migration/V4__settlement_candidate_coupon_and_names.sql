-- 정산 후보의 상품 쿠폰 할인액(판매자 부담). 판매가(sale_price)는 할인 뒤 금액이고, 이 컬럼은 뺀 할인액 기록이다.
-- 할인 전 금액 = sale_price + product_coupon_discount. 기존 후보는 쿠폰 반영 전이라 0
alter table settlement_candidate add column product_coupon_discount bigint not null default 0;

-- 주문할 때의 상품명·옵션명 스냅샷(구매 확정 이벤트 ConfirmedOrderItemDto). 정산 내역·엑셀 표시용
-- 이름이 실리기 전 이벤트로 만든 기존 후보는 null. 길이는 주문 항목(market_order_item)과 같다
alter table settlement_candidate add column product_name varchar(100);
alter table settlement_candidate add column option_name varchar(50);

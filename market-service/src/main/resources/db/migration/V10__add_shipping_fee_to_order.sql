-- 주문 배송비 (TRSS-210)
-- shipping_fee: 주문 생성 때 확정한 배송비. 상품 쿠폰 할인 후 상품 금액 합계가 30,000원 이상이면 0, 미만이면 2,500 (custom.order.*)
-- 이 컬럼이 생기기 전 주문은 배송비를 받지 않았으므로 0. pay_amount(V8)는 이미 배송비 없이 계산돼 있어 그대로 둔다
-- 이제 pay_amount = total_price - product_discount_amount - cart_coupon_discount_amount + shipping_fee
-- 팀 규칙에 따라 CHECK 제약은 더하지 않는다
alter table market_order
    add column shipping_fee bigint not null default 0;

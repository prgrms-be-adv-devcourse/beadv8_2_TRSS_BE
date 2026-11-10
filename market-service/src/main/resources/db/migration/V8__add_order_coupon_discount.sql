-- 주문 금액 구성: 상품 금액 합계(total_price) - 상품 쿠폰 할인 - 장바구니 쿠폰 할인 = 결제 금액(pay_amount)
-- 쿠폰 적용 전(설정 custom.order.couponEnabled = false)에 만든 주문은 할인이 0이고 결제 금액이 상품 금액 합계와 같다
-- 배송비는 배송비 작업에서 따로 추가한다(그때 pay_amount 식에 배송비를 더한다)
alter table market_order
    add column product_discount_amount bigint not null default 0,
    add column cart_coupon_discount_amount bigint not null default 0,
    add column pay_amount bigint;

-- 이미 있는 주문은 결제 금액 = 상품 금액 합계 (할인이 없었음)
update market_order set pay_amount = total_price where pay_amount is null;

alter table market_order alter column pay_amount set not null;

-- 할인은 음수가 될 수 없고, 정책상 0원 결제는 없으므로 할인 합계는 상품 금액 합계보다 작아야 한다(결제 금액 1원 이상)
alter table market_order
    add constraint ck_market_order_discount check (
        product_discount_amount >= 0
        and cart_coupon_discount_amount >= 0
        and product_discount_amount + cart_coupon_discount_amount < total_price);

-- 주문 항목별 상품 쿠폰 할인액 (상품 쿠폰이 붙지 않은 항목은 0). 정책상 할인 후 0원이 되는 쿠폰은 없으므로 할인액은 항목 금액(단가 x 수량)보다 작다
alter table market_order_item
    add column discount_amount bigint not null default 0;

alter table market_order_item
    add constraint ck_market_order_item_discount check (discount_amount >= 0 and discount_amount < line_amount);

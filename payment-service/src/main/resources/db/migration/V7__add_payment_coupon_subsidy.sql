-- 장바구니 쿠폰 지원금(플랫폼 부담). 플랫폼(관리자) 지갑에서 홀딩으로 채운다 (원장 COUPON_SUBSIDY)
-- 홀딩 금액 = amount - shipping_fee + coupon_subsidy_amount. 지금까지의 결제는 지원금이 없었으므로 0으로 채운다
alter table payment add column coupon_subsidy_amount bigint not null default 0;

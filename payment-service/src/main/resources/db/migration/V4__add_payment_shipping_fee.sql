-- 결제 금액 중 배송비. 홀딩에 넣지 않고 플랫폼(관리자) 지갑으로 옮긴다 (원장 SHIPPING_FEE)
-- 지금까지의 결제는 배송비가 없었으므로 0으로 채운다
alter table payment add column shipping_fee bigint not null default 0;

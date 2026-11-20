-- 결제 취소 (결제는 됐는데 주문 처리가 실패했을 때 홀딩 금액을 구매자 지갑으로 되돌림)

-- 결제 취소 시각
alter table payment add column canceled_at timestamp(6);

-- 주문당 홀딩 1개 제약을 "해제되지 않은 홀딩" 기준으로 바꾼다.
-- 취소된 결제의 홀딩(RELEASED)이 남아 있어도 같은 주문을 다시 결제할 수 있어야 하기 때문이다.
-- 같은 주문에 진행 중인 홀딩(HELD·SETTLED)은 여전히 1개만 허용해 PAID 결제가 두 번 생기는 것을 막는다.
alter table wallet_holding drop constraint wallet_holding_order_id_key;
create unique index uk_wallet_holding_order_active on wallet_holding (order_id) where status <> 'RELEASED';

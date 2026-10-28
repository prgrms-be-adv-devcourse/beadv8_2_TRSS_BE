-- 결제 건에 주문번호 저장 (관리자 결제 조회에서 표시·검색, 예치금 거래 내역의 참조 번호)
-- 주문번호는 바뀌지 않으므로 결제 시점에 주문 컨텍스트가 넘긴 값을 그대로 둔다
alter table payment add column order_number varchar(40);

-- 이 칼럼을 추가하기 전의 결제 건은 주문 테이블에서 채운다
update payment p
set order_number = o.order_number
from market_order o
where o.id = p.order_id
  and p.order_number is null;

-- 주문이 없는 결제 건(개발용 시드)은 시드와 같은 형식으로 채운다 (PaymentCreateSeedHoldingsUseCase)
update payment
set order_number = 'SEED-ORDER' || order_id
where order_number is null;

alter table payment alter column order_number set not null;

-- 관리자 결제 조회의 주문번호 검색 (한 주문에 결제 시도가 여러 건이라 유니크 아님)
create index idx_payment_order_number on payment (order_number);

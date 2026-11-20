-- 충전 건의 결제 요청 시각. 결제창 요청 일시로 보내고 PG 조회의 원거래 일시로 쓴다 (헥토 조회는 주문번호·원거래 일자·금액으로 함)
-- 지금까지의 충전 건은 충전 준비 때 만들어졌으므로 생성 시각으로 채운다
alter table topup add column requested_at timestamp(6);
update topup set requested_at = created_at;
alter table topup alter column requested_at set not null;

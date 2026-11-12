-- 충전 취소: PG에 취소가 확정된 금액 합계와 취소 요청 중인 금액
-- 일부만 취소된 충전 건은 DONE으로 두고, 충전 금액 전부가 취소됐을 때만 CANCELED로 바꾼다
alter table topup add column canceled_amount bigint not null default 0;
alter table topup add column canceling_amount bigint not null default 0;

-- 충전 취소 회차. 취소를 시작할 때마다 1씩 늘고, 회차마다 다른 멱등 키를 만들어 거절된 뒤에도 다시 취소할 수 있게 한다
alter table topup add column cancel_attempts int not null default 0;

-- 원장 유형 CHARGE_CANCEL_RESTORE(21자)가 들어가도록 넓힌다
alter table wallet_transaction alter column type type varchar(30);

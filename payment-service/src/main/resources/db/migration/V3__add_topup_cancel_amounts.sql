-- 충전 취소: PG에 취소가 확정된 금액 합계와 취소 요청 중인 금액
-- 일부만 취소된 충전 건은 DONE으로 두고, 충전 금액 전부가 취소됐을 때만 CANCELED로 바꾼다
alter table topup add column canceled_amount bigint not null default 0;
alter table topup add column canceling_amount bigint not null default 0;

-- 원장 유형 CHARGE_CANCEL_RESTORE(21자)가 들어가도록 넓힌다
alter table wallet_transaction alter column type type varchar(30);

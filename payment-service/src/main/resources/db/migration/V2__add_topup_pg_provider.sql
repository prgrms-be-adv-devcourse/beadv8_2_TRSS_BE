-- 충전 건마다 결제한 PG를 기록한다. 승인·조회(대사)·취소를 같은 PG로 보내기 위함
-- 지금까지의 충전 건은 모두 토스페이먼츠라 기본값 TOSS로 채운다
alter table topup add column pg_provider varchar(20) not null default 'TOSS';

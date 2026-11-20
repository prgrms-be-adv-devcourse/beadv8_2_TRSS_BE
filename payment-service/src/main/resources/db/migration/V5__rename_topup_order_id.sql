-- 두 번째 PG(헥토) 연동: 주문번호는 PG와 상관없이 같은 값을 쓰므로 컬럼 이름에서 토스를 뺀다
-- 이름만 바꿔서 정렬 규칙(collate "C")과 유니크 인덱스는 그대로 유지된다
alter table topup rename column toss_order_id to order_id;

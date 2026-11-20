-- 옵션 재고를 product_option에서 option_stock으로 분리한다.
-- 옵션 정보(이름·추가금 등)를 수정하는 UPDATE가 재고 컬럼까지 옛 값으로 덮어쓰지 않게(lost update),
-- 그리고 옵션 수정과 주문 재고 차감이 같은 행 잠금을 두고 기다리지 않게 행을 나눈다.
-- 옵션 하나에 재고 행 하나다(option_id가 PK). 옵션 행이 지워지면 재고 행도 함께 지운다.
create table option_stock (
    stock integer not null,
    created_at timestamp(6) not null,
    option_id bigint not null,
    updated_at timestamp(6) not null,
    primary key (option_id),
    -- 재고는 0 미만이 될 수 없다. 애플리케이션 검사를 우회한 쓰기도 DB에서 막는다
    constraint ck_option_stock_stock check (stock >= 0)
);

alter table option_stock
   add constraint fk_option_stock_option_id
   foreign key (option_id)
   references product_option
   on delete cascade;

-- 기존 재고를 그대로 옮긴다
insert into option_stock (option_id, stock, created_at, updated_at)
select id, stock, created_at, updated_at
from product_option;

alter table product_option drop constraint ck_product_option_stock;
alter table product_option drop column stock;

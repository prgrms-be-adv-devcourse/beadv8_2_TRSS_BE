-- 다중 옵션(최대 3단 조합)과 옵션별 추가금
-- 옵션 이름(색상·사이즈 등)은 상품에, 각 조합의 옵션 값(흰색·M 등)과 추가금은 옵션(조합 한 줄)에 둔다.
-- 옵션 표시 이름("흰색 / M")은 값으로 계산하므로 product_option.name은 지운다. 옵션이 없는 상품은 값이 모두 null이고 "기본"으로 보인다.

-- 옵션 이름은 1번부터 채운다 (2번만 있거나 3번만 있는 상품은 없다)
alter table product
    add column option_name_1 varchar(20),
    add column option_name_2 varchar(20),
    add column option_name_3 varchar(20);

alter table product
    add constraint ck_product_option_names check (
        (option_name_2 is null or option_name_1 is not null)
        and (option_name_3 is null or option_name_2 is not null));

-- 옵션 값은 주문·리뷰의 option_name(varchar(50))에 "값 / 값 / 값"으로 들어가야 하므로 하나에 14자까지다 (14 x 3 + 구분자 6 = 48)
-- 단가 = 상품 가격 + 추가금. 추가금은 0 이상이다
alter table product_option
    add column option_value_1 varchar(14),
    add column option_value_2 varchar(14),
    add column option_value_3 varchar(14),
    add column additional_price bigint not null default 0;

alter table product_option
    add constraint ck_product_option_additional_price check (additional_price >= 0);

-- 기존 옵션은 모두 "기본"이다. 혹시 다른 이름이 있으면 그 상품을 1단 옵션("옵션")으로 보고 이름을 값으로 옮긴다
update product set option_name_1 = '옵션'
where id in (select product_id from product_option where name <> '기본');

update product_option o set option_value_1 = left(o.name, 14)
from product p
where p.id = o.product_id and p.option_name_1 is not null;

alter table product_option drop column name;

-- 한 상품 안에서 삭제되지 않은 옵션은 같은 조합이 둘일 수 없다. 단계가 적은 상품은 값이 null이므로 null끼리도 같은 값으로 본다
create unique index ux_product_option_values
    on product_option (product_id, option_value_1, option_value_2, option_value_3) nulls not distinct
    where deleted_at is null;

-- 재고는 0 미만이 될 수 없다. 애플리케이션 검사를 우회한 쓰기도 DB에서 막는다
alter table product_option
    add constraint ck_product_option_stock check (stock >= 0);

-- 품절 판단 서브쿼리는 상품으로 옵션을, 재고 이력 조회는 옵션으로 이력을 찾는다
create index idx_product_option_product on product_option (product_id);
create index idx_stock_history_option on stock_history (option_id);

-- 대분류(parent_id가 null)끼리도 같은 이름을 두 번 넣지 못하게 한다
alter table category drop constraint uk_category_parent_name;
alter table category
    add constraint uk_category_parent_name unique nulls not distinct (parent_id, name);

-- 기획서 1장의 기본 카테고리. 카테고리가 하나라도 있으면(이미 운영 중인 DB) 넣지 않는다
-- 넣은 순서가 정렬 순서가 된다 (대분류·소분류 모두 0부터)
do $$
begin
    if exists (select 1 from category) then
        return;
    end if;

    insert into category (name, parent_id, sort_order, is_active, created_at, updated_at)
    values ('뷰티', null, 0, true, now(), now()),
           ('의류', null, 1, true, now(), now());

    insert into category (name, parent_id, sort_order, is_active, created_at, updated_at)
    select child.name, root.id, child.sort_order, true, now(), now()
    from (values ('뷰티', '메이크업', 0), ('뷰티', '바디케어', 1), ('뷰티', '선케어', 2), ('뷰티', '마스크팩', 3),
                 ('뷰티', '클렌징', 4), ('뷰티', '스킨케어', 5), ('뷰티', '헤어케어', 6), ('뷰티', '건강식품', 7),
                 ('의류', '상의', 0), ('의류', '아우터', 1), ('의류', '바지', 2), ('의류', '원피스/스커트', 3),
                 ('의류', '신발', 4), ('의류', '가방', 5), ('의류', '모자', 6), ('의류', '소품', 7))
             as child(root_name, name, sort_order)
             join category root on root.parent_id is null and root.name = child.root_name;
end $$;

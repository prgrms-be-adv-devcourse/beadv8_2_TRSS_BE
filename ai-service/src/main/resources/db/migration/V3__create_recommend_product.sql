-- AI 추천용 상품 사본. market의 상품 변경 이벤트(ProductChangedEvent)로만 채운다. id = market product.id
-- 임베딩은 OpenAI text-embedding-3-small(1536차원). vector 타입은 init 스크립트가 ai 스키마에 만든 pgvector 확장이다.
-- 근사 인덱스(HNSW 등)는 두지 않는다: 피하는 성분 필터를 같은 SQL에 걸면 근사 검색은 결과 수를 보장하지 못한다. 상품 수가 적은 동안 순차 비교로 정확히 찾는다.
create table recommend_product (
    beauty boolean not null,
    ingredients_known boolean not null,   -- 전성분에서 성분명을 하나 이상 읽었는지. false면 피하는 성분을 고른 회원에게 추천하지 않는다
    on_sale boolean not null,
    created_at timestamp(6) not null,
    id bigint not null,
    last_event_at timestamp(6) not null,  -- 반영한 이벤트의 발생 시각. 더 오래된 이벤트는 버린다
    price bigint not null,
    updated_at timestamp(6) not null,
    beauty_category varchar(20),          -- 관심 카테고리 코드(BeautyCategory). 설문 카테고리가 아니면 null
    category_name varchar(50) not null,
    name varchar(100) not null,
    embedding_source_hash varchar(64) not null,   -- 임베딩한 글(카테고리·이름·설명)의 SHA-256. 바뀌면 임베딩을 지우고 다시 만든다
    thumbnail_url varchar(500),
    description text not null,
    ingredients_text text,
    ingredient_tags text[] not null,
    embedding vector(1536),               -- null이면 아직 임베딩 전(스케줄러가 채움). 추천에서 빠진다
    primary key (id)
);

-- 임베딩을 기다리는 판매 중 뷰티 상품 (스케줄러가 찾는다)
create index idx_recommend_product_embedding_pending on recommend_product (id) where embedding is null and on_sale and beauty;

-- 설문 임베딩. 설문을 저장하면 null로 지우고 다시 만든다
alter table beauty_profile add column embedding vector(1536);

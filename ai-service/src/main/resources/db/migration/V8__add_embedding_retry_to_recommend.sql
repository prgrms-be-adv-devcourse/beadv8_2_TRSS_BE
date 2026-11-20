-- 임베딩 실패 재시도 상태. 한 상품·설문이 계속 실패해도 나머지가 막히지 않게, 실패한 것은 다음 시도 시각까지 대상 조회에서 뺀다.
-- embedding_attempts: 연속 실패 횟수. 임베딩을 저장하거나 글이 바뀌면 0으로 되돌린다
-- embedding_retry_at: 이 시각이 지나야 다시 시도한다. null이면 바로 시도한다
alter table recommend_product add column embedding_attempts integer not null default 0;
alter table recommend_product add column embedding_retry_at timestamp(6);

alter table beauty_profile add column embedding_attempts integer not null default 0;
alter table beauty_profile add column embedding_retry_at timestamp(6);

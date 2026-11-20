-- 설문을 저장할 때 확인한 회원의 인플루언서 ID(회원 서비스 influencer.id). 인플루언서가 아니면 null.
-- 크리에이터 추천의 후보를 고를 때 쓰고, 승인 여부는 추천 요청 때 회원 서비스에 다시 묻는다(인플루언서 상태는 요청마다 확인).
alter table beauty_profile add column influencer_id bigint;

create index idx_beauty_profile_influencer on beauty_profile (influencer_id) where influencer_id is not null;

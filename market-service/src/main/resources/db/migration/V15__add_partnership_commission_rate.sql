-- 협약의 인플루언서 수수료율(%). 판매자가 요청할 때 정하고 인플루언서가 수락하면 확정된다. 정산에서 인플루언서 몫을 뗄 때 쓴다
-- 인플루언서 기능이 없어 아직 협약 행이 생길 수 없었으므로 기본값 없이 NOT NULL로 추가한다 (행이 있으면 이 마이그레이션이 실패한다)
alter table partnership
    add column commission_rate integer not null
        constraint ck_partnership_commission_rate check (commission_rate between 15 and 30);

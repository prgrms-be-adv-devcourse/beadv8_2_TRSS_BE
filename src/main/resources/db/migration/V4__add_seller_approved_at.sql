-- 판매자 승인일: 내 판매자 정보 화면에 보여 준다. 최근 상태 변경일은 철회 거절 등이 생기면 승인일과 달라진다.
-- 재신청하면 비우므로, 기존 판매자는 지금 신청(applied_at) 뒤의 승인 이력으로 채운다.

alter table seller
    add column approved_at timestamp(6);

update seller s
   set approved_at = (
       select max(h.changed_at)
         from seller_status_history h
        where h.seller_id = s.id
          and h.from_status = 'PENDING'
          and h.to_status = 'APPROVED'
          and h.changed_at >= s.applied_at
   );

-- 정산서 보류 종류. 보류를 푸는 경로(관리자 해제, 판매자 제재 해지, 원장 불일치 해소)가 종류마다 달라서
-- 사유 문자열 대신 이 값으로 구분한다(SettlementHoldType: ADMIN, SANCTION, LEDGER_MISMATCH). HOLD가 아니면 null
alter table settlement add column hold_type varchar(20);

-- 이미 HOLD인 정산서 채우기
-- 1) 원장 재대조 불일치 보류: 고정 사유(LedgerMismatchRule.REASON)
update settlement
   set hold_type = 'LEDGER_MISMATCH'
 where status = 'HOLD' and hold_type is null
   and hold_reason = '지난 정산 지급 원장 재대조 불일치가 풀리지 않아 지급을 보류합니다.';

-- 2) 판매자 제재 보류: 확정 단계 판별(SellerStatusRule.SANCTIONED_REASON), 지급 직전 확인(SettlementPayoutUseCase.SANCTIONED_HOLD_REASON)
update settlement
   set hold_type = 'SANCTION'
 where status = 'HOLD' and hold_type is null
   and hold_reason in ('관리자 제재된 판매자라 정산을 보류합니다.',
                       '판매자 제재 상태라 지급 직전에 보류(제재 이벤트 수신 전 지급 방지)');

-- 3) 판매자 제재 이벤트 보류: 사유는 관리자가 입력한 제재 사유라 문자열로는 알 수 없어, 마지막 보류 이력의 trigger(EVENT)로 찾는다
--    지금 HOLD를 만든 건 마지막 보류 이력뿐이라 그것만 본다
--    (제재 보류 → 관리자 해제 → 관리자 재보류처럼 예전 이력에만 EVENT가 있으면 관리자 보류다)
update settlement s
   set hold_type = 'SANCTION'
 where s.status = 'HOLD' and s.hold_type is null
   and (select h.trigger_type from settlement_process_history h
         where h.target_type = 'SETTLEMENT' and h.target_id = s.id and h.stage = 'HOLD'
         order by h.id desc
         limit 1) = 'EVENT';

-- 4) 나머지 HOLD는 관리자 보류
--    #225 전 SettlementApi.holdSeller로 판매자 제재 보류한 정산서도 여기로 온다. 이력이 관리자 보류와 똑같고
--    (trigger ADMIN, 관리자가 입력한 사유) 판매자 상태는 회원 서비스에만 있어 SQL로는 가를 수 없다.
--    제재 보류를 관리자 보류로 잘못 보면 제재 해지 후에도 HOLD로 남을 뿐이지만(관리자가 직접 해제),
--    관리자 보류를 제재 보류로 잘못 보면 제재 해지 이벤트에 풀려 지급되므로 관리자 보류 쪽으로 둔다
update settlement
   set hold_type = 'ADMIN'
 where status = 'HOLD' and hold_type is null;

-- 제재 해지 시 판매자의 제재 보류 정산서 조회(seller_id, status = HOLD, hold_type = SANCTION)
create index idx_settlement_seller_hold on settlement (seller_id, status, hold_type);

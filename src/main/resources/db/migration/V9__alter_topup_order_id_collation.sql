-- 관리자 충전 탭의 충전번호 앞부분 일치 검색(like 'TOPUP-…%')이 기존 유니크 인덱스를 타도록 바이트 순서(C)로 바꾼다
-- DB 기본 정렬 규칙(en_US.utf8)에서는 like 앞부분 일치에 일반 b-tree 인덱스를 쓰지 못한다
-- 충전번호는 TOPUP- + UUID(영문 소문자·숫자·하이픈)라 C로 바꿔도 비교 결과가 달라지지 않고, 유니크 인덱스는 새 규칙으로 다시 만들어진다
alter table topup alter column toss_order_id type varchar(64) collate "C";

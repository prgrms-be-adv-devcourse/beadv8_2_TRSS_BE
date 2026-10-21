-- 판매자는 회원 역할이 아니라 판매자 상태(seller.status = 'APPROVED')로 판단한다.
-- 역할에서 SELLER를 없애므로, 승인 때 SELLER가 된 회원을 USER로 되돌린다.
UPDATE member SET role = 'USER' WHERE role = 'SELLER';

-- 카카오 연결 회원의 프로필 이미지 주소. 동의하지 않았거나 기본 이미지면 null
-- 연결 정보와 함께 지워지도록 member_social_account에 둔다 (연결 해제 시 행 삭제)
alter table member_social_account
    add column profile_image_url varchar(500);

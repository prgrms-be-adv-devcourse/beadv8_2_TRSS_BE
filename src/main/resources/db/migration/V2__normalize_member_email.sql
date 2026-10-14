-- 이메일을 소문자로 정리하고, 대소문자만 다른 이메일로 다시 가입하는 것을 DB에서도 막는다.
-- 앱은 요청 DTO에서 이메일을 소문자로 바꾸므로(Member.normalizeEmail) 이 인덱스는 다른 경로로 들어온 값을 막는 안전장치다.

-- 대소문자만 다른 이메일이 이미 둘 이상 있으면 어느 계정을 남길지 사람이 정해야 하므로 멈춘다.
DO $$
DECLARE
    duplicated text;
BEGIN
    SELECT string_agg(lower_email, ', ') INTO duplicated
    FROM (SELECT lower(email) AS lower_email FROM member GROUP BY lower(email) HAVING count(*) > 1) d;

    IF duplicated IS NOT NULL THEN
        RAISE EXCEPTION '대소문자만 다른 중복 이메일이 있습니다. 계정을 정리한 뒤 다시 실행하세요: %', duplicated;
    END IF;
END $$;

UPDATE member SET email = lower(email) WHERE email <> lower(email);

CREATE UNIQUE INDEX uk_member_email_lower ON member (lower(email));

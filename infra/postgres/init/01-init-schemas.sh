#!/usr/bin/env bash
# 서비스별 스키마와 DB 계정을 만든다. 각 서비스는 자기 스키마만 읽고 쓸 수 있다(계정 = 스키마 소유자).
# 테이블은 여기서 만들지 않는다. 테이블은 각 서비스의 Flyway가 자기 스키마에 만든다.
#
# 실행
#   - 빈 DB(로컬·새 서버): postgres 컨테이너가 처음 뜰 때 /docker-entrypoint-initdb.d에서 자동 실행된다.
#     로컬은 docker compose -f infra/compose.yaml down -v 후 다시 up 하면 된다.
#   - 이미 데이터가 있는 DB(운영): 자동 실행되지 않으므로 서버에서 한 번 직접 실행한다.
#     docker exec -i palette-postgres bash < infra/postgres/init/01-init-schemas.sh
#
# 여러 번 실행해도 에러가 나지 않는다. 이미 있는 계정·스키마는 건너뛴다(비밀번호도 바꾸지 않는다).
# 비밀번호는 postgres 컨테이너의 환경변수(MEMBER_DB_PASSWORD 등)로 받는다.

set -euo pipefail

# 계정을 만들고, 그 계정이 소유한 스키마를 만든다. 접속하면 기본으로 자기 스키마를 보게 한다.
create_service_schema() {
    local schema="$1"
    local password="$2"

    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
         -v schema="$schema" -v role="${schema}_user" -v password="$password" <<'SQL'
-- CREATE ROLE에는 IF NOT EXISTS가 없어서, 없을 때만 실행하도록 \gexec로 만든다
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'role', :'password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role')\gexec

SELECT format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', :'schema', :'role')\gexec

SELECT format('ALTER ROLE %I SET search_path = %I', :'role', :'schema')\gexec
SQL

    echo "✔ ${schema} 스키마 / ${schema}_user 계정"
}

# public 스키마에 아무나 테이블을 만들지 못하게 한다. 서비스 테이블은 모두 자기 스키마에 둔다.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -c "REVOKE CREATE ON SCHEMA public FROM PUBLIC;"

create_service_schema member     "${MEMBER_DB_PASSWORD:?MEMBER_DB_PASSWORD가 없습니다}"
create_service_schema market     "${MARKET_DB_PASSWORD:?MARKET_DB_PASSWORD가 없습니다}"
create_service_schema payment    "${PAYMENT_DB_PASSWORD:?PAYMENT_DB_PASSWORD가 없습니다}"
create_service_schema settlement "${SETTLEMENT_DB_PASSWORD:?SETTLEMENT_DB_PASSWORD가 없습니다}"
create_service_schema ai         "${AI_DB_PASSWORD:?AI_DB_PASSWORD가 없습니다}"

# 모든 스키마를 읽기만 하는 계정. 팀원이 개발 서버 데이터를 확인할 때 쓴다(서버는 dbtunnel 터널로 접속).
# 스키마마다 지금 있는 테이블 + 앞으로 그 주인이 만들 테이블(DEFAULT PRIVILEGES)에 읽기 권한을 준다.
create_readonly_user() {
    local password="$1"
    # "스키마:주인" — public은 앱 계정(POSTGRES_USER)이, 나머지는 서비스 계정이 테이블을 만든다
    local schema_owners=(
        "public:${POSTGRES_USER}"
        "member:member_user"
        "market:market_user"
        "payment:payment_user"
        "settlement:settlement_user"
        "ai:ai_user"
    )

    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
         -v password="$password" <<'SQL'
SELECT format('CREATE ROLE readonly_user LOGIN PASSWORD %L', :'password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'readonly_user')\gexec
SQL

    local pair schema owner
    for pair in "${schema_owners[@]}"; do
        schema="${pair%%:*}"
        owner="${pair#*:}"
        psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
             -v schema="$schema" -v owner="$owner" <<'SQL'
SELECT format('GRANT USAGE ON SCHEMA %I TO readonly_user', :'schema')\gexec
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA %I TO readonly_user', :'schema')\gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT SELECT ON TABLES TO readonly_user', :'owner', :'schema')\gexec
SQL
    done

    echo "✔ readonly_user 계정 (모든 스키마 읽기만)"
}

create_readonly_user "${READONLY_DB_PASSWORD:?READONLY_DB_PASSWORD가 없습니다}"

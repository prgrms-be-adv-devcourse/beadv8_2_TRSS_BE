#!/usr/bin/env bash
# ES 계정 2개와 각 계정의 역할(권한 묶음)을 만든다.
#   market_es_user   : products* 인덱스 읽기·쓰기 (market 앱·상품 담당자)
#   readonly_es_user : 모든 인덱스 읽기만 (팀원 확인용)
# 역할·계정 API(PUT)는 없으면 만들고 있으면 덮어쓰므로, 다시 실행하면 비밀번호와 권한이 .env 값으로 맞춰진다.
#
# 실행(EC2-B, ES가 뜬 뒤, 레포 루트에서): bash infra/elasticsearch/init-users.sh
# 값은 infra/.env에서 읽는다. 로컬 ES에 쓸 때는 앞에 값을 붙인다:
#   ES_URL=http://localhost:9200 ELASTIC_PASSWORD=elastic MARKET_ES_PASSWORD=market_es_user READONLY_ES_PASSWORD=readonly_es_user bash infra/elasticsearch/init-users.sh

set -euo pipefail

main() {
    local env_file
    env_file="$(dirname "$0")/../.env"
    if [[ -f "$env_file" ]]; then
        # 명령 앞에 직접 붙인 값이 .env보다 우선하게, 비어 있는 변수만 .env에서 채운다
        while IFS='=' read -r key value; do
            [[ "$key" =~ ^[A-Z_]+$ && -z "${!key:-}" ]] && export "$key=$value"
        done < "$env_file"
    fi

    : "${ELASTIC_PASSWORD:?ELASTIC_PASSWORD가 없습니다}"
    : "${MARKET_ES_PASSWORD:?MARKET_ES_PASSWORD가 없습니다}"
    : "${READONLY_ES_PASSWORD:?READONLY_ES_PASSWORD가 없습니다}"
    ES_URL="${ES_URL:-http://${ES_BIND_IP:?ES_BIND_IP나 ES_URL이 필요합니다}:9200}"

    # 관리자 비밀번호가 ps 명령의 프로세스 목록에 보이지 않게, curl 인자 대신 권한 600인 임시 파일로 넘긴다
    AUTH_FILE="$(mktemp)"
    trap 'rm -f "$AUTH_FILE"' EXIT
    chmod 600 "$AUTH_FILE"
    printf 'user = "elastic:%s"\n' "$ELASTIC_PASSWORD" > "$AUTH_FILE"

    put "_security/role/market_es_role" '{
      "cluster": ["monitor"],
      "indices": [{ "names": ["products*"], "privileges": ["all"] }]
    }'
    put "_security/role/readonly_es_role" '{
      "cluster": ["monitor"],
      "indices": [{ "names": ["*"], "privileges": ["read", "view_index_metadata"] }]
    }'
    put "_security/user/market_es_user" "$(user_body "$MARKET_ES_PASSWORD" market_es_role)"
    put "_security/user/readonly_es_user" "$(user_body "$READONLY_ES_PASSWORD" readonly_es_role)"

    echo "✔ ES 계정 준비 완료 ($ES_URL)"
}

# 요청 본문은 표준 입력으로 보낸다(비밀번호가 프로세스 목록에 보이지 않게). 실패하면 ES의 오류 응답을 보여 주고 멈춘다
put() {
    local path="$1" body="$2" response
    if ! response="$(printf '%s' "$body" | curl -sS --fail-with-body -K "$AUTH_FILE" \
            -X PUT "$ES_URL/$path" -H 'Content-Type: application/json' --data-binary @-)"; then
        echo "✖ $path 실패: $response" >&2
        exit 1
    fi
    echo "• $path"
}

user_body() {
    local password="$1" role="$2"
    # JSON 문자열을 깨뜨리는 문자는 받지 않는다(openssl rand -base64 결과에는 없다)
    if [[ "$password" == *'"'* || "$password" == *'\'* ]]; then
        echo "✖ 비밀번호에 \" 나 \\ 는 쓸 수 없습니다" >&2
        exit 1
    fi
    printf '{ "password": "%s", "roles": ["%s"] }' "$password" "$role"
}

main "$@"

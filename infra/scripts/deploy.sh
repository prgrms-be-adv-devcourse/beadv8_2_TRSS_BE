#!/usr/bin/env bash
# 개발 서버(EC2)에서 앱을 다시 빌드·실행하고 앱이 제대로 실행됐는지 확인한다.
#
# 자동 배포: CI가 통과하면 GitHub Actions가 배포 전용 키로 SSH 접속한다. 서버의 authorized_keys에 고정한
#   infra/scripts/deploy-remote.sh가 그 커밋 SHA를 검증·체크아웃한 뒤 이 스크립트를 실행한다.
# 수동 배포: cd ~/palette && git pull && bash infra/scripts/deploy.sh
#
# 서버의 .env(infra/.env)는 Git이 관리하지 않으므로 배포해도 그대로 남는다.

set -euo pipefail

# 실패하면 마지막에 "어느 단계에서, 왜"를 두 줄로 남긴다. deploy.yml이 이 두 줄을 Slack 실패 알림에 붙인다.
#   ❌ 단계: 앱 실행 확인
#   ❌ 원인: palette-market 상태가 unhealthy입니다
# 원인을 따로 정하지 못한 실패(빌드 오류 등)는 단계만 알리고 자세한 내용은 Actions 로그에서 본다.
DEPLOY_STAGE="배포 준비"
DEPLOY_REASON=""
report_failure() {
    local rc=$?
    [ "$rc" -eq 0 ] && return
    echo "❌ 단계: ${DEPLOY_STAGE}"
    echo "❌ 원인: ${DEPLOY_REASON:-${DEPLOY_STAGE} 중 명령이 실패했습니다 (위 로그 참고)}"
}
trap report_failure EXIT

# 스크립트 전체를 함수로 읽은 뒤 실행한다. 실행 중 git이 이 파일을 바꿔도 영향받지 않게 하기 위함이다.
main() {
    local app_dir="${APP_DIR:-$HOME/palette}"
    # 앱마다 compose healthcheck 상태가 healthy인지 본다(compose.prod.yaml의 x-app-healthcheck).
    # 서비스가 분리되어 컨테이너가 늘면 여기에 추가한다
    local health_containers=(
        "palette-member"
        "palette-market"
        "palette-payment"
        "palette-settlement"
        "palette-delivery"
    )
    # 앱과 별개로, 재시작한 nginx(80)가 요청을 앱으로 넘기는지 본다. 로그인 없이 열리는 경로를 쓴다
    local nginx_url="http://localhost/v3/api-docs"
    # 앱 실행 확인 상한(초). 앱이 뜨는 중(starting)이면 이 시간까지 기다린다. 정상이면 닿지 않는 안전장치다
    local wait_limit="${DEPLOY_WAIT_LIMIT:-600}"
    local compose=(docker compose -f infra/compose.prod.yaml)

    cd "$app_dir"

    # 배포가 겹치면 뒤에 온 배포는 앞 배포가 끝날 때까지 기다린다.
    exec 9>/tmp/palette-deploy.lock
    flock 9

    echo "▶ 배포 대상: $(git log -1 --format='%h %s')"

    # 앱 로그의 service.version에 배포 커밋을 남긴다(compose.prod.yaml). Slack 에러 알림이 이 값으로 배포 커밋 링크를 단다
    export APP_VERSION
    APP_VERSION="$(git rev-parse --short=7 HEAD)"

    DEPLOY_STAGE="빌드·재시작"
    echo "▶ 빌드·재시작"
    "${compose[@]}" up -d --build --remove-orphans

    # infra/nginx/default.conf는 파일 하나를 bind mount해서, git pull로 파일이 바뀌어도 실행 중인 nginx는 예전 파일을 본다.
    # 재시작해야 새 설정을 읽는다(재시작하는 1~2초 동안 요청이 끊긴다).
    DEPLOY_STAGE="nginx 재시작"
    echo "▶ nginx 설정 다시 읽기"
    "${compose[@]}" restart nginx

    # 시간이 아니라 상태로 판단한다(compose.prod.yaml의 x-app-healthcheck).
    #   starting  : 뜨는 중 → 계속 기다린다
    #   healthy   : 다 떴다
    #   unhealthy : 시작 시간(start_period)이 지났는데도 응답이 없다 → 바로 실패
    DEPLOY_STAGE="앱 실행 확인"
    echo "▶ 앱 실행 확인 (unhealthy면 바로 실패, 최대 ${wait_limit}초)"
    local started=$SECONDS last_report=$SECONDS container status waiting
    while true; do
        waiting=()
        for container in "${health_containers[@]}"; do
            status="$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || echo "없음")"
            case "$status" in
                healthy) ;;
                starting) waiting+=("${container#palette-}") ;;
                없음)
                    fail_with_logs "${container} 컨테이너가 없습니다 (떴다가 종료됐을 수 있음)" "${container#palette-}"
                    return 1
                    ;;
                *)
                    fail_with_logs "${container} 상태가 ${status}입니다" "${container#palette-}"
                    return 1
                    ;;
            esac
        done

        # 앱이 다 떴으면 nginx를 거쳐서도 응답하는지 본다(nginx가 막 재시작했으면 잠깐 실패할 수 있어 다시 본다)
        if [ "${#waiting[@]}" -eq 0 ] && curl -fsS -o /dev/null "$nginx_url" 2>/dev/null; then
            echo "✅ 배포 완료 ($(( SECONDS - started ))초)"
            docker image prune -f > /dev/null   # 이전 빌드에서 남은 이미지 정리 (디스크 50GB 제한)
            return 0
        fi

        if (( SECONDS - started >= wait_limit )); then
            fail_with_logs "${wait_limit}초 안에 끝나지 않았습니다. 아직 뜨는 중: ${waiting[*]:-nginx}" "${waiting[@]}"
            return 1
        fi

        # 30초마다 아직 뜨는 중인 앱을 알려 준다(Actions 로그에서 어디서 오래 걸리는지 보이게)
        if (( SECONDS - last_report >= 30 )); then
            echo "  … $(( SECONDS - started ))초, 뜨는 중: ${waiting[*]:-없음 (nginx 확인 중)}"
            last_report=$SECONDS
        fi
        sleep 5
    done
}

# 실패 이유와 함께 컨테이너 상태, 문제 서비스의 최근 로그를 보여 준다(서비스를 못 집으면 앱 전체)
fail_with_logs() {
    DEPLOY_REASON="$1"
    shift
    local services=("$@")
    [ "${#services[@]}" -eq 0 ] && services=(member market payment settlement delivery)

    echo "✖ ${DEPLOY_REASON}"
    docker compose -f infra/compose.prod.yaml ps
    echo "▶ 최근 로그: ${services[*]}"
    docker compose -f infra/compose.prod.yaml logs --tail 80 "${services[@]}"
}

main "$@"
exit $?

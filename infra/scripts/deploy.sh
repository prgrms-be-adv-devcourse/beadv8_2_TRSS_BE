#!/usr/bin/env bash
# 개발 서버(EC2)에서 앱을 다시 빌드·실행하고 정상 기동을 확인한다.
#
# 자동 배포: CI가 통과하면 GitHub Actions가 배포 전용 키로 SSH 접속한다. 서버의 authorized_keys에 고정한
#   infra/scripts/deploy-remote.sh가 그 커밋 SHA를 검증·체크아웃한 뒤 이 스크립트를 실행한다.
# 수동 배포: cd ~/palette && git pull && bash infra/scripts/deploy.sh
#
# 서버의 .env(infra/.env)는 Git이 관리하지 않으므로 배포해도 그대로 남는다.

set -euo pipefail

# 스크립트 전체를 함수로 읽은 뒤 실행한다. 실행 중 git이 이 파일을 바꿔도 영향받지 않게 하기 위함이다.
main() {
    local app_dir="${APP_DIR:-$HOME/palette}"
    # 앱마다 compose healthcheck 상태가 healthy인지 본다(compose.prod.yaml의 x-app-healthcheck).
    # 서비스가 분리되어 컨테이너가 늘면 여기에 추가한다
    local health_containers=(
        "palette-app"
        "palette-member"
        "palette-market"
        "palette-settlement"
    )
    # 앱과 별개로, 재시작한 nginx(80)가 요청을 앱으로 넘기는지 본다. 로그인 없이 열리는 경로를 쓴다
    local nginx_url="http://localhost/v3/api-docs"
    local compose=(docker compose -f infra/compose.prod.yaml)

    cd "$app_dir"

    # 배포가 겹치면 뒤에 온 배포는 앞 배포가 끝날 때까지 기다린다.
    exec 9>/tmp/palette-deploy.lock
    flock 9

    echo "▶ 배포 대상: $(git log -1 --format='%h %s')"

    # 앱 로그의 service.version에 배포 커밋을 남긴다(compose.prod.yaml). Slack 에러 알림이 이 값으로 배포 커밋 링크를 단다
    export APP_VERSION
    APP_VERSION="$(git rev-parse --short=7 HEAD)"

    echo "▶ 빌드·재시작"
    "${compose[@]}" up -d --build --remove-orphans

    # infra/nginx/default.conf는 파일 하나를 bind mount해서, git pull로 파일이 바뀌어도 실행 중인 nginx는 예전 파일을 본다.
    # 재시작해야 새 설정을 읽는다(재시작하는 1~2초 동안 요청이 끊긴다).
    echo "▶ nginx 설정 다시 읽기"
    "${compose[@]}" restart nginx

    echo "▶ 기동 확인 (최대 150초)"
    local container healthy
    for _ in $(seq 1 30); do
        # 하나라도 준비되지 않으면 5초 뒤 처음부터 다시 본다
        healthy=true
        for container in "${health_containers[@]}"; do
            [ "$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null)" = "healthy" ] \
                || { healthy=false; break; }
        done
        if $healthy; then
            curl -fsS -o /dev/null "$nginx_url" || healthy=false
        fi
        if $healthy; then
            echo "✅ 배포 완료"
            docker image prune -f > /dev/null   # 이전 빌드에서 남은 이미지 정리 (디스크 50GB 제한)
            return 0
        fi
        sleep 5
    done

    echo "❌ 앱이 150초 안에 응답하지 않습니다. 최근 앱 로그:"
    "${compose[@]}" ps
    "${compose[@]}" logs --tail 80 app member market settlement
    return 1
}

main "$@"
exit $?

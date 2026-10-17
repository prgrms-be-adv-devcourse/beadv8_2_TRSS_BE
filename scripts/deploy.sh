#!/usr/bin/env bash
# 개발 서버(EC2)에서 앱을 다시 빌드·실행하고 정상 기동을 확인한다.
#
# 자동 배포: GitHub Actions가 배포 전용 키로 SSH 접속하면, 서버의 authorized_keys에 고정한 명령이
#   develop 최신 코드를 받은 뒤 이 스크립트를 실행한다 (키로는 이 동작만 할 수 있다).
# 수동 배포: cd ~/palette && git pull && bash scripts/deploy.sh
#
# 서버의 .env는 Git이 관리하지 않으므로 배포해도 그대로 남는다.

set -euo pipefail

# 스크립트 전체를 함수로 읽은 뒤 실행한다. 실행 중 git이 이 파일을 바꿔도 영향받지 않게 하기 위함이다.
main() {
    local app_dir="${APP_DIR:-$HOME/palette}"
    local health_url="http://localhost/v3/api-docs"   # nginx(80)를 거쳐 앱이 응답하는지 본다
    local compose=(docker compose -f compose.prod.yaml)

    cd "$app_dir"

    # 배포가 겹치면 뒤에 온 배포는 앞 배포가 끝날 때까지 기다린다.
    exec 9>/tmp/palette-deploy.lock
    flock 9

    echo "▶ 배포 대상: $(git log -1 --format='%h %s')"

    echo "▶ 빌드·재시작"
    "${compose[@]}" up -d --build --remove-orphans

    echo "▶ 기동 확인 (최대 150초)"
    for _ in $(seq 1 30); do
        if curl -fsS -o /dev/null "$health_url"; then
            echo "✅ 배포 완료"
            docker image prune -f > /dev/null   # 이전 빌드에서 남은 이미지 정리 (디스크 50GB 제한)
            return 0
        fi
        sleep 5
    done

    echo "❌ 앱이 150초 안에 응답하지 않습니다. 최근 앱 로그:"
    "${compose[@]}" ps
    "${compose[@]}" logs --tail 80 app
    return 1
}

main "$@"
exit $?

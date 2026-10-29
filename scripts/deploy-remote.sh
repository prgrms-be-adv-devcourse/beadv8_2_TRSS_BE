#!/usr/bin/env bash
# 자동 배포 진입점. 서버의 authorized_keys가 배포 전용 키로 접속하면 이 스크립트만 실행하도록 고정한다.
#   command="bash ~/palette/scripts/deploy-remote.sh",no-pty,... ssh-ed25519 AAAA...
#
# GitHub Actions(deploy.yml)는 CI가 통과한 커밋을 "deploy <40자리 SHA>"로 넘기고, 그 값은 $SSH_ORIGINAL_COMMAND로 들어온다.
# 키가 유출돼도 develop에 있는 커밋 배포 말고는 할 수 없도록 입력을 엄격히 검사한 뒤
# 그 커밋으로 체크아웃하고 scripts/deploy.sh를 실행한다.
#
# 배포 순서는 deploy.yml의 concurrency가 한 번에 하나씩 보장한다.

set -euo pipefail

# 스크립트 전체를 함수로 읽은 뒤 실행한다. 체크아웃으로 이 파일이 바뀌어도 영향받지 않게 하기 위함이다.
main() {
    local app_dir="${APP_DIR:-$HOME/palette}"
    local request="${SSH_ORIGINAL_COMMAND:-}"

    # 정확히 "deploy <소문자 40자리 SHA>"만 받는다. 짧은 SHA·브랜치 이름·추가 명령은 모두 거부한다.
    if [[ ! "$request" =~ ^deploy\ ([0-9a-f]{40})$ ]]; then
        echo "❌ 허용되지 않은 요청입니다. 형식: deploy <40자리 커밋 SHA>" >&2
        return 1
    fi
    local sha="${BASH_REMATCH[1]}"

    cd "$app_dir"

    git fetch --quiet origin develop

    # develop에 있는 커밋만 배포한다.
    if ! git merge-base --is-ancestor "$sha" origin/develop 2>/dev/null; then
        echo "❌ develop에 없는 커밋입니다: $sha" >&2
        return 1
    fi

    # CI가 늦게 끝난 이전 커밋이 이미 배포된 최신 커밋을 덮어쓰지 않게 한다. (같은 커밋 재배포는 허용)
    if ! git merge-base --is-ancestor HEAD "$sha"; then
        echo "⏭ 이미 더 최신 커밋이 배포되어 있어 건너뜁니다. 현재: $(git rev-parse --short HEAD), 요청: ${sha:0:7}"
        return 0
    fi

    # 로컬 develop을 요청한 커밋에 맞춘다. 서버에서 수동으로 git pull 해도 계속 동작한다.
    git checkout --quiet --force -B develop "$sha"

    bash scripts/deploy.sh
}

main "$@"
exit $?

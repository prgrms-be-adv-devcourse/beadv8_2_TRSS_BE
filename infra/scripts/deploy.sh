#!/usr/bin/env bash
# 개발 서버(EC2)에서 바뀐 앱만 다시 빌드·실행하고 앱이 제대로 실행됐는지 확인한다.
#
# 자동 배포: CI가 통과하면 GitHub Actions가 배포 전용 키로 SSH 접속한다. 서버의 authorized_keys에 고정한
#   infra/scripts/deploy-remote.sh가 그 커밋 SHA를 검증·체크아웃한 뒤 이 스크립트를 실행한다.
# 수동 배포: cd ~/palette && git pull && bash infra/scripts/deploy.sh
#   앱을 전부 다시 빌드하려면: DEPLOY_ALL=1 bash infra/scripts/deploy.sh
#
# 서버의 .env(infra/.env)는 Git이 관리하지 않으므로 배포해도 그대로 남는다.
#
# ai-service만 Compose가 아니라 같은 서버의 k3s(쿠버네티스)에서 돈다(TRSS-327, infra/k8s/). ai는 따로 처리한다:
#   빌드(palette-ai:<커밋>) → k3s에 넣기 → .env로 Secret 갱신 → 설정 적용 → 새 파드가 준비될 때까지 기다리기(실패하면 이전 버전으로 되돌림)
# Compose 앱을 다시 만들기 전에 ai를 먼저 배포한다. gateway가 새 주소로 바뀌는 순간 k3s의 ai가 이미 준비돼 있게 하기 위함이다.
# 처음 한 번 k3s를 설치해야 한다: sudo bash infra/scripts/setup-k3s.sh

set -euo pipefail

# Compose 앱 서비스 목록. compose.prod.yaml의 서비스 이름이고, 소스 폴더는 module_dir(gateway만 gateway, 나머지는 <이름>-service), 컨테이너는 palette-<이름>이다.
# 서비스가 분리되면 여기에 추가한다. ai는 k3s에서 돌아 여기에 없다(아래 AI_*)
APP_SERVICES=(gateway member market payment settlement delivery notification)

# k3s에서 도는 ai. 이미지 이름, 소스 폴더, 쿠버네티스 네임스페이스
AI_IMAGE=palette-ai
AI_MODULE=ai-service
AI_NAMESPACE=palette
# ai 이미지에 들어가는 공통 파일. 아래 SHARED_PATHS에서 compose.prod.yaml만 뺐다(ai 이미지는 compose 설정을 쓰지 않는다)
AI_SHARED_PATHS='^(common/|gradle/|gradlew$|build\.gradle\.kts$|settings\.gradle\.kts$|lombok\.config$|Dockerfile$)'

# 서비스 이름 → 소스 폴더(Gradle 모듈) 이름
module_dir() {
    if [ "$1" = gateway ]; then echo gateway; else echo "$1-service"; fi
}

# 모니터링 서비스 목록. infra/monitoring/<이름>/ 설정 파일을 bind mount하는 compose 서비스다.
# 첫 배포·DEPLOY_ALL=1이면 전부 재시작해 설정을 다시 읽게 한다.
MONITORING_SERVICES=(alloy grafana loki prometheus)

# 모든 앱 이미지에 들어가는 파일. 하나라도 바뀌면 앱을 전부 다시 빌드한다.
#   common/(공통 코드), Gradle 설정, Dockerfile, infra/compose.prod.yaml(앱 build.args 등이 여기 있어 바뀌면 이미지를 다시 만든다)
SHARED_PATHS='^(common/|gradle/|gradlew$|build\.gradle\.kts$|settings\.gradle\.kts$|lombok\.config$|Dockerfile$|infra/compose\.prod\.yaml$)'

# 마지막으로 배포에 성공한 커밋. 이번 커밋과 비교해 바뀐 앱을 고른다. 저장소 밖(서버 홈)에 둬서 git이 건드리지 않는다
STATE_FILE="${DEPLOY_STATE_FILE:-$HOME/.palette-last-deployed}"

# 실패하면 마지막에 "어느 단계에서, 왜"를 두 줄로 남긴다. deploy.yml이 이 두 줄을 Slack 실패 알림에 붙인다.
#   ❌ 단계: 앱 실행 확인
#   ❌ 원인: palette-market 상태가 unhealthy입니다
# 원인을 따로 정하지 못한 실패(빌드 오류 등)는 단계만 알리고 자세한 내용은 Actions 로그에서 본다.
DEPLOY_STAGE="배포 준비"
DEPLOY_REASON=""
report_failure() {
    local rc=$?
    end_gateway_alert_silence   # 성공·실패 모두 배포 중 무음을 푼다(❌ 두 줄보다 먼저 출력되게 여기서 부른다)
    [ "$rc" -eq 0 ] && return
    echo "❌ 단계: ${DEPLOY_STAGE}"
    echo "❌ 원인: ${DEPLOY_REASON:-${DEPLOY_STAGE} 중 명령이 실패했습니다 (위 로그 참고)}"
}
trap report_failure EXIT

# 배포하는 동안 "gateway 서비스 응답 실패" 알림(infra/monitoring/grafana/provisioning/alerting/rules.yaml)만 Grafana 무음(silence)으로 꺼 둔다.
# 서비스가 재시작되는 동안 gateway가 연결에 실패하는 건 예상된 일이라서다. 그 밖의 알림(앱 ERROR 로그)은 끄지 않는다.
#   - 스크립트가 중간에 죽어도 무음이 계속 남지 않게, 걸 때 끝나는 시각을 정해 둔다(배포 대기 상한 + 5분)
#   - 배포가 끝나면(성공·실패 모두, 위 trap) 끝나는 시각을 2분 뒤로 당긴다. 규칙이 최근 1분만 세므로 배포 중 실패가 빠진 뒤에 풀린다
#   - Grafana가 꺼져 있거나 응답이 없으면 경고만 남기고 배포는 계속한다
# Grafana는 서버 안에서만 열린 127.0.0.1:3000에 /grafana 아래로 뜬다(compose.prod.yaml). 관리자 비밀번호는 infra/.env에 있다
GRAFANA_SILENCE_API="http://127.0.0.1:3000/grafana/api/alertmanager/grafana/api/v2/silences"
GATEWAY_ALERT_RULE_UID="palette-gateway-connection-failure"
GATEWAY_SILENCE_ID=""

# 무음을 새로 걸거나(첫 인자 비움) 이미 건 무음의 끝 시각을 바꾼다(첫 인자 = 무음 id). 성공하면 무음 id를 출력한다
put_gateway_alert_silence() {
    local id="$1" ends_in_seconds="$2" password body
    password="$(grep -m1 '^GRAFANA_ADMIN_PASSWORD=' infra/.env | cut -d= -f2- | tr -d '\r"')"
    body="$(printf '{%s"matchers":[{"name":"__alert_rule_uid__","value":"%s","isRegex":false,"isEqual":true}],"startsAt":"%s","endsAt":"%s","createdBy":"deploy.sh","comment":"배포 중 서비스 재시작으로 gateway 연결 실패가 예상됨"}' \
        "${id:+\"id\":\"$id\",}" "$GATEWAY_ALERT_RULE_UID" \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(date -u -d "+${ends_in_seconds} seconds" +%Y-%m-%dT%H:%M:%SZ)")"
    curl -fsS -m 5 -u "admin:${password}" -H 'Content-Type: application/json' -d "$body" "$GRAFANA_SILENCE_API" \
        | sed -n 's/.*"silenceID":"\([^"]*\)".*/\1/p'
}

end_gateway_alert_silence() {
    [ -z "$GATEWAY_SILENCE_ID" ] && return 0
    put_gateway_alert_silence "$GATEWAY_SILENCE_ID" 120 > /dev/null \
        || echo "⚠ gateway 응답 실패 알림 무음의 끝 시각을 바꾸지 못했습니다. 처음 정한 시각에 풀립니다"
}

# 스크립트 전체를 함수로 읽은 뒤 실행한다. 실행 중 git이 이 파일을 바꿔도 영향받지 않게 하기 위함이다.
main() {
    local app_dir="${APP_DIR:-$HOME/palette}"
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
    local current
    current="$(git rev-parse HEAD)"

    # 다시 빌드한 앱 이미지에 배포 커밋을 넣는다(Dockerfile의 APP_VERSION). 로그 service.version → Slack 에러 알림의 커밋 링크
    export APP_VERSION="${current:0:7}"

    DEPLOY_STAGE="바뀐 앱 고르기"
    echo "▶ 바뀐 앱 고르기"
    pick_changed_apps "$current"

    # k3s에 접속되는지, 지금 떠 있는 ai 이미지 태그가 무엇인지 본다. k3s에 ai가 아직 없으면(처음 배포) ai를 빌드한다
    DEPLOY_STAGE="k3s 확인"
    if ! kubectl get nodes > /dev/null 2>&1; then
        DEPLOY_REASON="k3s에 접속하지 못했습니다. 처음이면 sudo bash infra/scripts/setup-k3s.sh 로 설치하세요"
        return 1
    fi
    AI_DEPLOYED_TAG="$(kubectl -n "$AI_NAMESPACE" get deploy ai -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null | sed 's/^.*://' || true)"
    if [ -z "$AI_DEPLOYED_TAG" ] && ! $BUILD_AI; then
        echo "  k3s에 ai가 아직 없음 → ai 빌드"
        BUILD_AI=true
    fi

    DEPLOY_STAGE="빌드·재시작"
    # Dockerfile의 공통 빌드 단계가 바뀐 앱 jar만 만들게 한다(기본은 jar 전부). 예: "gateway market-service ai-service"
    # Compose 앱과 ai에 같은 목록을 넘겨야 공통 빌드 단계(Gradle)를 한 번만 돌고 캐시를 같이 쓴다
    local modules="" s
    for s in "${BUILD_SERVICES[@]}"; do modules+="$(module_dir "$s") "; done
    $BUILD_AI && modules+="${AI_MODULE} "
    if [ "${#BUILD_SERVICES[@]}" -gt 0 ]; then
        echo "▶ 빌드: ${BUILD_SERVICES[*]}"
        "${compose[@]}" build --build-arg SERVICES="$modules" "${BUILD_SERVICES[@]}"
    else
        echo "▶ 빌드(Compose 앱): 바뀐 앱이 없어 건너뜁니다"
    fi
    # 재시작하는 동안 gateway 응답 실패 알림을 끈다(위 put_gateway_alert_silence 설명 참고)
    GATEWAY_SILENCE_ID="$(put_gateway_alert_silence "" $(( wait_limit + 300 )) || true)"
    if [ -n "$GATEWAY_SILENCE_ID" ]; then
        echo "▶ gateway 응답 실패 알림 무음 (배포가 끝나고 2분 뒤 풀림)"
    else
        echo "⚠ gateway 응답 실패 알림을 무음으로 하지 못했습니다(Grafana 확인). 배포는 계속합니다"
    fi

    # ai를 Compose 재시작보다 먼저 배포한다(머리말 참고). 실패하면 여기서 멈춰 Compose 앱(gateway 주소 포함)은 그대로 둔다
    DEPLOY_STAGE="ai(k3s) 배포"
    deploy_ai "$modules" "$wait_limit"

    DEPLOY_STAGE="빌드·재시작"
    # 이미지나 설정(compose.prod.yaml)이 바뀐 컨테이너만 다시 만든다. 그대로인 앱은 재시작되지 않는다
    echo "▶ 재시작 (이미지·설정이 바뀐 컨테이너만)"
    "${compose[@]}" up -d --remove-orphans

    # infra/nginx/default.conf는 파일 하나를 bind mount해서, git pull로 파일이 바뀌어도 실행 중인 nginx는 예전 파일을 본다.
    # 재시작해야 새 설정을 읽는다(재시작하는 1~2초 동안 요청이 끊기므로 설정이 바뀐 배포에서만 한다).
    DEPLOY_STAGE="nginx 재시작"
    if $RESTART_NGINX; then
        echo "▶ nginx 설정 다시 읽기"
        "${compose[@]}" restart nginx
    else
        # 다시 만든 앱 컨테이너는 IP가 바뀔 수 있지만, nginx는 앱 주소를 요청할 때 찾으므로(nginx/default.conf의 resolver) 그대로 둔다
        echo "▶ nginx: 설정이 그대로라 재시작하지 않습니다"
    fi

    # 모니터링 설정 파일도 bind mount라 git pull만으로 실행 중인 컨테이너에 반영되지 않는다(Grafana provisioning은 뜰 때만 읽음).
    # 모니터링 컨테이너는 앱 실행 확인 대상이 아니라 배포 성공 판단에는 넣지 않는다
    DEPLOY_STAGE="모니터링 재시작"
    if [ "${#RESTART_MONITORING[@]}" -gt 0 ]; then
        echo "▶ 모니터링 설정 다시 읽기: ${RESTART_MONITORING[*]}"
        "${compose[@]}" restart "${RESTART_MONITORING[@]}"
    fi

    # 시간이 아니라 상태로 판단한다(compose.prod.yaml의 x-app-healthcheck).
    #   starting  : 뜨는 중 → 계속 기다린다
    #   healthy   : 다 떴다
    #   unhealthy : 시작 시간(start_period)이 지났는데도 응답이 없다 → 바로 실패
    DEPLOY_STAGE="앱 실행 확인"
    echo "▶ 앱 실행 확인 (unhealthy면 바로 실패, 최대 ${wait_limit}초)"
    local started=$SECONDS last_report=$SECONDS service status waiting
    while true; do
        waiting=()
        for service in "${APP_SERVICES[@]}"; do
            status="$(docker inspect -f '{{.State.Health.Status}}' "palette-${service}" 2>/dev/null || echo "없음")"
            case "$status" in
                healthy) ;;
                starting) waiting+=("$service") ;;
                없음)
                    fail_with_logs "palette-${service} 컨테이너가 없습니다 (떴다가 종료됐을 수 있음)" "$service"
                    return 1
                    ;;
                *)
                    fail_with_logs "palette-${service} 상태가 ${status}입니다" "$service"
                    return 1
                    ;;
            esac
        done

        # 앱이 다 떴으면 nginx를 거쳐서도 응답하는지 본다(nginx가 막 재시작했으면 잠깐 실패할 수 있어 다시 본다)
        if [ "${#waiting[@]}" -eq 0 ] && curl -fsS -o /dev/null "$nginx_url" 2>/dev/null; then
            echo "✅ 배포 완료 ($(( SECONDS - started ))초)"
            # 다음 배포는 이 커밋과 비교한다. 실패한 배포는 기록하지 않아 다음 배포에서 다시 빌드된다
            echo "$current" > "$STATE_FILE"
            clean_up_disk
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

# 마지막 배포 커밋과 이번 커밋을 비교해 다시 빌드할 앱(BUILD_SERVICES), nginx 재시작 여부(RESTART_NGINX),
# 재시작할 모니터링 컨테이너(RESTART_MONITORING)를 정한다.
#   - 기록이 없거나(첫 배포) 비교할 수 없으면, 또는 DEPLOY_ALL=1이면 → 앱 전부, nginx·모니터링 재시작
#   - 공통 파일(SHARED_PATHS)이 바뀌면 → 앱 전부
#   - 그 앱 소스 폴더(module_dir, 예: gateway/, market-service/) 아래가 바뀌면 → 그 앱만
#   - infra/nginx/ 아래가 바뀌면 → nginx 재시작
#   - infra/monitoring/<이름>/ 아래가 바뀌면 → 같은 이름의 compose 서비스 재시작(alloy·grafana·loki·prometheus)
pick_changed_apps() {
    local current="$1" last="" changed shared service monitoring
    BUILD_SERVICES=()
    BUILD_AI=false
    RESTART_NGINX=false
    RESTART_MONITORING=()
    [ -f "$STATE_FILE" ] && last="$(cat "$STATE_FILE")"

    if [ "${DEPLOY_ALL:-0}" = 1 ]; then
        echo "  DEPLOY_ALL=1 → 앱 전부"
        BUILD_SERVICES=("${APP_SERVICES[@]}")
        BUILD_AI=true
        RESTART_NGINX=true
        RESTART_MONITORING=("${MONITORING_SERVICES[@]}")
        return
    fi
    # 기록한 커밋이 서버 git에 없거나, 이번 커밋의 조상이 아니면(되돌린 배포 등) 비교하지 않고 전부 빌드한다
    if [ -z "$last" ] || ! git merge-base --is-ancestor "$last" "$current" 2>/dev/null; then
        echo "  마지막 배포 기록이 없거나 비교할 수 없음 → 앱 전부"
        BUILD_SERVICES=("${APP_SERVICES[@]}")
        BUILD_AI=true
        RESTART_NGINX=true
        RESTART_MONITORING=("${MONITORING_SERVICES[@]}")
        return
    fi

    changed="$(git diff --name-only "$last" "$current")"
    echo "  ${last:0:7} → ${current:0:7}, 바뀐 파일 $(grep -c . <<< "$changed" || true)개"

    shared="$(grep -E "$SHARED_PATHS" <<< "$changed" || true)"
    if [ -n "$shared" ]; then
        echo "  공통 파일이 바뀜 → 앱 전부 ($(head -3 <<< "$shared" | tr '\n' ' '))"
        BUILD_SERVICES=("${APP_SERVICES[@]}")
    else
        for service in "${APP_SERVICES[@]}"; do
            grep -q "^$(module_dir "$service")/" <<< "$changed" && BUILD_SERVICES+=("$service")
        done
    fi
    # ai는 공통 파일(compose.prod.yaml 제외)이나 ai-service/ 아래가 바뀌면 다시 빌드한다. k3s 설정(infra/k8s/)은 매 배포 적용하므로 여기서 보지 않는다
    if grep -qE "$AI_SHARED_PATHS|^${AI_MODULE}/" <<< "$changed"; then
        BUILD_AI=true
    fi
    $BUILD_AI && echo "  ai 다시 빌드"
    grep -q '^infra/nginx/' <<< "$changed" && RESTART_NGINX=true
    # 바뀐 파일이 없으면 grep이 실패하므로 || true (set -e)
    monitoring="$(grep -oE '^infra/monitoring/[a-z]+/' <<< "$changed" || true)"
    if [ -n "$monitoring" ]; then
        RESTART_MONITORING=($(cut -d/ -f3 <<< "$monitoring" | sort -u))
    fi
    return 0
}

# k3s의 ai를 배포한다(머리말 참고). 실패하면 이전 버전으로 되돌리고 DEPLOY_REASON을 남긴 뒤 1을 돌려준다.
#   $1: 공통 빌드 단계에 넘길 모듈 목록(Compose 앱 빌드와 같은 값이라야 Gradle을 한 번만 돈다)
#   $2: 새 파드가 준비될 때까지 기다릴 최대 시간(초)
deploy_ai() {
    local modules="$1" wait_limit="$2" tag="$AI_DEPLOYED_TAG" names env_text env_hash
    AI_PREVIOUS_TAG="$AI_DEPLOYED_TAG"

    if $BUILD_AI; then
        tag="$APP_VERSION"
        echo "▶ ai 빌드 (${AI_IMAGE}:${tag})"
        docker build --build-arg MODULE="$AI_MODULE" --build-arg SERVICES="$modules" \
            --build-arg APP_VERSION="$APP_VERSION" -t "${AI_IMAGE}:${tag}" .
        # k3s는 Docker 이미지를 바로 못 본다. 레지스트리 없이 서버 안에서 넘겨 준다
        echo "▶ ai 이미지를 k3s에 넣기"
        docker save "${AI_IMAGE}:${tag}" | sudo k3s ctr images import - > /dev/null
    else
        echo "▶ ai: 바뀐 코드가 없어 지금 이미지를 그대로 씁니다 (${AI_IMAGE}:${tag})"
    fi

    # .env를 그대로 넣지 않고 Compose가 해석한 값(따옴표 제거·중복 이름 처리)에서 .env에 있는 이름만 골라 Secret으로 만든다.
    # Compose 앱이 받는 값과 똑같아진다(infra/k8s/ai/deployment.yaml 머리말). 값은 화면에 찍지 않는다
    echo "▶ ai 설정(Secret) 갱신"
    names="$(grep -oE '^[A-Za-z_][A-Za-z0-9_]*' infra/.env | sort -u | paste -sd'|')"
    env_text="$(docker compose -f infra/compose.prod.yaml config --environment | grep -E "^(${names})=")"
    kubectl apply -f infra/k8s/namespace.yaml > /dev/null
    kubectl -n "$AI_NAMESPACE" create secret generic ai-env --from-env-file=<(printf '%s\n' "$env_text") \
        --dry-run=client -o yaml | kubectl apply -f - > /dev/null
    # Secret 내용의 지문. .env만 바뀌어도 이 값이 바뀌어 파드가 새 값으로 다시 뜬다(deployment.yaml의 palette/env-hash)
    env_hash="$(printf '%s' "$env_text" | sha256sum | cut -c1-16)"

    # 자동 스케일링(Adapter·HPA)·로그·지표 수집기(Alloy)까지 매 배포 적용한다. 바뀐 게 없으면 쿠버네티스가 아무것도 안 한다
    echo "▶ k3s 설정 적용 (ai · 자동 스케일링 · 로그·지표 수집)"
    kubectl apply -f infra/k8s/prometheus-adapter.yaml -f infra/k8s/alloy.yaml > /dev/null
    sed -e "s/IMAGE_TAG/${tag}/" -e "s/ENV_HASH/${env_hash}/" infra/k8s/ai/deployment.yaml | kubectl apply -f - > /dev/null
    kubectl apply -f infra/k8s/ai/service.yaml -f infra/k8s/ai/hpa.yaml > /dev/null

    # 새 파드가 준비 검사를 통과하고 옛 파드가 내려갈 때까지 기다린다. 바뀐 게 없으면 바로 끝난다
    echo "▶ ai 롤링 업데이트 확인 (최대 ${wait_limit}초)"
    if ! kubectl -n "$AI_NAMESPACE" rollout status deploy/ai --timeout="${wait_limit}s"; then
        DEPLOY_REASON="ai(k3s) 새 파드가 ${wait_limit}초 안에 준비되지 않아 이전 버전으로 되돌렸습니다"
        echo "✖ ${DEPLOY_REASON}"
        kubectl -n "$AI_NAMESPACE" get pods -l app=ai
        echo "▶ 최근 로그: ai 파드"
        kubectl -n "$AI_NAMESPACE" logs -l app=ai --tail 80 --prefix 2>&1 || true
        kubectl -n "$AI_NAMESPACE" rollout undo deploy/ai > /dev/null || true
        return 1
    fi
    AI_DEPLOYED_TAG="$tag"
}

# 배포가 끝나면 디스크를 정리한다. 배포마다 빌드 캐시·이미지가 쌓여 디스크(50GB)를 채운다(2026-11-19 빌드 캐시만 24GB, 82%).
# 쿠버네티스는 디스크 85%부터 이미지를 지우고 90%부터 파드를 쫓아내므로 미리 비운다. 정리가 실패해도 배포 결과는 바꾸지 않는다
clean_up_disk() {
    docker image prune -f > /dev/null || true   # 이름 없는 옛 이미지
    # 빌드 캐시는 최근에 쓴 5GB만 남긴다. 다 지우면 다음 빌드가 Gradle 의존성부터 다시 받아 느려진다
    docker builder prune -f --keep-storage 5GB > /dev/null || true
    # ai 이미지: Docker 쪽은 k3s에 넣고 나면 필요 없어 지금 태그만 남긴다
    docker images "$AI_IMAGE" --format '{{.Repository}}:{{.Tag}}' | grep -vx "${AI_IMAGE}:${AI_DEPLOYED_TAG}" \
        | xargs -r docker rmi > /dev/null 2>&1 || true
    # k3s 쪽은 지금 태그와 바로 전 태그만 남긴다. 바로 전 버전으로 손으로 되돌릴 수 있게(kubectl rollout undo)
    sudo k3s ctr images ls -q | grep "^docker.io/library/${AI_IMAGE}:" \
        | grep -v -e ":${AI_DEPLOYED_TAG}\$" -e ":${AI_PREVIOUS_TAG:-없음}\$" \
        | xargs -r sudo k3s ctr images rm > /dev/null 2>&1 || true
}

# 실패 이유와 함께 컨테이너 상태, 문제 서비스의 최근 로그를 보여 준다(서비스를 못 집으면 앱 전체)
fail_with_logs() {
    DEPLOY_REASON="$1"
    shift
    local services=("$@")
    [ "${#services[@]}" -eq 0 ] && services=("${APP_SERVICES[@]}")

    echo "✖ ${DEPLOY_REASON}"
    docker compose -f infra/compose.prod.yaml ps
    echo "▶ 최근 로그: ${services[*]}"
    docker compose -f infra/compose.prod.yaml logs --tail 80 "${services[@]}"
}

main "$@"
exit $?

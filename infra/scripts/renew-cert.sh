#!/usr/bin/env bash
# Let's Encrypt 인증서(90일)를 갱신하고, 새로 받았으면 nginx가 다시 읽게 한다.
# 만료 30일 전부터만 실제로 갱신하므로 매일 돌려도 된다.
# 갱신이 실패하거나 만료까지 20일 이하로 남으면 팀 Slack 채널에 알린다(문제가 계속되면 매일 한 번).
#
# 서버 root cron에 등록 (sudo crontab -e):
#   30 4 * * * bash /home/ubuntu/palette/infra/scripts/renew-cert.sh >> /var/log/palette-renew-cert.log 2>&1
# 연습 실행 (실제로 받지 않고, Slack에도 보내지 않음): sudo bash infra/scripts/renew-cert.sh --dry-run

set -euo pipefail

DOMAIN="palette-shop.duckdns.org"
ALERT_DAYS=20                  # 만료까지 이 일수 이하로 남으면 알림 (정상이면 30일 전에 갱신됨)
SLACK_CHANNEL="C0C3CMPUP2B"    # 팀 알림 채널 (deploy.yml, grafana slack.yaml과 같은 채널)
LOG_FILE="/var/log/palette-renew-cert.log"

# 실행 중에 git이 이 파일을 바꿔도 영향받지 않게 함수로 감싼다 (deploy.sh와 같음)
main() {
    local app_dir="${APP_DIR:-/home/ubuntu/palette}"
    local live_cert="/etc/letsencrypt/live/${DOMAIN}/fullchain.pem"
    local dry_run=false
    [[ " $* " == *" --dry-run "* ]] && dry_run=true

    echo "▶ $(date '+%F %T') 인증서 갱신 확인"

    # 갱신되면 live 인증서가 가리키는 실제 파일이 바뀐다. 전후를 비교해 갱신 여부를 판단한다
    local before after renew_ok=true
    before="$(readlink -f "$live_cert")"

    # nginx를 멈추지 않고 갱신한다 (확인용 파일은 nginx가 보여 줌)
    docker run --rm \
        -v /etc/letsencrypt:/etc/letsencrypt \
        -v "${app_dir}/infra/certbot/www:/var/www/certbot" \
        certbot/certbot renew --webroot -w /var/www/certbot --no-random-sleep-on-renew "$@" \
        || renew_ok=false

    after="$(readlink -f "$live_cert")"

    if [ "$before" != "$after" ]; then
        # reload는 접속을 끊지 않고 새 인증서로 바꾼다
        docker exec palette-nginx nginx -s reload
        echo "✅ 새 인증서로 교체: $after"
    fi

    # 지금 인증서의 만료일과 남은 일수
    local expire_at expire_date days_left
    expire_at="$(openssl x509 -enddate -noout -in "$live_cert" | cut -d= -f2)"
    expire_date="$(date -d "$expire_at" +%F)"
    days_left=$(( ($(date -d "$expire_at" +%s) - $(date +%s)) / 86400 ))
    echo "• 만료까지 ${days_left}일 (${expire_date})"

    if $renew_ok && [ "$days_left" -gt "$ALERT_DAYS" ]; then
        echo "✔ 정상"
        return 0
    fi

    echo "❌ 갱신 실패 또는 만료 임박"
    $dry_run || notify_slack "$days_left" "$expire_date"
    return 1
}

# Slack 전송이 실패해도 스크립트 결과는 바꾸지 않고 로그만 남긴다
notify_slack() {
    local days_left="$1" expire_date="$2" token
    token="$(grep '^SLACK_BOT_TOKEN=' "${APP_DIR:-/home/ubuntu/palette}/infra/.env" | cut -d= -f2-)" || true
    if [ -z "$token" ]; then
        echo "⚠ SLACK_BOT_TOKEN이 infra/.env에 없어 Slack 알림을 건너뜁니다"
        return 0
    fi

    local text="❌ *[인증서] ${DOMAIN} 갱신 실패*\n• 만료까지 ${days_left}일 (${expire_date})\n• 서버 로그: ${LOG_FILE}"
    curl -sS -X POST https://slack.com/api/chat.postMessage \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json; charset=utf-8" \
        --data "{\"channel\":\"${SLACK_CHANNEL}\",\"text\":\"${text}\"}" \
        | grep -q '"ok":true' \
        || echo "⚠ Slack 알림 전송 실패"
}

main "$@"
exit $?

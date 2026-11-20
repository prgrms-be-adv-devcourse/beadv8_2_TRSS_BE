#!/usr/bin/env bash
# 데이터 서버(EC2-B)를 처음 준비한다: 서울 시간대, Docker 설치, 스왑 만들기, ES가 요구하는 커널 설정.
# 이미 된 단계는 건너뛰므로 여러 번 실행해도 결과가 같다. 중간에 끊기면 그냥 다시 실행하면 된다.
#
# 실행(EC2-B, 레포 루트에서): sudo bash infra/scripts/setup-data-server.sh
# 끝나면 compose.data.yaml로 ES를 띄운다(파일 머리말 참고).

set -euo pipefail

SWAP_FILE="/swapfile"
SWAP_SIZE="2G"
SYSCTL_FILE="/etc/sysctl.d/99-elasticsearch.conf"
TIMEZONE="Asia/Seoul"

main() {
    if [[ $EUID -ne 0 ]]; then
        echo "✖ root 권한이 필요합니다: sudo bash $0" >&2
        exit 1
    fi

    set_timezone
    install_docker
    create_swap
    apply_sysctl

    echo
    echo "✔ 준비 완료"
    echo "  시간대 : $(timedatectl show -p Timezone --value) (지금 $(date '+%F %T %Z'))"
    echo "  docker : $(docker --version)"
    echo "  compose: $(docker compose version --short)"
    echo "  스왑   : $(swapon --show=NAME,SIZE --noheadings | tr -s ' ')"
    echo "  커널   : $(sysctl -n vm.max_map_count) (vm.max_map_count), $(sysctl -n vm.swappiness) (vm.swappiness)"
    echo "  docker 그룹은 다시 로그인해야 적용된다(그 전까지는 sudo docker ...)"
}

set_timezone() {
    # 서버 시계(로그·cron 시각)를 서울 시간으로 맞춘다. 컨테이너 안의 시간대는 각 컨테이너 설정(TZ)을 따르므로 바뀌지 않는다
    if [[ "$(timedatectl show -p Timezone --value)" == "$TIMEZONE" ]]; then
        echo "• 시간대가 이미 ${TIMEZONE}이라 건너뜀"
    else
        echo "▶ 시간대를 ${TIMEZONE}로 설정"
        timedatectl set-timezone "$TIMEZONE"
    fi
}

install_docker() {
    if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
        echo "• Docker가 이미 있어 건너뜀"
    else
        echo "▶ Docker 설치"
        # Ubuntu 기본 저장소 패키지를 쓴다. 외부 저장소·키를 추가하지 않아도 되고 apt 업데이트로 같이 관리된다.
        # buildx는 compose의 --build(이미지 빌드)에 필요하다
        apt-get update -q
        apt-get install -y -q docker.io docker-compose-v2 docker-buildx
        systemctl enable --now docker
    fi

    # sudo 없이 docker를 쓰게 sudo를 실행한 사람(보통 ubuntu)을 docker 그룹에 넣는다. 이미 있으면 바뀌는 것이 없다
    if [[ -n "${SUDO_USER:-}" ]]; then
        usermod -aG docker "$SUDO_USER"
    fi
}

create_swap() {
    # 메모리(4GB)가 모자랄 때 디스크를 대신 써서, 리눅스가 큰 프로세스(ES)를 강제 종료하는 일을 막는 안전장치다
    if swapon --show=NAME --noheadings | grep -qx "$SWAP_FILE"; then
        echo "• 스왑이 이미 켜져 있어 건너뜀"
    else
        echo "▶ 스왑 ${SWAP_SIZE} 만들기"
        [[ -f "$SWAP_FILE" ]] || fallocate -l "$SWAP_SIZE" "$SWAP_FILE"
        chmod 600 "$SWAP_FILE"   # 스왑에는 메모리 내용이 그대로 써지므로 root만 읽게 한다
        mkswap "$SWAP_FILE" >/dev/null
        swapon "$SWAP_FILE"
    fi

    # 재부팅 뒤에도 스왑이 켜지게 등록한다. 이미 있으면 줄을 또 넣지 않는다
    grep -q "^${SWAP_FILE} " /etc/fstab || echo "${SWAP_FILE} none swap sw 0 0" >> /etc/fstab
}

apply_sysctl() {
    echo "▶ 커널 설정 적용 (${SYSCTL_FILE})"
    # 파일을 통째로 덮어써서 몇 번 실행해도 내용이 같다. /etc/sysctl.d에 두면 재부팅 뒤에도 유지된다
    cat > "$SYSCTL_FILE" <<'EOF'
# Elasticsearch: 색인 파일을 메모리에 연결(mmap)하는 영역 수. 기본값(65530)이면 ES가 시작하지 않는다
vm.max_map_count=262144
# 스왑은 정말 모자랄 때만 쓴다(기본 60). 스왑을 자주 쓰면 ES와 DB가 느려진다
vm.swappiness=1
EOF
    sysctl -q -p "$SYSCTL_FILE"
}

main "$@"

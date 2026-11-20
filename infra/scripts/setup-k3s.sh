#!/usr/bin/env bash
# 앱 서버(EC2-A)에 k3s(가벼운 쿠버네티스, 노드 1개)를 설치한다. 지금은 ai-service만 k3s에서 돌린다(TRSS-327).
# 이미 된 단계는 건너뛰므로 여러 번 실행해도 결과가 같다. 버전을 바꾸려면 K3S_VERSION만 고치고 다시 실행한다(제자리 업그레이드).
#
# 실행(EC2-A, 레포 루트에서): sudo bash infra/scripts/setup-k3s.sh
# 끝나면 sudo를 실행한 계정(ubuntu)이 sudo 없이 kubectl을 쓸 수 있다: kubectl get nodes
#
# Docker(Compose)와 같은 서버에서 같이 돈다. k3s는 Docker가 아니라 자기 containerd로 파드를 돌려서 서로의 컨테이너가 섞이지 않는다.

set -euo pipefail

# 버전을 고정한다. 설치 명령은 기본으로 그날의 최신 버전을 받아서, 나중에 다시 깔면 버전이 달라질 수 있다.
# 2026-11-18 k3s stable 채널 버전
K3S_VERSION="v1.37.2+k3s1"
CONFIG_FILE="/etc/rancher/k3s/config.yaml"

main() {
    if [[ $EUID -ne 0 ]]; then
        echo "✖ root 권한이 필요합니다: sudo bash $0" >&2
        exit 1
    fi

    write_config
    install_k3s
    wait_node_ready

    echo
    echo "✔ 준비 완료"
    echo "  k3s    : $(k3s --version | head -1)"
    echo "  노드   : $(kubectl get nodes --no-headers | awk '{print $1, $2}')"
    echo "  메모리 : $(free -m | awk '/^Mem:/ {print "사용 " $3 "MB / 전체 " $2 "MB, 바로 쓸 수 있는 양 " $7 "MB"}')"
}

write_config() {
    # kubeconfig(관리자 열쇠, /etc/rancher/k3s/k3s.yaml)를 root와 sudo를 실행한 계정의 그룹(보통 ubuntu)만 읽게 한다.
    # 배포 스크립트(deploy.sh)가 ubuntu 계정으로 kubectl을 부르기 때문이다.
    # 복사본(~/.kube/config)을 두지 않는다: k3s의 kubectl은 이 파일이 있으면 이걸 먼저 읽어서, 복사본이 있어도 권한 오류가 난다
    local group
    group="$(id -gn "${SUDO_USER:-root}")"

    # 설치 옵션을 명령줄이 아니라 설정 파일에 둔다. 서버에서 무엇을 껐는지 파일로 남고, 재시작·업그레이드 때도 같은 옵션이 쓰인다
    local new
    new="$(cat <<EOF
# setup-k3s.sh가 만든 파일. 고칠 때는 스크립트를 고치고 다시 실행한다
write-kubeconfig-mode: "0640"
write-kubeconfig-group: "${group}"
disable:
  # Traefik(입구)·servicelb(로드밸런서)는 서버의 80·443을 잡으려 해 nginx와 부딪힌다. 이번 구조(NodePort)에선 안 쓴다
  - traefik
  - servicelb
  # ai는 디스크에 저장하지 않아(데이터는 데이터 서버) 파드용 디스크 기능이 필요 없다
  - local-storage
EOF
)"
    mkdir -p "$(dirname "$CONFIG_FILE")"
    if [[ -f "$CONFIG_FILE" ]] && [[ "$(cat "$CONFIG_FILE")" == "$new" ]]; then
        echo "• 설정 파일이 같아 건너뜀 (${CONFIG_FILE})"
        CONFIG_CHANGED=false
    else
        echo "▶ 설정 파일 쓰기 (${CONFIG_FILE})"
        printf '%s\n' "$new" > "$CONFIG_FILE"
        CONFIG_CHANGED=true
    fi
}

install_k3s() {
    local current=""
    command -v k3s >/dev/null && current="$(k3s --version | awk 'NR==1 {print $3}')"

    if [[ "$current" == "$K3S_VERSION" ]]; then
        echo "• k3s ${K3S_VERSION}가 이미 있어 건너뜀"
        # 설정만 바뀌었으면 다시 읽게 재시작한다. 지금 도는 파드는 재시작 뒤 다시 붙는다
        if [[ "$CONFIG_CHANGED" == true ]]; then
            echo "▶ 설정이 바뀌어 k3s 재시작"
            systemctl restart k3s
        fi
    else
        echo "▶ k3s ${K3S_VERSION} 설치 (지금: ${current:-없음})"
        # k3s 공식 설치 스크립트. 받은 k3s 실행 파일은 설치 스크립트가 공식 체크섬으로 검사한다.
        # systemd 서비스(k3s)로 등록돼 재부팅해도 다시 뜬다. kubectl·crictl·ctr 명령도 같이 깔린다
        curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION="$K3S_VERSION" sh -s - server
    fi
}

wait_node_ready() {
    echo "▶ 노드가 Ready가 될 때까지 기다림 (최대 2분)"
    for _ in $(seq 1 60); do
        if kubectl get nodes --no-headers 2>/dev/null | grep -q ' Ready '; then
            echo "• 노드 Ready"
            return
        fi
        sleep 2
    done
    echo "✖ 2분 안에 노드가 Ready가 되지 않았습니다: journalctl -u k3s -n 50 으로 원인을 보세요" >&2
    exit 1
}

main "$@"

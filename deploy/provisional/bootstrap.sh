#!/usr/bin/env bash
# 시선(See:Sun) 가운영 서버 준비 — Ubuntu 24.04 x86_64(Lightsail 서울 2GB) 처음 한 번. 여러 번 실행해도 같다(멱등).
#   1) Docker Engine + compose 플러그인(Docker 공식 apt 저장소)  2) 스왑 2GB  3) 자동 보안 업데이트
#   4) ufw: 22·80·443만 허용  5) 시간대 Asia/Seoul  6) 로그인 사용자를 docker 그룹에
# 사용: sudo ./bootstrap.sh   (끝나면 SSH를 끊고 다시 접속해야 docker 그룹이 적용된다)
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "sudo ./bootstrap.sh 로 실행하세요" >&2; exit 1; }
. /etc/os-release
[ "${ID:-}" = ubuntu ] || { echo "Ubuntu 전용입니다(현재 ${ID:-?})" >&2; exit 1; }
[ "$(dpkg --print-architecture)" = amd64 ] || { echo "x86_64(amd64) 인스턴스가 필요합니다(이미지가 amd64)" >&2; exit 1; }
IN_CONTAINER=0; [ -f /.dockerenv ] && IN_CONTAINER=1   # 스크립트 시험용 컨테이너에서는 커널·방화벽 단계를 건너뜀
TARGET_USER=${SUDO_USER:-ubuntu}
export DEBIAN_FRONTEND=noninteractive
log() { printf '[bootstrap] %s\n' "$*"; }

log "패키지 목록 갱신·기본 도구"
apt-get update -q
apt-get install -y -q ca-certificates curl gnupg ufw unattended-upgrades cron python3 >/dev/null

# ---- 1. Docker Engine(공식 저장소, https://docs.docker.com/engine/install/ubuntu/) ----------------
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  log "Docker 공식 저장소 등록·설치"
  for pkg in docker.io docker-doc docker-compose docker-compose-v2 podman-docker containerd runc; do
    apt-get remove -y -q "$pkg" >/dev/null 2>&1 || true
  done
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${UBUNTU_CODENAME:-$VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin >/dev/null
else
  log "Docker 이미 설치됨: $(docker --version)"
fi
# 컨테이너 로그 크기 상한(compose에도 있지만 다른 컨테이너 대비)
if [ ! -f /etc/docker/daemon.json ]; then
  mkdir -p /etc/docker
  printf '{\n  "log-driver": "json-file",\n  "log-opts": {"max-size": "10m", "max-file": "3"}\n}\n' > /etc/docker/daemon.json
  [ "$IN_CONTAINER" = 1 ] || systemctl restart docker
fi
if [ "$IN_CONTAINER" = 0 ]; then
  systemctl enable --now docker >/dev/null
fi
if id "$TARGET_USER" >/dev/null 2>&1 && ! id -nG "$TARGET_USER" | grep -qw docker; then
  usermod -aG docker "$TARGET_USER"
  log "$TARGET_USER 를 docker 그룹에 넣음(SSH 재접속 필요)"
fi

if [ "$IN_CONTAINER" = 0 ]; then
  # ---- 2. 스왑 2GB(2GB 메모리에서 이미지 적재·마이그레이션 때 OOM 방지) --------------------------
  if ! swapon --show=NAME --noheadings | grep -q '^/swapfile$'; then
    if [ ! -f /swapfile ]; then
      log "스왑 2GB 만들기"
      fallocate -l 2G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
      chmod 600 /swapfile
      mkswap /swapfile >/dev/null
    fi
    swapon /swapfile
  fi
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  printf 'vm.swappiness=10\n' > /etc/sysctl.d/90-seesun-swap.conf
  sysctl -q -p /etc/sysctl.d/90-seesun-swap.conf

  # ---- 3. 자동 보안 업데이트(재부팅은 자동으로 하지 않음) ---------------------------------------
  printf 'APT::Periodic::Update-Package-Lists "1";\nAPT::Periodic::Unattended-Upgrade "1";\n' > /etc/apt/apt.conf.d/20auto-upgrades
  systemctl enable --now unattended-upgrades >/dev/null 2>&1 || true

  # ---- 4. 방화벽: 22·80·443만 ---------------------------------------------------------
  # (Docker가 공개하는 포트는 ufw를 우회하므로 compose는 80·443만 공개한다. Lightsail 콘솔 방화벽도 같은 3개만)
  ufw default deny incoming >/dev/null
  ufw default allow outgoing >/dev/null
  ufw allow 22/tcp >/dev/null
  ufw allow 80/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
  ufw allow 443/udp >/dev/null   # HTTP/3
  ufw --force enable >/dev/null
  ufw status verbose | sed 's/^/[ufw] /'

  # ---- 5. 시간대(백업 cron 03:15 = 한국 시간) ----------------------------------------------
  timedatectl set-timezone Asia/Seoul || true
fi

log "완료. 확인: docker --version / docker compose version / free -h / sudo ufw status"
docker --version
docker compose version
[ "$IN_CONTAINER" = 1 ] || free -h | sed 's/^/[mem] /'
log "다음: exit 로 SSH를 끊고 다시 접속한 뒤 ./deploy.sh"

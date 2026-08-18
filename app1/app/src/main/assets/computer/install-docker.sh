#!/bin/sh
set -eu

# 仅支持计划内的 Ubuntu 与 Debian，使用 Docker 官方 APT 仓库安装。
[ "$(id -u)" -eq 0 ] || { printf '%s\n' '需要 root 权限' >&2; exit 20; }
[ -r /etc/os-release ] || { printf '%s\n' '缺少 /etc/os-release' >&2; exit 21; }
. /etc/os-release

case "${ID:-}" in
    ubuntu|debian) ;;
    *) printf '%s\n' '当前系统不支持自动安装 Docker' >&2; exit 22 ;;
esac

export DEBIAN_FRONTEND=noninteractive
# 云服务器常有 unattended-upgrades 占用 dpkg。等待现有任务结束，避免把正常并发误报成安装失败。
apt-get -o DPkg::Lock::Timeout=300 update
apt-get -o DPkg::Lock::Timeout=300 install -y ca-certificates curl gnupg
install -m 0755 -d /etc/apt/keyrings
temporary_key="/etc/apt/keyrings/docker.asc.everytalk.$$"
trap 'rm -f "$temporary_key"' EXIT INT TERM
curl --fail --silent --show-error --location "https://download.docker.com/linux/$ID/gpg" --output "$temporary_key"
chmod 0644 "$temporary_key"
mv -f "$temporary_key" /etc/apt/keyrings/docker.asc
trap - EXIT INT TERM

architecture="$(dpkg --print-architecture)"
codename="${VERSION_CODENAME:-}"
[ -n "$codename" ] || { printf '%s\n' '无法确定系统代号' >&2; exit 23; }
printf '%s\n' "deb [arch=$architecture signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/$ID $codename stable" \
    > /etc/apt/sources.list.d/docker.list

apt-get -o DPkg::Lock::Timeout=300 update
apt-get -o DPkg::Lock::Timeout=300 install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
docker version >/dev/null

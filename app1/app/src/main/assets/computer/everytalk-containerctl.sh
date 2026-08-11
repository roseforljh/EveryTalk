#!/bin/sh
set -eu

VERSION="2"
HELPER_PATH="/usr/local/libexec/everytalk-containerctl"
RUNTIME_WRAPPER_PATH="/usr/local/libexec/everytalk-runtime-wrapper"
DOCKERFILE_PATH="/usr/local/share/everytalk/Dockerfile"
IMAGE="everytalk-sandbox:1"
NETWORK="everytalk-agent"

fail() { printf '%s\n' "$1" >&2; exit "${2:-50}"; }
require_root() { [ "$(id -u)" -eq 0 ] || fail 'helper 需要 root 权限' 51; }
require_exact_args() {
    expected="$1"
    shift
    [ "$#" -eq "$expected" ] || fail 'helper 参数数量无效' 74
}
valid_id() {
    value="${1:-}"
    [ -n "$value" ] && [ "${#value}" -le 128 ] || return 1
    case "$value" in *[!A-Za-z0-9_-]*) return 1 ;; esac
}
target_user() {
    user="${SUDO_USER:-root}"
    valid_id "$user" || fail 'SSH 用户名不适合 Container helper' 52
    printf '%s' "$user"
}
target_home() {
    user="$(target_user)"
    home="$(getent passwd "$user" | cut -d: -f6)"
    [ -n "$home" ] || fail '无法确定 SSH 用户 Home' 53
    printf '%s' "$home"
}
workspace_path() {
    workspace_id="${1:-}"
    valid_id "$workspace_id" || fail 'Workspace ID 无效' 54
    printf '%s/.everytalk/workspaces/%s' "$(target_home)" "$workspace_id"
}
container_name() {
    workspace_id="${1:-}"
    valid_id "$workspace_id" || fail 'Workspace ID 无效' 54
    printf 'everytalk-%s' "$workspace_id"
}

require_workspace_container() {
    workspace_id="${1:-}"
    valid_id "$workspace_id" || fail 'Workspace ID 无效' 54
    name="$(container_name "$workspace_id")"
    docker container inspect "$name" >/dev/null 2>&1 || fail 'Workspace Container 不存在' 70
    managed="$(docker inspect -f '{{index .Config.Labels "com.everytalk.managed"}}' "$name")"
    owner="$(docker inspect -f '{{index .Config.Labels "com.everytalk.workspace"}}' "$name")"
    [ "$managed" = true ] && [ "$owner" = "$workspace_id" ] || fail 'Container 归属校验失败' 60
    printf '%s' "$name"
}

ensure_network() {
    if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
        docker network create --driver bridge --label com.everytalk.managed=true "$NETWORK" >/dev/null
    fi
}

set_ipv4_rule() {
    action="$1"
    subnet="$2"
    destination="$3"
    if [ "$action" = add ]; then
        iptables -C DOCKER-USER -s "$subnet" -d "$destination" -j REJECT >/dev/null 2>&1 || \
            iptables -I DOCKER-USER 1 -s "$subnet" -d "$destination" -j REJECT
    else
        while iptables -C DOCKER-USER -s "$subnet" -d "$destination" -j REJECT >/dev/null 2>&1; do
            iptables -D DOCKER-USER -s "$subnet" -d "$destination" -j REJECT
        done
    fi
}

configure_network_boundary() {
    mode="${1:-}"
    [ "$mode" = restricted ] || [ "$mode" = private ] || fail '网络模式无效' 55
    ensure_network
    subnet="$(docker network inspect -f '{{(index .IPAM.Config 0).Subnet}}' "$NETWORK")"
    action=add
    [ "$mode" = private ] && action=remove
    for destination in 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4; do
        set_ipv4_rule "$action" "$subnet" "$destination"
    done
}

install_helper() {
    runtime_source="${1:-}"
    dockerfile_source="${2:-}"
    case "$runtime_source" in /tmp/everytalk-bootstrap-*/runtime-wrapper.sh) ;; *) fail 'runtime 安装源无效' 56 ;; esac
    case "$dockerfile_source" in /tmp/everytalk-bootstrap-*/Dockerfile) ;; *) fail 'Dockerfile 安装源无效' 57 ;; esac
    [ -f "$runtime_source" ] && [ -f "$dockerfile_source" ] || fail '安装资产缺失' 58

    install -d -o root -g root -m 0755 /usr/local/libexec /usr/local/share/everytalk
    install -o root -g root -m 0755 "$0" "$HELPER_PATH"
    install -o root -g root -m 0755 "$runtime_source" "$RUNTIME_WRAPPER_PATH"
    install -o root -g root -m 0644 "$dockerfile_source" "$DOCKERFILE_PATH"

    user="$(target_user)"
    if [ "$user" != root ]; then
        sudoers_temporary="/etc/sudoers.d/everytalk-$user.tmp.$$"
        printf '%s ALL=(root) NOPASSWD: %s *\n' "$user" "$HELPER_PATH" > "$sudoers_temporary"
        chmod 0440 "$sudoers_temporary"
        command -v visudo >/dev/null 2>&1 || fail '缺少 visudo' 59
        visudo -cf "$sudoers_temporary" >/dev/null
        mv -f "$sudoers_temporary" "/etc/sudoers.d/everytalk-$user"
    fi
    printf 'version=%s\n' "$VERSION"
}

build_image() {
    docker build --pull --tag "$IMAGE" --file "$DOCKERFILE_PATH" /usr/local/share/everytalk
}

ensure_workspace() {
    workspace_id="${1:-}"
    valid_id "$workspace_id" || fail 'Workspace ID 无效' 54
    user="$(target_user)"
    uid="$(id -u "$user")"
    gid="$(id -g "$user")"
    workspace="$(workspace_path "$workspace_id")"
    name="$(container_name "$workspace_id")"
    install -d -o "$uid" -g "$gid" -m 0700 "$workspace"
    ensure_network

    if docker container inspect "$name" >/dev/null 2>&1; then
        name="$(require_workspace_container "$workspace_id")"
        # 迁移旧版本创建的 Container，确保 VPS 重启后不会自动拉起历史会话。
        docker update --restart=no "$name" >/dev/null
        docker start "$name" >/dev/null
        printf 'container=%s\n' "$name"
        return
    fi

    # 明确不传 CPU、内存、磁盘、swap 或 PID 配额参数。
    docker run --detach \
        --name "$name" \
        --label com.everytalk.managed=true \
        --label "com.everytalk.workspace=$workspace_id" \
        --restart no \
        --security-opt no-new-privileges:true \
        --network "$NETWORK" \
        --user "$uid:$gid" \
        --env HOME=/workspace \
        --workdir /workspace \
        --mount "type=bind,src=$workspace,dst=/workspace" \
        --mount "type=bind,src=$RUNTIME_WRAPPER_PATH,dst=/usr/local/bin/everytalk-runtime-wrapper,readonly" \
        "$IMAGE" >/dev/null
    printf 'container=%s\n' "$name"
}

container_address() {
    name="$(require_workspace_container "${1:-}")"
    docker inspect -f "{{with index .NetworkSettings.Networks \"$NETWORK\"}}{{.IPAddress}}{{end}}" "$name"
}

run_workspace() {
    workspace_id="${1:-}"
    runtime_id="${2:-}"
    root_mode="${3:-false}"
    timeout_seconds="${4:-120}"
    valid_id "$workspace_id" || fail 'Workspace ID 无效' 54
    valid_id "$runtime_id" || fail 'Runtime ID 无效' 64
    [ "$root_mode" = true ] || [ "$root_mode" = false ] || fail 'root 参数无效' 65
    case "$timeout_seconds" in ''|*[!0-9]*) fail 'timeout 参数无效' 66 ;; esac
    [ "$timeout_seconds" -ge 1 ] && [ "$timeout_seconds" -le 3600 ] || fail 'timeout 参数越界' 66
    name="$(require_workspace_container "$workspace_id")"
    runtime="/workspace/.everytalk/runtime/$runtime_id"
    user_arguments=""
    [ "$root_mode" = true ] && user_arguments="--user 0:0"
    # user_arguments 只可能为空或固定的 --user 0:0。
    docker exec $user_arguments "$name" timeout --signal=TERM --kill-after=5s "${timeout_seconds}s" \
        /usr/local/bin/everytalk-runtime-wrapper "$runtime"
}

run_workspace_background() {
    workspace_id="${1:-}"
    runtime_id="${2:-}"
    process_id="${3:-}"
    root_mode="${4:-false}"
    valid_id "$workspace_id" || fail 'Workspace ID 无效' 54
    valid_id "$runtime_id" || fail 'Runtime ID 无效' 64
    valid_id "$process_id" || fail 'Process ID 无效' 67
    [ "$root_mode" = true ] || [ "$root_mode" = false ] || fail 'root 参数无效' 65
    name="$(require_workspace_container "$workspace_id")"
    runtime="/workspace/.everytalk/runtime/$runtime_id"
    logs="/workspace/.everytalk/background/$process_id"
    user_arguments=""
    [ "$root_mode" = true ] && user_arguments="--user 0:0"
    script="umask 077; mkdir -p '$logs'; nohup setsid /usr/local/bin/everytalk-runtime-wrapper '$runtime' > '$logs/stdout.log' 2> '$logs/stderr.log' < /dev/null & printf 'pid=%s\\n' \"\$!\""
    docker exec $user_arguments "$name" /bin/sh -c "$script"
    printf 'process_id=%s\nlogs=/workspace/.everytalk/background/%s\n' "$process_id" "$process_id"
}

open_terminal() {
    workspace_id="${1:-}"
    valid_id "$workspace_id" || fail 'Workspace ID 无效' 54
    name="$(require_workspace_container "$workspace_id")"
    docker exec -it "$name" /bin/bash
}

remove_preview_rules() {
    preview_id="${1:-}"
    valid_id "$preview_id" || fail 'Preview ID 无效' 68
    marker="everytalk-preview:$preview_id"
    while :; do
        rule_number="$(iptables -L DOCKER-USER --line-numbers -n 2>/dev/null | awk -v marker="$marker" 'index($0, marker) { print $1; exit }')"
        [ -n "$rule_number" ] || break
        case "$rule_number" in *[!0-9]*) fail 'Preview 网络规则编号无效' 73 ;; esac
        iptables -D DOCKER-USER "$rule_number"
    done
}

open_public_preview() {
    workspace_id="${1:-}"
    preview_id="${2:-}"
    remote_port="${3:-}"
    expires_seconds="${4:-}"
    valid_id "$workspace_id" || fail 'Workspace ID 无效' 54
    valid_id "$preview_id" || fail 'Preview ID 无效' 68
    case "$remote_port" in ''|*[!0-9]*) fail 'Preview 端口无效' 69 ;; esac
    [ "$remote_port" -ge 1 ] && [ "$remote_port" -le 65535 ] || fail 'Preview 端口越界' 69
    case "$expires_seconds" in ''|*[!0-9]*) fail 'Preview 有效期无效' 76 ;; esac
    [ "$expires_seconds" -le 604800 ] || fail 'Preview 有效期越界' 76
    target="$(require_workspace_container "$workspace_id")"
    proxy="everytalk-$preview_id"
    remove_preview_rules "$preview_id"
    if docker container inspect "$proxy" >/dev/null 2>&1; then
        managed="$(docker inspect -f '{{index .Config.Labels "com.everytalk.preview"}}' "$proxy")"
        [ "$managed" = "$preview_id" ] || fail '同名 Preview 不归 EveryTalk 管理' 71
        docker rm --force "$proxy" >/dev/null
    fi
    if [ "$expires_seconds" -eq 0 ]; then
        set -- /usr/bin/socat "TCP-LISTEN:$remote_port,fork,reuseaddr" "TCP:$target:$remote_port"
    else
        # 到期后停止 socat，但让只读代理 Container 保持空闲并占住原 IP。
        # 这样即使手机离线，端口也会失效，旧防火墙规则不会误套到复用该 IP 的其他 Container。
        set -- /bin/sh -c '
            /usr/bin/timeout --signal=TERM "$1" /usr/bin/socat "TCP-LISTEN:$2,fork,reuseaddr" "TCP:$3:$2" || true
            exec sleep infinity
        ' everytalk-preview "$expires_seconds" "$remote_port" "$target"
    fi
    docker run --detach \
        --name "$proxy" \
        --label com.everytalk.managed=true \
        --label "com.everytalk.preview=$preview_id" \
        --label "com.everytalk.workspace=$workspace_id" \
        --label "com.everytalk.remote-port=$remote_port" \
        --label "com.everytalk.expires-seconds=$expires_seconds" \
        --restart no \
        --read-only \
        --cap-drop ALL \
        --security-opt no-new-privileges:true \
        --network "$NETWORK" \
        --publish "$remote_port" \
        "$IMAGE" "$@" >/dev/null
    proxy_ip="$(docker inspect -f "{{with index .NetworkSettings.Networks \"$NETWORK\"}}{{.IPAddress}}{{end}}" "$proxy")"
    target_ip="$(docker inspect -f "{{with index .NetworkSettings.Networks \"$NETWORK\"}}{{.IPAddress}}{{end}}" "$target")"
    [ -n "$proxy_ip" ] && [ -n "$target_ip" ] || { docker rm --force "$proxy" >/dev/null; fail 'Preview 网络地址无效' 72; }
    if ! iptables -I DOCKER-USER 1 -s "$proxy_ip/32" -d "$target_ip/32" -p tcp --dport "$remote_port" \
        -m comment --comment "everytalk-preview:$preview_id" -j ACCEPT; then
        docker rm --force "$proxy" >/dev/null
        fail '无法创建 Preview 网络规则' 73
    fi
    mapping="$(docker port "$proxy" "$remote_port/tcp" | head -n 1)"
    public_port="${mapping##*:}"
    case "$public_port" in
        ''|*[!0-9]*) remove_preview_rules "$preview_id"; docker rm --force "$proxy" >/dev/null; fail '无法确定 Public Port' 72 ;;
    esac
    printf 'public_port=%s\n' "$public_port"
}

preview_status() {
    preview_id="${1:-}"
    valid_id "$preview_id" || fail 'Preview ID 无效' 68
    proxy="everytalk-$preview_id"
    if ! docker container inspect "$proxy" >/dev/null 2>&1; then
        printf 'status=missing\n'
        return 0
    fi
    managed="$(docker inspect -f '{{index .Config.Labels "com.everytalk.preview"}}' "$proxy")"
    everytalk_managed="$(docker inspect -f '{{index .Config.Labels "com.everytalk.managed"}}' "$proxy")"
    [ "$everytalk_managed" = true ] && [ "$managed" = "$preview_id" ] || fail 'Preview 归属校验失败' 71
    running="$(docker inspect -f '{{.State.Running}}' "$proxy")"
    if [ "$running" = true ] && docker exec "$proxy" pgrep -x socat >/dev/null 2>&1; then
        printf 'status=active\n'
    else
        printf 'status=inactive\n'
    fi
}

close_public_preview() {
    preview_id="${1:-}"
    valid_id "$preview_id" || fail 'Preview ID 无效' 68
    proxy="everytalk-$preview_id"
    remove_preview_rules "$preview_id"
    docker container inspect "$proxy" >/dev/null 2>&1 || return 0
    managed="$(docker inspect -f '{{index .Config.Labels "com.everytalk.preview"}}' "$proxy")"
    everytalk_managed="$(docker inspect -f '{{index .Config.Labels "com.everytalk.managed"}}' "$proxy")"
    [ "$everytalk_managed" = true ] && [ "$managed" = "$preview_id" ] || fail 'Preview 归属校验失败' 71
    docker rm --force "$proxy" >/dev/null
}

delete_workspace() {
    workspace_id="${1:-}"
    delete_files="${2:-false}"
    name="$(container_name "$workspace_id")"
    if docker container inspect "$name" >/dev/null 2>&1; then
        name="$(require_workspace_container "$workspace_id")"
        docker rm --force "$name" >/dev/null
    fi
    if [ "$delete_files" = true ]; then
        workspace="$(workspace_path "$workspace_id")"
        case "$workspace" in */.everytalk/workspaces/ws_*) rm -rf -- "$workspace" ;; *) fail '拒绝删除异常路径' 61 ;; esac
    elif [ "$delete_files" != false ]; then
        fail '删除参数无效' 62
    fi
}

require_root
command_name="${1:-}"
shift || true
case "$command_name" in
    install)
        require_exact_args 2 "$@"
        [ "$(readlink -f -- "$0")" != "$HELPER_PATH" ] || fail '已安装 helper 禁止重复 install' 75
        install_helper "$@"
        ;;
    version) require_exact_args 0 "$@"; printf 'version=%s\n' "$VERSION" ;;
    build-image) require_exact_args 0 "$@"; build_image ;;
    set-network) require_exact_args 1 "$@"; configure_network_boundary "$@" ;;
    ensure-workspace) require_exact_args 1 "$@"; ensure_workspace "$@" ;;
    container-address) require_exact_args 1 "$@"; container_address "$@" ;;
    run) require_exact_args 4 "$@"; run_workspace "$@" ;;
    run-background) require_exact_args 4 "$@"; run_workspace_background "$@" ;;
    terminal) require_exact_args 1 "$@"; open_terminal "$@" ;;
    open-public) require_exact_args 4 "$@"; open_public_preview "$@" ;;
    preview-status) require_exact_args 1 "$@"; preview_status "$@" ;;
    close-public) require_exact_args 1 "$@"; close_public_preview "$@" ;;
    delete-workspace) require_exact_args 2 "$@"; delete_workspace "$@" ;;
    *) fail 'helper 子命令无效' 63 ;;
esac

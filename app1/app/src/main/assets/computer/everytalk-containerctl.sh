#!/bin/sh
set -eu

VERSION="8"
HELPER_PATH="/usr/local/libexec/everytalk-containerctl"
RUNTIME_WRAPPER_PATH="/usr/local/libexec/everytalk-runtime-wrapper"
RUNTIME_WRAPPER_VERSION_PATH="/usr/local/libexec/everytalk-runtime-wrapper.sha256"
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
valid_request_hash() {
    value="${1:-}"
    case "$value" in ''|*[!0-9a-f]*) return 1 ;; esac
    [ "${#value}" -eq 64 ]
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
runtime_wrapper_hash() {
    [ -f "$RUNTIME_WRAPPER_VERSION_PATH" ] || fail 'Runtime Wrapper 版本缺失' 58
    IFS= read -r runtime_hash < "$RUNTIME_WRAPPER_VERSION_PATH"
    case "$runtime_hash" in ''|*[!0-9a-f]*) fail 'Runtime Wrapper 版本无效' 58 ;; esac
    [ "${#runtime_hash}" -eq 64 ] || fail 'Runtime Wrapper 版本无效' 58
    [ -f "$RUNTIME_WRAPPER_PATH-$runtime_hash" ] || fail 'Runtime Wrapper 文件缺失' 58
    printf '%s' "$runtime_hash"
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

container_allowed_owner_uids() {
    name="$1"
    configured_user="$(docker inspect -f '{{.Config.User}}' "$name" 2>/dev/null || true)"
    case "$configured_user" in
        ''|*[!0-9:]) printf '0 0' ;;
        *:*) printf '%s 0' "${configured_user%%:*}" ;;
        *) printf '%s 0' "$configured_user" ;;
    esac
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
    runtime_hash="$(sha256sum "$runtime_source" | cut -d' ' -f1)"
    runtime_target="$RUNTIME_WRAPPER_PATH-$runtime_hash"
    install -o root -g root -m 0755 "$runtime_source" "$runtime_target"
    runtime_version_temporary="$RUNTIME_WRAPPER_VERSION_PATH.tmp.$$"
    printf '%s\n' "$runtime_hash" > "$runtime_version_temporary"
    chmod 0644 "$runtime_version_temporary"
    chown root:root "$runtime_version_temporary"
    mv -f "$runtime_version_temporary" "$RUNTIME_WRAPPER_VERSION_PATH"
    # 兼容旧 Container，同时让当前版本继续使用不变的挂载目标。
    if [ -e "$RUNTIME_WRAPPER_PATH" ] && [ ! -L "$RUNTIME_WRAPPER_PATH" ]; then
        rm -f "$RUNTIME_WRAPPER_PATH"
    fi
    ln -sfn "${runtime_target##*/}" "$RUNTIME_WRAPPER_PATH"
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
    runtime_hash="$(runtime_wrapper_hash)"

    if docker container inspect "$name" >/dev/null 2>&1; then
        name="$(require_workspace_container "$workspace_id")"
        # 迁移旧版本创建的 Container，确保 VPS 重启后不会自动拉起历史会话。
        docker update --restart=no "$name" >/dev/null
        mounted_wrapper_hash="$(docker inspect -f '{{index .Config.Labels "com.everytalk.wrapper"}}' "$name")"
        if [ "$mounted_wrapper_hash" != "$runtime_hash" ]; then
            # 旧 Container 没有当前哈希标签时按需重建，Host Workspace 继续原路径挂载。
            stop_workspace_backgrounds "$workspace_id" "$name"
            docker rm --force "$name" >/dev/null
        else
            docker start "$name" >/dev/null
            printf 'container=%s\n' "$name"
            return
        fi
    fi

    # 明确不传 CPU、内存、磁盘、swap 或 PID 配额参数。
    docker run --detach \
        --name "$name" \
        --label com.everytalk.managed=true \
        --label "com.everytalk.workspace=$workspace_id" \
        --label "com.everytalk.wrapper=$runtime_hash" \
        --restart no \
        --security-opt no-new-privileges:true \
        --network "$NETWORK" \
        --user "$uid:$gid" \
        --env HOME=/workspace \
        --workdir /workspace \
        --mount "type=bind,src=$workspace,dst=/workspace" \
        --mount "type=bind,src=$RUNTIME_WRAPPER_PATH-$runtime_hash,dst=/usr/local/bin/everytalk-runtime-wrapper,readonly" \
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
    cleanup_runtime() {
        docker exec "$name" rm -f -- \
            "$runtime/environment.sh" "$runtime/stdin" "$runtime/cwd" "$runtime/command.sh" >/dev/null 2>&1 || true
        docker exec "$name" rmdir -- "$runtime" >/dev/null 2>&1 || true
    }
    trap cleanup_runtime EXIT
    trap 'exit 143' HUP INT TERM
    # user_arguments 只可能为空或固定的 --user 0:0。
    status=0
    docker exec -i $user_arguments "$name" timeout --signal=TERM --kill-after=5s "${timeout_seconds}s" \
        /usr/local/bin/everytalk-runtime-wrapper "$runtime" '' --envelope || status="$?"
    trap - HUP INT TERM
    cleanup_runtime
    trap - EXIT
    return "$status"
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
    # Wrapper 先从当前 docker exec stdin 持久化 Runtime，再切换为后台任务。
    docker exec -i $user_arguments "$name" \
        /usr/local/bin/everytalk-runtime-wrapper "$runtime" "$logs" --envelope
    printf 'process_id=%s\nlogs=/workspace/.everytalk/background/%s\n' "$process_id" "$process_id"
}

run_workspace_execution() {
    workspace_id="${1:-}"
    runtime_id="${2:-}"
    execution_id="${3:-}"
    root_mode="${4:-false}"
    timeout_seconds="${5:-120}"
    request_hash="${6:-}"
    valid_id "$workspace_id" || fail 'Workspace ID 无效' 54
    valid_id "$runtime_id" || fail 'Runtime ID 无效' 64
    case "$execution_id" in execution_[A-Za-z0-9_-]*) ;; *) fail 'Execution ID 无效' 67 ;; esac
    [ "$root_mode" = true ] || [ "$root_mode" = false ] || fail 'root 参数无效' 65
    case "$timeout_seconds" in ''|*[!0-9]*) fail 'timeout 参数无效' 66 ;; esac
    [ "$timeout_seconds" -ge 0 ] && [ "$timeout_seconds" -le 3600 ] || fail 'timeout 参数越界' 66
    case "$request_hash" in ''|*[!0-9a-f]*) fail 'request hash 无效' 67 ;; esac
    [ "${#request_hash}" -eq 64 ] || fail 'request hash 无效' 67
    name="$(require_workspace_container "$workspace_id")"
    owner_uids="$(container_allowed_owner_uids "$name")"
    # 先在容器内逐级确认固定根目录不是符号链接，再创建 executions。
    # 不能直接使用 mkdir -p，否则用户可通过 Workspace 内的链接把状态目录引到其他路径。
    docker exec "$name" /bin/sh -c '
        workspace=/workspace
        root="$workspace/.everytalk"
        executions="$root/executions"
        [ -d "$workspace" ] && [ ! -L "$workspace" ] || exit 46
        workspace_real="$(realpath -e -- "$workspace" 2>/dev/null || true)"
        [ "$workspace_real" = "$workspace" ] || exit 46
        if [ -e "$root" ] || [ -L "$root" ]; then
            [ -d "$root" ] && [ ! -L "$root" ] || exit 46
        else
            mkdir "$root" || exit 46
        fi
        root_real="$(realpath -e -- "$root" 2>/dev/null || true)"
        [ "$root_real" = "$root" ] || exit 46
        if [ -e "$executions" ] || [ -L "$executions" ]; then
            [ -d "$executions" ] && [ ! -L "$executions" ] || exit 46
        else
            mkdir "$executions" || exit 46
        fi
        executions_real="$(realpath -e -- "$executions" 2>/dev/null || true)"
        [ "$executions_real" = "$executions" ] || exit 46
    '
    runtime="/workspace/.everytalk/runtime/$runtime_id"
    execution="/workspace/.everytalk/executions/$execution_id"
    user_arguments=""
    [ "$root_mode" = true ] && user_arguments="--user 0:0"
    # Wrapper 负责读取 Envelope、创建状态文件并脱离当前 SSH Channel 执行。
    docker exec -i -e "EVERYTALK_ALLOWED_OWNER_UIDS=$owner_uids" $user_arguments "$name" \
        /usr/local/bin/everytalk-runtime-wrapper "$runtime" "$execution" --envelope-v2 "$timeout_seconds" "$request_hash"
}

execution_status() {
    workspace_id="${1:-}"
    execution_id="${2:-}"
    request_hash="${3:-}"
    valid_id "$workspace_id" || fail 'Workspace ID 无效' 54
    case "$execution_id" in execution_[A-Za-z0-9_-]*) ;; *) fail 'Execution ID 无效' 67 ;; esac
    valid_request_hash "$request_hash" || fail 'request hash 无效' 67
    name="$(require_workspace_container "$workspace_id")"
    owner_uids="$(container_allowed_owner_uids "$name")"
    execution_dir="/workspace/.everytalk/executions/$execution_id"
    docker exec --user 0:0 -e "EVERYTALK_ALLOWED_OWNER_UIDS=$owner_uids" "$name" /usr/local/bin/everytalk-runtime-wrapper \
        "$execution_dir" '' --execution-status 0 "$request_hash"
}

execution_result() {
    workspace_id="${1:-}"
    execution_id="${2:-}"
    stdout_offset="${3:-0}"
    stderr_offset="${4:-0}"
    max_bytes="${5:-2048}"
    request_hash="${6:-}"
    valid_id "$workspace_id" || fail 'Workspace ID 无效' 54
    case "$execution_id" in execution_[A-Za-z0-9_-]*) ;; *) fail 'Execution ID 无效' 67 ;; esac
    case "$stdout_offset" in ''|*[!0-9]*) fail 'stdout 偏移无效' 68 ;; esac
    case "$stderr_offset" in ''|*[!0-9]*) fail 'stderr 偏移无效' 68 ;; esac
    case "$max_bytes" in ''|*[!0-9]*) fail '日志读取长度无效' 68 ;; esac
    [ "$max_bytes" -ge 1 ] && [ "$max_bytes" -le 262144 ] || fail '日志读取长度无效' 68
    valid_request_hash "$request_hash" || fail 'request hash 无效' 67
    name="$(require_workspace_container "$workspace_id")"
    owner_uids="$(container_allowed_owner_uids "$name")"
    execution_dir="/workspace/.everytalk/executions/$execution_id"
    docker exec --user 0:0 -e "EVERYTALK_ALLOWED_OWNER_UIDS=$owner_uids" "$name" /usr/local/bin/everytalk-runtime-wrapper \
        "$execution_dir" '' --execution-result "$stdout_offset" "$stderr_offset" "$max_bytes" "$request_hash"
}

watch_exec_helper() {
    workspace_id="${1:-}"
    execution_id="${2:-}"
    stdout_cursor="${3:-0}"
    stderr_cursor="${4:-0}"
    max_bytes="${5:-2048}"
    request_hash="${6:-}"
    valid_id "$workspace_id" || fail 'Workspace ID 无效' 54
    case "$execution_id" in execution_[A-Za-z0-9_-]*) ;; *) fail 'Execution ID 无效' 67 ;; esac
    case "$stdout_cursor" in ''|*[!0-9]*) fail 'stdout 游标无效' 68 ;; esac
    case "$stderr_cursor" in ''|*[!0-9]*) fail 'stderr 游标无效' 68 ;; esac
    case "$max_bytes" in ''|*[!0-9]*) fail '日志读取长度无效' 68 ;; esac
    [ "$max_bytes" -ge 1 ] && [ "$max_bytes" -le 262144 ] || fail '日志读取长度无效' 68
    valid_request_hash "$request_hash" || fail 'request hash 无效' 67
    name="$(require_workspace_container "$workspace_id")"
    owner_uids="$(container_allowed_owner_uids "$name")"
    execution_dir="/workspace/.everytalk/executions/$execution_id"
    docker exec --user 0:0 -e "EVERYTALK_ALLOWED_OWNER_UIDS=$owner_uids" "$name" /usr/local/bin/everytalk-runtime-wrapper \
        "$execution_dir" '' --watch-execution "$stdout_cursor" "$stderr_cursor" "$max_bytes" "$request_hash"
}

list_executions() {
    workspace_id="${1:-}"
    valid_id "$workspace_id" || fail 'Workspace ID 无效' 54
    name="$(require_workspace_container "$workspace_id")"
    owner_uids="$(container_allowed_owner_uids "$name")"
    # 路径由 Workspace ID 固定推导，只读取受管状态文件，不接受任意路径参数。
    docker exec --user 0:0 -e "EVERYTALK_ALLOWED_OWNER_UIDS=$owner_uids" "$name" /bin/sh -c '
        root=/workspace/.everytalk/executions
        [ -d "$root" ] && [ ! -L "$root" ] || exit 0
        root_real="$(realpath -e -- "$root" 2>/dev/null || true)"
        [ "$root_real" = "$root" ] || exit 0
        for state in "$root"/execution_*/state; do
            [ -f "$state" ] && [ ! -L "$state" ] || continue
            execution_dir="${state%/state}"
            execution_real="$(realpath -e -- "$execution_dir" 2>/dev/null || true)"
            [ "$execution_real" = "$execution_dir" ] || continue
            execution_owner="$(stat -c "%u" -- "$execution_dir" 2>/dev/null || true)"
            case "$execution_owner" in ''|*[!0-9]*) continue ;; esac
            directory_allowed=false
            for expected in ${EVERYTALK_ALLOWED_OWNER_UIDS:-0}; do
                [ "$execution_owner" = "$expected" ] && directory_allowed=true && break
            done
            [ "$directory_allowed" = true ] || continue
            owner="$(stat -c "%u" -- "$state" 2>/dev/null || true)"
            allowed=false
            for expected in ${EVERYTALK_ALLOWED_OWNER_UIDS:-0}; do
                [ "$owner" = "$expected" ] && allowed=true && break
            done
            [ "$allowed" = true ] || continue
            execution_id="$(awk -F= "\$1 == \"execution_id\" { print substr(\$0, length(\$1) + 2); exit }" "$state")"
            [ "$execution_id" = "${execution_dir##*/}" ] || continue
            cat -- "$state"
        done
    '
}

cancel_execution() {
    workspace_id="${1:-}"
    execution_id="${2:-}"
    request_hash="${3:-}"
    valid_id "$workspace_id" || fail 'Workspace ID 无效' 54
    case "$execution_id" in execution_[A-Za-z0-9_-]*) ;; *) fail 'Execution ID 无效' 67 ;; esac
    valid_request_hash "$request_hash" || fail 'request hash 无效' 67
    name="$(require_workspace_container "$workspace_id")"
    owner_uids="$(container_allowed_owner_uids "$name")"
    execution_dir="/workspace/.everytalk/executions/$execution_id"
    docker exec --user 0:0 -e "EVERYTALK_ALLOWED_OWNER_UIDS=$owner_uids" "$name" /usr/local/bin/everytalk-runtime-wrapper \
        "$execution_dir" '' --execution-cancel 0 "$request_hash"
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

state_value() {
    state_file="$1"
    state_key="$2"
    awk -F= -v key="$state_key" '$1 == key { print $2; exit }' "$state_file"
}

mark_background_states_stopped() {
    workspace_id="$1"
    background_root="$(workspace_path "$workspace_id")/.everytalk/background"
    [ -d "$background_root" ] && [ ! -L "$background_root" ] || return 0
    for process_dir in "$background_root"/process_*; do
        [ -d "$process_dir" ] && [ ! -L "$process_dir" ] || continue
        process_id="${process_dir##*/}"
        valid_id "$process_id" || continue
        case "$process_id" in process_*) ;; *) continue ;; esac
        state_file="$process_dir/state"
        [ -f "$state_file" ] && [ ! -L "$state_file" ] || continue
        pid="$(state_value "$state_file" pid)"
        start_ticks="$(state_value "$state_file" start_ticks)"
        execution_id="$(state_value "$state_file" execution_id)"
        case "$pid" in ''|*[!0-9]*) pid=0 ;; esac
        case "$start_ticks" in ''|*[!0-9]*) start_ticks=0 ;; esac
        valid_id "$execution_id" || execution_id=unknown
        temporary_state="$process_dir/state.tmp.$$"
        {
            printf 'process_id=%s\n' "$process_id"
            printf 'execution_id=%s\n' "$execution_id"
            printf 'pid=%s\n' "$pid"
            printf 'start_ticks=%s\n' "$start_ticks"
            printf 'status=STOPPED\n'
            printf 'updated_at=%s\n' "$(date +%s)"
        } > "$temporary_state"
        chown --reference="$state_file" "$temporary_state"
        chmod 600 "$temporary_state"
        mv -f "$temporary_state" "$state_file"
    done
}

stop_workspace_backgrounds() {
    workspace_id="$1"
    name="$2"
    running="$(docker inspect -f '{{.State.Running}}' "$name")"
    if [ "$running" = true ]; then
        docker stop --time 5 "$name" >/dev/null
    fi
    mark_background_states_stopped "$workspace_id"
}

delete_workspace() {
    workspace_id="${1:-}"
    delete_files="${2:-false}"
    name="$(container_name "$workspace_id")"
    if docker container inspect "$name" >/dev/null 2>&1; then
        name="$(require_workspace_container "$workspace_id")"
        stop_workspace_backgrounds "$workspace_id" "$name"
        docker rm --force "$name" >/dev/null
    else
        mark_background_states_stopped "$workspace_id"
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
    version) require_exact_args 0 "$@"; runtime_wrapper_hash >/dev/null; printf 'version=%s\n' "$VERSION" ;;
    build-image) require_exact_args 0 "$@"; build_image ;;
    set-network) require_exact_args 1 "$@"; configure_network_boundary "$@" ;;
    ensure-workspace) require_exact_args 1 "$@"; ensure_workspace "$@" ;;
    container-address) require_exact_args 1 "$@"; container_address "$@" ;;
    run) require_exact_args 4 "$@"; run_workspace "$@" ;;
    run-background) require_exact_args 4 "$@"; run_workspace_background "$@" ;;
    start-execution) require_exact_args 6 "$@"; run_workspace_execution "$@" ;;
    execution-status) require_exact_args 3 "$@"; execution_status "$@" ;;
    execution-result) require_exact_args 6 "$@"; execution_result "$@" ;;
    list-executions) require_exact_args 1 "$@"; list_executions "$@" ;;
    cancel-execution) require_exact_args 3 "$@"; cancel_execution "$@" ;;
    watch-execution) require_exact_args 6 "$@"; watch_exec_helper "$@" ;;
    watch-executions) require_exact_args 6 "$@"; watch_exec_helper "$@" ;;
    terminal) require_exact_args 1 "$@"; open_terminal "$@" ;;
    open-public) require_exact_args 4 "$@"; open_public_preview "$@" ;;
    preview-status) require_exact_args 1 "$@"; preview_status "$@" ;;
    close-public) require_exact_args 1 "$@"; close_public_preview "$@" ;;
    delete-workspace) require_exact_args 2 "$@"; delete_workspace "$@" ;;
    *) fail 'helper 子命令无效' 63 ;;
esac

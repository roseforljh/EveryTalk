#!/bin/sh
set -eu

# Wrapper 只接受经过 Android 校验的 Runtime 目录。
# 新协议从当前 exec Channel 的 stdin 一次读取 cwd、环境、命令和命令 stdin；
# 旧文件协议继续保留，兼容升级前已经启动或尚未清理的后台任务。
runtime_dir="${1:-}"
background_dir="${2:-}"
input_mode="${3:-}"
host_mode=false
ensure_host_private_dir() {
    relative_dir="$1"
    case "$relative_dir" in
        host-runtime|host-executions) ;;
        *) return 1 ;;
    esac
    root="$HOME/.everytalk"
    [ -d "$HOME" ] && [ ! -L "$HOME" ] || return 1
    if [ -e "$root" ] || [ -L "$root" ]; then
        [ -d "$root" ] && [ ! -L "$root" ] || return 1
    else
        mkdir "$root" || return 1
    fi
    root_real="$(realpath -e -- "$root" 2>/dev/null || true)"
    [ "$root_real" = "$root" ] || return 1
    root_owner="$(stat -c '%u' -- "$root" 2>/dev/null || true)"
    [ "$root_owner" = "$(id -u)" ] || return 1
    directory="$root/$relative_dir"
    if [ -e "$directory" ] || [ -L "$directory" ]; then
        [ -d "$directory" ] && [ ! -L "$directory" ] || return 1
    else
        mkdir "$directory" || return 1
    fi
    directory_real="$(realpath -e -- "$directory" 2>/dev/null || true)"
    [ "$directory_real" = "$directory" ] || return 1
    directory_owner="$(stat -c '%u' -- "$directory" 2>/dev/null || true)"
    [ "$directory_owner" = "$(id -u)" ]
}
case "$input_mode" in
--host-envelope|--host-envelope-v2|--host-managed-v2)
    host_mode=true
    case "$runtime_dir" in run_*) ;; *) printf '%s\n' 'runtime ID 无效' >&2; exit 40 ;; esac
    runtime_dir="$HOME/.everytalk/host-runtime/$runtime_dir"
    ensure_host_private_dir host-runtime || { printf '%s\n' 'Host Runtime 目录无效' >&2; exit 46; }
    case "$input_mode" in
        --host-envelope-v2|--host-managed-v2)
            ensure_host_private_dir host-executions || { printf '%s\n' 'Host Execution 目录无效' >&2; exit 46; }
            chmod 700 "$HOME/.everytalk/host-executions" ;;
    esac
    chmod 700 "$HOME/.everytalk" "$HOME/.everytalk/host-runtime"
    ;;
--host-execution-status|--host-execution-result|--host-execution-cancel|--host-watch-execution|--host-watch-executions)
    host_mode=true
    ensure_host_private_dir host-executions || { printf '%s\n' 'Host Execution 目录无效' >&2; exit 46; }
    chmod 700 "$HOME/.everytalk/host-executions"
    ;;
--execution-status|--execution-result|--execution-cancel|--watch-execution|--watch-executions)
    case "$runtime_dir" in
        /workspace/.everytalk/executions/execution_*|"$HOME"/.everytalk/workspaces/ws_*/.everytalk/executions/execution_*) ;;
        *) printf '%s\n' 'Execution 目录无效' >&2; exit 40 ;;
    esac
    ;;
*)
    case "$runtime_dir" in
        /workspace/.everytalk/runtime/run_*|*/.everytalk/runtime/run_*) ;;
        *) printf '%s\n' 'runtime 目录无效' >&2; exit 40 ;;
    esac
    ;;
esac
runtime_name="${runtime_dir##*/}"
case "$runtime_name" in ''|*[!A-Za-z0-9_-]*) printf '%s\n' 'runtime ID 无效' >&2; exit 40 ;; esac

command_file="$runtime_dir/command.sh"
environment_file="$runtime_dir/environment.sh"
stdin_file="$runtime_dir/stdin"
working_directory_file="$runtime_dir/cwd"

if [ "$host_mode" = true ]; then
    workspace="$HOME"
elif [ "$input_mode" = --execution-status ] || [ "$input_mode" = --execution-result ] || [ "$input_mode" = --execution-cancel ] || [ "$input_mode" = --watch-execution ] || [ "$input_mode" = --watch-executions ]; then
    workspace="${runtime_dir%%/.everytalk/executions/*}"
    workspace="$(cd "$workspace" && pwd -P)"
else
    workspace="${runtime_dir%%/.everytalk/runtime/*}"
    workspace="$(cd "$workspace" && pwd -P)"
fi

if [ "$input_mode" = --envelope-v2 ] || [ "$input_mode" = --host-envelope-v2 ] || \
   [ "$input_mode" = --managed-v2 ] || [ "$input_mode" = --host-managed-v2 ] || \
   [ "$input_mode" = --host-execution-status ] || [ "$input_mode" = --host-execution-result ] || \
   [ "$input_mode" = --host-execution-cancel ] || [ "$input_mode" = --host-watch-execution ] || [ "$input_mode" = --host-watch-executions ] || \
   [ "$input_mode" = --execution-status ] || [ "$input_mode" = --execution-result ] || [ "$input_mode" = --execution-cancel ] || \
   [ "$input_mode" = --watch-execution ] || [ "$input_mode" = --watch-executions ]; then
    v2_host=false
    case "$input_mode" in
        --host-envelope-v2|--host-managed-v2|--host-execution-status|--host-execution-result|--host-execution-cancel|--host-watch-execution|--host-watch-executions)
            v2_host=true ;;
    esac

    valid_execution_id() {
        # * 在 shell 模式里可匹配任意字符，必须额外拒绝路径分隔符和其他非法字符。
        case "${1:-}" in ''|*[!A-Za-z0-9_-]*) return 1 ;; esac
        case "$1" in execution_?*) return 0 ;; *) return 1 ;; esac
    }
    valid_decimal() { case "${1:-}" in ''|*[!0-9]*) return 1 ;; esac; }
    state_value() { awk -F= -v key="$2" '$1 == key { print substr($0, length(key) + 2); exit }' "$1"; }
    state_owner_allowed() {
        state_candidate="$1"
        [ -f "$state_candidate" ] && [ ! -L "$state_candidate" ] || return 1
        state_owner="$(stat -c '%u' -- "$state_candidate" 2>/dev/null || true)"
        case "$state_owner" in ''|*[!0-9]*) return 1 ;; esac
        allowed_owners="${EVERYTALK_ALLOWED_OWNER_UIDS:-$(id -u)}"
        for allowed_owner in $allowed_owners; do
            [ "$state_owner" = "$allowed_owner" ] && return 0
        done
        return 1
    }
    execution_directory_safe() {
        [ -d "$execution_dir" ] && [ ! -L "$execution_dir" ] || return 1
        execution_real="$(realpath -e -- "$execution_dir" 2>/dev/null || true)"
        [ "$execution_real" = "$execution_dir" ] || return 1
        execution_owner="$(stat -c '%u' -- "$execution_dir" 2>/dev/null || true)"
        case "$execution_owner" in ''|*[!0-9]*) return 1 ;; esac
        for allowed_owner in ${EVERYTALK_ALLOWED_OWNER_UIDS:-$(id -u)}; do
            [ "$execution_owner" = "$allowed_owner" ] && return 0
        done
        return 1
    }
    path_owner_allowed() {
        candidate="$1"
        owner="$(stat -c '%u' -- "$candidate" 2>/dev/null || true)"
        case "$owner" in ''|*[!0-9]*) return 1 ;; esac
        for allowed_owner in ${EVERYTALK_ALLOWED_OWNER_UIDS:-$(id -u)}; do
            [ "$owner" = "$allowed_owner" ] && return 0
        done
        return 1
    }
    execution_parent_safe() {
        execution_parent="${execution_dir%/*}"
        [ -d "$execution_parent" ] && [ ! -L "$execution_parent" ] || return 1
        execution_parent_real="$(realpath -e -- "$execution_parent" 2>/dev/null || true)"
        [ -n "$execution_parent_real" ] && [ "$execution_parent_real" = "$execution_parent" ] || return 1
        path_owner_allowed "$execution_parent"
    }
    process_owner_allowed() {
        process_owner="$(awk '/^Uid:/{print $2; exit}' "/proc/$state_pid/status" 2>/dev/null || true)"
        case "$process_owner" in ''|*[!0-9]*) return 1 ;; esac
        for allowed_owner in ${EVERYTALK_ALLOWED_OWNER_UIDS:-$(id -u)}; do
            [ "$process_owner" = "$allowed_owner" ] && return 0
        done
        return 1
    }
    reject_untrusted_state() {
        # 不可信状态不能伪造成全 0 request_hash。全 0 会被 Android 误判成另一条请求，
        # 也会掩盖真正失败的是路径、所有者还是状态身份校验。
        printf 'Execution 状态不可信: %s\n' "$1" >&2
        exit 47
    }

    if [ "$v2_host" = true ]; then
        if [ "$input_mode" = --host-envelope-v2 ] || [ "$input_mode" = --host-managed-v2 ]; then
            execution_id="$background_dir"
        else
            execution_id="$runtime_dir"
        fi
        valid_execution_id "$execution_id" || { printf '%s\n' 'Execution ID 无效' >&2; exit 40; }
        execution_dir="$HOME/.everytalk/host-executions/$execution_id"
        v2_workspace="$HOME"
    elif [ "$input_mode" = --execution-status ] || [ "$input_mode" = --execution-result ] || [ "$input_mode" = --execution-cancel ] || [ "$input_mode" = --watch-execution ] || [ "$input_mode" = --watch-executions ]; then
        execution_id="${runtime_dir##*/}"
        valid_execution_id "$execution_id" || { printf '%s\n' 'Execution ID 无效' >&2; exit 40; }
        execution_dir="$runtime_dir"
        v2_workspace="$workspace"
    else
        execution_id="${background_dir##*/}"
        valid_execution_id "$execution_id" || { printf '%s\n' 'Execution ID 无效' >&2; exit 40; }
        execution_dir="$background_dir"
        v2_workspace="${runtime_dir%%/.everytalk/runtime/*}"
        v2_workspace="$(cd "$v2_workspace" && pwd -P)"
        case "$execution_dir" in
            /workspace/.everytalk/executions/$execution_id|*/.everytalk/executions/$execution_id) ;;
            *) printf '%s\n' 'Execution 目录越界' >&2; exit 46 ;;
        esac
    fi
    process_id="process_$execution_id"
    state_file="$execution_dir/state"
    stdout_log="$execution_dir/stdout.log"
    stderr_log="$execution_dir/stderr.log"
    timeout_seconds="${4:-120}"
    # status/cancel 的请求哈希在第 5 个参数，result 还要先接收三个日志分页参数。
    # 统一从第 7 个参数读取 result 的哈希，避免把 stderr offset 当成身份校验值。
    if [ "$input_mode" = --host-execution-result ] || [ "$input_mode" = --execution-result ] || \
       [ "$input_mode" = --host-watch-execution ] || [ "$input_mode" = --watch-execution ] || \
       [ "$input_mode" = --host-watch-executions ] || [ "$input_mode" = --watch-executions ]; then
        request_hash="${7:-}"
    else
        request_hash="${5:-}"
    fi
    expected_request_hash="$request_hash"
    if [ "$v2_host" = true ]; then state_target=HOST; else state_target=CONTAINER; fi

    state_has_valid_process() {
        state_pid="$(state_value "$state_file" pid)"
        state_ticks="$(state_value "$state_file" start_ticks)"
        valid_decimal "$state_pid" && valid_decimal "$state_ticks" || return 1
        [ "$state_pid" -gt 1 ] || return 1
        read_process_identity "$state_pid" &&
            [ "$cancel_process_ticks" = "$state_ticks" ] &&
            [ "$cancel_process_group" = "$state_pid" ] && process_group_owner_allowed
    }

    state_has_expected_identity() {
        [ "$(state_value "$state_file" execution_id)" = "$execution_id" ] || return 1
        [ "$(state_value "$state_file" process_id)" = "$process_id" ] || return 1
        [ "$(state_value "$state_file" target)" = "$state_target" ] || return 1
    }

    write_v2_state() {
        # 登记取消后只有持有 cancel.lock 的取消端发布终态；执行端不再覆盖状态。
        case "$input_mode" in --managed-v2|--host-managed-v2)
            [ ! -e "$execution_dir/cancel.members" ] || return 0 ;;
        esac
        state_status="$1"
        state_exit="${2:-}"
        state_pid="${3:-${state_pid:-}}"
        state_ticks="${4:-${state_ticks:-}}"
        state_started="${5:-${state_started:-}}"
        state_termination_reason="${6:-}"
        state_boot_id="$(state_value "$state_file" boot_id 2>/dev/null || true)"
        [ -n "$state_boot_id" ] || state_boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || printf 'unknown')"
        state_tmp="$state_file.tmp.$$"
        stdout_bytes="$(wc -c < "$stdout_log" 2>/dev/null || printf '0')"
        stderr_bytes="$(wc -c < "$stderr_log" 2>/dev/null || printf '0')"
        {
            printf 'protocol=2\n'
            printf 'execution_id=%s\n' "$execution_id"
            printf 'process_id=%s\n' "$process_id"
            printf 'request_hash=%s\n' "$request_hash"
            printf 'target=%s\n' "$state_target"
            printf 'pid=%s\n' "$state_pid"
            printf 'start_ticks=%s\n' "$state_ticks"
            printf 'status=%s\n' "$state_status"
            printf 'exit_code=%s\n' "$state_exit"
            printf 'started_at=%s\n' "$state_started"
            printf 'updated_at=%s\n' "$(date +%s)"
            printf 'stdout_bytes=%s\n' "$stdout_bytes"
            printf 'stderr_bytes=%s\n' "$stderr_bytes"
            printf 'boot_id=%s\n' "$state_boot_id"
            printf 'termination_reason=%s\n' "$state_termination_reason"
        } > "$state_tmp"
        chmod 600 "$state_tmp"
        mv -f "$state_tmp" "$state_file"
    }
    print_v2_state() {
        if [ ! -e "$execution_dir" ]; then
            printf 'protocol=2\nexecution_id=%s\nprocess_id=%s\nrequest_hash=0000000000000000000000000000000000000000000000000000000000000000\ntarget=%s\npid=0\nstart_ticks=0\nstatus=MISSING\nexit_code=\nstarted_at=0\nupdated_at=0\nstdout_bytes=0\nstderr_bytes=0\n' "$execution_id" "$process_id" "$state_target"
            return 0
        fi
        execution_parent_safe || reject_untrusted_state '父目录校验失败'
        execution_directory_safe || reject_untrusted_state '执行目录校验失败'
        if [ -f "$state_file" ] && [ ! -L "$state_file" ]; then
            state_owner_allowed "$state_file" || reject_untrusted_state '状态文件所有者校验失败'
            state_has_expected_identity || reject_untrusted_state '状态身份校验失败'
            current_status="$(state_value "$state_file" status)"
            if ! cancel_pending && { [ "$current_status" = RUNNING ] || [ "$current_status" = STARTING ]; }; then
                if ! state_has_valid_process; then
                    # boot_id 改变表示 VPS 重启；boot_id 相同表示进程被外部终止。
                    request_hash="$(state_value "$state_file" request_hash)"
                    existing_target="$(state_value "$state_file" target)"
                    [ -n "$existing_target" ] && state_target="$existing_target"
                    original_boot_id="$(state_value "$state_file" boot_id)"
                    current_boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || printf 'unknown')"
                    termination_reason=REMOTE_PROCESS_TERMINATED
                    [ -n "$original_boot_id" ] && [ "$original_boot_id" != "$current_boot_id" ] && termination_reason=VPS_RESTARTED
                    write_v2_state STOPPED 143 "$(state_value "$state_file" pid)" \
                        "$(state_value "$state_file" start_ticks)" "$(state_value "$state_file" started_at)" "$termination_reason"
                fi
            fi
            # 兼容仍在运行的旧 Wrapper：它可能提前写 CANCELLED，但取消端尚未确认子进程退出。
            # 对外仅发布 UNKNOWN，直到本次 cancel 的完整收尾标记落盘。
            if cancel_pending; then
                awk -F= '$1 == "status" { print "status=UNKNOWN"; next }
                    $1 == "exit_code" { print "exit_code="; next } { print }' "$state_file"
            else
                cat "$state_file"
            fi
        else
            reject_untrusted_state '状态文件缺失或为符号链接'
        fi
    }
    cleanup_v2_runtime() {
        rm -f -- "$environment_file" "$stdin_file" "$working_directory_file" "$command_file"
        rm -f -- "$runtime_dir/execution.id"
        rmdir "$runtime_dir" 2>/dev/null || true
    }

    cancel_is_complete() {
        state_owner_allowed "$execution_dir/cancel.complete" &&
            [ "$(cat "$execution_dir/cancel.complete")" = "$request_hash" ]
    }

    # 查询只投影取消状态，不参与取消状态落盘；失败也不能覆盖恰好完成的正常结果。
    cancel_pending() {
        [ "${cancel_unconfirmed:-false}" = true ] ||
            { [ -e "$execution_dir/cancel.members" ] && ! cancel_is_complete; }
    }
    observed_v2_status() {
        if cancel_pending; then printf UNKNOWN; else state_value "$state_file" status; fi
    }
    cancel_clock() {
        # /proc/uptime 是单调时间，不受系统校时影响；读取与计算均用 Shell 内建命令。
        IFS=' ' read -r cancel_uptime cancel_idle < /proc/uptime || return 1
        cancel_fraction="${cancel_uptime#*.}"
        cancel_now="$((${cancel_uptime%%.*} * 100 + 1$cancel_fraction - 100))"
    }
    cancel_within_deadline() {
        cancel_clock && [ "$cancel_now" -lt "$cancel_deadline" ]
    }

    # 只枚举这次 setsid 启动的会话，不跨到其他 Execution、systemd 服务或自行脱离的守护进程。
    # timeout 可以创建新的进程组，但仍属于同一会话，因此不能只 kill 原进程组。
    # 每个成员记录 PID + start_ticks，发信号前再次验证，拒绝接管复用的 PID。
    read_process_identity() {
        cancel_process_pid="$1"
        cancel_process_line=''
        IFS= read -r cancel_process_line 2>/dev/null < "/proc/$cancel_process_pid/stat" || return 1
        cancel_process_rest="${cancel_process_line##*) }"
        set -- $cancel_process_rest
        [ "$#" -ge 20 ] || return 1
        cancel_process_state="$1"
        cancel_process_group="$3"
        cancel_process_session="$4"
        shift 19
        cancel_process_ticks="$1"
        # 僵尸已经释放命令的内存和文件描述符，只能由其父进程回收，不能再发信号。
        [ "$cancel_process_state" != Z ] && [ "$cancel_process_state" != X ]
    }
    cancel_member_matches() {
        valid_decimal "$1" && [ "$1" -gt 1 ] && valid_decimal "$2" || return 1
        cancel_expected_ticks="$2"
        read_process_identity "$1" &&
            [ "$cancel_process_session" = "$cancel_session" ] &&
            [ "$cancel_process_ticks" = "$cancel_expected_ticks" ] &&
            path_owner_allowed "/proc/$cancel_process_pid"
    }
    collect_cancel_members() {
        for cancel_stat in /proc/[0-9]*/stat; do
            cancel_within_deadline || return 1
            cancel_pid="${cancel_stat%/stat}"
            cancel_pid="${cancel_pid##*/}"
            read_process_identity "$cancel_pid" || continue
            [ "$cancel_process_session" = "$cancel_session" ] || continue
            [ "$cancel_process_ticks" -ge "$cancel_start_ticks" ] || return 1
            path_owner_allowed "/proc/$cancel_pid" || return 1
            printf '%s %s\n' "$cancel_pid" "$cancel_process_ticks"
        done
    }
    cancel_has_anchor() {
        while read -r cancel_pid cancel_ticks; do
            cancel_within_deadline || return 1
            cancel_member_matches "$cancel_pid" "$cancel_ticks" && return 0
        done < "$execution_dir/cancel.members"
        return 1
    }
    process_group_owner_allowed() {
        # 进程组必须通过同一所有者检查；调用方另外校验 PID、start_ticks 和 session。
        process_owner_allowed
    }
    save_cancel_members() {
        cancel_members_tmp="$(mktemp "$execution_dir/cancel-members.XXXXXX")" || return 1
        printf '%s\n' "$cancel_members" > "$cancel_members_tmp"
        mv -f -- "$cancel_members_tmp" "$execution_dir/cancel.members"
    }
    signal_cancel_members() {
        # 一批成员只启动一次 Python。代码随 Wrapper 的摘要版本一起部署，无额外安装文件。
        # 必须先打开绑定原进程的 pidfd，再验证身份，最后通过句柄发信号。
        # 不支持 pidfd / 权限不足时返回失败，由上层报告 UNKNOWN；绝不退回数字 PID kill。
        python3 -I - "$execution_dir/cancel.members" "$cancel_session" "$1" "$cancel_deadline" \
            "${EVERYTALK_ALLOWED_OWNER_UIDS:-$(id -u)}" <<'EVERYTALK_PIDFD'
import os
import select
import signal
import sys
import time


def signal_members(members_path, session, signal_name, deadline, owners):
    if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
        raise RuntimeError("系统缺少 pidfd 支持，未降级为 PID kill")
    with open(members_path, encoding="ascii") as members:
        for line in members:
            if time.monotonic() * 100 >= deadline:
                raise RuntimeError("取消超过时间预算")
            pid, ticks = map(int, line.split())
            if pid <= 1 or ticks < 0:
                raise RuntimeError("取消成员身份无效")
            try:
                fd = os.pidfd_open(pid, 0)
            except ProcessLookupError:
                continue
            try:
                # /proc 使用数字路径；读取期间原进程可能退出并被复用。
                # 读取后再次检查 pidfd 是否已退出，拒绝拿新进程身份验证旧句柄。
                try:
                    with open(f"/proc/{pid}/stat", encoding="utf-8", errors="replace") as stat:
                        fields = stat.read().rsplit(") ", 1)[1].split()
                    owner = os.stat(f"/proc/{pid}").st_uid
                except (FileNotFoundError, ProcessLookupError):
                    continue
                if (fields[0] in ("Z", "X") or int(fields[3]) != session
                        or int(fields[19]) != ticks or owner not in owners):
                    continue
                poller = select.poll()
                poller.register(fd, select.POLLIN)
                if poller.poll(0):
                    continue
                # 即使此处原进程恰好退出，句柄也只能命中它，不会命中复用 PID 的服务。
                try:
                    signal.pidfd_send_signal(fd, getattr(signal, "SIG" + signal_name))
                except ProcessLookupError:
                    pass
            finally:
                os.close(fd)


try:
    signal_members(sys.argv[1], int(sys.argv[2]), sys.argv[3], int(sys.argv[4]),
                   {int(owner) for owner in sys.argv[5].split()})
except (OSError, RuntimeError, ValueError, IndexError, AttributeError) as error:
    print(f"停止未确认: {error}", file=sys.stderr)
    sys.exit(1)
EVERYTALK_PIDFD
    }
    cleanup_cancelled_execution() {
        # SIGKILL 无法执行 trap。新任务将 runtime ID 单独留在其 Execution 目录，
        # 取消端只删除该目录映射下的四个临时输入文件，绝不递归删除 Workspace。
        if state_owner_allowed "$execution_dir/runtime.id"; then
            cancel_runtime_id="$(cat "$execution_dir/runtime.id")"
            case "$cancel_runtime_id" in
                run_*) ;;
                *) return 1 ;;
            esac
            case "$cancel_runtime_id" in *[!A-Za-z0-9_-]*) return 1 ;; esac
            if [ "$v2_host" = true ]; then
                cancel_runtime="$HOME/.everytalk/host-runtime/$cancel_runtime_id"
            else
                cancel_runtime="$v2_workspace/.everytalk/runtime/$cancel_runtime_id"
            fi
            if [ -e "$cancel_runtime" ] || [ -L "$cancel_runtime" ]; then
                [ -d "$cancel_runtime" ] && [ ! -L "$cancel_runtime" ] &&
                    [ "$(realpath -e -- "$cancel_runtime")" = "$cancel_runtime" ] &&
                    path_owner_allowed "$cancel_runtime" || return 1
                # 反向校验防止损坏的 runtime.id 指向另一个命令的临时目录。
                state_owner_allowed "$cancel_runtime/execution.id" &&
                    [ "$(cat "$cancel_runtime/execution.id")" = "$execution_id" ] || return 1
                rm -f -- "$cancel_runtime/environment.sh" "$cancel_runtime/stdin" \
                    "$cancel_runtime/cwd" "$cancel_runtime/command.sh" "$cancel_runtime/execution.id"
                rmdir -- "$cancel_runtime" 2>/dev/null || true
            fi
        fi
        # 仅已确认停止的本次命令日志保留前 256 KiB，原地截断不产生额外大副本。
        # 项目文件和其他任务日志一律不碰；链接文件拒绝处理，防止伤及链接目标。
        for cancel_log in "$stdout_log" "$stderr_log"; do
            state_owner_allowed "$cancel_log" && [ "$(stat -c '%h' -- "$cancel_log")" = 1 ] || continue
            if [ "$(stat -c '%s' -- "$cancel_log")" -gt 262144 ]; then
                truncate -s 262144 -- "$cancel_log" || return 1
            fi
        done
    }
    cancel_managed_execution() {
        # SSH 重试与按钮请求可能重叠；锁仅覆盖这个 Execution，关闭 channel 后自动释放。
        if [ -e "$execution_dir/cancel.lock" ] || [ -L "$execution_dir/cancel.lock" ]; then
            state_owner_allowed "$execution_dir/cancel.lock" &&
                [ "$(stat -c '%h' -- "$execution_dir/cancel.lock")" = 1 ] || return 1
        fi
        exec 9> "$execution_dir/cancel.lock"
        # 锁忙时不覆盖另一个取消请求正在发布的状态，交给 Android 稍后重试。
        flock -n 9 || exit 75
        cancel_is_complete && return 0
        cancel_clock || return 1
        cancel_kill_at="$((cancel_now + 500))"
        cancel_deadline="$((cancel_now + 800))"
        cancel_session="$(state_value "$state_file" pid)"
        cancel_start_ticks="$(state_value "$state_file" start_ticks)"
        valid_decimal "$cancel_session" && [ "$cancel_session" -gt 1 ] &&
            valid_decimal "$cancel_start_ticks" || return 1
        [ "$(state_value "$state_file" boot_id)" = "$(cat /proc/sys/kernel/random/boot_id)" ] || return 1
        if [ -e "$execution_dir/cancel.members" ] || [ -L "$execution_dir/cancel.members" ]; then
            state_owner_allowed "$execution_dir/cancel.members" || return 1
        else
            # 已自然完成的历史命令不会被补杀或清理。
            case "$(state_value "$state_file" status)" in STARTING|RUNNING) ;; *) return 0 ;; esac
            state_has_valid_process || return 1
            cancel_members="$(collect_cancel_members)" || return 1
            [ -n "$cancel_members" ] || return 1
            save_cancel_members || return 1
        fi
        # 不相信 wrapper 提前写出的 CANCELLED；必须看到受管会话真的没有活进程。
        # 重新扫描前要求至少一个已知成员仍在，防止会话号复用后扩大杀伤范围。
        signal_cancel_members TERM || return 1
        cancel_next_scan="$cancel_kill_at"
        while cancel_within_deadline; do
            cancel_anchored=false
            cancel_has_anchor && cancel_anchored=true
            # 等待时只探测已登记成员；到 KILL 阶段每秒补扫一次，或在确认退出前最后扫描。
            # 不再每 100ms 扫描整台 VPS。扫描和发信号内部也检查同一截止时间。
            if [ "$cancel_anchored" = false ] || [ "$cancel_now" -ge "$cancel_next_scan" ]; then
                cancel_members="$(collect_cancel_members)" || return 1
                if [ -z "$cancel_members" ]; then
                    cleanup_cancelled_execution || return 1
                    write_v2_state CANCELLED 137 "$cancel_session" "$cancel_start_ticks" \
                        "$(state_value "$state_file" started_at)"
                    cancel_complete_tmp="$(mktemp "$execution_dir/cancel-complete.XXXXXX")" || return 1
                    printf '%s\n' "$request_hash" > "$cancel_complete_tmp"
                    mv -f -- "$cancel_complete_tmp" "$execution_dir/cancel.complete"
                    return 0
                fi
                [ "$cancel_anchored" = true ] || return 1
                save_cancel_members || return 1
                signal_cancel_members KILL || return 1
                cancel_next_scan="$((cancel_now + 100))"
            fi
            sleep 0.1
        done
        return 1
    }

    if [ "$input_mode" = --host-execution-status ] || [ "$input_mode" = --host-execution-result ] || \
       [ "$input_mode" = --host-execution-cancel ] || [ "$input_mode" = --host-watch-execution ] || [ "$input_mode" = --host-watch-executions ] || \
       [ "$input_mode" = --execution-status ] || [ "$input_mode" = --execution-result ] || \
       [ "$input_mode" = --execution-cancel ] || [ "$input_mode" = --watch-execution ] || [ "$input_mode" = --watch-executions ]; then
        # 停止可能先于远端启动到达。按固定 ID 原子占位，后到的同一请求只能读到 CANCELLED，
        # 不能出现“取消看到 MISSING 返回了，命令随后又启动”的孤儿任务。
        if { [ "$input_mode" = --host-execution-cancel ] || [ "$input_mode" = --execution-cancel ]; } &&
           [ ! -e "$execution_dir" ] && [ ! -L "$execution_dir" ]; then
            case "$expected_request_hash" in ''|*[!0-9a-f]*) reject_untrusted_state '取消请求哈希无效' ;; esac
            [ "${#expected_request_hash}" -eq 64 ] || reject_untrusted_state '取消请求哈希长度无效'
            execution_parent_safe || reject_untrusted_state '取消目标父目录无效'
            if (umask 077; mkdir "$execution_dir") 2>/dev/null; then
                : > "$stdout_log"
                : > "$stderr_log"
                write_v2_state CANCELLED 0 0 0 0
            fi
        fi
        # 查询前先完成信任校验。长监听禁止先读取不可信 state 再等待 25 秒，
        # 否则旧目录会看起来像一条正常运行中的任务。
        if [ -e "$execution_dir" ]; then
            execution_parent_safe || reject_untrusted_state '父目录校验失败'
            execution_directory_safe || reject_untrusted_state '执行目录校验失败'
            [ -f "$state_file" ] && [ ! -L "$state_file" ] || reject_untrusted_state '状态文件缺失或为符号链接'
            state_owner_allowed "$state_file" || reject_untrusted_state '状态文件所有者校验失败'
            state_has_expected_identity || reject_untrusted_state '状态身份校验失败'
        fi
        if [ -f "$state_file" ] && [ ! -L "$state_file" ] && execution_parent_safe && execution_directory_safe && state_owner_allowed "$state_file" && \
           [ -n "$expected_request_hash" ]; then
            existing_hash="$(state_value "$state_file" request_hash)"
            if [ -n "$existing_hash" ] && [ "$existing_hash" != "$expected_request_hash" ]; then
                printf '%s\n' 'Execution request hash 冲突' >&2
                exit 49
            fi
        fi
        if [ "$input_mode" = --host-execution-result ] || [ "$input_mode" = --execution-result ] || \
           [ "$input_mode" = --host-watch-execution ] || [ "$input_mode" = --watch-execution ] || \
           [ "$input_mode" = --host-watch-executions ] || [ "$input_mode" = --watch-executions ]; then
            stdout_cursor="${4:-0}"
            stderr_cursor="${5:-0}"
            stdout_offset="$stdout_cursor"
            stderr_offset="$stderr_cursor"
            max_bytes="${6:-2048}"
            valid_decimal "$stdout_cursor" && valid_decimal "$stderr_cursor" && valid_decimal "$max_bytes" || {
                printf '%s\n' '日志读取参数无效' >&2; exit 48;
            }
            [ "$max_bytes" -ge 1 ] && [ "$max_bytes" -le 262144 ] || { printf '%s\n' '日志读取长度无效' >&2; exit 48; }
        fi
        if [ "$input_mode" = --host-execution-cancel ] || [ "$input_mode" = --execution-cancel ]; then
          # 路径、状态身份和请求哈希已经在本分支入口统一校验，无需在这里重复一遍。
          if [ -f "$state_file" ]; then
            current_status="$(state_value "$state_file" status)"
            if [ "$current_status" = RUNNING ] || [ "$current_status" = STARTING ] || \
               [ -e "$execution_dir/cancel.members" ]; then
                if ! cancel_managed_execution; then
                    cancel_unconfirmed=true
                fi
            fi
          fi
        fi
        if [ "$input_mode" = --host-watch-execution ] || [ "$input_mode" = --watch-execution ] || \
           [ "$input_mode" = --host-watch-executions ] || [ "$input_mode" = --watch-executions ]; then
            # 一个 Channel 最多等待约 25 秒。有新增日志或进入终态时立即返回，
            # 没有变化时返回心跳，让 Android 保持监听但不制造假进度。
            watch_attempt=0
            stdout_total=0
            stderr_total=0
            while [ "$watch_attempt" -lt 84 ]; do
                st_val="$(observed_v2_status 2>/dev/null || printf 'UNKNOWN')"
                if [ -f "$stdout_log" ] && [ ! -L "$stdout_log" ] && state_owner_allowed "$stdout_log"; then
                    stdout_total="$(wc -c < "$stdout_log" | tr -d ' ')"
                fi
                if [ -f "$stderr_log" ] && [ ! -L "$stderr_log" ] && state_owner_allowed "$stderr_log"; then
                    stderr_total="$(wc -c < "$stderr_log" | tr -d ' ')"
                fi
                if [ "$st_val" != RUNNING ] && [ "$st_val" != STARTING ]; then break; fi
                if [ "$stdout_total" -gt "$stdout_cursor" ] || [ "$stderr_total" -gt "$stderr_cursor" ]; then
                    # 首个新增字节出现后再合并约 300ms，避免高频输出每一行都写一次 Room。
                    sleep 0.3
                    if [ -f "$stdout_log" ] && [ ! -L "$stdout_log" ] && state_owner_allowed "$stdout_log"; then
                        stdout_total="$(wc -c < "$stdout_log" | tr -d ' ')"
                    fi
                    if [ -f "$stderr_log" ] && [ ! -L "$stderr_log" ] && state_owner_allowed "$stderr_log"; then
                        stderr_total="$(wc -c < "$stderr_log" | tr -d ' ')"
                    fi
                    break
                fi
                sleep 0.3
                watch_attempt="$((watch_attempt + 1))"
            done

            stdout_count="$((stdout_total - stdout_cursor))"
            stderr_count="$((stderr_total - stderr_cursor))"
            [ "$stdout_count" -ge 0 ] || stdout_count=0
            [ "$stderr_count" -ge 0 ] || stderr_count=0
            [ "$stdout_count" -le "$max_bytes" ] || stdout_count="$max_bytes"
            [ "$stderr_count" -le "$max_bytes" ] || stderr_count="$max_bytes"
            curr_stdout_cursor="$((stdout_cursor + stdout_count))"
            curr_stderr_cursor="$((stderr_cursor + stderr_count))"
            print_v2_state
            st_val="$(observed_v2_status 2>/dev/null || printf 'UNKNOWN')"
            if [ "$st_val" = RUNNING ] || [ "$st_val" = STARTING ]; then
                if [ "$stdout_count" -gt 0 ] || [ "$stderr_count" -gt 0 ]; then
                    event_type="PROGRESS"
                else
                    event_type="HEARTBEAT"
                fi
            else
                event_type="TERMINAL"
            fi
            observed_at="$(date +%s)"
            event_seq="$((observed_at * 1000000 + curr_stdout_cursor + curr_stderr_cursor))"
            printf 'event_type=%s\nevent_seq=%s\nstdout_offset=%s\nstderr_offset=%s\nstdout_cursor=%s\nstderr_cursor=%s\nobserved_at=%s\n' \
                "$event_type" "$event_seq" "$stdout_cursor" "$stderr_cursor" \
                "$curr_stdout_cursor" "$curr_stderr_cursor" "$observed_at"
            if [ -f "$stdout_log" ] && [ ! -L "$stdout_log" ] && state_owner_allowed "$stdout_log"; then
                stdout_chunk="$(tail -c +$((stdout_cursor + 1)) "$stdout_log" 2>/dev/null | head -c "$stdout_count" | base64 2>/dev/null | tr -d '\n' || true)"
            else
                stdout_chunk=""
            fi
            if [ -f "$stderr_log" ] && [ ! -L "$stderr_log" ] && state_owner_allowed "$stderr_log"; then
                stderr_chunk="$(tail -c +$((stderr_cursor + 1)) "$stderr_log" 2>/dev/null | head -c "$stderr_count" | base64 2>/dev/null | tr -d '\n' || true)"
            else
                stderr_chunk=""
            fi
            printf 'stdout_base64=%s\n' "$stdout_chunk"
            printf 'stderr_base64=%s\n' "$stderr_chunk"
        elif [ "$input_mode" = --host-execution-result ] || [ "$input_mode" = --execution-result ]; then
            print_v2_state
            printf 'stdout_offset=%s\n' "$stdout_offset"
            printf 'stderr_offset=%s\n' "$stderr_offset"
            printf 'stdout_cursor=%s\n' "$stdout_offset"
            printf 'stderr_cursor=%s\n' "$stderr_offset"
            printf 'event_seq=1\n'
            printf 'event_type=TERMINAL\n'
            printf 'observed_at=%s\n' "$(date +%s)"
            if [ -f "$stdout_log" ] && [ ! -L "$stdout_log" ] && state_owner_allowed "$stdout_log"; then
                stdout_chunk="$(tail -c +$((stdout_offset + 1)) "$stdout_log" 2>/dev/null | head -c "$max_bytes" | base64 2>/dev/null | tr -d '\n' || true)"
            else
                stdout_chunk=""
            fi
            if [ -f "$stderr_log" ] && [ ! -L "$stderr_log" ] && state_owner_allowed "$stderr_log"; then
                stderr_chunk="$(tail -c +$((stderr_offset + 1)) "$stderr_log" 2>/dev/null | head -c "$max_bytes" | base64 2>/dev/null | tr -d '\n' || true)"
            else
                stderr_chunk=""
            fi
            printf 'stdout_base64=%s\n' "$stdout_chunk"
            printf 'stderr_base64=%s\n' "$stderr_chunk"
        else
            print_v2_state
        fi
        exit 0
    fi

    if [ "$input_mode" = --managed-v2 ] || [ "$input_mode" = --host-managed-v2 ]; then
        [ -f "$command_file" ] || { printf '%s\n' '缺少 command.sh' >&2; exit 41; }
        [ -f "$working_directory_file" ] || { printf '%s\n' '缺少 cwd' >&2; exit 42; }
        valid_decimal "$timeout_seconds" && [ "$timeout_seconds" -ge 0 ] && [ "$timeout_seconds" -le 3600 ] || {
            printf '%s\n' 'Runtime timeout 无效' >&2; exit 46;
        }
        umask 077
        state_pid="$$"
        # 与取消/查询使用同一解析器；comm 含空格或括号时不能按整行第 22 列读取。
        read_process_identity "$state_pid" || { printf '%s\n' '无法读取进程起始标记' >&2; exit 46; }
        state_ticks="$cancel_process_ticks"
        state_started="$(date +%s)"
        handle_signal() {
            trap - HUP INT TERM
            write_v2_state CANCELLED 143 "$state_pid" "$state_ticks" "$state_started"
            cleanup_v2_runtime
            exit 143
        }
        trap handle_signal HUP INT TERM
        write_v2_state STARTING '' "$state_pid" "$state_ticks" "$state_started"
        relative_cwd="$(cat "$working_directory_file")"
        cwd_without_cr="$(printf '%s' "$relative_cwd" | tr -d '\r')"
        [ "$cwd_without_cr" = "$relative_cwd" ] || { write_v2_state FAILED 43 "$state_pid" "$state_ticks" "$state_started"; cleanup_v2_runtime; exit 43; }
        if [ "$v2_host" = true ]; then
            case "$relative_cwd" in
                '~') target_cwd="$HOME" ;;
                /*) target_cwd="$(realpath -m "$relative_cwd")" ;;
                *) write_v2_state FAILED 43 "$state_pid" "$state_ticks" "$state_started"; cleanup_v2_runtime; exit 43 ;;
            esac
        else
            case "$relative_cwd" in
                *'..'*|/*|*"\n"*) write_v2_state FAILED 43 "$state_pid" "$state_ticks" "$state_started"; cleanup_v2_runtime; exit 43 ;;
                *) ;;
            esac
            target_cwd="$(realpath -m "$v2_workspace/$relative_cwd")"
            case "$target_cwd" in
                "$v2_workspace"|"$v2_workspace"/*) ;;
                *) write_v2_state FAILED 44 "$state_pid" "$state_ticks" "$state_started"; cleanup_v2_runtime; exit 44 ;;
            esac
        fi
        [ -d "$target_cwd" ] || { write_v2_state FAILED 45 "$state_pid" "$state_ticks" "$state_started"; cleanup_v2_runtime; exit 45; }
        if [ -f "$environment_file" ]; then
            set -a
            . "$environment_file"
            set +a
            rm -f "$environment_file"
        fi
        cd "$target_cwd"
        write_v2_state RUNNING '' "$state_pid" "$state_ticks" "$state_started"
        set +e
        if [ "$timeout_seconds" -eq 0 ]; then
            if [ -f "$stdin_file" ]; then
                /bin/sh "$command_file" < "$stdin_file"
            else
                /bin/sh "$command_file" < /dev/null
            fi
        elif [ -f "$stdin_file" ]; then
            timeout --signal=TERM --kill-after=5s "${timeout_seconds}s" /bin/sh "$command_file" < "$stdin_file"
        else
            timeout --signal=TERM --kill-after=5s "${timeout_seconds}s" /bin/sh "$command_file" < /dev/null
        fi
        command_status="$?"
        set -e
        trap - HUP INT TERM
        if [ "$command_status" -eq 0 ]; then
            write_v2_state SUCCEEDED "$command_status" "$state_pid" "$state_ticks" "$state_started"
        elif [ "$command_status" -eq 124 ] || [ "$command_status" -eq 137 ]; then
            write_v2_state TIMED_OUT "$command_status" "$state_pid" "$state_ticks" "$state_started"
        else
            write_v2_state FAILED "$command_status" "$state_pid" "$state_ticks" "$state_started"
        fi
        cleanup_v2_runtime
        exit 0
    fi

    if [ "$input_mode" = --envelope-v2 ] || [ "$input_mode" = --host-envelope-v2 ]; then
        if [ "$v2_host" = true ]; then
            mkdir -p "$HOME/.everytalk/host-executions"
            chmod 700 "$HOME/.everytalk" "$HOME/.everytalk/host-executions"
        fi
        execution_parent_safe || { printf '%s\n' 'Execution 父目录无效' >&2; exit 46; }
        valid_decimal "$timeout_seconds" && [ "$timeout_seconds" -ge 0 ] && [ "$timeout_seconds" -le 3600 ] || {
            printf '%s\n' 'Runtime timeout 无效' >&2; exit 46;
        }
        if [ -e "$execution_dir" ]; then
            # 同一 Execution 只有在目录、状态文件、归属和身份都可信时才允许幂等返回。
            # 任一检查失败都拒绝接管可能由用户或其他程序放入的目录。
            if ! execution_directory_safe || [ ! -f "$state_file" ] || [ -L "$state_file" ] || \
               ! state_owner_allowed "$state_file" || ! state_has_expected_identity; then
                printf '%s\n' 'Execution 目录已存在但状态归属无效' >&2
                exit 47
            fi
            existing_hash="$(state_value "$state_file" request_hash)"
            if [ -n "$request_hash" ] && [ -n "$existing_hash" ] && [ "$existing_hash" != "$request_hash" ]; then
                printf '%s\n' 'Execution request hash 冲突' >&2
                exit 49
            fi
            print_v2_state
            exit 0
        fi
        [ ! -e "$execution_dir" ] || { printf '%s\n' 'Execution 目录已存在但状态无效' >&2; exit 47; }
        umask 077
        # 与提前到达的 cancel 竞争时只允许一个请求创建目录，不能覆盖取消占位。
        mkdir "$execution_dir" || exit 47
        cleanup_outer() {
            rm -f -- "$environment_file" "$stdin_file" "$working_directory_file" "$command_file"
            rm -f -- "$runtime_dir/execution.id"
            rmdir "$runtime_dir" 2>/dev/null || true
            rmdir "$execution_dir" 2>/dev/null || true
        }
        trap cleanup_outer EXIT HUP INT TERM
        IFS= read -r envelope_magic || { printf '%s\n' 'Runtime Envelope 缺少版本' >&2; exit 41; }
        if [ "$v2_host" = true ]; then
            [ "$envelope_magic" = EVERYTALK_EXEC_HOST_V1 ] || { printf '%s\n' 'Runtime Envelope 版本无效' >&2; exit 41; }
        else
            [ "$envelope_magic" = EVERYTALK_EXEC_V1 ] || { printf '%s\n' 'Runtime Envelope 版本无效' >&2; exit 41; }
        fi
        IFS= read -r cwd_size || exit 41
        IFS= read -r environment_size || exit 41
        IFS= read -r command_size || exit 41
        IFS= read -r stdin_size || exit 41
        for part_size in "$cwd_size" "$environment_size" "$command_size" "$stdin_size"; do
            valid_decimal "$part_size" || { printf '%s\n' 'Runtime Envelope 长度无效' >&2; exit 41; }
        done
        [ "$cwd_size" -le 4096 ] || exit 41
        [ "$environment_size" -le 1048576 ] || exit 41
        [ "$command_size" -ge 1 ] && [ "$command_size" -le 1048576 ] || exit 41
        [ "$stdin_size" -le 4194304 ] || exit 41
        mkdir -p "$runtime_dir"
        printf '%s\n' "$runtime_name" > "$execution_dir/runtime.id"
        printf '%s\n' "$execution_id" > "$runtime_dir/execution.id"
        if [ "$cwd_size" -gt 0 ]; then
            dd if=/dev/stdin of="$working_directory_file" bs="$cwd_size" count=1 iflag=fullblock 2>/dev/null
            [ "$(wc -c < "$working_directory_file")" -eq "$cwd_size" ] || exit 41
        else
            : > "$working_directory_file"
        fi
        if [ "$environment_size" -gt 0 ]; then
            dd if=/dev/stdin of="$environment_file" bs="$environment_size" count=1 iflag=fullblock 2>/dev/null
            [ "$(wc -c < "$environment_file")" -eq "$environment_size" ] || exit 41
        fi
        dd if=/dev/stdin of="$command_file" bs="$command_size" count=1 iflag=fullblock 2>/dev/null
        [ "$(wc -c < "$command_file")" -eq "$command_size" ] || exit 41
        if [ "$stdin_size" -gt 0 ]; then
            dd if=/dev/stdin of="$stdin_file" bs="$stdin_size" count=1 iflag=fullblock 2>/dev/null
            [ "$(wc -c < "$stdin_file")" -eq "$stdin_size" ] || exit 41
        fi
        chmod 600 "$working_directory_file" "$command_file"
        [ ! -f "$environment_file" ] || chmod 600 "$environment_file"
        [ ! -f "$stdin_file" ] || chmod 600 "$stdin_file"
        stdout_log="$execution_dir/stdout.log"
        stderr_log="$execution_dir/stderr.log"
        : > "$stdout_log"
        : > "$stderr_log"
        chmod 600 "$stdout_log" "$stderr_log"
        if [ "$v2_host" = true ]; then
            # 子进程重新按 runtime ID 解析 Host Runtime，避免把绝对路径误当成 ID。
            nohup setsid "$0" "$runtime_name" "$execution_id" --host-managed-v2 "$timeout_seconds" "$request_hash" > "$stdout_log" 2> "$stderr_log" < /dev/null &
        else
            nohup setsid "$0" "$runtime_dir" "$execution_dir" --managed-v2 "$timeout_seconds" "$request_hash" > "$stdout_log" 2> "$stderr_log" < /dev/null &
        fi
        background_pid="$!"
        attempt=0
        while [ ! -f "$state_file" ] && [ "$attempt" -lt 50 ]; do
            sleep 0.1
            attempt="$((attempt + 1))"
        done
        [ -f "$state_file" ] || { kill -TERM "-$background_pid" 2>/dev/null || true; exit 77; }
        trap - EXIT HUP INT TERM
        cat "$state_file"
        exit 0
    fi
fi

if [ "$input_mode" = --envelope ] || [ "$input_mode" = --host-envelope ]; then
    umask 077
    [ ! -e "$runtime_dir" ] || { printf '%s\n' 'Runtime ID 已存在' >&2; exit 47; }
    mkdir "$runtime_dir"
    cleanup_partial_runtime() {
        rm -f "$environment_file" "$stdin_file" "$working_directory_file" "$command_file"
        rmdir "$runtime_dir" 2>/dev/null || true
    }
    trap cleanup_partial_runtime EXIT HUP INT TERM

    IFS= read -r envelope_magic || { printf '%s\n' 'Runtime Envelope 缺少版本' >&2; exit 41; }
    if [ "$host_mode" = true ]; then
        [ "$envelope_magic" = EVERYTALK_EXEC_HOST_V1 ] || { printf '%s\n' 'Runtime Envelope 版本无效' >&2; exit 41; }
    else
        [ "$envelope_magic" = EVERYTALK_EXEC_V1 ] || { printf '%s\n' 'Runtime Envelope 版本无效' >&2; exit 41; }
    fi
    IFS= read -r cwd_size || exit 41
    IFS= read -r environment_size || exit 41
    IFS= read -r command_size || exit 41
    IFS= read -r stdin_size || exit 41
    for part_size in "$cwd_size" "$environment_size" "$command_size" "$stdin_size"; do
        case "$part_size" in ''|*[!0-9]*) printf '%s\n' 'Runtime Envelope 长度无效' >&2; exit 41 ;; esac
    done
    [ "$cwd_size" -le 4096 ] || exit 41
    [ "$environment_size" -le 1048576 ] || exit 41
    [ "$command_size" -ge 1 ] && [ "$command_size" -le 1048576 ] || exit 41
    [ "$stdin_size" -le 4194304 ] || exit 41

    if [ "$cwd_size" -gt 0 ]; then
        dd if=/dev/stdin of="$working_directory_file" bs="$cwd_size" count=1 iflag=fullblock 2>/dev/null
        [ "$(wc -c < "$working_directory_file")" -eq "$cwd_size" ] || exit 41
    else
        : > "$working_directory_file"
    fi
    if [ "$environment_size" -gt 0 ]; then
        dd if=/dev/stdin of="$environment_file" bs="$environment_size" count=1 iflag=fullblock 2>/dev/null
        [ "$(wc -c < "$environment_file")" -eq "$environment_size" ] || exit 41
    fi
    dd if=/dev/stdin of="$command_file" bs="$command_size" count=1 iflag=fullblock 2>/dev/null
    [ "$(wc -c < "$command_file")" -eq "$command_size" ] || exit 41
    if [ "$stdin_size" -gt 0 ]; then
        dd if=/dev/stdin of="$stdin_file" bs="$stdin_size" count=1 iflag=fullblock 2>/dev/null
        [ "$(wc -c < "$stdin_file")" -eq "$stdin_size" ] || exit 41
    fi
    chmod 600 "$working_directory_file" "$command_file"
    [ ! -f "$environment_file" ] || chmod 600 "$environment_file"
    [ ! -f "$stdin_file" ] || chmod 600 "$stdin_file"
    trap - EXIT HUP INT TERM

    if [ -n "$background_dir" ]; then
        # Host 后台执行在 Android 参数层已禁用；Wrapper 再次拒绝，避免留下无法管理的主机进程。
        [ "$host_mode" = false ] || { cleanup_partial_runtime; exit 46; }
        background_root="$(cd "$workspace/.everytalk/background" && pwd -P)"
        mkdir -p "$background_dir"
        chmod 700 "$background_dir"
        background_dir="$(cd "$background_dir" && pwd -P)"
        case "$background_dir" in "$background_root"/process_*) ;; *) cleanup_partial_runtime; exit 46 ;; esac
        nohup setsid "$0" "$runtime_dir" "$background_dir" \
            > "$background_dir/stdout.log" 2> "$background_dir/stderr.log" < /dev/null &
        background_pid="$!"
        attempt=0
        while [ ! -f "$background_dir/state" ] && [ "$attempt" -lt 30 ]; do
            sleep 0.1
            attempt="$((attempt + 1))"
        done
        [ -f "$background_dir/state" ] || {
            kill -TERM "-$background_pid" 2>/dev/null || true
            cleanup_partial_runtime
            exit 77
        }
        state_pid="$(awk -F= '$1 == "pid" { print $2; exit }' "$background_dir/state")"
        [ "$state_pid" = "$background_pid" ] || {
            kill -TERM "-$background_pid" 2>/dev/null || true
            cleanup_partial_runtime
            exit 77
        }
        printf 'pid=%s\n' "$background_pid"
        exit 0
    fi
else
    [ -f "$command_file" ] || { printf '%s\n' '缺少 command.sh' >&2; exit 41; }
    [ -f "$working_directory_file" ] || { printf '%s\n' '缺少 cwd' >&2; exit 42; }
fi

# 后台模式把 PID、进程起始标记和最终状态保存在 Workspace。
# 删除 Workspace 时会同时核对 PID、起始标记和命令参数，避免误杀复用同一 PID 的其他进程。
if [ -n "$background_dir" ]; then
    background_root="$(cd "$workspace/.everytalk/background" && pwd -P)"
    background_dir="$(cd "$background_dir" && pwd -P)"
    process_id="${background_dir##*/}"
    [ "${#process_id}" -le 128 ] || { printf '%s\n' 'Process ID 过长' >&2; exit 46; }
    case "$process_id" in process_*[!A-Za-z0-9_-]*|process_) printf '%s\n' 'Process ID 无效' >&2; exit 46 ;; esac
    case "$background_dir" in "$background_root"/"$process_id") ;; *) printf '%s\n' '后台状态目录越界' >&2; exit 46 ;; esac
    start_ticks="$(awk '{print $22}' "/proc/$$/stat" 2>/dev/null || true)"
    case "$start_ticks" in ''|*[!0-9]*) printf '%s\n' '无法读取进程起始标记' >&2; exit 46 ;; esac
    execution_id="${runtime_name#run_}"
    state_file="$background_dir/state"

    write_background_state() {
        status_line="$1"
        exit_code="${2:-}"
        temporary_state="$state_file.tmp.$$"
        {
            printf 'process_id=%s\n' "$process_id"
            printf 'execution_id=%s\n' "$execution_id"
            printf 'pid=%s\n' "$$"
            printf 'start_ticks=%s\n' "$start_ticks"
            printf '%s\n' "$status_line"
            [ -z "$exit_code" ] || printf 'exit_code=%s\n' "$exit_code"
            printf 'updated_at=%s\n' "$(date +%s)"
        } > "$temporary_state"
        chmod 600 "$temporary_state"
        mv -f "$temporary_state" "$state_file"
    }

    cleanup_runtime() {
        rm -f "$environment_file" "$stdin_file" "$working_directory_file" "$command_file"
        rmdir "$runtime_dir" 2>/dev/null || true
    }

    handle_background_signal() {
        trap - HUP INT TERM
        set +e
        write_background_state "status=CANCELLED" 143
        cleanup_runtime
        exit 143
    }
fi

relative_cwd="$(cat "$working_directory_file")"
cwd_without_cr="$(printf '%s' "$relative_cwd" | tr -d '\r')"
[ "$cwd_without_cr" = "$relative_cwd" ] || { printf '%s\n' 'cwd 无效' >&2; exit 43; }
if [ "$host_mode" = true ]; then
    case "$relative_cwd" in
        '~') target_cwd="$HOME" ;;
        /*) target_cwd="$(realpath -m "$relative_cwd")" ;;
        *) printf '%s\n' 'Host cwd 无效' >&2; exit 43 ;;
    esac
else
    case "$relative_cwd" in
        *'..'*|/*|*"
"*) printf '%s\n' 'cwd 无效' >&2; exit 43 ;;
    esac
    target_cwd="$(realpath -m "$workspace/$relative_cwd")"
    case "$target_cwd" in
        "$workspace"|"$workspace"/*) ;;
        *) printf '%s\n' 'cwd 越界' >&2; exit 44 ;;
    esac
fi
[ -d "$target_cwd" ] || { printf '%s\n' 'cwd 不存在' >&2; exit 45; }

if [ -f "$environment_file" ]; then
    set -a
    # environment.sh 由 Android 逐项校验变量名并安全引用变量值。
    . "$environment_file"
    set +a
    rm -f "$environment_file"
fi

cd "$target_cwd"
if [ -n "$background_dir" ]; then
    write_background_state "status=RUNNING"
    trap handle_background_signal HUP INT TERM
    set +e
    if [ -f "$stdin_file" ]; then
        /bin/sh "$command_file" < "$stdin_file"
    else
        /bin/sh "$command_file" < /dev/null
    fi
    exit_code="$?"
    set -e
    trap - HUP INT TERM
    if [ "$exit_code" -eq 0 ]; then
        write_background_state "status=SUCCEEDED" "$exit_code"
    else
        write_background_state "status=FAILED" "$exit_code"
    fi
    cleanup_runtime
    exit "$exit_code"
fi
if [ -f "$stdin_file" ]; then
    exec setsid /bin/sh "$command_file" < "$stdin_file"
fi
exec setsid /bin/sh "$command_file" < /dev/null

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
        case "${1:-}" in execution_[A-Za-z0-9_-]*) return 0 ;; *) return 1 ;; esac
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
    process_group_owner_allowed() {
        process_group="$1"
        group_found=false
        for process_stat in /proc/[0-9]*/stat; do
            [ -r "$process_stat" ] || continue
            process_line="$(cat "$process_stat" 2>/dev/null || true)"
            process_rest="${process_line##*) }"
            actual_group="$(printf '%s\n' "$process_rest" | awk '{print $3}')"
            [ "$actual_group" = "$process_group" ] || continue
            group_found=true
            process_status="${process_stat%/stat}/status"
            process_owner="$(awk '/^Uid:/{print $2; exit}' "$process_status" 2>/dev/null || true)"
            case "$process_owner" in ''|*[!0-9]*) return 1 ;; esac
            owner_allowed=false
            for allowed_owner in ${EVERYTALK_ALLOWED_OWNER_UIDS:-$(id -u)}; do
                [ "$process_owner" = "$allowed_owner" ] && owner_allowed=true && break
            done
            [ "$owner_allowed" = true ] || return 1
        done
        [ "$group_found" = true ]
    }
    print_untrusted_state() {
        # 归属校验失败时只返回无法核验的固定身份，不采信状态文件内容。
        printf 'protocol=2\nexecution_id=%s\nprocess_id=process_%s\nrequest_hash=0000000000000000000000000000000000000000000000000000000000000000\ntarget=%s\npid=0\nstart_ticks=0\nstatus=UNKNOWN\nexit_code=\nstarted_at=0\nupdated_at=0\nstdout_bytes=0\nstderr_bytes=0\n' \
            "$execution_id" "$execution_id" "$state_target"
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
        [ -r "/proc/$state_pid/stat" ] || return 1
        actual_ticks="$(awk '{print $22}' "/proc/$state_pid/stat" 2>/dev/null || true)"
        actual_group="$(awk '{print $5}' "/proc/$state_pid/stat" 2>/dev/null || true)"
        [ "$actual_ticks" = "$state_ticks" ] && [ "$actual_group" = "$state_pid" ] && process_owner_allowed
    }

    state_has_expected_identity() {
        [ "$(state_value "$state_file" execution_id)" = "$execution_id" ] || return 1
        [ "$(state_value "$state_file" process_id)" = "$process_id" ] || return 1
        [ "$(state_value "$state_file" target)" = "$state_target" ] || return 1
    }

    write_v2_state() {
        state_status="$1"
        state_exit="${2:-}"
        state_pid="${3:-${state_pid:-}}"
        state_ticks="${4:-${state_ticks:-}}"
        state_started="${5:-${state_started:-}}"
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
        } > "$state_tmp"
        chmod 600 "$state_tmp"
        mv -f "$state_tmp" "$state_file"
    }
    print_v2_state() {
        if [ ! -e "$execution_dir" ]; then
            printf 'protocol=2\nexecution_id=%s\nprocess_id=%s\nrequest_hash=0000000000000000000000000000000000000000000000000000000000000000\ntarget=%s\npid=0\nstart_ticks=0\nstatus=MISSING\nexit_code=\nstarted_at=0\nupdated_at=0\nstdout_bytes=0\nstderr_bytes=0\n' "$execution_id" "$process_id" "$state_target"
            return 0
        fi
        if ! execution_parent_safe || ! execution_directory_safe; then
            print_untrusted_state
            return 0
        fi
        if [ -f "$state_file" ] && [ ! -L "$state_file" ]; then
            if ! state_owner_allowed "$state_file"; then
                print_untrusted_state
                return 0
            fi
            if ! state_has_expected_identity; then
                print_untrusted_state
                return 0
            fi
            current_status="$(state_value "$state_file" status)"
            if [ "$current_status" = RUNNING ] || [ "$current_status" = STARTING ]; then
                if ! state_has_valid_process; then
                    # VPS 重启或进程被外部终止后，不能把旧 RUNNING 永久当成活动任务。
                    request_hash="$(state_value "$state_file" request_hash)"
                    existing_target="$(state_value "$state_file" target)"
                    [ -n "$existing_target" ] && state_target="$existing_target"
                    write_v2_state STOPPED 143 "$(state_value "$state_file" pid)" \
                        "$(state_value "$state_file" start_ticks)" "$(state_value "$state_file" started_at)"
                fi
            fi
            cat "$state_file"
        else
            print_untrusted_state
        fi
    }
    cleanup_v2_runtime() {
        rm -f -- "$environment_file" "$stdin_file" "$working_directory_file" "$command_file"
        rmdir "$runtime_dir" 2>/dev/null || true
    }

    if [ "$input_mode" = --host-execution-status ] || [ "$input_mode" = --host-execution-result ] || \
       [ "$input_mode" = --host-execution-cancel ] || [ "$input_mode" = --host-watch-execution ] || [ "$input_mode" = --host-watch-executions ] || \
       [ "$input_mode" = --execution-status ] || [ "$input_mode" = --execution-result ] || \
       [ "$input_mode" = --execution-cancel ] || [ "$input_mode" = --watch-execution ] || [ "$input_mode" = --watch-executions ]; then
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
          if [ -f "$state_file" ] && [ ! -L "$state_file" ] && \
             execution_parent_safe && execution_directory_safe && state_owner_allowed "$state_file"; then
            current_status="$(state_value "$state_file" status)"
            if [ "$current_status" = RUNNING ] || [ "$current_status" = STARTING ]; then
                state_hash="$(state_value "$state_file" request_hash)"
                if [ -n "$expected_request_hash" ] && [ "$state_hash" != "$expected_request_hash" ]; then
                    printf '%s\n' 'Execution request hash 冲突' >&2
                    exit 49
                fi
                request_hash="$state_hash"
                state_started="$(state_value "$state_file" started_at)"
                if state_has_expected_identity && state_has_valid_process && \
                   process_group_owner_allowed "$(state_value "$state_file" pid)"; then
                    kill -TERM "-$(state_value "$state_file" pid)" 2>/dev/null || true
                    attempt=0
                    while [ "$attempt" -lt 50 ]; do
                        current_status="$(state_value "$state_file" status)"
                        [ "$current_status" = RUNNING ] || [ "$current_status" = STARTING ] || break
                        sleep 0.1
                        attempt="$((attempt + 1))"
                    done
                    current_status="$(state_value "$state_file" status)"
                    if [ "$current_status" = RUNNING ] || [ "$current_status" = STARTING ]; then
                        if state_has_expected_identity && state_has_valid_process && \
                           process_group_owner_allowed "$(state_value "$state_file" pid)"; then
                            kill -KILL "-$(state_value "$state_file" pid)" 2>/dev/null || true
                        fi
                        attempt=0
                        while [ "$attempt" -lt 20 ]; do
                            current_status="$(state_value "$state_file" status)"
                            [ "$current_status" = RUNNING ] || [ "$current_status" = STARTING ] || break
                            sleep 0.1
                            attempt="$((attempt + 1))"
                        done
                        current_status="$(state_value "$state_file" status)"
                        if [ "$current_status" = RUNNING ] || [ "$current_status" = STARTING ]; then
                            # KILL 后仍能确认进程存在时不能伪造 CANCELLED，保留 UNKNOWN 交给 Android 重试。
                            if state_has_valid_process; then
                                write_v2_state UNKNOWN '' "$(state_value "$state_file" pid)" \
                                    "$(state_value "$state_file" start_ticks)" "$state_started"
                            else
                                write_v2_state CANCELLED 137 "$(state_value "$state_file" pid)" \
                                    "$(state_value "$state_file" start_ticks)" "$state_started"
                            fi
                        fi
                    fi
                else
                    request_hash="$(state_value "$state_file" request_hash)"
                    state_started="$(state_value "$state_file" started_at)"
                    write_v2_state UNKNOWN '' "$(state_value "$state_file" pid)" \
                        "$(state_value "$state_file" start_ticks)" "$state_started"
                fi
            fi
          fi
        fi
        if [ "$input_mode" = --host-watch-execution ] || [ "$input_mode" = --watch-execution ] || \
           [ "$input_mode" = --host-watch-executions ] || [ "$input_mode" = --watch-executions ]; then
            curr_stdout_cursor="$stdout_cursor"
            curr_stderr_cursor="$stderr_cursor"
            print_v2_state
            st_val="$(state_value "$state_file" status 2>/dev/null || printf 'UNKNOWN')"
            if [ "$st_val" = RUNNING ] || [ "$st_val" = STARTING ]; then
                event_type="PROGRESS"
            else
                event_type="TERMINAL"
            fi
            printf 'event_type=%s\nevent_seq=1\nstdout_cursor=%s\nstderr_cursor=%s\nobserved_at=%s\n' \
                "$event_type" "$curr_stdout_cursor" "$curr_stderr_cursor" "$(date +%s)"
            if [ -f "$stdout_log" ] && [ ! -L "$stdout_log" ] && state_owner_allowed "$stdout_log"; then
                stdout_chunk="$(tail -c +$((curr_stdout_cursor + 1)) "$stdout_log" 2>/dev/null | head -c "$max_bytes" | base64 2>/dev/null | tr -d '\n' || true)"
            else
                stdout_chunk=""
            fi
            if [ -f "$stderr_log" ] && [ ! -L "$stderr_log" ] && state_owner_allowed "$stderr_log"; then
                stderr_chunk="$(tail -c +$((curr_stderr_cursor + 1)) "$stderr_log" 2>/dev/null | head -c "$max_bytes" | base64 2>/dev/null | tr -d '\n' || true)"
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
        state_ticks="$(awk '{print $22}' "/proc/$$/stat" 2>/dev/null || true)"
        valid_decimal "$state_ticks" || { printf '%s\n' '无法读取进程起始标记' >&2; exit 46; }
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
        mkdir -p "$execution_dir"
        cleanup_outer() {
            rm -f -- "$environment_file" "$stdin_file" "$working_directory_file" "$command_file"
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

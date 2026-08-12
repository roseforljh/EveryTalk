#!/bin/sh
set -eu

# Wrapper 只接受经过 Android 校验的 Runtime 目录。
# 新协议从当前 exec Channel 的 stdin 一次读取 cwd、环境、命令和命令 stdin；
# 旧文件协议继续保留，兼容升级前已经启动或尚未清理的后台任务。
runtime_dir="${1:-}"
background_dir="${2:-}"
input_mode="${3:-}"
host_mode=false
if [ "$input_mode" = --host-envelope ]; then
    host_mode=true
    case "$runtime_dir" in run_*) ;; *) printf '%s\n' 'runtime ID 无效' >&2; exit 40 ;; esac
    runtime_dir="$HOME/.everytalk/host-runtime/$runtime_dir"
    mkdir -p "$HOME/.everytalk/host-runtime"
    chmod 700 "$HOME/.everytalk" "$HOME/.everytalk/host-runtime"
else
    case "$runtime_dir" in
        /workspace/.everytalk/runtime/run_*|*/.everytalk/runtime/run_*) ;;
        *) printf '%s\n' 'runtime 目录无效' >&2; exit 40 ;;
    esac
fi
runtime_name="${runtime_dir##*/}"
case "$runtime_name" in ''|*[!A-Za-z0-9_-]*) printf '%s\n' 'runtime ID 无效' >&2; exit 40 ;; esac

command_file="$runtime_dir/command.sh"
environment_file="$runtime_dir/environment.sh"
stdin_file="$runtime_dir/stdin"
working_directory_file="$runtime_dir/cwd"

if [ "$host_mode" = true ]; then
    workspace="$HOME"
else
    workspace="${runtime_dir%%/.everytalk/runtime/*}"
    workspace="$(cd "$workspace" && pwd -P)"
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

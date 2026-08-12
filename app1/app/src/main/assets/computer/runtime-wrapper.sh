#!/bin/sh
set -eu

# Wrapper 只接受经过 Android 校验的 Runtime 目录，具体命令和环境从 0600 文件读取。
runtime_dir="${1:-}"
background_dir="${2:-}"
case "$runtime_dir" in
    /workspace/.everytalk/runtime/run_*|*/.everytalk/runtime/run_*) ;;
    *) printf '%s\n' 'runtime 目录无效' >&2; exit 40 ;;
esac
runtime_name="${runtime_dir##*/}"
case "$runtime_name" in ''|*[!A-Za-z0-9_-]*) printf '%s\n' 'runtime ID 无效' >&2; exit 40 ;; esac

command_file="$runtime_dir/command.sh"
environment_file="$runtime_dir/environment.sh"
stdin_file="$runtime_dir/stdin"
working_directory_file="$runtime_dir/cwd"

[ -f "$command_file" ] || { printf '%s\n' '缺少 command.sh' >&2; exit 41; }
[ -f "$working_directory_file" ] || { printf '%s\n' '缺少 cwd' >&2; exit 42; }
workspace="${runtime_dir%%/.everytalk/runtime/*}"
workspace="$(cd "$workspace" && pwd -P)"

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
case "$relative_cwd" in
    *'..'*|/*|*"
"*) printf '%s\n' 'cwd 无效' >&2; exit 43 ;;
esac
cwd_without_cr="$(printf '%s' "$relative_cwd" | tr -d '\r')"
[ "$cwd_without_cr" = "$relative_cwd" ] || { printf '%s\n' 'cwd 无效' >&2; exit 43; }
target_cwd="$(realpath -m "$workspace/$relative_cwd")"
case "$target_cwd" in
    "$workspace"|"$workspace"/*) ;;
    *) printf '%s\n' 'cwd 越界' >&2; exit 44 ;;
esac
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

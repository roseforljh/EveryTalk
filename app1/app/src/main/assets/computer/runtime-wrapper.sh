#!/bin/sh
set -eu

# Wrapper 只接受经过 Android 校验的 Runtime 目录，具体命令和环境从 0600 文件读取。
runtime_dir="${1:-}"
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
if [ -f "$stdin_file" ]; then
    exec setsid /bin/sh "$command_file" < "$stdin_file"
fi
exec setsid /bin/sh "$command_file" < /dev/null

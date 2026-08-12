#!/bin/sh
set -eu

# 在临时 Linux Workspace 验证后台 Runtime 的成功、取消、状态落盘和清理行为。
wrapper="${1:-}"
[ -f "$wrapper" ] || { printf '%s\n' '请传入 runtime-wrapper.sh 路径' >&2; exit 2; }

test_root="$(mktemp -d /tmp/everytalk-runtime-test.XXXXXX)"
cleanup() {
    case "$test_root" in
        /tmp/everytalk-runtime-test.*) rm -rf -- "$test_root" ;;
        *) exit 90 ;;
    esac
}
trap cleanup EXIT

workspace="$test_root/workspace"
runtime="$workspace/.everytalk/runtime/run_execution_success"
background="$workspace/.everytalk/background/process_success"
mkdir -p "$runtime" "$background"
printf '%s\n' 'printf success > result.txt' > "$runtime/command.sh"
printf '%s' '' > "$runtime/cwd"
chmod 600 "$runtime/command.sh" "$runtime/cwd"
setsid /bin/sh "$wrapper" "$runtime" "$background" >/dev/null 2>&1 &
success_pid="$!"
wait "$success_pid"
grep -Fx 'status=SUCCEEDED' "$background/state"
grep -Fx "pid=$success_pid" "$background/state"
[ -f "$workspace/result.txt" ]
[ ! -d "$runtime" ]

runtime="$workspace/.everytalk/runtime/run_execution_cancel"
background="$workspace/.everytalk/background/process_cancel"
mkdir -p "$runtime" "$background"
printf '%s\n' 'sleep 30' > "$runtime/command.sh"
printf '%s' '' > "$runtime/cwd"
chmod 600 "$runtime/command.sh" "$runtime/cwd"
setsid /bin/sh "$wrapper" "$runtime" "$background" >/dev/null 2>&1 &
cancel_pid="$!"
attempt=0
while ! grep -Fx 'status=RUNNING' "$background/state" >/dev/null 2>&1 && [ "$attempt" -lt 30 ]; do
    sleep 0.1
    attempt="$((attempt + 1))"
done
grep -Fx 'status=RUNNING' "$background/state"
kill -TERM "-$cancel_pid"
set +e
wait "$cancel_pid"
cancel_exit="$?"
set -e
[ "$cancel_exit" -eq 143 ]
grep -Fx 'status=CANCELLED' "$background/state"
[ ! -d "$runtime" ]

printf '%s\n' 'runtime-wrapper self-test passed'

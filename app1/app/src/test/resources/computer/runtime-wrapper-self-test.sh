#!/bin/sh
set -eu

# 在临时 Linux Workspace 验证单 Channel Envelope、后台成功、取消、状态落盘和清理行为。
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
mkdir -p "$workspace/.everytalk/runtime" "$workspace/.everytalk/background"

runtime="$workspace/.everytalk/runtime/run_execution_envelope"
command='printf "%s|%s" "$MESSAGE" "$(cat)"'
cwd=''
environment="MESSAGE='你好'"
stdin='输入内容'
{
    printf 'EVERYTALK_EXEC_V1\n%s\n%s\n%s\n%s\n' \
        "${#cwd}" "${#environment}" "${#command}" "${#stdin}"
    printf '%s%s%s%s' "$cwd" "$environment" "$command" "$stdin"
} | /bin/sh "$wrapper" "$runtime" '' --envelope > "$workspace/envelope-result.txt"
grep -Fx '你好|输入内容' "$workspace/envelope-result.txt"
[ ! -d "$runtime" ] || {
    rm -f "$runtime/environment.sh" "$runtime/stdin" "$runtime/cwd" "$runtime/command.sh"
    rmdir "$runtime"
}

# Host Envelope 使用独立协议，工作目录允许 SSH 用户 Home 或绝对路径。
host_home="$test_root/home"
mkdir -p "$host_home"
runtime_id='run_execution_host'
command='pwd'
cwd="$test_root"
environment=''
stdin=''
{
    printf 'EVERYTALK_EXEC_HOST_V1\n%s\n%s\n%s\n%s\n' \
        "${#cwd}" "${#environment}" "${#command}" "${#stdin}"
    printf '%s%s%s%s' "$cwd" "$environment" "$command" "$stdin"
} | HOME="$host_home" /bin/sh "$wrapper" "$runtime_id" '' --host-envelope > "$workspace/host-result.txt"
grep -Fx "$test_root" "$workspace/host-result.txt"
rm -f "$host_home/.everytalk/host-runtime/$runtime_id/environment.sh" \
    "$host_home/.everytalk/host-runtime/$runtime_id/stdin" \
    "$host_home/.everytalk/host-runtime/$runtime_id/cwd" \
    "$host_home/.everytalk/host-runtime/$runtime_id/command.sh"
rmdir "$host_home/.everytalk/host-runtime/$runtime_id"

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

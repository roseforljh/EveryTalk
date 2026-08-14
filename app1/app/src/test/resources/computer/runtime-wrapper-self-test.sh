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

byte_length() {
    printf '%s' "$1" | wc -c | tr -d ' '
}

runtime="$workspace/.everytalk/runtime/run_execution_envelope"
command='printf "%s|%s" "$MESSAGE" "$(cat)"'
cwd=''
environment="MESSAGE='你好'"
stdin='输入内容'
{
    printf 'EVERYTALK_EXEC_V1\n%s\n%s\n%s\n%s\n' \
        "$(byte_length "$cwd")" "$(byte_length "$environment")" \
        "$(byte_length "$command")" "$(byte_length "$stdin")"
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
        "$(byte_length "$cwd")" "$(byte_length "$environment")" \
        "$(byte_length "$command")" "$(byte_length "$stdin")"
    printf '%s%s%s%s' "$cwd" "$environment" "$command" "$stdin"
} | HOME="$host_home" /bin/sh "$wrapper" "$runtime_id" '' --host-envelope > "$workspace/host-result.txt"
grep -Fx "$test_root" "$workspace/host-result.txt"
rm -f "$host_home/.everytalk/host-runtime/$runtime_id/environment.sh" \
    "$host_home/.everytalk/host-runtime/$runtime_id/stdin" \
    "$host_home/.everytalk/host-runtime/$runtime_id/cwd" \
    "$host_home/.everytalk/host-runtime/$runtime_id/command.sh"
rmdir "$host_home/.everytalk/host-runtime/$runtime_id"

# Host Runtime V2 使用独立的 Execution 目录，验证 Host 查询不会退回旧的单次 Channel 语义。
host_v2_home="$test_root/host-v2-home"
host_v2_execution="execution_host_v2"
host_v2_runtime="run_host_v2"
mkdir -p "$host_v2_home"
command='printf host-v2-success'
cwd="$host_v2_home"
environment=''
stdin=''
request_hash="$(printf '%s' "$command" | sha256sum | cut -d ' ' -f1)"
{
    printf 'EVERYTALK_EXEC_HOST_V1\n%s\n%s\n%s\n%s\n' \
        "$(byte_length "$cwd")" "$(byte_length "$environment")" \
        "$(byte_length "$command")" "$(byte_length "$stdin")"
    printf '%s%s%s%s' "$cwd" "$environment" "$command" "$stdin"
} | HOME="$host_v2_home" /bin/sh "$wrapper" "$host_v2_runtime" "$host_v2_execution" --host-envelope-v2 30 "$request_hash" > "$test_root/host-v2-start.txt"
host_v2_state="$host_v2_home/.everytalk/host-executions/$host_v2_execution/state"
attempt=0
while ! grep -Fx 'status=SUCCEEDED' "$host_v2_state" >/dev/null 2>&1 && [ "$attempt" -lt 30 ]; do
    sleep 0.1
    attempt="$((attempt + 1))"
done
grep -Fx 'status=SUCCEEDED' "$host_v2_state"
HOME="$host_v2_home" /bin/sh "$wrapper" "$host_v2_execution" '' --host-execution-status 0 "$request_hash" > "$test_root/host-v2-status.txt"
grep -Fx 'target=HOST' "$test_root/host-v2-status.txt"
HOME="$host_v2_home" /bin/sh "$wrapper" "$host_v2_execution" '' --host-execution-result 0 0 2048 "$request_hash" > "$test_root/host-v2-result.txt"
grep -Fx 'stdout_base64=aG9zdC12Mi1zdWNjZXNz' "$test_root/host-v2-result.txt"

# Runtime V2 验证：启动后状态和日志都保存在 VPS，查询使用固定 Execution ID，
# 远端目录不存在时必须返回完整的 MISSING 协议，不能伪装成损坏响应。
v2_home="$test_root/v2-home"
v2_workspace="$v2_home/.everytalk/workspaces/ws_test"
mkdir -p "$v2_workspace/.everytalk/runtime" "$v2_workspace/.everytalk/executions"
runtime="$v2_workspace/.everytalk/runtime/run_execution_v2"
execution="$v2_workspace/.everytalk/executions/execution_v2"
# 输出超过 GNU base64 默认的 76 字符换行阈值，防止结果协议被拆成裸行。
command='head -c 256 /dev/zero | tr "\000" x'
cwd=''
environment=''
stdin=''
request_hash="$(printf '%s' "$command" | sha256sum | cut -d ' ' -f1)"
{
    printf 'EVERYTALK_EXEC_V1\n%s\n%s\n%s\n%s\n' \
        "$(byte_length "$cwd")" "$(byte_length "$environment")" \
        "$(byte_length "$command")" "$(byte_length "$stdin")"
    printf '%s%s%s%s' "$cwd" "$environment" "$command" "$stdin"
} | HOME="$v2_home" /bin/sh "$wrapper" "$runtime" "$execution" --envelope-v2 30 "$request_hash" > "$v2_workspace/v2-start.txt"
grep -E '^status=(STARTING|RUNNING|SUCCEEDED)$' "$v2_workspace/v2-start.txt"
grep -Fx "request_hash=$request_hash" "$v2_workspace/v2-start.txt"
attempt=0
while ! grep -Fx 'status=SUCCEEDED' "$execution/state" >/dev/null 2>&1 && [ "$attempt" -lt 30 ]; do
    sleep 0.1
    attempt="$((attempt + 1))"
done
grep -Fx 'status=SUCCEEDED' "$execution/state"
HOME="$v2_home" /bin/sh "$wrapper" "$execution" '' --execution-status 0 "$request_hash" > "$v2_workspace/v2-status.txt"
grep -Fx 'status=SUCCEEDED' "$v2_workspace/v2-status.txt"
grep -Fx "request_hash=$request_hash" "$v2_workspace/v2-status.txt"
HOME="$v2_home" /bin/sh "$wrapper" "$execution" '' --execution-result 0 0 2048 "$request_hash" > "$v2_workspace/v2-result.txt"
awk 'index($0, "=") == 0 { exit 1 }' "$v2_workspace/v2-result.txt"
stdout_base64="$(awk -F= '$1 == "stdout_base64" { print substr($0, length($1) + 2); exit }' "$v2_workspace/v2-result.txt")"
[ "$(printf '%s' "$stdout_base64" | base64 -d | wc -c | tr -d ' ')" -eq 256 ]
wrong_hash="$(printf '%064d' 0)"
set +e
HOME="$v2_home" /bin/sh "$wrapper" "$execution" '' --execution-result 0 0 2048 "$wrong_hash" > "$v2_workspace/v2-conflict.txt" 2> "$v2_workspace/v2-conflict.err"
conflict_exit="$?"
set -e
[ "$conflict_exit" -eq 49 ]
grep -F 'request hash' "$v2_workspace/v2-conflict.err"
missing="$v2_workspace/.everytalk/executions/execution_missing"
HOME="$v2_home" /bin/sh "$wrapper" "$missing" '' --execution-status 0 "$request_hash" > "$v2_workspace/v2-missing.txt"
grep -Fx 'status=MISSING' "$v2_workspace/v2-missing.txt"
grep -Fx 'pid=0' "$v2_workspace/v2-missing.txt"
grep -Fx 'start_ticks=0' "$v2_workspace/v2-missing.txt"
grep -Fx 'updated_at=0' "$v2_workspace/v2-missing.txt"

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

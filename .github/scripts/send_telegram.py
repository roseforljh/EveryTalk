import os
import glob
import requests
import sys

def send_telegram_notification():
    # 从环境变量获取配置
    token = os.environ.get('TG_TOKEN')
    chat_id = os.environ.get('TG_CHAT_ID')
    repo = os.environ.get('REPO')
    branch = os.environ.get('BRANCH')
    run_id = os.environ.get('RUN_ID')
    commit_msg = os.environ.get('COMMIT_MSG')
    apk_dir = os.environ.get('APK_DIR')

    if not all([token, chat_id, repo, apk_dir]):
        print("Error: Missing required environment variables.")
        sys.exit(1)

    workspace = os.environ.get('GITHUB_WORKSPACE', '')
    if os.path.isabs(apk_dir):
        search_dir = apk_dir
    else:
        search_dir = os.path.join(workspace, apk_dir) if workspace else apk_dir

    apk_files = glob.glob(os.path.join(search_dir, '*.apk'))
    if not apk_files:
        print(f"Error: No APK files found in {search_dir}")
        sys.exit(1)
    
    # 假设只发送找到的第一个 APK，或者可以循环发送
    apk_file = apk_files[0]
    print(f"Found APK: {apk_file}")

    # 构造 Commit URL
    # 注意：在 pull_request 事件中 github.sha 是 merge commit，而在 push/tag 中是实际 commit
    # 这里我们简化处理，假设是在 release tag 触发
    commit_sha = os.environ.get('SHA')
    commit_url = f"https://github.com/{repo}/commit/{commit_sha}"
    run_url = f"https://github.com/{repo}/actions/runs/{run_id}"

    # 构造 Caption
    # 对 commit message 进行适当截断，防止过长
    if len(commit_msg) > 800:
        commit_msg = commit_msg[:800] + "..."

    # 处理 HTML 特殊字符转义 (简单处理)
    commit_msg = commit_msg.replace("<", "<").replace(">", ">")

    caption = f"""<b>EveryTalk</b>
Branch: {branch}
#ci_{run_id}

<blockquote>{commit_msg}</blockquote>

<a href="{commit_url}">Commit</a>
<a href="{run_url}">Workflow run</a>"""

    print("Sending document...")
    
    with open(apk_file, 'rb') as f:
        files = {'document': f}
        data = {
            'chat_id': chat_id,
            'parse_mode': 'HTML',
            'caption': caption
        }
        
        try:
            response = requests.post(
                f'https://api.telegram.org/bot{token}/sendDocument',
                data=data,
                files=files,
                timeout=120 # 上传文件可能需要较长时间
            )
            response.raise_for_status()
            print("Successfully sent Telegram notification.")
            print(f"Response: {response.text}")
        except Exception as e:
            print(f"Failed to send notification: {e}")
            if hasattr(e, 'response') and e.response:
                print(f"Server response: {e.response.text}")
            sys.exit(1)

if __name__ == "__main__":
    send_telegram_notification()

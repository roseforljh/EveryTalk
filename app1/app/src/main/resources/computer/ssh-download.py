# Shared with Android's computer/ssh-download.py. Runs only on the selected host.
import http.client
import http.server
import json
import os
import re
import secrets
import selectors
import shutil
import signal
import stat
import subprocess
import threading
import sys
import time
from urllib.parse import quote


def publish(path, name, ttl):
    cloudflared = shutil.which("cloudflared")
    if not cloudflared:
        raise ValueError("所选服务器未安装 cloudflared，未创建下载链接")
    path = os.path.expanduser(path) if path.startswith("~/") else path
    if not stat.S_ISREG(os.lstat(path).st_mode):
        raise ValueError("目标不是普通文件，不能发布符号链接或目录")
    fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
    if not stat.S_ISREG(os.fstat(fd).st_mode):
        raise ValueError("目标不是普通文件")
    name = name or os.path.basename(path)
    if not name or len(name) > 200 or any(c in '/\\' or ord(c) < 32 for c in name):
        raise ValueError("下载文件名无效")
    route = "/" + secrets.token_hex(24) + "/" + quote(name, safe="")
    size = os.fstat(fd).st_size

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # The URL grants access; do not put it in the access log.

        def setup(self):
            self.request.settimeout(5)
            super().setup()

        def serve_file(self, send_body):
            if self.path != route:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + quote(name, safe=""))
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "private, no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            if send_body:
                offset = 0
                while offset < size:
                    chunk = os.pread(fd, min(65536, size - offset), offset)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    offset += len(chunk)

        def do_HEAD(self):
            self.serve_file(False)

        def do_GET(self):
            self.serve_file(True)

    with http.server.HTTPServer(("127.0.0.1", 0), Handler) as server:
        port = server.server_port
        # Pinned origin over loopback; no new public listening port or proxy in EveryTalk.
        tunnel = subprocess.Popen([cloudflared, "--config", "/dev/null", "tunnel", "--protocol", "http2", "--url", f"http://127.0.0.1:{port}"],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        with tunnel:
            try:
                with selectors.DefaultSelector() as selector:
                    selector.register(tunnel.stdout, selectors.EVENT_READ)
                    output = b""
                    origin = None
                    until = time.monotonic() + 25
                    while not origin and selector.select(max(0, until - time.monotonic())):
                        chunk = os.read(tunnel.stdout.fileno(), 4096)
                        if not chunk or time.monotonic() >= until:
                            break
                        output = (output + chunk)[-8192:]
                        match = re.search(rb"https://[a-z0-9-]+\.trycloudflare\.com\b", output)
                        if match:
                            origin = match.group().decode("ascii")
                    if not origin:
                        raise ValueError("所选服务器的 HTTPS 隧道未启动，未创建下载链接")
                    # Keep draining logs without writing files. Confirm the tunnel can reach this exact handler.
                    threading.Thread(target=drain, args=(tunnel.stdout,), daemon=True).start()
                    threading.Thread(target=server.serve_forever, daemon=True).start()
                    for _ in range(8):
                        try:
                            conn = http.client.HTTPSConnection(origin[len("https://"):], timeout=2)
                            try:
                                conn.request("HEAD", route)
                                response = conn.getresponse()
                                if (response.status, response.getheader("Content-Length")) == (200, str(size)):
                                    break
                            finally:
                                conn.close()
                        except (OSError, http.client.HTTPException):
                            pass
                        time.sleep(1)
                    else:
                        raise ValueError("所选服务器的 HTTPS 链接未通过连通性检查")
                    emit({"file_link": origin + route, "name": name, "size": size, "expires_at": int(time.time()) + ttl})
                    try:
                        tunnel.wait(timeout=ttl)
                    except subprocess.TimeoutExpired:
                        pass
            finally:
                if tunnel.poll() is None:
                    tunnel.kill()


def drain(stream):
    try:
        while os.read(stream.fileno(), 4096):
            pass
    except OSError:
        pass


def emit(result):
    global ready
    if ready is not None:
        output, ready = ready, None
        with os.fdopen(output, "w") as pipe:
            print(json.dumps(result), file=pipe, flush=True)


# Return the metadata to SSH, without tying the download lifetime to the SSH session.
if __name__ == "__main__":
    read, ready = os.pipe()
    child = os.fork()
    if child:
        os.close(ready)
        with os.fdopen(read) as source:
            sys.stdout.write(source.read())
        sys.exit(0)
    os.close(read)
    os.setsid()
    with open(os.devnull, "r+b") as null:
        for stream in (0, 1, 2):
            os.dup2(null.fileno(), stream)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    try:
        publish(sys.argv[1], sys.argv[2], int(sys.argv[3]))
    except ValueError as error:
        emit({"error": str(error)})
    except OSError:
        emit({"error": "所选服务器无法读取文件或启动下载服务"})
    finally:
        os._exit(0)

FROM ubuntu:24.04

# 提供常见代码运行环境，用户仍可在自己的 Workspace 中安装其他工具。
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        bash build-essential ca-certificates curl file git jq less openssh-client \
        python3 python3-pip python3-venv nodejs npm procps socat tini \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["sleep", "infinity"]

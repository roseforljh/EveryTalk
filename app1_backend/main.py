# --- main.py: EzTalk Proxy v1.8.11 优化流式体验版 ---

import os
import json
from fastapi import FastAPI, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, JSONResponse
from pydantic import BaseModel, Field
from typing import List, Dict, Any, Literal, Optional, AsyncGenerator
import httpx
import logging
from contextlib import asynccontextmanager
import time
import asyncio

# ==== 日志配置 ====
logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO"),
    format='%(asctime)s - %(levelname)s - [%(name)s:%(lineno)d] - %(message)s'
)
logger = logging.getLogger("main")
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("uvicorn").setLevel(logging.INFO)

# ==== 常量定义 ====
API_TIMEOUT = int(os.getenv("API_TIMEOUT", 300))
MAX_CONNECTIONS = int(os.getenv("MAX_CONNECTIONS", 200))
DEFAULT_OPENAI_API_BASE_URL = "https://api.openai.com"
GOOGLE_API_BASE_URL = "https://generativelanguage.googleapis.com"
OPENAI_COMPATIBLE_PATH = "/v1/chat/completions"

COMMON_HEADERS = {"X-Accel-Buffering": "no"}
COMPATIBLE_HEADERS = {"Content-Type": "application/json", "Accept": "application/json"}
GOOGLE_HEADERS = {"Content-Type": "application/json", "Accept": "text/event-stream"}

# ==== 全局 HTTP 客户端 ====
http_client: Optional[httpx.AsyncClient] = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    "FastAPI 生命周期：初始化/关闭 httpx AsyncClient"
    global http_client
    http_client = httpx.AsyncClient(
        timeout=httpx.Timeout(API_TIMEOUT, read=60.0),
        limits=httpx.Limits(max_connections=MAX_CONNECTIONS),
        http2=True,
        follow_redirects=True,
        trust_env=False
    )
    logger.info(f"HTTPX AsyncClient created (Timeout={API_TIMEOUT}s(Read=60s), MaxConns={MAX_CONNECTIONS}, HTTP/2:True)")
    yield
    await http_client.aclose()
    logger.info("HTTP Client closed")

app = FastAPI(
    title="EzTalk Proxy",
    description="Proxy for OpenAI, Google, and compatible endpoints.",
    version="1.8.11",
    lifespan=lifespan,
    docs_url="/docs", redoc_url="/redoc"
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], allow_credentials=True,
    allow_methods=["*"], allow_headers=["*"], expose_headers=["*"]
)

# ==== Pydantic 模型 ====
class ApiMessage(BaseModel):
    role: str
    content: Optional[str] = None
    name: Optional[str] = None

class ChatRequest(BaseModel):
    api_address: Optional[str] = None
    messages: List[ApiMessage]
    provider: Literal["openai", "google"]
    model: str
    api_key: str
    temperature: Optional[float] = Field(None, ge=0.0, le=2.0)
    top_p: Optional[float] = Field(None, ge=0.0, le=1.0)
    max_tokens: Optional[int] = Field(None, gt=0)
    tools: Optional[List[Dict[str, Any]]] = None
    tool_config: Optional[Dict[str, Any]] = None

def json_dumps_wrapper(data):
    return json.dumps(data, ensure_ascii=False, separators=(',', ':'))

def error_response(code: int, msg: str, headers: Optional[Dict[str, str]] = None):
    final_headers = COMMON_HEADERS.copy()
    if headers: final_headers.update(headers)
    logger.warning(f"Responding with error - Code: {code}, Message: {msg}")
    return JSONResponse(
        status_code=code,
        content={"error": {"message": msg, "code": code}},
        headers=final_headers
    )

# ==== 流式解析辅助 ====
async def process_sse_line(line_bytes: bytes) -> AsyncGenerator[str, None]:
    event_start = b"data: "
    done_marker = b"[DONE]"
    if not line_bytes or not line_bytes.startswith(event_start): return
    raw_data_bytes = line_bytes[len(event_start):].strip()
    if not raw_data_bytes or raw_data_bytes == done_marker: return

    try:
        data = json.loads(raw_data_bytes.decode("utf-8"))
        for choice in data.get('choices', []):
            delta = choice.get('delta', {})
            if "reasoning_content" in delta:
                yield json_dumps_wrapper({"type": "reasoning", "text": delta["reasoning_content"]}) + "\n"
            if "content" in delta:
                yield json_dumps_wrapper({"type": "content", "text": delta["content"]}) + "\n"
            fr = delta.get("finish_reason") or choice.get("finish_reason")
            if fr: yield json_dumps_wrapper({"type": "finish", "reason": fr}) + "\n"
    except Exception as e:
        logger.warning(f"process_sse_line parse err: {e}")

async def process_google_sse_message(message_lines: List[bytes]) -> AsyncGenerator[str, None]:
    event_start = b"data: "
    for m in message_lines:
        if not m.startswith(event_start): continue
        try:
            data = json.loads(m[len(event_start):].strip())
            for c in data.get('candidates', []):
                content = c.get('content', {})
                parts = content.get('parts', [])
                part_text = "".join(p.get('text', '') for p in parts if 'text' in p)
                if part_text:
                    yield json_dumps_wrapper({"type": "content", "text": part_text}) + "\n"
                finish = c.get('finishReason')
                if finish and finish != "FINISH_REASON_UNSPECIFIED":
                    yield json_dumps_wrapper({"type": "finish", "reason": finish}) + "\n"
        except Exception as e:
            logger.warning(f"google_sse_message parse err: {e}")

# ==== 主 API 端点 ====
@app.post("/chat", response_class=StreamingResponse, summary="Proxy Chat Completions")
async def chat_proxy(request: ChatRequest, http_client_dependency: httpx.AsyncClient = Depends(lambda: http_client)):
    if http_client_dependency is None:
        return error_response(503, "Service unavailable: HTTP client not initialized properly.")

    # === 1. 构建参数 ===
    try:
        if request.provider == "openai":
            target_base_url = request.api_address.strip() if request.api_address else DEFAULT_OPENAI_API_BASE_URL
            url = f"{target_base_url.rstrip('/')}{OPENAI_COMPATIBLE_PATH}"
            headers = COMPATIBLE_HEADERS.copy()
            headers["Authorization"] = f"Bearer {request.api_key}"
            payload = {
                "model": request.model,
                "messages": [m.model_dump(exclude_unset=True) for m in request.messages],
                "stream": True
            }
            if request.temperature is not None: payload["temperature"] = request.temperature
            if request.top_p is not None: payload["top_p"] = request.top_p
            if request.max_tokens is not None: payload["max_tokens"] = request.max_tokens
            if request.tools is not None: payload["tools"] = request.tools
            if request.tool_config is not None: payload["tool_config"] = request.tool_config
            is_openai = True; is_google = False
            params = None
        elif request.provider == "google":
            url = f"{GOOGLE_API_BASE_URL}/v1beta/models/{request.model}:streamGenerateContent"
            headers = GOOGLE_HEADERS.copy()
            params = {"key": request.api_key, "alt": "sse"}
            contents = []
            google_role_map = {"assistant": "model", "user": "user", "tool": "function"}
            for msg in request.messages:
                google_role = google_role_map.get(msg.role)
                if google_role and msg.content:
                    contents.append({"role": google_role, "parts": [{"text": msg.content}]})
            payload = {"contents": contents}
            generation_config = {}
            if request.temperature is not None: generation_config["temperature"] = request.temperature
            if request.top_p is not None: generation_config["topP"] = request.top_p
            if request.max_tokens is not None: generation_config["maxOutputTokens"] = request.max_tokens
            if generation_config: payload["generationConfig"] = generation_config
            if request.tools: payload["tools"] = request.tools
            is_openai = False; is_google = True
        else:
            return error_response(400, f"Invalid provider: {request.provider}")
    except Exception as e:
        logger.error(f"request-prepare-err: {e}")
        return error_response(500, f"Request preparation error: {str(e)}")

    logger.info(f"Proxying to: {url}")

    # === 2. 流式响应生成器 ===
    async def stream_generator():
        buffer = bytearray()
        google_message_lines_buffer = []
        try:
            async with http_client_dependency.stream("POST", url, headers=headers, json=payload, params=params) as resp:
                if not (200 <= resp.status_code < 300):
                    error_text = (await resp.aread()).decode("utf-8", errors="replace")
                    yield json_dumps_wrapper({"type": "error", "message": f"Upstream error {resp.status_code}: {error_text[:100]}"}) + "\n"
                    return

                # 启用流式每片 flush
                async for chunk in resp.aiter_raw():
                    if not chunk: continue
                    buffer.extend(chunk)
                    while True:
                        # 逐行析出
                        line_end = buffer.find(b'\n')
                        if line_end == -1: break
                        line_bytes = buffer[:line_end]
                        buffer = buffer[line_end+1:]
                        # yield + flush
                        if is_openai:
                            async for formatted in process_sse_line(line_bytes):
                                yield formatted
                                await asyncio.sleep(0)  # <--- 主动让步，协程更流畅
                        elif is_google:
                            if not line_bytes:
                                if google_message_lines_buffer:
                                    async for r in process_google_sse_message(google_message_lines_buffer):
                                        yield r
                                        await asyncio.sleep(0)
                                    google_message_lines_buffer.clear()
                            else:
                                google_message_lines_buffer.append(line_bytes)
                        # 其余情况 pass
                    # 强制flush(StreamingResponse会自动flush，但yield后sleep(0)可加快上下游推送/拉取切换)
                    await asyncio.sleep(0)
                # 流结束后处理 google 残留
                if is_google and google_message_lines_buffer:
                    async for r in process_google_sse_message(google_message_lines_buffer):
                        yield r
                        await asyncio.sleep(0)
                    google_message_lines_buffer.clear()
        except Exception as e:
            logger.error(f"stream_generator error: {type(e)} {e}")
            yield json_dumps_wrapper({"type": "error", "message": f"Backend streaming error: {str(e)}"}) + "\n"

    # === 3. 返回流式响应 ===
    streaming_headers = COMMON_HEADERS.copy()
    streaming_headers["Content-Type"] = "text/event-stream"
    streaming_headers["Cache-Control"] = "no-cache"
    streaming_headers["Connection"] = "keep-alive"

    return StreamingResponse(
        stream_generator(),
        media_type="text/event-stream",
        headers=streaming_headers
    )

# ==== 本地调试入口 ====
if __name__ == "__main__":
    import uvicorn
    HOST = os.getenv("HOST", "0.0.0.0"); PORT = int(os.getenv("PORT", 8000))
    uvicorn.run("main:app", host=HOST, port=PORT, log_level="info", reload=os.getenv("DEV_RELOAD", "false").lower() == "true")

# --- END OF FILE main.py ---

# --- main.py: EzTalk Proxy v1.8.11 (orjson 优化版) ---

import os
# --- **修改点 1: 导入 orjson** ---
# import json # 不再使用标准库 json
import orjson # <--- 使用 orjson
# --- End 修改点 1 ---
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

# ==== 日志配置 (保持不变) ====
logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO"),
    format='%(asctime)s - %(levelname)s - [%(name)s:%(lineno)d] - %(message)s'
)
logger = logging.getLogger("main")
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("uvicorn").setLevel(logging.INFO)

# ==== 常量定义 (保持不变) ====
API_TIMEOUT = int(os.getenv("API_TIMEOUT", 300))
READ_TIMEOUT = float(os.getenv("READ_TIMEOUT", 60.0))
MAX_CONNECTIONS = int(os.getenv("MAX_CONNECTIONS", 200)) # 可根据服务器能力调整
DEFAULT_OPENAI_API_BASE_URL = "https://api.openai.com"
GOOGLE_API_BASE_URL = "https://generativelanguage.googleapis.com"
OPENAI_COMPATIBLE_PATH = "/v1/chat/completions"

COMMON_HEADERS = {"X-Accel-Buffering": "no"}
COMPATIBLE_HEADERS = {"Content-Type": "application/json", "Accept": "application/json"}
GOOGLE_HEADERS = {"Content-Type": "application/json", "Accept": "text/event-stream"}

# ==== 全局 HTTP 客户端 (保持不变) ====
http_client: Optional[httpx.AsyncClient] = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    global http_client
    http_client = httpx.AsyncClient(
        timeout=httpx.Timeout(API_TIMEOUT, read=READ_TIMEOUT),
        limits=httpx.Limits(max_connections=MAX_CONNECTIONS),
        http2=True, follow_redirects=True, trust_env=False
    )
    logger.info(f"HTTPX AsyncClient created (Timeout={API_TIMEOUT}s(Read={READ_TIMEOUT}s), MaxConns={MAX_CONNECTIONS}, HTTP/2:True)")
    yield
    await http_client.aclose()
    logger.info("HTTP Client closed")

# ==== FastAPI 应用实例 (保持不变) ====
app = FastAPI(
    title="EzTalk Proxy", description="Proxy for OpenAI, Google, and compatible endpoints.",
    version="1.8.11-orjson", lifespan=lifespan, docs_url="/docs", redoc_url="/redoc"
)
app.add_middleware(
    CORSMiddleware, allow_origins=["*"], allow_credentials=True,
    allow_methods=["*"], allow_headers=["*"], expose_headers=["*"]
)

# ==== Pydantic 数据模型 (保持不变) ====
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

# ==== 辅助函数 (修改 JSON 处理) ====

# --- **修改点 2: 使用 orjson.dumps** ---
def orjson_dumps_bytes_wrapper(data):
    "使用 orjson 序列化为 bytes"
    # orjson.OPT_APPEND_NEWLINE 可能有助于某些客户端处理，但 SSE 通常自带换行
    return orjson.dumps(data, option=orjson.OPT_NON_STR_KEYS | orjson.OPT_PASSTHROUGH_DATETIME) # 使用一些常用选项
# --- End 修改点 2 ---

def error_response(code: int, msg: str, headers: Optional[Dict[str, str]] = None):
    final_headers = COMMON_HEADERS.copy()
    if headers: final_headers.update(headers)
    logger.warning(f"Responding with error - Code: {code}, Message: {msg}")
    # 错误响应仍然可以使用标准 JSON 或 orjson
    error_content = {"error": {"message": msg, "code": code}}
    return JSONResponse(
        status_code=code,
        content=error_content, # FastAPI 会自动处理 JSON 序列化
        # content=orjson.dumps(error_content).decode('utf-8'), # 如果想强制用 orjson
        headers=final_headers
    )

# ==== 流式解析辅助 (修改 JSON 处理) ====

async def process_sse_line(line_bytes: bytes) -> AsyncGenerator[bytes, None]: # 返回 bytes
    event_start = b"data: "
    done_marker = b"[DONE]"
    if not line_bytes or not line_bytes.startswith(event_start): return
    raw_data_bytes = line_bytes[len(event_start):].strip()
    if not raw_data_bytes or raw_data_bytes == done_marker: return

    try:
        # --- **修改点 3: 使用 orjson.loads** ---
        data = orjson.loads(raw_data_bytes) # 使用 orjson 解析
        # --- End 修改点 3 ---
        for choice in data.get('choices', []):
            delta = choice.get('delta', {})
            # --- **修改点 4: 使用 orjson_dumps_bytes_wrapper** ---
            if "reasoning_content" in delta and delta["reasoning_content"]:
                yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": delta["reasoning_content"]}) + b"\n"
                await asyncio.sleep(0)
            if "content" in delta and delta["content"]:
                yield orjson_dumps_bytes_wrapper({"type": "content", "text": delta["content"]}) + b"\n"
                await asyncio.sleep(0)
            fr = delta.get("finish_reason") or choice.get("finish_reason")
            if fr:
                 yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": fr}) + b"\n"
                 await asyncio.sleep(0)
            # --- End 修改点 4 ---
    except orjson.JSONDecodeError as e: # 捕获 orjson 的错误
        logger.warning(f"process_sse_line orjson parse err: {e} on line: {line_bytes[:100]}")
    except Exception as e:
        logger.warning(f"process_sse_line process err: {e} on line: {line_bytes[:100]}")

async def process_google_sse_line(line_bytes: bytes) -> AsyncGenerator[bytes, None]: # 返回 bytes
    event_start = b"data: "
    if not line_bytes.startswith(event_start): return

    try:
        data_bytes = line_bytes[len(event_start):].strip()
        if not data_bytes: return

        # --- **修改点 5: 使用 orjson.loads** ---
        data = orjson.loads(data_bytes) # 使用 orjson 解析
        # --- End 修改点 5 ---

        for c in data.get('candidates', []):
            content = c.get('content', {})
            parts = content.get('parts', [])
            part_text = "".join(p.get('text', '') for p in parts if 'text' in p)

            # --- **修改点 6: 使用 orjson_dumps_bytes_wrapper** ---
            if part_text:
                yield orjson_dumps_bytes_wrapper({"type": "content", "text": part_text}) + b"\n"
                await asyncio.sleep(0)

            finish = c.get('finishReason')
            if finish and finish != "FINISH_REASON_UNSPECIFIED" and finish != "STOP":
                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish}) + b"\n"
                await asyncio.sleep(0)
            # --- End 修改点 6 ---

    except orjson.JSONDecodeError as e: # 捕获 orjson 的错误
        logger.warning(f"google_sse_line orjson parse err: {e} on line: {line_bytes[:100]}")
    except Exception as e:
        logger.warning(f"google_sse_line process err: {e} on line: {line_bytes[:100]}")


# ==== 主 API 端点 (修改 JSON 处理) ====
@app.post("/chat", response_class=StreamingResponse, summary="Proxy Chat Completions")
async def chat_proxy(request: ChatRequest, http_client_dependency: httpx.AsyncClient = Depends(lambda: http_client)):
    if http_client_dependency is None:
        return error_response(503, "Service unavailable: HTTP client not initialized properly.")

    # === 1. 构建上游请求参数 (代码结构保持不变, payload 会自动序列化) ===
    try:
        if request.provider == "openai":
            target_base_url = request.api_address.strip() if request.api_address else DEFAULT_OPENAI_API_BASE_URL
            url = f"{target_base_url.rstrip('/')}{OPENAI_COMPATIBLE_PATH}"
            headers = COMPATIBLE_HEADERS.copy()
            headers["Authorization"] = f"Bearer {request.api_key}"
            payload = { "model": request.model, "messages": [m.model_dump(exclude_unset=True, exclude_none=True) for m in request.messages], "stream": True }
            if request.temperature is not None: payload["temperature"] = request.temperature
            if request.top_p is not None: payload["top_p"] = request.top_p
            if request.max_tokens is not None: payload["max_tokens"] = request.max_tokens
            if request.tools is not None: payload["tools"] = request.tools
            if request.tool_config is not None: payload["tool_config"] = request.tool_config
            is_openai = True; is_google = False; params = None
            logger.info(f"OpenAI Payload: {orjson.dumps(payload).decode('utf-8')}") # 使用 orjson 打印日志

        elif request.provider == "google":
            url = f"{GOOGLE_API_BASE_URL}/v1beta/models/{request.model}:streamGenerateContent"
            headers = GOOGLE_HEADERS.copy()
            params = {"key": request.api_key, "alt": "sse"}
            contents = []
            google_role_map = {"assistant": "model", "user": "user", "tool": "function"}
            for msg in request.messages:
                google_role = google_role_map.get(msg.role)
                if google_role and msg.content: contents.append({"role": google_role, "parts": [{"text": msg.content}]})
            payload = {"contents": contents}
            generation_config = {}
            if request.temperature is not None: generation_config["temperature"] = request.temperature
            if request.top_p is not None: generation_config["topP"] = request.top_p
            if request.max_tokens is not None: generation_config["maxOutputTokens"] = request.max_tokens
            if generation_config: payload["generationConfig"] = generation_config
            if request.tools: payload["tools"] = request.tools # 仍需注意格式兼容性
            is_openai = False; is_google = True
            logger.info(f"Google Payload: {orjson.dumps(payload).decode('utf-8')}") # 使用 orjson 打印日志

        else:
            return error_response(400, f"Invalid provider specified: {request.provider}")

    except Exception as e:
        logger.error(f"Error preparing request payload: {e}", exc_info=True)
        return error_response(500, f"Internal error during request preparation: {str(e)}")

    logger.info(f"Proxying request for provider '{request.provider}' model '{request.model}' to URL: {url}")

    # === 2. 流式响应生成器 (代码结构保持不变，内部调用已修改) ===
    async def stream_generator():
        buffer = bytearray()
        try:
            async with http_client_dependency.stream("POST", url, headers=headers, json=payload, params=params) as resp:
                if not (200 <= resp.status_code < 300):
                    error_body = b""
                    try:
                        error_body = await resp.aread()
                        error_text = error_body.decode("utf-8", errors="replace")
                        logger.error(f"Upstream API request failed! Status: {resp.status_code}, URL: {url}, Response: {error_text[:500]}")
                        try:
                            # --- **修改点 7: 使用 orjson.loads 解析上游错误** ---
                            upstream_error = orjson.loads(error_text) # 使用 orjson
                            err_msg = upstream_error.get("error", {}).get("message") or upstream_error.get("message", f"Upstream returned status {resp.status_code}")
                            # --- **修改点 8: 使用 orjson_dumps_bytes_wrapper 格式化错误** ---
                            yield orjson_dumps_bytes_wrapper({"type": "error", "message": err_msg}) + b"\n"
                        except orjson.JSONDecodeError: # 捕获 orjson 错误
                            yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Upstream error {resp.status_code}: {error_text[:100]}"}) + b"\n"
                        # --- End 修改点 7 & 8 ---
                    except Exception as read_err:
                        logger.error(f"Failed to read error body from upstream status {resp.status_code}: {read_err}")
                        yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Upstream error {resp.status_code} (failed to read body)"}) + b"\n"
                    return # 出错后停止

                async for chunk in resp.aiter_raw():
                    if not chunk: continue
                    buffer.extend(chunk)
                    while True:
                        line_end = buffer.find(b'\n')
                        if line_end == -1: break
                        line_bytes = buffer[:line_end]
                        buffer = buffer[line_end+1:]
                        if not line_bytes: continue

                        if is_openai:
                            async for formatted_line in process_sse_line(line_bytes):
                                yield formatted_line # 返回 bytes
                        elif is_google:
                            async for formatted_line in process_google_sse_line(line_bytes):
                                yield formatted_line # 返回 bytes
                        await asyncio.sleep(0)
                    await asyncio.sleep(0)

        except httpx.RequestError as e:
             logger.error(f"HTTPX RequestError connecting to {url}: {e}")
             yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Network error contacting upstream API: {str(e)}"}) + b"\n"
        except asyncio.CancelledError:
             logger.info(f"Streaming request to {url} cancelled by client.")
        except Exception as e:
            logger.error(f"Unexpected error in stream_generator for {url}: {type(e)} {e}", exc_info=True)
            yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Internal backend streaming error: {str(e)}"}) + b"\n"
        finally:
             logger.info(f"Stream generator finished for request to {url}.")

    # === 3. 返回 FastAPI 的 StreamingResponse (保持不变) ===
    streaming_headers = COMMON_HEADERS.copy()
    streaming_headers["Content-Type"] = "text/event-stream"
    streaming_headers["Cache-Control"] = "no-cache"
    streaming_headers["Connection"] = "keep-alive"

    return StreamingResponse(
        stream_generator(), # 返回 bytes 的生成器
        media_type="text/event-stream",
        headers=streaming_headers
    )

# ==== 本地调试入口 (保持不变) ====
if __name__ == "__main__":
    import uvicorn
    HOST = os.getenv("HOST", "0.0.0.0")
    PORT = int(os.getenv("PORT", 8000))
    RELOAD = os.getenv("DEV_RELOAD", "false").lower() == "true"
    uvicorn.run( "main:app", host=HOST, port=PORT, log_level="info", reload=RELOAD )

# --- END OF FILE main.py ---
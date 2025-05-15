# --- main.py: EzTalk Proxy v1.8.14 (SSE极致优化、小bug修复) ---

import os
import orjson
from fastapi import FastAPI, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, JSONResponse
from pydantic import BaseModel, Field
from typing import List, Dict, Any, Literal, Optional, AsyncGenerator
import httpx
import logging
from contextlib import asynccontextmanager
import asyncio

# ==== 日志配置 ====
LOG_LEVEL_FROM_ENV = os.getenv("LOG_LEVEL", "INFO").upper()
numeric_level = getattr(logging, LOG_LEVEL_FROM_ENV, logging.INFO)
logging.basicConfig(
    level=numeric_level,
    format='%(asctime)s %(levelname)-8s [%(name)s:%(module)s:%(lineno)d] - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger("EzTalkProxy")
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("uvicorn.access").setLevel(logging.WARNING)
logging.getLogger("uvicorn.error").setLevel(logging.INFO)

# ==== 全局 HTTP 客户端 ====
http_client: Optional[httpx.AsyncClient] = None

# ==== Lifespan 管理器 ====
@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    global http_client
    try:
        api_timeout = int(os.getenv("API_TIMEOUT", "300"))
        read_timeout = float(os.getenv("READ_TIMEOUT", "60.0"))
        max_connections = int(os.getenv("MAX_CONNECTIONS", "200"))
        http_client = httpx.AsyncClient(
            timeout=httpx.Timeout(api_timeout, read=read_timeout),
            limits=httpx.Limits(max_connections=max_connections),
            http2=True,
            follow_redirects=True,
            trust_env=False
        )
        logger.info(f"Lifespan: HTTP client init OK")
    except Exception as e:
        logger.error(f"Lifespan: HTTP client init fail: {e}", exc_info=True)
        http_client = None
    yield
    if http_client:
        try:
            await http_client.aclose()
            logger.info("Lifespan: HTTP client closed")
        except Exception as e:
            logger.error(f"Lifespan: Error during client close: {e}")
        finally:
            http_client = None

# ==== FastAPI 应用 ====
APP_VERSION = "1.8.14"
app = FastAPI(
    title="EzTalk Proxy",
    description="Proxy for OpenAI, Google etc.",
    version=APP_VERSION,
    lifespan=lifespan,
    docs_url="/docs",
    redoc_url="/redoc"
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["*"]
)
logger.info("FastAPI application with CORS ready.")

# ==== 常量 ====
DEFAULT_OPENAI_API_BASE_URL = "https://api.openai.com"
GOOGLE_API_BASE_URL = "https://generativelanguage.googleapis.com"
OPENAI_COMPATIBLE_PATH = "/v1/chat/completions"
COMMON_HEADERS = {"X-Accel-Buffering": "no"}

# ==== 数据模型 ====
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
    tool_config: Optional[Dict[str, Any]] = None  # 修复前面 syntax bug!

def orjson_dumps_bytes_wrapper(data: Any) -> bytes:
    return orjson.dumps(data, option=orjson.OPT_NON_STR_KEYS | orjson.OPT_PASSTHROUGH_DATETIME | orjson.OPT_APPEND_NEWLINE)

def error_response(code: int, msg: str, headers: Optional[Dict[str, str]] = None) -> JSONResponse:
    final_headers = COMMON_HEADERS.copy()
    if headers:
        final_headers.update(headers)
    logger.warning(f"Responding error {code}: {msg}")
    error_content = {"error": {"message": msg, "code": code, "type": "proxy_error"}}
    return JSONResponse(
        status_code=code,
        content=error_content,
        headers=final_headers
    )

# ==== 辅助SSE切包 ====
MAX_SSE_LINE_LENGTH = 24 * 1024  # 24kb，防止极端大包溢出

def extract_sse_lines(buffer: bytearray):
    # 按lines返回(兼容\r\n)，剩余字节返回
    lines = []
    start = 0
    while True:
        idx = buffer.find(b'\n', start)
        if idx == -1: break
        lin = buffer[start:idx]
        if lin.endswith(b'\r'): lin = lin[:-1]  # strip \r
        if len(lin) > MAX_SSE_LINE_LENGTH:
            logger.warning(f"SSE line too long ({len(lin)}), skipping.")
            start = idx + 1
            continue
        lines.append(lin)
        start = idx + 1
    rest = buffer[start:]
    return lines, rest

# ==== 流解析 ====
async def process_sse_line(line_bytes: bytes) -> AsyncGenerator[bytes, None]:
    event_start = b"data: "
    done_marker = b"[DONE]"
    if not line_bytes or not line_bytes.startswith(event_start):
        return
    raw_data_bytes = line_bytes[len(event_start):].strip()
    if not raw_data_bytes or raw_data_bytes == done_marker:
        return
    try:
        data = orjson.loads(raw_data_bytes)
        for choice in data.get('choices', []):
            delta = choice.get('delta', {})
            if "reasoning_content" in delta and delta["reasoning_content"]:
                yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": delta["reasoning_content"]})
            if "content" in delta and delta["content"]:
                yield orjson_dumps_bytes_wrapper({"type": "content", "text": delta["content"]})
            finish_reason = delta.get("finish_reason") or choice.get("finish_reason")
            if finish_reason:
                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish_reason})
    except orjson.JSONDecodeError:
        logger.warning(f"process_sse_line: orjson parse error. {line_bytes[:100]!r}")
    except Exception as e:
        logger.error(f"process_sse_line: Unexpected error {e}", exc_info=True)

async def process_google_sse_line(line_bytes: bytes) -> AsyncGenerator[bytes, None]:
    event_start = b"data: "
    if not line_bytes.startswith(event_start):
        return
    data_bytes = line_bytes[len(event_start):].strip()
    if not data_bytes:
        return
    try:
        data = orjson.loads(data_bytes)
        for candidate in data.get('candidates', []):
            content_part = candidate.get('content', {}).get('parts', [{}])[0]
            text_content = content_part.get('text', '')
            if text_content:
                yield orjson_dumps_bytes_wrapper({"type": "content", "text": text_content})
            finish_reason = candidate.get('finishReason')
            if finish_reason:
                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish_reason})
    except orjson.JSONDecodeError:
        logger.warning(f"process_google_sse_line: orjson parse error. {data_bytes[:100]!r}")
    except Exception as e:
        logger.error(f"process_google_sse_line: Unexpected error {e}", exc_info=True)

# ==== 主接口 ====
@app.post("/chat", response_class=StreamingResponse, summary="Proxy for AI Chat", tags=["AI Proxy"])
async def chat_proxy(
    request_data: ChatRequest,
    current_http_client: Optional[httpx.AsyncClient] = Depends(lambda: http_client)
):
    request_id = os.urandom(8).hex()
    logger.info(f"RID-{request_id}: Received /chat {request_data.provider} {request_data.model}")
    if current_http_client is None or not isinstance(current_http_client, httpx.AsyncClient):
        return error_response(503, "Service unavailable: HTTP client not initialized properly.")

    try:
        if request_data.provider == "openai":
            target_base_url = request_data.api_address.strip() if request_data.api_address else DEFAULT_OPENAI_API_BASE_URL
            url = f"{target_base_url.rstrip('/')}{OPENAI_COMPATIBLE_PATH}"
            headers = {"Content-Type": "application/json", "Accept": "application/json"}
            headers.update({"Authorization": f"Bearer {request_data.api_key}"})
            payload = {
                "model": request_data.model,
                "messages": [m.model_dump(exclude_unset=True, exclude_none=True) for m in request_data.messages],
                "stream": True
            }
            if request_data.temperature is not None: payload["temperature"] = request_data.temperature
            if request_data.top_p is not None: payload["top_p"] = request_data.top_p
            if request_data.max_tokens is not None: payload["max_tokens"] = request_data.max_tokens
            if request_data.tools is not None: payload["tools"] = request_data.tools
            if request_data.tool_config is not None: payload["tool_config"] = request_data.tool_config
            is_openai_provider = True; params = None
        elif request_data.provider == "google":
            url = f"{GOOGLE_API_BASE_URL}/v1beta/models/{request_data.model}:streamGenerateContent"
            headers = {"Content-Type": "application/json", "Accept": "text/event-stream"}
            params = {"key": request_data.api_key, "alt": "sse"}
            contents = []
            google_role_map = {"assistant": "model", "user": "user", "tool": "function"}
            for msg in request_data.messages:
                google_role = google_role_map.get(msg.role)
                if google_role and msg.content:
                    contents.append({"role": google_role, "parts": [{"text": msg.content}]})
            payload = {"contents": contents}
            generation_config = {}
            if request_data.temperature is not None: generation_config["temperature"] = request_data.temperature
            if request_data.top_p is not None: generation_config["topP"] = request_data.top_p
            if request_data.max_tokens is not None: generation_config["maxOutputTokens"] = request_data.max_tokens
            if generation_config: payload["generationConfig"] = generation_config
            if request_data.tools: payload["tools"] = request_data.tools
            is_openai_provider = False
        else:
            return error_response(400, f"Invalid provider specified: {request_data.provider}")
    except Exception as e:
        logger.error(f"RID-{request_id}: Error preparing payload: {e}", exc_info=True)
        return error_response(500, f"Internal error during request preparation: {str(e)}")

    async def stream_generator() -> AsyncGenerator[bytes, None]:
        buffer = bytearray()
        upstream_request_successful = False
        try:
            async with current_http_client.stream("POST", url, headers=headers, json=payload, params=params) as resp:
                if not (200 <= resp.status_code < 300):
                    error_body_bytes = await resp.aread()
                    error_text = error_body_bytes.decode("utf-8", errors="replace")
                    logger.error(f"RID-{request_id}: Upstream API failed! Status: {resp.status_code}, Response: {error_text[:200]}")
                    try:
                        upstream_error_data = orjson.loads(error_text)
                        err_msg_detail = upstream_error_data.get("error", {}).get("message") or upstream_error_data.get("message")
                        yield orjson_dumps_bytes_wrapper({"type": "error", "message": err_msg_detail, "upstream_status": resp.status_code})
                    except Exception:
                        yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Upstream error {resp.status_code}: {error_text[:200]}", "upstream_status": resp.status_code})
                    yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "error"})  # 补一个finish
                    return
                upstream_request_successful = True
                async for chunk in resp.aiter_raw():  # 支持 chunked
                    if not chunk: continue
                    buffer.extend(chunk)
                    # ---- 新行切分 ----
                    lines, buffer = extract_sse_lines(buffer)
                    has_lines = False
                    for line in lines:
                        if not line.strip(): continue  # Skip empty
                        has_lines = True
                        if is_openai_provider:
                            async for formatted_chunk in process_sse_line(line):
                                yield formatted_chunk
                        else:
                            async for formatted_chunk in process_google_sse_line(line):
                                yield formatted_chunk
                    if has_lines or len(chunk) > 256:
                        await asyncio.sleep(0.0001)  # 最优协程让步
        except httpx.TimeoutException as e:
            logger.error(f"RID-{request_id}: HTTPX TimeoutException: {e}")
            yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Upstream timeout: {str(e)}"})
            yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "error"})
        except httpx.RequestError as e:
            logger.error(f"RID-{request_id}: HTTPX RequestError {e}")
            yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Network error: {str(e)}"})
            yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "error"})
        except asyncio.CancelledError:
            logger.info(f"RID-{request_id}: Stream cancelled.")
            yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "cancelled"})
        except Exception as e:
            logger.error(f"RID-{request_id}: Unexpected stream exception: {e}", exc_info=True)
            if not upstream_request_successful:
                yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Internal streaming error: {str(e)}"})
                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "error"})
        finally:
            logger.info(f"RID-{request_id}: Stream finished. Upstream success: {upstream_request_successful}")

    streaming_headers = COMMON_HEADERS.copy()
    streaming_headers["Content-Type"] = "text/event-stream; charset=utf-8"
    streaming_headers["Cache-Control"] = "no-cache"
    streaming_headers["Connection"] = "keep-alive"

    logger.info(f"RID-{request_id}: Returning StreamingResponse to client.")
    return StreamingResponse(
        stream_generator(),
        media_type="text/event-stream",
        headers=streaming_headers
    )

# ==== 本地调试入口 ====
if __name__ == "__main__":
    import uvicorn
    APP_HOST = os.getenv("HOST", "0.0.0.0")
    APP_PORT = int(os.getenv("PORT", 8000))
    DEV_RELOAD = os.getenv("DEV_RELOAD", "false").lower() == "true"
    log_config = {
        "version": 1,
        "disable_existing_loggers": False,
        "formatters": {
            "default": {
                "()": "uvicorn.logging.DefaultFormatter",
                "fmt": "%(asctime)s %(levelname)-8s [%(name)s:%(module)s:%(lineno)d] - %(message)s",
                "datefmt": "%Y-%m-%d %H:%M:%S",
                "use_colors": None,
            },
            "access": {
                "()": "uvicorn.logging.AccessFormatter",
                "fmt": '%(asctime)s %(levelname)-8s [%(name)s:%(module)s:%(lineno)d] - %(client_addr)s - "%(request_line)s" %(status_code)s',
                "datefmt": "%Y-%m-%d %H:%M:%S",
                "use_colors": None,
            },
        },
        "handlers": {
            "default": {
                "formatter": "default",
                "class": "logging.StreamHandler",
                "stream": "ext://sys.stderr",
            },
            "access": {
                "formatter": "access",
                "class": "logging.StreamHandler",
                "stream": "ext://sys.stdout",
            },
        },
        "loggers": {
            "EzTalkProxy": {"handlers": ["default"], "level": LOG_LEVEL_FROM_ENV, "propagate": False},
            "uvicorn": {"handlers": ["default"], "level": "INFO", "propagate": False},
            "uvicorn.error": {"handlers": ["default"], "level": "INFO", "propagate": False},
            "uvicorn.access": {"handlers": ["access"], "level": "INFO", "propagate": False},
        },
    }
    logger.info(f"Starting Uvicorn server on http://{APP_HOST}:{APP_PORT}")
    logger.info(f"Development reload is {'ENABLED' if DEV_RELOAD else 'DISABLED'}")
    logger.info(f"Log level set to: {LOG_LEVEL_FROM_ENV}")
    uvicorn.run(
        "main:app",
        host=APP_HOST,
        port=APP_PORT,
        log_config=log_config,
        reload=DEV_RELOAD,
    )

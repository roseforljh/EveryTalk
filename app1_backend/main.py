# --- main.py: EzTalk Proxy v1.8.14 (SSE极致优化、小bug修复、增强lifespan日志) ---

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
import logging

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

print("VERCEL_MAIN_PY_LOADED_TEST: Top of main.py reached.")
logger = logging.getLogger("EzTalkProxy") # 确保 logger 在使用前已定义
logger.critical("VERCEL_MAIN_PY_LOGGER_CRITICAL_TEST: Logger active at top of main.py.")
# ==== Lifespan 管理器 ====
@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    global http_client
    logger.info("Lifespan: Starting up, attempting to initialize HTTP client...")
    try:
        # --- BEGIN: 增强日志 ---
        raw_api_timeout = os.getenv("API_TIMEOUT", "300")
        raw_read_timeout = os.getenv("READ_TIMEOUT", "60.0")
        raw_max_connections = os.getenv("MAX_CONNECTIONS", "200")

        logger.info(f"Lifespan: Raw API_TIMEOUT from env: '{raw_api_timeout}' (defaulting to '300')")
        logger.info(f"Lifespan: Raw READ_TIMEOUT from env: '{raw_read_timeout}' (defaulting to '60.0')")
        logger.info(f"Lifespan: Raw MAX_CONNECTIONS from env: '{raw_max_connections}' (defaulting to '200')")

        api_timeout = int(raw_api_timeout)
        read_timeout = float(raw_read_timeout)
        max_connections = int(raw_max_connections)

        logger.info(f"Lifespan: Parsed API_TIMEOUT: {api_timeout}")
        logger.info(f"Lifespan: Parsed READ_TIMEOUT: {read_timeout}")
        logger.info(f"Lifespan: Parsed MAX_CONNECTIONS: {max_connections}")
        # --- END: 增强日志 ---

        http_client = httpx.AsyncClient(
            timeout=httpx.Timeout(api_timeout, read=read_timeout),
            limits=httpx.Limits(max_connections=max_connections),
            http2=True,
            follow_redirects=True,
            trust_env=False # 通常建议保持 False 以避免意外的系统代理影响
        )
        logger.info(f"Lifespan: HTTP client initialized successfully. Client object: {http_client}")
    except ValueError as ve: # 更具体地捕获值转换错误
        logger.error(f"Lifespan: HTTP client initialization failed due to ValueError (likely invalid environment variable format): {ve}", exc_info=True)
        http_client = None
    except Exception as e:
        logger.error(f"Lifespan: HTTP client initialization failed with an unexpected error: {e}", exc_info=True)
        http_client = None
    
    yield # 应用运行

    logger.info("Lifespan: Shutting down, attempting to close HTTP client...")
    if http_client:
        try:
            await http_client.aclose()
            logger.info("Lifespan: HTTP client closed successfully.")
        except Exception as e:
            logger.error(f"Lifespan: Error during HTTP client close: {e}", exc_info=True)
        finally:
            http_client = None # 确保在关闭后也设置为 None
            logger.info("Lifespan: HTTP client set to None after close attempt.")
    else:
        logger.info("Lifespan: HTTP client was None, no closing needed.")
    logger.info("Lifespan: Shutdown complete.")


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
    tool_config: Optional[Dict[str, Any]] = None

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
MAX_SSE_LINE_LENGTH = 24 * 1024

def extract_sse_lines(buffer: bytearray):
    lines = []
    start = 0
    while True:
        idx = buffer.find(b'\n', start)
        if idx == -1: break
        lin = buffer[start:idx]
        if lin.endswith(b'\r'): lin = lin[:-1]
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

# 在你的 FastAPI app 定义之后 (例如，在 @app.post("/chat") 之前或之后)
@app.get("/health", status_code=200, include_in_schema=False) # include_in_schema=False 使其不显示在API文档中
async def health_check():
    if http_client is None: # 简单的检查 http_client 是否已初始化
         return {"status": "warning", "detail": "HTTP client not initialized"}
    return {"status": "ok"}
# ==== 主接口 ====
@app.post("/chat", response_class=StreamingResponse, summary="Proxy for AI Chat", tags=["AI Proxy"])
async def chat_proxy(
    request_data: ChatRequest,
    current_http_client: Optional[httpx.AsyncClient] = Depends(lambda: http_client)
):
    request_id = os.urandom(8).hex()
    logger.info(f"RID-{request_id}: Received /chat {request_data.provider} {request_data.model}")

    if current_http_client is None: # 简化检查，因为类型检查由 Depends 和类型提示处理
        logger.error(f"RID-{request_id}: HTTP client is None. Cannot process request.")
        return error_response(503, "Service unavailable: HTTP client not initialized properly.")
    if not isinstance(current_http_client, httpx.AsyncClient): # 双重保险
        logger.error(f"RID-{request_id}: current_http_client is not an AsyncClient instance. Type: {type(current_http_client)}")
        return error_response(503, "Service unavailable: HTTP client in unexpected state.")


    try:
        if request_data.provider == "openai":
            target_base_url = request_data.api_address.strip() if request_data.api_address else DEFAULT_OPENAI_API_BASE_URL
            url = f"{target_base_url.rstrip('/')}{OPENAI_COMPATIBLE_PATH}"
            headers = {"Content-Type": "application/json", "Accept": "application/json"} # OpenAI 通常接受 application/json
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
            headers = {"Content-Type": "application/json"} # Google API 通常用 application/json 作为请求体
                                                           # Accept header for SSE is handled by httpx when streaming
            params = {"key": request_data.api_key, "alt": "sse"}
            contents = []
            google_role_map = {"assistant": "model", "user": "user", "tool": "function"} # "tool" 角色对 Google GenAI 可能需要特殊处理
            for msg in request_data.messages:
                google_role = google_role_map.get(msg.role)
                if google_role and msg.content: # 确保有内容
                    contents.append({"role": google_role, "parts": [{"text": msg.content}]})
                # 注意：Google GenAI 对 "tool" 角色的支持可能与 OpenAI 不同，这里简化处理
            payload = {"contents": contents}
            generation_config = {}
            if request_data.temperature is not None: generation_config["temperature"] = request_data.temperature
            if request_data.top_p is not None: generation_config["topP"] = request_data.top_p
            if request_data.max_tokens is not None: generation_config["maxOutputTokens"] = request_data.max_tokens
            if generation_config: payload["generationConfig"] = generation_config
            if request_data.tools: payload["tools"] = request_data.tools # Google GenAI 的 tools 格式可能也不同
            is_openai_provider = False
        else:
            logger.warning(f"RID-{request_id}: Invalid provider specified: {request_data.provider}")
            return error_response(400, f"Invalid provider specified: {request_data.provider}")
    except Exception as e:
        logger.error(f"RID-{request_id}: Error preparing payload for {request_data.provider}: {e}", exc_info=True)
        return error_response(500, f"Internal error during request preparation: {str(e)}")

    async def stream_generator() -> AsyncGenerator[bytes, None]:
        buffer = bytearray()
        upstream_request_successful = False
        try:
            logger.info(f"RID-{request_id}: Making POST request to URL: {url} with params: {params}")
            # logger.debug(f"RID-{request_id}: Payload: {payload}") # 注意：API Key 在 payload 或 headers 中，按需取消注释
            async with current_http_client.stream("POST", url, headers=headers, json=payload, params=params) as resp:
                logger.info(f"RID-{request_id}: Upstream response status: {resp.status_code}")
                logger.debug(f"RID-{request_id}: Upstream response headers: {resp.headers}")
                if not (200 <= resp.status_code < 300):
                    error_body_bytes = await resp.aread()
                    error_text = error_body_bytes.decode("utf-8", errors="replace")
                    logger.error(f"RID-{request_id}: Upstream API request failed! Status: {resp.status_code}, Response: {error_text[:500]}")
                    try:
                        upstream_error_data = orjson.loads(error_text)
                        err_msg_detail = upstream_error_data.get("error", {}).get("message") or upstream_error_data.get("message", error_text[:200])
                        yield orjson_dumps_bytes_wrapper({"type": "error", "message": err_msg_detail, "upstream_status": resp.status_code})
                    except Exception as parse_exc:
                        logger.warning(f"RID-{request_id}: Could not parse upstream error JSON: {parse_exc}. Raw error: {error_text[:200]}")
                        yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Upstream error {resp.status_code}: {error_text[:200]}", "upstream_status": resp.status_code})
                    yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "error"})
                    return

                upstream_request_successful = True
                logger.info(f"RID-{request_id}: Successfully connected to upstream. Starting to stream response.")
                async for chunk in resp.aiter_raw():
                    if not chunk:
                        # logger.debug(f"RID-{request_id}: Received empty chunk from upstream.") # 可能过于频繁
                        continue
                    # logger.debug(f"RID-{request_id}: Received chunk of size {len(chunk)} from upstream.")
                    buffer.extend(chunk)
                    lines, buffer = extract_sse_lines(buffer) # 使用之前定义的函数
                    has_lines = False
                    for line in lines:
                        if not line.strip(): continue
                        has_lines = True
                        if is_openai_provider:
                            async for formatted_chunk in process_sse_line(line):
                                yield formatted_chunk
                        else:
                            async for formatted_chunk in process_google_sse_line(line):
                                yield formatted_chunk
                    if has_lines or len(chunk) > 256: # 如果有解析出行或者原始块较大，则让步
                        await asyncio.sleep(0.0001)

                # 处理 buffer 中剩余的最后数据 (如果上游没有以 \n 结尾)
                if buffer:
                    logger.info(f"RID-{request_id}: Processing remaining buffer data of size {len(buffer)} after stream end.")
                    line = buffer.strip() # 通常 SSE 最后一行也是 data: ...
                    if line:
                        if is_openai_provider:
                            async for formatted_chunk in process_sse_line(line):
                                yield formatted_chunk
                        else:
                            async for formatted_chunk in process_google_sse_line(line):
                                yield formatted_chunk
                    buffer.clear()


        except httpx.TimeoutException as e:
            logger.error(f"RID-{request_id}: HTTPX TimeoutException during upstream request to {url}: {e}", exc_info=True)
            yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Upstream timeout: {str(e)}"})
            yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "timeout_error"})
        except httpx.RequestError as e: # 包括 ConnectError, ReadError 等
            logger.error(f"RID-{request_id}: HTTPX RequestError during upstream request to {url}: {e}", exc_info=True)
            yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Network error communicating with upstream: {str(e)}"})
            yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "network_error"})
        except asyncio.CancelledError:
            logger.info(f"RID-{request_id}: Stream generation cancelled by client or server shutdown.")
            # 通常不需要发送 'finish'，因为连接可能已断开
            # yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "cancelled"})
        except Exception as e:
            logger.error(f"RID-{request_id}: Unexpected exception in stream_generator for {url}: {e}", exc_info=True)
            # 仅当上游请求未成功时才发送此通用错误，否则可能是客户端断开连接等。
            if not upstream_request_successful:
                yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Internal streaming error: {str(e)}"})
                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "internal_error"})
        finally:
            logger.info(f"RID-{request_id}: Stream_generator for {url} finished. Upstream request was successful: {upstream_request_successful}")

    streaming_headers = COMMON_HEADERS.copy()
    streaming_headers["Content-Type"] = "text/event-stream; charset=utf-8"
    streaming_headers["Cache-Control"] = "no-cache"
    streaming_headers["Connection"] = "keep-alive" # 确保连接保持

    logger.info(f"RID-{request_id}: Returning StreamingResponse to client with headers: {streaming_headers}")
    return StreamingResponse(
        stream_generator(),
        media_type="text/event-stream", # media_type 必须是 text/event-stream
        headers=streaming_headers
    )

# ==== 本地调试入口 ====
if __name__ == "__main__":
    import uvicorn
    APP_HOST = os.getenv("HOST", "0.0.0.0")
    APP_PORT = int(os.getenv("PORT", 8000))
    DEV_RELOAD = os.getenv("DEV_RELOAD", "false").lower() == "true"
    
    # 使用 uvicorn 自己的 log_config，它会覆盖 basicConfig
    log_config = uvicorn.config.LOGGING_CONFIG
    log_config["formatters"]["default"]["fmt"] = "%(asctime)s %(levelname)-8s [%(name)s:%(module)s:%(lineno)d] - %(message)s"
    log_config["formatters"]["default"]["datefmt"] = "%Y-%m-%d %H:%M:%S"
    log_config["formatters"]["access"]["fmt"] = '%(asctime)s %(levelname)-8s [%(name)s:%(module)s:%(lineno)d] - %(client_addr)s - "%(request_line)s" %(status_code)s'
    log_config["formatters"]["access"]["datefmt"] = "%Y-%m-%d %H:%M:%S"
    
    # 确保我们自定义的 logger 也使用 uvicorn 的 handler 和 formatter
    log_config["loggers"]["EzTalkProxy"] = {
        "handlers": ["default"], # uvicorn 的 'default' handler
        "level": LOG_LEVEL_FROM_ENV,
        "propagate": False # 避免重复日志
    }

    logger.info(f"Starting Uvicorn server on http://{APP_HOST}:{APP_PORT}")
    logger.info(f"Development reload is {'ENABLED' if DEV_RELOAD else 'DISABLED'}")
    logger.info(f"Application log level (EzTalkProxy) set to: {LOG_LEVEL_FROM_ENV}")
    
    uvicorn.run(
        "main:app", # app 实例的路径
        host=APP_HOST,
        port=APP_PORT,
        log_config=log_config,
        reload=DEV_RELOAD,
    )

    


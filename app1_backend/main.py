# --- main.py: EzTalk Proxy v1.8.12 (orjson, robust lifespan, clear logging) ---

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
import sys

# ==== 日志配置 ====
# LOG_LEVEL 默认为 INFO，可以根据需要在 Vercel/Railway 环境变量中设置为 DEBUG
LOG_LEVEL_FROM_ENV = os.getenv("LOG_LEVEL", "INFO").upper()
# 确保 LOG_LEVEL_FROM_ENV 是一个有效的日志级别
numeric_level = getattr(logging, LOG_LEVEL_FROM_ENV, logging.INFO)

logging.basicConfig(
    level=numeric_level,
    format='%(asctime)s %(levelname)-8s [%(name)s:%(module)s:%(lineno)d] - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger("EzTalkProxy") # 给 logger 一个明确的名字

# 调整常用库的日志级别，减少干扰，除非需要调试它们
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("uvicorn.access").setLevel(logging.WARNING) # Uvicorn 访问日志
logging.getLogger("uvicorn.error").setLevel(logging.INFO)   # Uvicorn 错误和应用启动日志

logger.info(f"EzTalkProxy logger initialized with level: {LOG_LEVEL_FROM_ENV}")

# ==== 全局 HTTP 客户端 ====
http_client: Optional[httpx.AsyncClient] = None

# ==== Lifespan 管理器 ====
@asynccontextmanager
async def lifespan(app_instance: FastAPI): # app_instance 是 FastAPI lifespan 规范的一部分
    # !!!!!! 最最最顶层的调试打印 !!!!!!
    print("DEBUG_LIFESPAN: LIFESPAN FUNCTION ENTERED (Attempt 1 - stdout)", flush=True)
    sys.stderr.write("DEBUG_LIFESPAN: LIFESPAN FUNCTION ENTERED (Attempt 2 - stderr)\n")
    sys.stderr.flush()
    # !!!!!! END 最顶层调试打印 !!!!!!
    global http_client
    logger.info("Lifespan: Event - Application startup initiated.")
    logger.info("Lifespan: Attempting to initialize global HTTP client...")

    try:
        # 从环境变量读取配置，带默认值和类型转换
        # 如果环境变量未设置，使用默认值。如果设置了但转换失败，会抛出 ValueError。
        raw_api_timeout = os.getenv("API_TIMEOUT", "300")
        raw_read_timeout = os.getenv("READ_TIMEOUT", "60.0")
        raw_max_connections = os.getenv("MAX_CONNECTIONS", "200")

        logger.info(f"Lifespan: Raw env values - API_TIMEOUT='{raw_api_timeout}', READ_TIMEOUT='{raw_read_timeout}', MAX_CONNECTIONS='{raw_max_connections}'")

        api_timeout = int(raw_api_timeout)
        read_timeout = float(raw_read_timeout)
        max_connections = int(raw_max_connections)

        logger.info(f"Lifespan: Parsed config for HTTP client - API_TIMEOUT={api_timeout}s, READ_TIMEOUT={read_timeout}s, MAX_CONNECTIONS={max_connections}")

        http_client = httpx.AsyncClient(
            timeout=httpx.Timeout(api_timeout, read=read_timeout),
            limits=httpx.Limits(max_connections=max_connections),
            http2=True,
            follow_redirects=True,
            trust_env=False # 推荐在托管环境中显式控制代理等行为
        )
        logger.info(f"Lifespan: Global HTTP client INITIALIZED SUCCESSFULLY. Client object: {http_client}")
    except ValueError as ve:
        logger.error(f"Lifespan: CRITICAL ERROR - Invalid value for environment variable during HTTP client config: {ve}", exc_info=True)
        logger.error("Lifespan: HTTP client will NOT be initialized. Application might not function correctly.")
        http_client = None # 确保如果配置转换失败，客户端为 None
    except Exception as e:
        logger.error(f"Lifespan: CRITICAL ERROR during HTTP client initialization: {e}", exc_info=True)
        logger.error("Lifespan: HTTP client will NOT be initialized. Application might not function correctly.")
        http_client = None # 确保如果发生其他错误，客户端为 None

    yield # FastAPI 应用在此期间运行

    # --- Shutdown phase ---
    logger.info("Lifespan: Event - Application shutdown initiated.")
    if http_client:
        logger.info(f"Lifespan: Attempting to close global HTTP client: {http_client}")
        try:
            await http_client.aclose()
            logger.info("Lifespan: Global HTTP client closed successfully.")
        except Exception as e:
            logger.error(f"Lifespan: Error during HTTP client close: {e}", exc_info=True)
        finally:
            http_client = None # 清理引用
    else:
        logger.warning("Lifespan: Global HTTP client was None (not initialized or initialization failed), skipping close.")
    logger.info("Lifespan: Event - Application shutdown finished.")


# ==== FastAPI 应用实例 ====
# 使用清晰的版本号，方便追踪部署
APP_VERSION = "1.8.12"
logger.info(f"Initializing FastAPI application - Version {APP_VERSION}")
app = FastAPI(
    title="EzTalk Proxy",
    description="Proxy for OpenAI, Google, and compatible endpoints, with orjson optimization.",
    version=APP_VERSION,
    lifespan=lifespan, # 注册 lifespan 管理器
    docs_url="/docs",
    redoc_url="/redoc"
)

# CORS 中间件，允许所有来源 (在生产环境中可能需要更严格的配置)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["*"] # 如果前端需要读取自定义响应头
)
logger.info("FastAPI application initialized with CORS middleware.")

# ==== 常量定义 (移到 FastAPI 实例化之后，以确保 logger 已配置) ====
DEFAULT_OPENAI_API_BASE_URL = "https://api.openai.com"
GOOGLE_API_BASE_URL = "https://generativelanguage.googleapis.com"
OPENAI_COMPATIBLE_PATH = "/v1/chat/completions"

COMMON_HEADERS = {"X-Accel-Buffering": "no"} # 建议用于流式响应，防止 Nginx 等代理缓冲
COMPATIBLE_HEADERS = {"Content-Type": "application/json", "Accept": "application/json"}
GOOGLE_HEADERS = {"Content-Type": "application/json", "Accept": "text/event-stream"}


# ==== Pydantic 数据模型 ====
class ApiMessage(BaseModel):
    role: str
    content: Optional[str] = None
    name: Optional[str] = None

class ChatRequest(BaseModel):
    api_address: Optional[str] = None
    messages: List[ApiMessage]
    provider: Literal["openai", "google"]
    model: str
    api_key: str # API Key 由客户端在请求中提供
    temperature: Optional[float] = Field(None, ge=0.0, le=2.0)
    top_p: Optional[float] = Field(None, ge=0.0, le=1.0)
    max_tokens: Optional[int] = Field(None, gt=0)
    tools: Optional[List[Dict[str, Any]]] = None
    tool_config: Optional[Dict[str, Any]] = None
    # stream: bool = Field(True, const=True) # 强制流式，如果需要

# ==== 辅助函数 ====
def orjson_dumps_bytes_wrapper(data: Any) -> bytes:
    """使用 orjson 序列化为 bytes，包含常用选项。"""
    return orjson.dumps(data, option=orjson.OPT_NON_STR_KEYS | orjson.OPT_PASSTHROUGH_DATETIME | orjson.OPT_APPEND_NEWLINE)

def error_response(code: int, msg: str, headers: Optional[Dict[str, str]] = None) -> JSONResponse:
    final_headers = COMMON_HEADERS.copy()
    if headers:
        final_headers.update(headers)
    logger.warning(f"Responding with error - HTTP Status: {code}, Message: '{msg}'")
    error_content = {"error": {"message": msg, "code": code, "type": "proxy_error"}}
    return JSONResponse(
        status_code=code,
        content=error_content, # FastAPI 会自动处理 JSON 序列化
        headers=final_headers
    )

# ==== 流式解析辅助 ====
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
                await asyncio.sleep(0)
            if "content" in delta and delta["content"]:
                yield orjson_dumps_bytes_wrapper({"type": "content", "text": delta["content"]})
                await asyncio.sleep(0)
            finish_reason = delta.get("finish_reason") or choice.get("finish_reason")
            if finish_reason:
                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish_reason})
                await asyncio.sleep(0)
    except orjson.JSONDecodeError as e:
        logger.warning(f"process_sse_line: orjson parse error on line '{line_bytes[:100].decode('utf-8', 'replace')}'. Error: {e}")
    except Exception as e:
        logger.error(f"process_sse_line: Unexpected error processing line '{line_bytes[:100].decode('utf-8', 'replace')}'. Error: {e}", exc_info=True)

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
            content_part = candidate.get('content', {}).get('parts', [{}])[0] # Assuming one part for simplicity
            text_content = content_part.get('text', '')

            if text_content:
                yield orjson_dumps_bytes_wrapper({"type": "content", "text": text_content})
                await asyncio.sleep(0)

            finish_reason = candidate.get('finishReason')
            # Google Gemini API uses various finish reasons, map them if needed or pass through
            if finish_reason and finish_reason not in ["FINISH_REASON_UNSPECIFIED", "STOP", "MAX_TOKENS"]: # Filter some common non-error stops
                 yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish_reason})
                 await asyncio.sleep(0)
            elif finish_reason == "STOP" or finish_reason == "MAX_TOKENS": # Consider these as normal completion
                 yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish_reason}) # Pass through "STOP"
                 await asyncio.sleep(0)

    except orjson.JSONDecodeError as e:
        logger.warning(f"process_google_sse_line: orjson parse error on line '{data_bytes[:100].decode('utf-8', 'replace')}'. Error: {e}")
    except Exception as e:
        logger.error(f"process_google_sse_line: Unexpected error processing line '{data_bytes[:100].decode('utf-8', 'replace')}'. Error: {e}", exc_info=True)


# ==== 主 API 端点 ====
@app.post("/chat",
          response_class=StreamingResponse,
          summary="Proxy for AI Chat Completions (OpenAI, Google)",
          tags=["AI Proxy"])
async def chat_proxy(
    request_data: ChatRequest, # Renamed for clarity
    # The dependency will provide the initialized http_client or None
    current_http_client: Optional[httpx.AsyncClient] = Depends(lambda: http_client)
):
    request_id = os.urandom(8).hex() # Generate a simple request ID for logging
    logger.info(f"RID-{request_id}: Received /chat request. Provider: {request_data.provider}, Model: {request_data.model}")
    logger.debug(f"RID-{request_id}: HTTP client from dependency: {current_http_client} (Type: {type(current_http_client)})")

    if current_http_client is None:
        logger.error(f"RID-{request_id}: CRITICAL - HTTP client is None. Lifespan event likely failed to initialize it.")
        return error_response(503, "Service unavailable: HTTP client not initialized properly. Please check server logs.")
    if not isinstance(current_http_client, httpx.AsyncClient):
        logger.error(f"RID-{request_id}: CRITICAL - HTTP client is not an httpx.AsyncClient! Type: {type(current_http_client)}. This should not happen.")
        return error_response(503, "Service unavailable: HTTP client has incorrect type. Please check server logs.")

    # === 1. 构建上游请求参数 ===
    try:
        if request_data.provider == "openai":
            target_base_url = request_data.api_address.strip() if request_data.api_address else DEFAULT_OPENAI_API_BASE_URL
            url = f"{target_base_url.rstrip('/')}{OPENAI_COMPATIBLE_PATH}"
            headers = COMPATIBLE_HEADERS.copy()
            headers["Authorization"] = f"Bearer {request_data.api_key}"
            payload = {
                "model": request_data.model,
                "messages": [m.model_dump(exclude_unset=True, exclude_none=True) for m in request_data.messages],
                "stream": True # Always stream from this proxy
            }
            if request_data.temperature is not None: payload["temperature"] = request_data.temperature
            if request_data.top_p is not None: payload["top_p"] = request_data.top_p
            if request_data.max_tokens is not None: payload["max_tokens"] = request_data.max_tokens
            if request_data.tools is not None: payload["tools"] = request_data.tools
            if request_data.tool_config is not None: payload["tool_config"] = request_data.tool_config
            is_openai_provider = True; params = None
            logger.debug(f"RID-{request_id}: OpenAI request prepared for URL: {url}")

        elif request_data.provider == "google":
            url = f"{GOOGLE_API_BASE_URL}/v1beta/models/{request_data.model}:streamGenerateContent"
            headers = GOOGLE_HEADERS.copy()
            params = {"key": request_data.api_key, "alt": "sse"}
            contents = []
            google_role_map = {"assistant": "model", "user": "user", "tool": "function"} # "system" might need special handling for Google
            for msg in request_data.messages:
                google_role = google_role_map.get(msg.role)
                if google_role and msg.content: # Ensure content is not None
                    contents.append({"role": google_role, "parts": [{"text": msg.content}]})
            payload = {"contents": contents}
            generation_config = {}
            if request_data.temperature is not None: generation_config["temperature"] = request_data.temperature
            if request_data.top_p is not None: generation_config["topP"] = request_data.top_p # Note: Google uses "topP"
            if request_data.max_tokens is not None: generation_config["maxOutputTokens"] = request_data.max_tokens
            if generation_config: payload["generationConfig"] = generation_config
            if request_data.tools: payload["tools"] = request_data.tools # Google's tool format might differ
            is_openai_provider = False
            logger.debug(f"RID-{request_id}: Google request prepared for URL: {url}")
        else:
            logger.warning(f"RID-{request_id}: Invalid provider specified: {request_data.provider}")
            return error_response(400, f"Invalid provider specified: {request_data.provider}")

    except Exception as e:
        logger.error(f"RID-{request_id}: Error preparing request payload for provider '{request_data.provider}': {e}", exc_info=True)
        return error_response(500, f"Internal error during request preparation: {str(e)}")

    logger.info(f"RID-{request_id}: Proxying request to upstream URL: {url}")
    # logger.debug(f"RID-{request_id}: Upstream Payload: {orjson.dumps(payload).decode('utf-8')}") # Careful with logging full payloads

    # === 2. 流式响应生成器 ===
    async def stream_generator() -> AsyncGenerator[bytes, None]:
        buffer = bytearray()
        upstream_request_successful = False
        try:
            async with current_http_client.stream("POST", url, headers=headers, json=payload, params=params) as resp:
                logger.info(f"RID-{request_id}: Upstream response status: {resp.status_code} from {url}")
                if not (200 <= resp.status_code < 300):
                    error_body_bytes = await resp.aread()
                    error_text = error_body_bytes.decode("utf-8", errors="replace")
                    logger.error(f"RID-{request_id}: Upstream API request FAILED! Status: {resp.status_code}, URL: {url}, Response: {error_text[:1000]}") # Log more of the error
                    try:
                        # Attempt to parse upstream error as JSON
                        upstream_error_data = orjson.loads(error_text)
                        err_msg_detail = upstream_error_data.get("error", {}).get("message") or \
                                         upstream_error_data.get("message", f"Upstream returned status {resp.status_code} with non-JSON error.")
                        yield orjson_dumps_bytes_wrapper({"type": "error", "message": err_msg_detail, "upstream_status": resp.status_code})
                    except orjson.JSONDecodeError:
                        # If upstream error is not JSON, send a generic one
                        yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Upstream error {resp.status_code}: {error_text[:200]}", "upstream_status": resp.status_code})
                    return # Stop generation after error

                upstream_request_successful = True # Mark as successful to avoid generic error later
                async for chunk in resp.aiter_raw():
                    if not chunk: continue
                    buffer.extend(chunk)
                    while True:
                        line_end_idx = buffer.find(b'\n')
                        if line_end_idx == -1: break # Not enough data for a full line yet
                        
                        line_bytes_to_process = buffer[:line_end_idx]
                        buffer = buffer[line_end_idx+1:] # Remove processed line from buffer
                        
                        if not line_bytes_to_process.strip(): # Skip empty lines
                            # logger.debug(f"RID-{request_id}: Skipping empty line in stream.")
                            continue

                        # logger.debug(f"RID-{request_id}: Processing line: {line_bytes_to_process[:100].decode('utf-8', 'replace')}")
                        if is_openai_provider:
                            async for formatted_chunk in process_sse_line(line_bytes_to_process):
                                yield formatted_chunk
                        else: # Google provider
                            async for formatted_chunk in process_google_sse_line(line_bytes_to_process):
                                yield formatted_chunk
                        # Introduce a very small delay to yield control, important in serverless
                        await asyncio.sleep(0.0001) # 0.1 ms
                    # After processing all full lines in buffer, another small delay
                    await asyncio.sleep(0.0001)

        except httpx.TimeoutException as e: # Specific timeout handling
            logger.error(f"RID-{request_id}: HTTPX TimeoutException while connecting or reading from {url}: {e}", exc_info=True)
            yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Upstream API timed out: {str(e)}"})
        except httpx.RequestError as e: # Other network errors
            logger.error(f"RID-{request_id}: HTTPX RequestError while connecting to {url}: {e}", exc_info=True)
            yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Network error contacting upstream API: {str(e)}"})
        except asyncio.CancelledError:
            logger.info(f"RID-{request_id}: Streaming request to {url} was cancelled by the client or due to timeout.")
            # Optionally, send a cancellation message if the protocol supports it
            # yield orjson_dumps_bytes_wrapper({"type": "status", "message": "Stream cancelled"})
        except Exception as e:
            logger.error(f"RID-{request_id}: Unexpected error in stream_generator for {url}: {type(e).__name__} - {e}", exc_info=True)
            # Only yield generic error if upstream request itself wasn't successful initially
            if not upstream_request_successful:
                 yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Internal backend streaming error: {str(e)}"})
        finally:
            logger.info(f"RID-{request_id}: Stream generator finished for request to {url}. Upstream success: {upstream_request_successful}")

    # === 3. 返回 FastAPI 的 StreamingResponse ===
    streaming_headers = COMMON_HEADERS.copy()
    streaming_headers["Content-Type"] = "text/event-stream; charset=utf-8"
    streaming_headers["Cache-Control"] = "no-cache"
    streaming_headers["Connection"] = "keep-alive"
    # X-Accel-Buffering is already in COMMON_HEADERS

    logger.info(f"RID-{request_id}: Returning StreamingResponse to client.")
    return StreamingResponse(
        stream_generator(),
        media_type="text/event-stream", # media_type in StreamingResponse sets Content-Type
        headers=streaming_headers
    )

# ==== 本地调试入口 ====
if __name__ == "__main__":
    import uvicorn

    # Get HOST and PORT from environment or use defaults
    APP_HOST = os.getenv("HOST", "0.0.0.0")
    APP_PORT = int(os.getenv("PORT", 8000))
    DEV_RELOAD = os.getenv("DEV_RELOAD", "false").lower() == "true"

    # Configure Uvicorn logging to match our format for consistency
    # Based on uvicorn.config.LOGGING_CONFIG
    log_config = {
        "version": 1,
        "disable_existing_loggers": False,
        "formatters": {
            "default": {
                "()": "uvicorn.logging.DefaultFormatter",
                "fmt": "%(asctime)s %(levelname)-8s [%(name)s:%(module)s:%(lineno)d] - %(message)s",
                "datefmt": "%Y-%m-%d %H:%M:%S",
                "use_colors": None, # Auto-detect or set True/False
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
                "stream": "ext://sys.stdout", # Access logs to stdout
            },
        },
        "loggers": {
            "EzTalkProxy": {"handlers": ["default"], "level": LOG_LEVEL_FROM_ENV, "propagate": False},
            "uvicorn": {"handlers": ["default"], "level": "INFO", "propagate": False}, # Uvicorn's own logs
            "uvicorn.error": {"handlers": ["default"], "level": "INFO", "propagate": False}, # App exceptions via uvicorn
            "uvicorn.access": {"handlers": ["access"], "level": "INFO", "propagate": False}, # Access logs
        },
    }
    # Update the root logger for any other unhandled logs
    # logging.getLogger().handlers = [log_config["handlers"]["default"]]
    # logging.getLogger().setLevel(LOG_LEVEL_FROM_ENV)


    logger.info(f"Starting Uvicorn server locally on http://{APP_HOST}:{APP_PORT}")
    logger.info(f"Development reload is {'ENABLED' if DEV_RELOAD else 'DISABLED'}")
    logger.info(f"Log level set to: {LOG_LEVEL_FROM_ENV}")

    uvicorn.run(
        "main:app", # refers to main.py and the app object
        host=APP_HOST,
        port=APP_PORT,
        log_config=log_config,
        reload=DEV_RELOAD,
        # lifespan="on" # Uvicorn >= 0.20.0 enables lifespan by default if present on app
    )
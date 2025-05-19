import os
import orjson
from fastapi import FastAPI, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, JSONResponse
from pydantic import BaseModel, Field
from typing import List, Dict, Any, Literal, Optional, AsyncGenerator, Union
import httpx
import logging
from contextlib import asynccontextmanager
import asyncio
import datetime
from dotenv import load_dotenv
load_dotenv()

from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

LOG_LEVEL_FROM_ENV = os.getenv("LOG_LEVEL", "INFO").upper()
numeric_level = getattr(logging, LOG_LEVEL_FROM_ENV, logging.INFO)
logging.basicConfig(
    level=numeric_level,
    format='%(asctime)s %(levelname)-8s [%(name)s:%(module)s:%(lineno)d] - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger("EzTalkProxy")
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("httpcore").setLevel(logging.WARNING)
logging.getLogger("uvicorn.access").setLevel(logging.WARNING)
logging.getLogger("uvicorn.error").setLevel(logging.INFO)
logging.getLogger("googleapiclient.discovery_cache").setLevel(logging.ERROR)

GOOGLE_API_KEY = os.getenv("GOOGLE_API_KEY")
GOOGLE_CSE_ID = os.getenv("GOOGLE_CSE_ID")

if not GOOGLE_API_KEY:
    logger.critical("CRITICAL: GOOGLE_API_KEY is not set. Google Web Search will FAIL.")
if not GOOGLE_CSE_ID:
    logger.critical("CRITICAL: GOOGLE_CSE_ID is not set. Google Web Search will FAIL.")

http_client: Optional[httpx.AsyncClient] = None

@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    global http_client
    logger.info("Lifespan: Initializing HTTP client...")
    try:
        api_timeout_str = os.getenv("API_TIMEOUT", "300")
        read_timeout_str = os.getenv("READ_TIMEOUT", "60.0")
        max_connections_str = os.getenv("MAX_CONNECTIONS", "200")
        api_timeout = int(api_timeout_str)
        read_timeout = float(read_timeout_str)
        max_connections = int(max_connections_str)
        logger.info(f"Lifespan: HTTP Client Config - API Timeout: {api_timeout}s, Read Timeout: {read_timeout}s, Max Connections: {max_connections}")
        http_client = httpx.AsyncClient(
            timeout=httpx.Timeout(api_timeout, read=read_timeout),
            limits=httpx.Limits(max_connections=max_connections),
            http2=True, follow_redirects=True, trust_env=False
        )
        logger.info("Lifespan: HTTP client initialized successfully.")
    except ValueError as ve:
        logger.error(f"Lifespan: HTTP client init failed (ValueError): {ve}", exc_info=True)
        http_client = None
    except Exception as e:
        logger.error(f"Lifespan: HTTP client init failed (Unexpected error): {e}", exc_info=True)
        http_client = None
    yield
    logger.info("Lifespan: Shutting down HTTP client...")
    if http_client:
        try:
            await http_client.aclose()
            logger.info("Lifespan: HTTP client closed.")
        except Exception as e:
            logger.error(f"Lifespan: Error closing HTTP client: {e}", exc_info=True)
        finally:
            http_client = None
    else:
        logger.info("Lifespan: HTTP client was not initialized or already closed.")
    logger.info("Lifespan: Shutdown complete.")

APP_VERSION = "1.9.9.16"
app = FastAPI(
    title="EzTalk Proxy",
    description=f"Proxy for OpenAI, Google Gemini, etc., with Web Search. Version: {APP_VERSION}",
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
logger.info(f"FastAPI EzTalk Proxy v{APP_VERSION} initialized with CORS.")

DEFAULT_OPENAI_API_BASE_URL = "https://api.openai.com"
GOOGLE_API_BASE_URL = "https://generativelanguage.googleapis.com"
OPENAI_COMPATIBLE_PATH = "/v1/chat/completions"
COMMON_HEADERS = {"X-Accel-Buffering": "no"}
MAX_SSE_LINE_LENGTH = 24 * 1024
SEARCH_RESULT_COUNT = 5
SEARCH_SNIPPET_MAX_LENGTH = 300
THINKING_PROCESS_SEPARATOR = "[[[FINAL-ANSWER]]]"

class OpenAIToolCallFunction(BaseModel):
    name: Optional[str] = None
    arguments: Optional[str] = None

class OpenAIToolCall(BaseModel):
    index: Optional[int] = None
    id: Optional[str] = None
    type: Optional[Literal["function"]] = "function"
    function: OpenAIToolCallFunction

class ApiMessage(BaseModel):
    role: str
    content: Optional[str] = None
    name: Optional[str] = None
    tool_call_id: Optional[str] = None
    tool_calls: Optional[List[OpenAIToolCall]] = None

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
    tool_choice: Optional[Union[str, Dict[str, Any]]] = None
    use_web_search: Optional[bool] = Field(None, alias="useWebSearch")
    force_google_reasoning_prompt: Optional[bool] = Field(None, alias="forceGoogleReasoningPrompt")

def orjson_dumps_bytes_wrapper(data: Any) -> bytes:
    return orjson.dumps(data, option=orjson.OPT_NON_STR_KEYS | orjson.OPT_PASSTHROUGH_DATETIME | orjson.OPT_APPEND_NEWLINE)

def error_response(code: int, msg: str, headers: Optional[Dict[str, str]] = None) -> JSONResponse:
    final_headers = COMMON_HEADERS.copy()
    final_headers.update(headers or {})
    logger.warning(f"Responding error {code}: {msg}")
    return JSONResponse(
        status_code=code,
        content={"error": {"message": msg, "code": code, "type": "proxy_error"}},
        headers=final_headers,
    )

def extract_sse_lines(buffer: bytearray):
    lines = []
    start = 0
    while True:
        idx = buffer.find(b'\n', start)
        if idx == -1:
            break
        line = buffer[start:idx].removesuffix(b'\r')
        if len(line) > MAX_SSE_LINE_LENGTH:
            logger.warning(f"SSE line too long ({len(line)} bytes), skipping.")
        else:
            lines.append(line)
        start = idx + 1
    return lines, buffer[start:]

def _convert_openai_tools_to_gemini_declarations(openai_tools: List[Dict[str, Any]], request_id: str) -> List[Dict[str, Any]]:
    declarations = []
    if not openai_tools: return []
    for tool_def in openai_tools:
        if tool_def.get("type") == "function" and "function" in tool_def:
            func_spec = tool_def["function"]
            declaration = {k: v for k, v in {"name": func_spec.get("name"), "description": func_spec.get("description"), "parameters": func_spec.get("parameters")}.items() if v is not None}
            if declaration.get("name"): declarations.append(declaration)
            else: logger.warning(f"RID-{request_id}: Google tool conversion: Tool definition missing name: {func_spec}")
    return declarations

def _convert_openai_tool_choice_to_gemini_tool_config(openai_tool_choice: Union[str, Dict[str, Any]], gemini_declarations: List[Dict[str, Any]], request_id: str) -> Optional[Dict[str, Any]]:
    if not openai_tool_choice: return None
    mode = "AUTO"; allowed_function_names = []
    if isinstance(openai_tool_choice, str):
        if openai_tool_choice == "none": mode = "NONE"
        elif openai_tool_choice == "auto": mode = "AUTO"
        elif openai_tool_choice == "required": mode = "ANY" if gemini_declarations else "AUTO"
        else: logger.warning(f"RID-{request_id}: Google tool_choice: Unsupported str value '{openai_tool_choice}', defaulting to AUTO."); mode = "AUTO"
    elif isinstance(openai_tool_choice, dict) and openai_tool_choice.get("type") == "function":
        func_name = openai_tool_choice.get("function", {}).get("name")
        if func_name:
            if any(decl["name"] == func_name for decl in gemini_declarations): mode = "ANY"; allowed_function_names = [func_name]
            else: logger.warning(f"RID-{request_id}: Google tool_choice: Specified func '{func_name}' not in declared tools. Defaulting to AUTO."); mode = "AUTO"
        else: mode = "AUTO"
    else: logger.warning(f"RID-{request_id}: Google tool_choice: Invalid format {openai_tool_choice}. Defaulting to AUTO."); mode = "AUTO"
    function_calling_config = {"mode": mode}
    if mode == "ANY" and allowed_function_names: function_calling_config["allowed_function_names"] = allowed_function_names
    return {"function_calling_config": function_calling_config}

def _convert_api_messages_to_gemini_contents(messages: List[ApiMessage], request_id: str) -> List[Dict[str, Any]]:
    gemini_contents = []
    for msg in messages:
        if msg.role == "user": gemini_contents.append({"role": "user", "parts": [{"text": msg.content or ""}]})
        elif msg.role == "system":
            if msg.content: gemini_contents.append({"role": "user", "parts": [{"text": f"[System Instruction or Context]\n{msg.content}"}]})
        elif msg.role == "assistant":
            parts = []
            if msg.content is not None: parts.append({"text": msg.content})
            if msg.tool_calls:
                for tc in msg.tool_calls:
                    if tc.type == "function" and tc.function.name and tc.function.arguments is not None:
                        try: args_obj = orjson.loads(tc.function.arguments)
                        except orjson.JSONDecodeError: logger.warning(f"RID-{request_id}: Google msg conversion: Invalid JSON args for '{tc.function.name}'. Args: {tc.function.arguments[:100]}"); args_obj = {}
                        parts.append({"functionCall": {"name": tc.function.name, "args": args_obj}})
                    else: logger.warning(f"RID-{request_id}: Google msg conversion: Incomplete assistant tool_call: {tc.model_dump_json(exclude_none=True)}")
            if parts: gemini_contents.append({"role": "model", "parts": parts})
        elif msg.role == "tool":
            if msg.name and msg.content is not None:
                try: response_obj = orjson.loads(msg.content)
                except orjson.JSONDecodeError: logger.error(f"RID-{request_id}: Google msg conversion: 'tool' content for '{msg.name}' not valid JSON: {msg.content[:100]}"); continue
                gemini_contents.append({"role": "user", "parts": [{"functionResponse": {"name": msg.name, "response": response_obj}}]})
            else: logger.warning(f"RID-{request_id}: Google msg conversion: 'tool' message missing 'name' or 'content': {msg.model_dump_json(exclude_none=True)}")
    return gemini_contents

async def process_openai_sse_line_standard(line_bytes: bytes, request_id: str) -> AsyncGenerator[bytes, None]:
    if not line_bytes.startswith(b"data: ") or line_bytes.endswith(b"[DONE]"): return
    raw_data = line_bytes[len(b"data: "):].strip()
    if not raw_data: return
    try:
        data = orjson.loads(raw_data)
        current_time_iso = datetime.datetime.utcnow().isoformat() + "Z"
        for choice in data.get('choices', []):
            delta = choice.get('delta', {})
            if "tool_calls" in delta and delta["tool_calls"]: yield orjson_dumps_bytes_wrapper({"type": "tool_calls_chunk", "data": delta["tool_calls"], "timestamp": current_time_iso})
            if "reasoning_content" in delta and delta["reasoning_content"]: yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": delta["reasoning_content"], "timestamp": current_time_iso})
            if "content" in delta and delta["content"] is not None: yield orjson_dumps_bytes_wrapper({"type": "content", "text": delta["content"], "timestamp": current_time_iso})
            finish_reason = choice.get("finish_reason") or delta.get("finish_reason")
            if finish_reason: yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish_reason, "timestamp": current_time_iso})
    except orjson.JSONDecodeError: logger.warning(f"RID-{request_id}: OpenAI SSE (standard): JSON parse error. Data: {raw_data[:100]!r}")
    except Exception as e: logger.error(f"RID-{request_id}: OpenAI SSE (standard): Error processing line: {e}", exc_info=True)

async def process_google_sse_line_standard(line_bytes: bytes, request_id: str) -> AsyncGenerator[bytes, None]:
    if not line_bytes.startswith(b"data: "): return
    raw_data = line_bytes[len(b"data: "):].strip()
    if not raw_data: return
    try:
        data = orjson.loads(raw_data)
        current_time_iso = datetime.datetime.utcnow().isoformat() + "Z"
        for candidate in data.get('candidates', []):
            text_content = ""; function_calls_parts = []
            if candidate.get("content", {}).get("parts"):
                for part in candidate["content"]["parts"]:
                    if "text" in part: text_content += part["text"]
                    if "functionCall" in part: function_calls_parts.append(part["functionCall"])
            if text_content: yield orjson_dumps_bytes_wrapper({"type": "content", "text": text_content, "timestamp": current_time_iso})
            for fc_part in function_calls_parts:
                proxy_fc_id = f"gemini_fc_{os.urandom(4).hex()}"
                yield orjson_dumps_bytes_wrapper({"type": "google_function_call_request", "id": proxy_fc_id, "name": fc_part.get("name"), "arguments_obj": fc_part.get("args", {}), "timestamp": current_time_iso})
            finish_reason = candidate.get('finishReason')
            if finish_reason: yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish_reason, "timestamp": current_time_iso})
    except orjson.JSONDecodeError: logger.warning(f"RID-{request_id}: Google SSE (standard): JSON parse error. Data: {raw_data[:100]!r}")
    except Exception as e: logger.error(f"RID-{request_id}: Google SSE (standard): Error processing line: {e}", exc_info=True)

@app.get("/health", status_code=200, include_in_schema=False)
async def health_check():
    return {"status": "ok" if http_client else "warning", "detail": "HTTP client " + ("initialized" if http_client else "not initialized")}

@app.post("/chat", response_class=StreamingResponse, summary="Proxy for AI Chat Completions", tags=["AI Proxy"])
async def chat_proxy(request_data: ChatRequest, client: Optional[httpx.AsyncClient] = Depends(lambda: http_client)):
    request_id = os.urandom(8).hex()
    logger.info(f"RID-{request_id}: Received /chat for {request_data.provider} model {request_data.model}. WebSearch: {request_data.use_web_search}, ForceGoogleReasoning: {request_data.force_google_reasoning_prompt}")
    if not client: return error_response(503, "Service unavailable: HTTP client not initialized.")

    payload_template: Dict[str, Any] = {}
    url: str = ""
    headers: Dict[str, str] = {"Content-Type": "application/json"}
    params: Optional[Dict[str, str]] = None
    current_llm_messages_or_contents = [m.model_dump(exclude_none=True) for m in request_data.messages]
    user_query_for_search = ""

    if request_data.use_web_search and GOOGLE_API_KEY and GOOGLE_CSE_ID:
        for msg_obj in reversed(request_data.messages):
            if msg_obj.role == "user" and msg_obj.content:
                user_query_for_search = msg_obj.content.strip()
                break
        if not user_query_for_search:
            logger.warning(f"RID-{request_id}: Web search requested, but no user query found.")
    elif request_data.use_web_search:
        logger.warning(f"RID-{request_id}: Web search requested, but GOOGLE_API_KEY or GOOGLE_CSE_ID not configured.")

    try:
        if request_data.provider == "openai":
            base = request_data.api_address.strip() if request_data.api_address else DEFAULT_OPENAI_API_BASE_URL
            url = f"{base.rstrip('/')}{OPENAI_COMPATIBLE_PATH}"
            headers["Authorization"] = f"Bearer {request_data.api_key}"
            payload_template = {"model": request_data.model, "stream": True}
            if request_data.temperature is not None:
                payload_template["temperature"] = request_data.temperature
            if request_data.top_p is not None:
                payload_template["top_p"] = request_data.top_p
            if request_data.max_tokens is not None:
                payload_template["max_tokens"] = request_data.max_tokens
            if request_data.tools:
                payload_template["tools"] = request_data.tools
            if request_data.tool_choice:
                payload_template["tool_choice"] = request_data.tool_choice
        elif request_data.provider == "google":
            url = f"{GOOGLE_API_BASE_URL}/v1beta/models/{request_data.model}:streamGenerateContent"
            params = {"key": request_data.api_key, "alt": "sse"}
            payload_template = {}
            gemini_declarations = _convert_openai_tools_to_gemini_declarations(request_data.tools or [], request_id)
            if gemini_declarations:
                payload_template["tools"] = [{"functionDeclarations": gemini_declarations}]
            if request_data.tool_choice:
                gemini_tool_config = _convert_openai_tool_choice_to_gemini_tool_config(request_data.tool_choice, gemini_declarations, request_id)
                if gemini_tool_config:
                    payload_template["toolConfig"] = gemini_tool_config
            generation_config = {k:v for k,v in {"temperature": request_data.temperature, "topP": request_data.top_p, "maxOutputTokens": request_data.max_tokens}.items() if v is not None}
            if generation_config:
                payload_template["generationConfig"] = generation_config
        else:
            return error_response(400, f"Invalid provider: {request_data.provider}")
    except Exception as e:
        logger.error(f"RID-{request_id}: Error preparing initial payload_template: {e}", exc_info=True)
        return error_response(500, f"Internal error during request preparation: {str(e)}")


    async def stream_generator() -> AsyncGenerator[bytes, None]:
        buffer = bytearray()
        upstream_ok = False
        search_results_this_stream: List[Dict[str, str]] = []
        apply_guided_reasoning_logic = False
        found_reasoning_separator = False
        accumulated_text_for_reasoning = ""

        def get_current_time_iso(): return datetime.datetime.utcnow().isoformat() + "Z"

        try:
            if request_data.use_web_search and GOOGLE_API_KEY and GOOGLE_CSE_ID and user_query_for_search:
                yield orjson_dumps_bytes_wrapper({"type": "status_update", "stage": "web_indexing_started", "timestamp": get_current_time_iso()})
                async def perform_web_search_google(query: str, request_id: str, num_results: int = SEARCH_RESULT_COUNT) -> List[Dict[str, str]]:
                    results = []
                    if not GOOGLE_API_KEY or not GOOGLE_CSE_ID: return results
                    if not query: return results
                    try:
                        def search_sync():
                            service = build("customsearch", "v1", developerKey=GOOGLE_API_KEY, cache_discovery=False)
                            res = service.cse().list(q=query, cx=GOOGLE_CSE_ID, num=min(num_results, 10)).execute()
                            return res.get('items', [])
                        search_items = await asyncio.to_thread(search_sync)
                        for i, item in enumerate(search_items):
                            snippet = item.get('snippet', 'N/A')
                            if len(snippet) > SEARCH_SNIPPET_MAX_LENGTH:
                                snippet = snippet[:SEARCH_SNIPPET_MAX_LENGTH] + "..."
                            results.append({"index": i + 1, "title": item.get('title', 'N/A'), "href": item.get('link', 'N/A'), "snippet": snippet})
                    except HttpError as e:
                        logger.error(f"RID-{request_id}: Google Web Search HttpError for '{query}': {e.resp.status} {e._get_reason()}", exc_info=False)
                        try: content = orjson.loads(e.content); error_details = content.get("error", {}).get("message", "Unknown Google API error")
                        except: error_details = "Could not parse Google API error content."
                        logger.error(f"RID-{request_id}: Google API error details: {error_details}")
                    except Exception as search_exc:
                        logger.error(f"RID-{request_id}: Google Web Search failed for '{query}': {search_exc}", exc_info=True)
                    return results

                search_results_this_stream = await perform_web_search_google(user_query_for_search, request_id)
                if search_results_this_stream:
                    search_context_parts = [f"你可以结合以下最新网页搜索结果，用于辅助你的回答：'{user_query_for_search}'。优先整合如下信息："]
                    for res_idx, res_item in enumerate(search_results_this_stream):
                        search_context_parts.append(
                            f"{res_item.get('index', res_idx + 1)}. 标题: {res_item.get('title', 'N/A')}\n   摘要: {res_item.get('snippet', 'N/A')}\n   来源(仅供AI理解，不要直接引用): {res_item.get('href', 'N/A')}"
                        )
                    search_context_msg_content = "\n\n".join(search_context_parts)
                    system_search_context_msg_dict = ApiMessage(role="system", content=search_context_msg_content).model_dump(exclude_none=True)
                    last_user_idx_search = next((i for i, msg_d in reversed(list(enumerate(current_llm_messages_or_contents))) if msg_d.get("role") == "user"), -1)
                    if last_user_idx_search != -1:
                        current_llm_messages_or_contents.insert(last_user_idx_search, system_search_context_msg_dict)
                    else:
                        current_llm_messages_or_contents.insert(0, system_search_context_msg_dict)
            if request_data.use_web_search and search_results_this_stream:
                yield orjson_dumps_bytes_wrapper({"type": "web_search_results", "results": search_results_this_stream, "timestamp": get_current_time_iso()})
            if request_data.use_web_search and user_query_for_search:
                yield orjson_dumps_bytes_wrapper({"type": "status_update", "stage": "web_analysis_started", "timestamp": get_current_time_iso()})

            # === 分流条件 ===
            model_name_lower = request_data.model.lower()
            force = request_data.force_google_reasoning_prompt

            if force is True:
                apply_guided_reasoning_logic = True
            elif force is False:
                apply_guided_reasoning_logic = False
            elif any(tag in model_name_lower for tag in ["gemini", "pro", "thinking", "flash"]):
                apply_guided_reasoning_logic = True
            else:
                apply_guided_reasoning_logic = False

            # -------- 推理分隔指令优化 --------
            instruction_text = (
                f"\n\nFirst, think out loud and explain your reasoning step by step. Do not give the final answer yet. "
                f"Only after you have finished your detailed reasoning, output a single separate line with only '{THINKING_PROCESS_SEPARATOR}', "
                f"then write your final structured answer below this line. "
                f"Do not repeat this separator more than once, and do not show any instructions to the user."
                                )   

            if apply_guided_reasoning_logic:
                last_user_idx_prompt = next((i for i, msg_d in reversed(list(enumerate(current_llm_messages_or_contents))) if msg_d.get("role") == "user"), -1)
                if last_user_idx_prompt != -1:
                    current_llm_messages_or_contents[last_user_idx_prompt]["content"] = (
                        (current_llm_messages_or_contents[last_user_idx_prompt].get("content", "") or "") + instruction_text
                    )
                    logger.info(f"RID-{request_id}: Injected reasoning prompt to model.")
                else:
                    logger.warning(f"RID-{request_id}: No user message to inject reasoning prompt. Deactivating guided reasoning.")
                    apply_guided_reasoning_logic = False

            final_api_payload = payload_template.copy()
            if request_data.provider == "openai":
                final_api_payload["messages"] = current_llm_messages_or_contents
            elif request_data.provider == "google":
                final_api_payload["contents"] = _convert_api_messages_to_gemini_contents([ApiMessage(**msg) for msg in current_llm_messages_or_contents], request_id)
                for key in ["tools", "toolConfig", "generationConfig"]:
                    if key in payload_template:
                        final_api_payload[key] = payload_template[key]
            logger.info(f"RID-{request_id}: POST to {url}. Provider: {request_data.provider}. Applying Guided Reasoning: {apply_guided_reasoning_logic}")

            first_chunk_received = False
            async with client.stream("POST", url, headers=headers, json=final_api_payload, params=params) as resp:
                logger.info(f"RID-{request_id}: LLM Upstream status: {resp.status_code}")
                if not (200 <= resp.status_code < 300):
                    err_body = await resp.aread()
                    err_text = err_body.decode("utf-8", errors="replace")
                    logger.error(f"RID-{request_id}: LLM Upstream error {resp.status_code}: {err_text[:500]}")
                    try:
                        err_data = orjson.loads(err_text)
                        msg = err_data.get("error", {}).get("message", err_text[:200])
                    except:
                        msg = err_text[:200]
                    yield orjson_dumps_bytes_wrapper({"type": "error", "message": msg, "upstream_status": resp.status_code, "timestamp": get_current_time_iso()})
                    yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "error", "timestamp": get_current_time_iso()})
                    return
                upstream_ok = True

                def split_once_by_sep(text, sep):
                    idx = text.find(sep)
                    if idx == -1:
                        return text, None
                    before = text[:idx]
                    after = text[idx+len(sep):]
                    return before, after

                async for raw_chunk_bytes in resp.aiter_raw():
                    if not raw_chunk_bytes:
                        continue
                    if not first_chunk_received:
                        if request_data.use_web_search and user_query_for_search:
                            yield orjson_dumps_bytes_wrapper({"type": "status_update", "stage": "web_analysis_complete", "timestamp": get_current_time_iso()})
                        first_chunk_received = True

                    buffer.extend(raw_chunk_bytes)
                    sse_lines, buffer = extract_sse_lines(buffer)
                    for sse_line_bytes in sse_lines:
                        if not sse_line_bytes.strip():
                            continue

                        # ========== 推理和结论分隔 ==========
                        if apply_guided_reasoning_logic:
                            sse_data_bytes = b""
                            if sse_line_bytes.startswith(b"data: "): sse_data_bytes = sse_line_bytes[len(b"data: "):].strip()
                            if not sse_data_bytes:
                                continue

                            current_parsing_provider = request_data.provider
                            if current_parsing_provider == "openai" and sse_data_bytes.strip() == b"[DONE]":
                                if not found_reasoning_separator and accumulated_text_for_reasoning.strip():
                                    yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": accumulated_text_for_reasoning, "timestamp": get_current_time_iso()})
                                    accumulated_text_for_reasoning = ""
                                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "stop", "timestamp": get_current_time_iso()})
                                continue

                            try:
                                parsed_data = orjson.loads(sse_data_bytes)
                                text_delta = ""
                                finish_reason = None
                                openai_tool_calls = None
                                google_fc_req = None

                                if current_parsing_provider == "openai":
                                    for choice in parsed_data.get('choices', []):
                                        delta = choice.get('delta', {})
                                        if "content" in delta and delta["content"] is not None:
                                            text_delta += delta["content"]
                                        if "tool_calls" in delta and delta["tool_calls"]:
                                            openai_tool_calls = delta["tool_calls"]
                                        finish_reason = choice.get("finish_reason") or delta.get("finish_reason")
                                elif current_parsing_provider == "google":
                                    for cand in parsed_data.get('candidates', []):
                                        if cand.get("content", {}).get("parts"):
                                            for part in cand["content"]["parts"]:
                                                if "text" in part:
                                                    text_delta += part["text"]
                                                if "functionCall" in part:
                                                    google_fc_req = part["functionCall"]
                                        finish_reason = cand.get('finishReason')

                                current_acc = accumulated_text_for_reasoning + text_delta

                                if not found_reasoning_separator:
                                    sep_idx = current_acc.find(THINKING_PROCESS_SEPARATOR)
                                    if sep_idx != -1:
                                        found_reasoning_separator = True
                                        before, after = split_once_by_sep(current_acc, THINKING_PROCESS_SEPARATOR)
                                        if before.strip():
                                            yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": before, "timestamp": get_current_time_iso()})
                                        content_after = after
                                        while content_after and THINKING_PROCESS_SEPARATOR in content_after:
                                            before_more, after_more = split_once_by_sep(content_after, THINKING_PROCESS_SEPARATOR)
                                            if before_more.strip():
                                                yield orjson_dumps_bytes_wrapper({"type": "content", "text": before_more, "timestamp": get_current_time_iso()})
                                            content_after = after_more
                                        if content_after and content_after.strip():
                                            yield orjson_dumps_bytes_wrapper({"type": "content", "text": content_after, "timestamp": get_current_time_iso()})
                                        accumulated_text_for_reasoning = ""
                                    else:
                                        accumulated_text_for_reasoning = current_acc
                                        if text_delta:
                                            yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": text_delta, "timestamp": get_current_time_iso()})
                                else:
                                    if text_delta:
                                        yield orjson_dumps_bytes_wrapper({"type": "content", "text": text_delta, "timestamp": get_current_time_iso()})

                                is_reasoning_tool_call = not found_reasoning_separator
                                if openai_tool_calls:
                                    yield orjson_dumps_bytes_wrapper({"type": "tool_calls_chunk", "data": openai_tool_calls, "timestamp": get_current_time_iso(), "is_reasoning_step": is_reasoning_tool_call})
                                if google_fc_req:
                                    fcid = f"gemini_fc_{os.urandom(4).hex()}"
                                    yield orjson_dumps_bytes_wrapper({"type": "google_function_call_request", "id": fcid, "name": google_fc_req.get("name"), "arguments_obj": google_fc_req.get("args", {}), "timestamp": get_current_time_iso(), "is_reasoning_step": is_reasoning_tool_call})
                                if finish_reason:
                                    if not found_reasoning_separator and accumulated_text_for_reasoning.strip():
                                        yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": accumulated_text_for_reasoning, "timestamp": get_current_time_iso()})
                                    yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish_reason, "timestamp": get_current_time_iso()})
                            except orjson.JSONDecodeError:
                                logger.warning(f"RID-{request_id}: SSE (guided/{current_parsing_provider}) JSON parse error. Data: {sse_data_bytes[:100]!r}")
                            except Exception as e_proc_guided_inner:
                                logger.error(f"RID-{request_id}: Error processing SSE (guided/{current_parsing_provider}) chunk: {e_proc_guided_inner}", exc_info=True)
                        else:
                            if request_data.provider == "openai":
                                async for fmt_chunk in process_openai_sse_line_standard(sse_line_bytes, request_id):
                                    yield fmt_chunk
                            elif request_data.provider == "google":
                                async for fmt_chunk in process_google_sse_line_standard(sse_line_bytes, request_id):
                                    yield fmt_chunk
                    await asyncio.sleep(0.0001)

                if buffer:
                    remaining_data_str = buffer.strip().decode('utf-8', errors='ignore')
                    buffer.clear()
                    if remaining_data_str:
                        if apply_guided_reasoning_logic:
                            final_str = accumulated_text_for_reasoning + remaining_data_str
                            if not found_reasoning_separator:
                                sep_idx_final = final_str.find(THINKING_PROCESS_SEPARATOR)
                                if sep_idx_final != -1:
                                    before, after = split_once_by_sep(final_str, THINKING_PROCESS_SEPARATOR)
                                    if before.strip():
                                        yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": before, "timestamp": get_current_time_iso()})
                                    if after and after.strip():
                                        yield orjson_dumps_bytes_wrapper({"type": "content", "text": after, "timestamp": get_current_time_iso()})
                                else:
                                    if final_str.strip():
                                        yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": final_str, "timestamp": get_current_time_iso()})
                            else:
                                if final_str.strip():
                                    yield orjson_dumps_bytes_wrapper({"type": "content", "text": final_str, "timestamp": get_current_time_iso()})

        except httpx.TimeoutException as e:
            logger.error(f"RID-{request_id}: Timeout: {e}", exc_info=True)
            yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Upstream timeout: {str(e)}", "timestamp": get_current_time_iso()})
            yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "timeout_error", "timestamp": get_current_time_iso()})
        except httpx.RequestError as e:
            logger.error(f"RID-{request_id}: Network error: {e}", exc_info=True)
            yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Upstream network error: {str(e)}", "timestamp": get_current_time_iso()})
            yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "network_error", "timestamp": get_current_time_iso()})
        except asyncio.CancelledError:
            logger.info(f"RID-{request_id}: Stream cancelled.")
        except Exception as e:
            logger.error(f"RID-{request_id}: Unexpected streaming error: {e}", exc_info=True)
            if not upstream_ok:
                yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Internal streaming error: {str(e)}", "timestamp": get_current_time_iso()})
                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "internal_error", "timestamp": get_current_time_iso()})
        finally:
            if apply_guided_reasoning_logic and not found_reasoning_separator and accumulated_text_for_reasoning.strip():
                yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": accumulated_text_for_reasoning, "timestamp": get_current_time_iso()})
            logger.info(f"RID-{request_id}: Stream generator finished. Upstream: {upstream_ok}. Applied Guided Reasoning: {apply_guided_reasoning_logic}, Separator Found: {found_reasoning_separator if apply_guided_reasoning_logic else 'N/A'}")

    return StreamingResponse(
        stream_generator(),
        media_type="text/event-stream",
        headers={"Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache", "Connection": "keep-alive", **COMMON_HEADERS}
    )

if __name__ == "__main__":
    import uvicorn
    APP_HOST = os.getenv("HOST", "0.0.0.0")
    APP_PORT = int(os.getenv("PORT", 8000))
    DEV_RELOAD = os.getenv("DEV_RELOAD", "false").lower() == "true"
    log_config = uvicorn.config.LOGGING_CONFIG
    log_config["formatters"]["default"]["fmt"] = "%(asctime)s %(levelname)-8s [%(name)s:%(module)s:%(lineno)d] - %(message)s"
    log_config["formatters"]["default"]["datefmt"] = "%Y-%m-%d %H:%M:%S"
    log_config["formatters"]["access"]["fmt"] = '%(asctime)s %(levelname)-8s [%(name)s:%(module)s:%(lineno)d] - %(client_addr)s - "%(request_line)s" %(status_code)s'
    log_config["formatters"]["access"]["datefmt"] = "%Y-%m-%d %H:%M:%S"
    if "EzTalkProxy" not in log_config["loggers"]:
        log_config["loggers"]["EzTalkProxy"] = {}
    log_config["loggers"]["EzTalkProxy"]["handlers"] = ["default"]
    log_config["loggers"]["EzTalkProxy"]["level"] = LOG_LEVEL_FROM_ENV
    log_config["loggers"]["EzTalkProxy"]["propagate"] = False
    log_config["loggers"]["uvicorn.error"]["level"] = "INFO"
    log_config["loggers"]["uvicorn.access"]["level"] = "WARNING"
    logger.info(f"Starting Uvicorn: http://{APP_HOST}:{APP_PORT}. Reload: {DEV_RELOAD}. Log Level (EzTalkProxy): {LOG_LEVEL_FROM_ENV}")
    uvicorn.run("main:app", host=APP_HOST, port=APP_PORT, log_config=log_config, reload=DEV_RELOAD, lifespan="on")

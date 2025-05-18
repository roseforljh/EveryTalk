import os
import orjson
from fastapi import FastAPI, Depends, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, JSONResponse
from pydantic import BaseModel, Field
from typing import List, Dict, Any, Literal, Optional, AsyncGenerator, Union
import httpx
import logging
from contextlib import asynccontextmanager
import asyncio
import datetime # 新增：用于生成时间戳

# import re # Removed as no longer needed

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

APP_VERSION = "1.9.9.5" # 版本号递增，标记本次修改
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

class OpenAIToolCallFunction(BaseModel): name: Optional[str] = None; arguments: Optional[str] = None
class OpenAIToolCall(BaseModel): index: Optional[int] = None; id: Optional[str] = None; type: Optional[Literal["function"]] = "function"; function: OpenAIToolCallFunction
class ApiMessage(BaseModel): role: str; content: Optional[str] = None; name: Optional[str] = None; tool_call_id: Optional[str] = None; tool_calls: Optional[List[OpenAIToolCall]] = None
class ChatRequest(BaseModel): api_address: Optional[str] = None; messages: List[ApiMessage]; provider: Literal["openai", "google"]; model: str; api_key: str; temperature: Optional[float] = Field(None, ge=0.0, le=2.0); top_p: Optional[float] = Field(None, ge=0.0, le=1.0); max_tokens: Optional[int] = Field(None, gt=0); tools: Optional[List[Dict[str, Any]]] = None; tool_choice: Optional[Union[str, Dict[str, Any]]] = None; use_web_search: Optional[bool] = Field(None, alias="useWebSearch")

def orjson_dumps_bytes_wrapper(data: Any) -> bytes:
    return orjson.dumps(data, option=orjson.OPT_NON_STR_KEYS | orjson.OPT_PASSTHROUGH_DATETIME | orjson.OPT_APPEND_NEWLINE)

def error_response(code: int, msg: str, headers: Optional[Dict[str, str]] = None) -> JSONResponse:
    final_headers = COMMON_HEADERS.copy()
    if headers:
        final_headers.update(headers)
    logger.warning(f"Responding error {code}: {msg}")
    return JSONResponse(status_code=code, content={"error": {"message": msg, "code": code, "type": "proxy_error"}}, headers=final_headers)

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

async def perform_web_search_google(query: str, request_id: str, num_results: int = SEARCH_RESULT_COUNT) -> List[Dict[str, str]]:
    logger.info(f"RID-{request_id}: Performing Google Web Search for query: '{query}' (max {num_results} results)")
    results = []
    if not GOOGLE_API_KEY or not GOOGLE_CSE_ID:
        logger.error(f"RID-{request_id}: Google API Key or CSE ID not configured.")
        return results
    if not query:
        logger.warning(f"RID-{request_id}: Google Web Search with empty query.")
        return results
    try:
        def search_sync():
            service = build("customsearch", "v1", developerKey=GOOGLE_API_KEY, cache_discovery=False)
            res = service.cse().list(q=query, cx=GOOGLE_CSE_ID, num=min(num_results, 10)).execute()
            return res.get('items', [])
        search_items = await asyncio.to_thread(search_sync)
        for i, item in enumerate(search_items): # Add index to search results
            snippet = item.get('snippet', 'N/A')
            if len(snippet) > SEARCH_SNIPPET_MAX_LENGTH:
                snippet = snippet[:SEARCH_SNIPPET_MAX_LENGTH] + "..."
            results.append({
                "index": i + 1, # 1-based index for display
                "title": item.get('title', 'N/A'),
                "href": item.get('link', 'N/A'),
                "snippet": snippet
            })
        logger.info(f"RID-{request_id}: Google Web Search successful, found {len(results)} results for '{query}'.")
    except HttpError as e:
        logger.error(f"RID-{request_id}: Google Web Search HttpError for '{query}': {e.resp.status} {e._get_reason()}", exc_info=False)
        try:
            content = orjson.loads(e.content)
            error_details = content.get("error", {}).get("message", "Unknown Google API error")
        except:
            error_details = "Could not parse Google API error content."
        logger.error(f"RID-{request_id}: Google API error details: {error_details}")
    except Exception as search_exc:
        logger.error(f"RID-{request_id}: Google Web Search failed for '{query}': {search_exc}", exc_info=True)
    return results

# --- (辅助函数 _convert_openai_tools_to_gemini_declarations, _convert_openai_tool_choice_to_gemini_tool_config, _convert_api_messages_to_gemini_contents, is_valid_real_url 保持不变) ---
def _convert_openai_tools_to_gemini_declarations(openai_tools: List[Dict[str, Any]], request_id: str) -> List[Dict[str, Any]]:
    declarations = []
    if not openai_tools:
        return []
    for tool_def in openai_tools:
        if tool_def.get("type") == "function" and "function" in tool_def:
            func_spec = tool_def["function"]
            declaration = {
                "name": func_spec.get("name"),
                "description": func_spec.get("description"),
                "parameters": func_spec.get("parameters")
            }
            declaration = {k: v for k, v in declaration.items() if v is not None}
            if declaration.get("name"):
                declarations.append(declaration)
            else:
                logger.warning(f"RID-{request_id}: Google tool conversion: Tool definition missing name: {func_spec}")
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
    for i, msg in enumerate(messages):
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
            elif not msg.content and not msg.tool_calls: logger.debug(f"RID-{request_id}: Google msg conversion: Assistant message empty. Original: {msg.model_dump_json(exclude_none=True)}")
        elif msg.role == "tool":
            if msg.name and msg.content is not None:
                try: response_obj = orjson.loads(msg.content)
                except orjson.JSONDecodeError: logger.error(f"RID-{request_id}: Google msg conversion: 'tool' content for '{msg.name}' not valid JSON: {msg.content[:100]}"); continue
                gemini_contents.append({"role": "user", "parts": [{"functionResponse": {"name": msg.name, "response": response_obj}}]})
            else: logger.warning(f"RID-{request_id}: Google msg conversion: 'tool' message missing 'name' or 'content': {msg.model_dump_json(exclude_none=True)}")
    return gemini_contents

def is_valid_real_url(url):
    """判断是不是 http:// 或 https:// 链接，且非 #"""
    return isinstance(url, str) and url.lower().startswith(("http://", "https://")) and url != "#"

async def process_openai_sse_line(line_bytes: bytes, request_id: str) -> AsyncGenerator[bytes, None]:
    if not line_bytes.startswith(b"data: ") or line_bytes.endswith(b"[DONE]"):
        return
    raw_data = line_bytes[len(b"data: "):].strip()
    if not raw_data:
        return
    try:
        data = orjson.loads(raw_data)
        for choice in data.get('choices', []):
            delta = choice.get('delta', {})

            if "tool_calls" in delta and delta["tool_calls"]:
                yield orjson_dumps_bytes_wrapper({"type": "tool_calls_chunk", "data": delta["tool_calls"], "timestamp": datetime.datetime.utcnow().isoformat() + "Z"}) # 新增时间戳

            if "reasoning_content" in delta and delta["reasoning_content"]: # 假设你的OpenAI兼容服务会返回这个
                yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": delta["reasoning_content"], "timestamp": datetime.datetime.utcnow().isoformat() + "Z"}) # 新增时间戳

            if "content" in delta and delta["content"] is not None:
                content_to_send = delta["content"]
                yield orjson_dumps_bytes_wrapper({"type": "content", "text": content_to_send, "timestamp": datetime.datetime.utcnow().isoformat() + "Z"}) # 新增时间戳

            finish_reason = choice.get("finish_reason") or delta.get("finish_reason")
            if finish_reason:
                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish_reason, "timestamp": datetime.datetime.utcnow().isoformat() + "Z"}) # 新增时间戳
    except orjson.JSONDecodeError:
        logger.warning(f"RID-{request_id}: OpenAI SSE: JSON parse error. Data: {raw_data[:100]!r}")
    except Exception as e:
        logger.error(f"RID-{request_id}: OpenAI SSE: Error processing line: {e}", exc_info=True)

async def process_google_sse_line(line_bytes: bytes, request_id: str) -> AsyncGenerator[bytes, None]:
    if not line_bytes.startswith(b"data: "): return
    raw_data = line_bytes[len(b"data: "):].strip()
    if not raw_data: return
    try:
        data = orjson.loads(raw_data)
        current_time_iso = datetime.datetime.utcnow().isoformat() + "Z" # 新增：获取当前时间
        for candidate in data.get('candidates', []):
            text_content = ""; function_calls_parts = []
            if candidate.get("content", {}).get("parts"):
                for part in candidate["content"]["parts"]:
                    if "text" in part: text_content += part["text"]
                    if "functionCall" in part: function_calls_parts.append(part["functionCall"])
            if text_content: yield orjson_dumps_bytes_wrapper({"type": "content", "text": text_content, "timestamp": current_time_iso}) # 新增时间戳
            for fc_part in function_calls_parts:
                proxy_fc_id = f"gemini_fc_{os.urandom(4).hex()}"
                yield orjson_dumps_bytes_wrapper({"type": "google_function_call_request", "id": proxy_fc_id, "name": fc_part.get("name"), "arguments_obj": fc_part.get("args", {}), "timestamp": current_time_iso}) # 新增时间戳
            finish_reason = candidate.get('finishReason')
            if finish_reason: yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish_reason, "timestamp": current_time_iso}) # 新增时间戳
    except orjson.JSONDecodeError: logger.warning(f"RID-{request_id}: Google SSE: JSON parse error. Data: {raw_data[:100]!r}")
    except Exception as e: logger.error(f"RID-{request_id}: Google SSE: Error processing line: {e}", exc_info=True)

@app.get("/health", status_code=200, include_in_schema=False)
async def health_check(): return {"status": "ok" if http_client else "warning", "detail": "HTTP client " + ("initialized" if http_client else "not initialized")}

@app.post("/chat", response_class=StreamingResponse, summary="Proxy for AI Chat Completions", tags=["AI Proxy"])
async def chat_proxy(request_data: ChatRequest, client: Optional[httpx.AsyncClient] = Depends(lambda: http_client)):
    request_id = os.urandom(8).hex()
    logger.info(f"RID-{request_id}: Received /chat for {request_data.provider} model {request_data.model}. WebSearch: {request_data.use_web_search}")
    if not client: return error_response(503, "Service unavailable: HTTP client not initialized.")

    payload: Dict[str, Any] = {}
    url: str = ""
    headers: Dict[str, str] = {"Content-Type": "application/json"}
    params: Optional[Dict[str, str]] = None
    is_openai_provider: bool = False
    processed_messages_dicts = [m.model_dump(exclude_none=True) for m in request_data.messages]
    
    # --- 网页搜索相关变量，确保它们在 stream_generator 的作用域内可被访问 ---
    user_query_for_search = ""
    # search_results_for_linking_this_request 在 stream_generator 内部被赋值和使用
    
    # --- 提取用户查询以供后续搜索使用 ---
    if request_data.use_web_search and GOOGLE_API_KEY and GOOGLE_CSE_ID:
        for msg_obj in reversed(request_data.messages):
            if msg_obj.role == "user" and msg_obj.content:
                user_query_for_search = msg_obj.content.strip()
                break
        if not user_query_for_search:
             logger.warning(f"RID-{request_id}: Web search requested, but no user query found to search for.")
    elif request_data.use_web_search:
        logger.warning(f"RID-{request_id}: Web search requested, but GOOGLE_API_KEY or GOOGLE_CSE_ID not configured.")


    # --- 构建发送给大模型的 payload ---
    # 注意：如果启用了网页搜索，processed_messages_dicts 将在下面 stream_generator 内部的搜索完成后被修改
    # 因此，payload 的构建也需要考虑到这一点。
    # 一个更清晰的做法可能是将 payload 的 messages 部分的最终确定也移到 stream_generator 内部，
    # 或者在 stream_generator 开始时就完成 messages 的最终构建。
    # 为保持与原结构相似，我们先按原样构建，但注意下面 stream_generator 中可能会修改 processed_messages_dicts
    try:
        if request_data.provider == "openai":
            is_openai_provider = True
            base = request_data.api_address.strip() if request_data.api_address else DEFAULT_OPENAI_API_BASE_URL
            url = f"{base.rstrip('/')}{OPENAI_COMPATIBLE_PATH}"
            headers["Authorization"] = f"Bearer {request_data.api_key}"
            # payload 的 messages 部分将在 stream_generator 中根据搜索结果动态调整
            payload = {"model": request_data.model, "stream": True} # messages 稍后添加
            if request_data.temperature is not None: payload["temperature"] = request_data.temperature
            if request_data.top_p is not None: payload["top_p"] = request_data.top_p
            if request_data.max_tokens is not None: payload["max_tokens"] = request_data.max_tokens
            if request_data.tools: payload["tools"] = request_data.tools
            if request_data.tool_choice: payload["tool_choice"] = request_data.tool_choice
        elif request_data.provider == "google":
            is_openai_provider = False
            url = f"{GOOGLE_API_BASE_URL}/v1beta/models/{request_data.model}:streamGenerateContent"
            params = {"key": request_data.api_key, "alt": "sse"}
            # payload 的 contents 部分也将在 stream_generator 中动态调整
            payload = {} # contents 和其他配置稍后添加
            gemini_declarations = []
            if request_data.tools:
                gemini_declarations = _convert_openai_tools_to_gemini_declarations(request_data.tools, request_id)
                if gemini_declarations: payload["tools"] = [{"functionDeclarations": gemini_declarations}]
            if request_data.tool_choice:
                gemini_tool_config = _convert_openai_tool_choice_to_gemini_tool_config(request_data.tool_choice, gemini_declarations, request_id)
                if gemini_tool_config: payload["toolConfig"] = gemini_tool_config
            generation_config = {}
            if request_data.temperature is not None: generation_config["temperature"] = request_data.temperature
            if request_data.top_p is not None: generation_config["topP"] = request_data.top_p
            if request_data.max_tokens is not None: generation_config["maxOutputTokens"] = request_data.max_tokens
            if generation_config: payload["generationConfig"] = generation_config
        else:
            return error_response(400, f"Invalid provider: {request_data.provider}")
    except Exception as e:
        logger.error(f"RID-{request_id}: Error preparing initial payload for {request_data.provider}: {e}", exc_info=True)
        return error_response(500, f"Internal error during request preparation: {str(e)}")


    async def stream_generator() -> AsyncGenerator[bytes, None]:
        buffer = bytearray()
        upstream_ok = False
        search_results_this_stream: List[Dict[str, str]] = [] # 用于当前流的搜索结果
        
        # processed_messages_dicts_for_llm 是将要发送给LLM的最终消息列表
        # 它基于初始的 processed_messages_dicts，并可能因搜索结果而修改
        processed_messages_dicts_for_llm = [msg.copy() for msg in processed_messages_dicts] # 创建副本以进行修改

        def get_current_time_iso(): # 辅助函数获取ISO格式时间戳
            return datetime.datetime.utcnow().isoformat() + "Z"

        try:
            # 阶段 1: 开始进行网页搜索 (如果需要)
            if request_data.use_web_search and GOOGLE_API_KEY and GOOGLE_CSE_ID and user_query_for_search:
                logger.info(f"RID-{request_id}: Yielding 'web_indexing_started' event.")
                yield orjson_dumps_bytes_wrapper({
                    "type": "status_update",
                    "stage": "web_indexing_started",
                    "timestamp": get_current_time_iso()
                })

                # 执行实际的网页搜索
                search_results_this_stream = await perform_web_search_google(user_query_for_search, request_id)
                
                # 如果有搜索结果，则修改 messages 发送给LLM
                if search_results_this_stream:
                    logger.info(f"RID-{request_id}: Google Web search successful, {len(search_results_this_stream)} results. Modifying LLM messages.")
                    search_context_parts = [
                        f"You are an AI assistant. Please use the following web search results to inform your answer to the user's query: '{user_query_for_search}'. Search Results:"
                    ]
                    for res_idx, res_item in enumerate(search_results_this_stream): # 确保 res_item 有 'index' key
                         search_context_parts.append(f"{res_item.get('index', res_idx + 1)}. Title: {res_item.get('title', 'N/A')}\n   Snippet: {res_item.get('snippet', 'N/A')}\n   Source URL (for your reference, do not output directly): {res_item.get('href', 'N/A')}")
                    search_context_msg_content = "\n\n".join(search_context_parts)
                    system_search_context_msg_dict = ApiMessage(role="system", content=search_context_msg_content).model_dump(exclude_none=True)
                    
                    # (这里的插入逻辑与您原来代码中将搜索结果注入processed_messages_dicts的逻辑一致)
                    model_name_lower = request_data.model.lower()
                    is_deepseek_reasoner_like = ("deepseek-reasoner" in model_name_lower or "deepseek" in model_name_lower) and request_data.provider == "openai"
                    if is_deepseek_reasoner_like:
                        if processed_messages_dicts_for_llm and processed_messages_dicts_for_llm[0].get("role") == "system":
                            existing_system_content = processed_messages_dicts_for_llm[0].get("content", "")
                            processed_messages_dicts_for_llm[0]["content"] = f"{search_context_msg_content}\n\n{existing_system_content}".strip()
                        else:
                            processed_messages_dicts_for_llm.insert(0, system_search_context_msg_dict)
                    else:
                        last_user_msg_idx = next((i for i, msg_d in reversed(list(enumerate(processed_messages_dicts_for_llm))) if msg_d.get("role") == "user"), -1)
                        if last_user_msg_idx != -1:
                            processed_messages_dicts_for_llm.insert(last_user_msg_idx, system_search_context_msg_dict)
                        else:
                            processed_messages_dicts_for_llm.insert(0, system_search_context_msg_dict)
                    logger.info(f"RID-{request_id}: LLM messages modified with web search context.")
                else:
                    logger.info(f"RID-{request_id}: No Google Web search results for '{user_query_for_search}', or search failed. LLM messages not modified with search context.")


            # 阶段 2: 发送网页搜索结果给客户端 (如果搜索被执行且有结果)
            if request_data.use_web_search and search_results_this_stream: # 检查 search_results_this_stream
                logger.info(f"RID-{request_id}: Yielding 'web_search_results' event with {len(search_results_this_stream)} items.")
                yield orjson_dumps_bytes_wrapper({
                    "type": "web_search_results",
                    "results": search_results_this_stream, # 使用 stream 内获取的结果
                    "timestamp": get_current_time_iso()
                })

            # 阶段 3: 准备开始让大模型分析处理 (分析阶段开始)
            if request_data.use_web_search and user_query_for_search : # 只有当确实执行了搜索意图时才发送
                logger.info(f"RID-{request_id}: Yielding 'web_analysis_started' event.")
                yield orjson_dumps_bytes_wrapper({
                    "type": "status_update",
                    "stage": "web_analysis_started",
                    "timestamp": get_current_time_iso()
                })
            
            # --- 为LLM API调用准备最终的payload ---
            final_llm_payload = payload.copy() # 从外部作用域复制基础payload
            if request_data.provider == "openai":
                final_llm_payload["messages"] = processed_messages_dicts_for_llm
            elif request_data.provider == "google":
                temp_api_messages_for_google_conversion = [ApiMessage(**msg_dict) for msg_dict in processed_messages_dicts_for_llm]
                final_llm_payload["contents"] = _convert_api_messages_to_gemini_contents(temp_api_messages_for_google_conversion, request_id)


            # --- 实际调用大模型API ---
            logger.info(f"RID-{request_id}: POST to {url} for LLM. Provider: {request_data.provider}")
            first_llm_chunk_received = False

            async with client.stream("POST", url, headers=headers, json=final_llm_payload, params=params) as resp:
                logger.info(f"RID-{request_id}: LLM Upstream status: {resp.status_code}")
                if not (200 <= resp.status_code < 300):
                    err_body = await resp.aread(); err_text = err_body.decode("utf-8", errors="replace")
                    logger.error(f"RID-{request_id}: LLM Upstream error {resp.status_code}: {err_text[:500]}")
                    try: err_data = orjson.loads(err_text); msg = err_data.get("error", {}).get("message", err_text[:200])
                    except: msg = err_text[:200]
                    yield orjson_dumps_bytes_wrapper({"type": "error", "message": msg, "upstream_status": resp.status_code, "timestamp": get_current_time_iso()})
                    yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "error", "timestamp": get_current_time_iso()})
                    return

                upstream_ok = True
                async for chunk in resp.aiter_raw():
                    if not chunk: continue

                    if not first_llm_chunk_received:
                        # 阶段 4: 大模型分析/思考完成，即将输出内容 (仅当使用了web search时有意义)
                        if request_data.use_web_search and user_query_for_search:
                            logger.info(f"RID-{request_id}: Yielding 'web_analysis_complete' event (first LLM chunk received).")
                            yield orjson_dumps_bytes_wrapper({
                                "type": "status_update",
                                "stage": "web_analysis_complete",
                                "timestamp": get_current_time_iso()
                            })
                        first_llm_chunk_received = True

                    buffer.extend(chunk)
                    lines, buffer = extract_sse_lines(buffer)
                    for line_bytes in lines:
                        if not line_bytes.strip(): continue
                        if is_openai_provider:
                            async for formatted_chunk in process_openai_sse_line(line_bytes, request_id):
                                yield formatted_chunk
                        else:
                            async for formatted_chunk in process_google_sse_line(line_bytes, request_id):
                                yield formatted_chunk
                    await asyncio.sleep(0.0001) # 避免忙等待，允许事件循环处理其他任务
                
                # 处理缓冲区中剩余的最后数据（如果有）
                if buffer:
                    line_bytes = buffer.strip()
                    if line_bytes:
                        if is_openai_provider:
                            async for formatted_chunk in process_openai_sse_line(line_bytes, request_id): yield formatted_chunk
                        else:
                            async for formatted_chunk in process_google_sse_line(line_bytes, request_id): yield formatted_chunk
        
        except httpx.TimeoutException as e:
            logger.error(f"RID-{request_id}: Timeout: {e}", exc_info=True)
            yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Upstream timeout: {str(e)}", "timestamp": get_current_time_iso()})
            yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "timeout_error", "timestamp": get_current_time_iso()})
        except httpx.RequestError as e:
            logger.error(f"RID-{request_id}: Network error: {e}", exc_info=True)
            yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Upstream network error: {str(e)}", "timestamp": get_current_time_iso()})
            yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "network_error", "timestamp": get_current_time_iso()})
        except asyncio.CancelledError:
            logger.info(f"RID-{request_id}: Stream cancelled by client or shutdown.")
        except Exception as e:
            logger.error(f"RID-{request_id}: Unexpected streaming error: {e}", exc_info=True)
            # 仅当尚未成功连接到上游时发送错误，避免在流中途出错时重复发送
            if not upstream_ok:
                yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Internal streaming error: {str(e)}", "timestamp": get_current_time_iso()})
                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "internal_error", "timestamp": get_current_time_iso()})
        finally:
            logger.info(f"RID-{request_id}: Stream generator finished. Upstream successful: {upstream_ok}")

    streaming_headers = COMMON_HEADERS.copy()
    streaming_headers.update({"Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache", "Connection": "keep-alive"})
    return StreamingResponse(stream_generator(), media_type="text/event-stream", headers=streaming_headers)

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

    if "EzTalkProxy" not in log_config["loggers"]: log_config["loggers"]["EzTalkProxy"] = {}
    log_config["loggers"]["EzTalkProxy"]["handlers"] = ["default"]
    log_config["loggers"]["EzTalkProxy"]["level"] = LOG_LEVEL_FROM_ENV
    log_config["loggers"]["EzTalkProxy"]["propagate"] = False
    
    log_config["loggers"]["uvicorn.error"]["level"] = "INFO" 
    log_config["loggers"]["uvicorn.access"]["level"] = "WARNING"

    logger.info(f"Starting Uvicorn: http://{APP_HOST}:{APP_PORT}. Reload: {DEV_RELOAD}. Log Level (EzTalkProxy): {LOG_LEVEL_FROM_ENV}")
    uvicorn.run("main:app", host=APP_HOST, port=APP_PORT, log_config=log_config, reload=DEV_RELOAD, lifespan="on")
    
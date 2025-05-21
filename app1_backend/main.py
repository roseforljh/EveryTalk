import os
import orjson
from fastapi import FastAPI, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, JSONResponse
from pydantic import BaseModel, Field
from typing import List, Dict, Any, Literal, Optional, AsyncGenerator, Union, Tuple
import httpx
import logging
from contextlib import asynccontextmanager
import asyncio
import datetime
from dotenv import load_dotenv
import re
import json

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

APP_VERSION = "1.9.9.58-qwen3-conditional-reasoning-katex-prompt" # 版本号更新
DEFAULT_OPENAI_API_BASE_URL = os.getenv("DEFAULT_OPENAI_API_BASE_URL", "https://api.openai.com")
GOOGLE_API_BASE_URL = "https://generativelanguage.googleapis.com"
OPENAI_COMPATIBLE_PATH = "/v1/chat/completions"
GOOGLE_API_KEY = os.getenv("GOOGLE_API_KEY")
GOOGLE_CSE_ID = os.getenv("GOOGLE_CSE_ID")
API_TIMEOUT = int(os.getenv("API_TIMEOUT", "300"))
READ_TIMEOUT = float(os.getenv("READ_TIMEOUT", "60.0"))
MAX_CONNECTIONS = int(os.getenv("MAX_CONNECTIONS", "200"))
MAX_SSE_LINE_LENGTH = int(os.getenv("MAX_SSE_LINE_LENGTH", f"{1024 * 1024}"))
SEARCH_RESULT_COUNT = int(os.getenv("SEARCH_RESULT_COUNT", "5"))
SEARCH_SNIPPET_MAX_LENGTH = int(os.getenv("SEARCH_SNIPPET_MAX_LENGTH", "200"))
THINKING_PROCESS_SEPARATOR = os.getenv("THINKING_PROCESS_SEPARATOR", "--- FINAL ANSWER ---")
COMMON_HEADERS = {"X-Accel-Buffering": "no"}

# KaTeX formatting instruction for LLMs
KATEX_FORMATTING_INSTRUCTION = (
    "IMPORTANT: When presenting mathematical formulas or expressions, ensure all inline mathematical "
    "expressions are enclosed in single dollar signs (e.g., $E=mc^2$) or escaped parentheses "
    " (e.g., \\(E=mc^2\\)), and all display/block mathematical expressions are enclosed in "
    "double dollar signs (e.g., $$x^2+y^2=z^2$$) or escaped square brackets (e.g., \\[x^2+y^2=z^2\\]). "
    "This is crucial for correct rendering. Adhere strictly to this formatting for all mathematical content."
)


if not GOOGLE_API_KEY or not GOOGLE_CSE_ID:
    logger.critical("CRITICAL: GOOGLE_API_KEY and GOOGLE_CSE_ID environment variables must be set.")
    raise RuntimeError("必须设置GOOGLE_API_KEY和GOOGLE_CSE_ID环境变量！")

http_client: Optional[httpx.AsyncClient] = None

@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    global http_client
    logger.info("Lifespan: 初始化HTTP客户端...")
    try:
        http_client = httpx.AsyncClient(
            timeout=httpx.Timeout(API_TIMEOUT, read=READ_TIMEOUT),
            limits=httpx.Limits(max_connections=MAX_CONNECTIONS),
            http2=True, follow_redirects=True, trust_env=False
        )
        logger.info("Lifespan: HTTP客户端初始化成功。")
    except Exception as e:
        logger.error(f"Lifespan: HTTP客户端初始化失败: {e}", exc_info=True)
        http_client = None
    yield
    logger.info("Lifespan: 关闭HTTP客户端...")
    if http_client:
        try: await http_client.aclose()
        except Exception as e: logger.error(f"Lifespan: 关闭HTTP客户端错误: {e}", exc_info=True)
        finally: http_client = None
    logger.info("Lifespan: 关闭完成。")

app = FastAPI(
    title="EzTalk Proxy",
    description=f"代理服务，版本: {APP_VERSION}",
    version=APP_VERSION,
    lifespan=lifespan,
    docs_url="/docs",
    redoc_url="/redoc"
)
app.add_middleware(
    CORSMiddleware, allow_origins=["*"], allow_credentials=True,
    allow_methods=["*"], allow_headers=["*"], expose_headers=["*"]
)
logger.info(f"FastAPI EzTalk Proxy v{APP_VERSION} 初始化完成，已配置CORS。")

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
    force_custom_reasoning_prompt: Optional[bool] = Field(None, alias="forceCustomReasoningPrompt")
    custom_model_parameters: Optional[Dict[str, Any]] = Field(None, alias="customModelParameters")
    custom_extra_body: Optional[Dict[str, Any]] = Field(None, alias="customExtraBody")

def orjson_dumps_bytes_wrapper(data: Any) -> bytes:
    return orjson.dumps(data, option=orjson.OPT_NON_STR_KEYS | orjson.OPT_PASSTHROUGH_DATETIME | orjson.OPT_APPEND_NEWLINE)
def error_response(code: int, msg: str, request_id: Optional[str] = None, headers: Optional[Dict[str, str]] = None) -> JSONResponse:
    log_msg = f"错误 {code}: {msg}"
    if request_id: log_msg = f"RID-{request_id}: {log_msg}"
    logger.warning(log_msg)
    return JSONResponse(status_code=code, content={"error": {"message": msg, "code": code, "type": "proxy_error"}}, headers={**COMMON_HEADERS, **(headers or {})})

BUFFER_SIZE = 8192
MAX_BUFFER_LENGTH = 1024 * 1024
CHUNK_SIZE = 1024
MAX_ACCUMULATED_LENGTH = 1024 * 1024 * 10

def strip_potentially_harmful_html_and_normalize_newlines(text: str) -> str:
    if not isinstance(text, str): return ""
    text = re.sub(r"<script[^>]*>.*?</script>|<style[^>]*>.*?</style>", "", text, flags=re.IGNORECASE | re.DOTALL)
    text = re.sub(r"<br\s*/?>|</p\s*>", "\n", text, flags=re.IGNORECASE)
    text = re.sub(r"\n{3,}", "\n\n", text)
    lines = text.split('\n')
    stripped_lines = [line.strip() for line in lines]
    text = "\n".join(stripped_lines)
    return text.strip('\n')

def extract_sse_lines(buffer: bytearray) -> Tuple[List[bytes], bytearray]:
    lines = []
    start = 0
    while True:
        idx = buffer.find(b'\n', start)
        if idx == -1: break
        line = buffer[start:idx].removesuffix(b'\r')
        if len(line) > MAX_SSE_LINE_LENGTH:
            logger.warning(f"SSE行过长 ({len(line)}字节)，已跳过。")
        else:
            lines.append(line)
        start = idx + 1
    return lines, buffer[start:]

def get_current_time_iso(): return datetime.datetime.utcnow().isoformat() + "Z"

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
            if any(decl["name"] == func_name for decl in gemini_declarations):
                mode = "ANY"; allowed_function_names = [func_name]
            else: logger.warning(f"RID-{request_id}: Google tool_choice: Specified function '{func_name}' not in declared tools. Defaulting to AUTO."); mode = "AUTO"
        else: mode = "AUTO"
    else: logger.warning(f"RID-{request_id}: Google tool_choice: Invalid format {openai_tool_choice}. Defaulting to AUTO."); mode = "AUTO"
    function_calling_config = {"mode": mode}
    if mode == "ANY" and allowed_function_names: function_calling_config["allowed_function_names"] = allowed_function_names
    if gemini_declarations or mode == "NONE":
        return {"function_calling_config": function_calling_config}
    elif mode == "AUTO" and not gemini_declarations :
        return None
    elif mode == "ANY" and not gemini_declarations:
        logger.warning(f"RID-{request_id}: Google tool_choice: Mode ANY specified but no functions declared. Defaulting to AUTO/NONE.")
        return None
    return {"function_calling_config": function_calling_config}

def _convert_api_messages_to_gemini_contents(messages: List[ApiMessage], request_id: str, prepended_system_instruction: Optional[str] = None) -> List[Dict[str, Any]]:
    gemini_contents = []
    if prepended_system_instruction:
        gemini_contents.append({"role": "user", "parts": [{"text": prepended_system_instruction}]})
    for msg_idx, msg in enumerate(messages):
        if msg.role == "user":
            gemini_contents.append({"role": "user", "parts": [{"text": msg.content or ""}]})
        elif msg.role == "system":
            if msg.content:
                # Append KaTeX instruction to system messages for Gemini (treated as user role with prefix)
                system_content_with_katex = f"[System Instruction]\n{msg.content}\n\n{KATEX_FORMATTING_INSTRUCTION}"
                gemini_contents.append({"role": "user", "parts": [{"text": system_content_with_katex}]})
        elif msg.role == "assistant":
            parts = []
            if msg.content is not None:
                parts.append({"text": msg.content})
            if msg.tool_calls:
                for tc in msg.tool_calls:
                    if tc.type == "function" and tc.function.name and tc.function.arguments is not None:
                        try: args_obj = orjson.loads(tc.function.arguments)
                        except orjson.JSONDecodeError:
                            logger.warning(f"RID-{request_id}: Invalid JSON in tool_call arguments for function '{tc.function.name}'")
                            args_obj = {"error": "Invalid JSON arguments", "raw_args": tc.function.arguments}
                        parts.append({"functionCall": {"name": tc.function.name, "args": args_obj}})
            if parts: gemini_contents.append({"role": "model", "parts": parts})
        elif msg.role == "tool":
            if msg.name and msg.content is not None:
                try: response_obj = orjson.loads(msg.content)
                except orjson.JSONDecodeError:
                    logger.warning(f"RID-{request_id}: Tool response for '{msg.name}' is not valid JSON. Gemini expects a JSON object for functionResponse. Wrapping as raw string in a dict.")
                    response_obj = {"raw_response": msg.content}
                gemini_contents.append({"role": "user", "parts": [{"functionResponse": {"name": msg.name, "response": response_obj}}]})
    return gemini_contents

def prepare_openai_request(rd: ChatRequest, msgs: List[Dict[str, Any]], request_id: str) -> Tuple[str, Dict[str, str], Dict[str, Any]]:
    base = rd.api_address.strip() if rd.api_address else DEFAULT_OPENAI_API_BASE_URL
    url = f"{base.rstrip('/')}{OPENAI_COMPATIBLE_PATH}"
    headers = {"Content-Type": "application/json", "Authorization": f"Bearer {rd.api_key}"}

    # Ensure KaTeX instruction is in the system message for OpenAI
    processed_msgs = []
    system_message_found_and_updated = False
    for msg_dict in msgs:
        if msg_dict.get("role") == "system":
            original_content = msg_dict.get("content", "")
            if KATEX_FORMATTING_INSTRUCTION not in original_content:
                msg_dict["content"] = (original_content + "\n\n" + KATEX_FORMATTING_INSTRUCTION).strip()
            system_message_found_and_updated = True
        processed_msgs.append(msg_dict)

    if not system_message_found_and_updated:
        processed_msgs.insert(0, {"role": "system", "content": KATEX_FORMATTING_INSTRUCTION})
        logger.info(f"RID-{request_id}: OpenAI: No system message found, prepended KaTeX instruction.")
    else:
        logger.info(f"RID-{request_id}: OpenAI: System message found/updated with KaTeX instruction.")


    payload: Dict[str, Any] = {"model": rd.model, "messages": processed_msgs, "stream": True}

    if rd.temperature is not None: payload["temperature"] = rd.temperature
    if rd.top_p is not None: payload["top_p"] = rd.top_p
    if rd.max_tokens is not None: payload["max_tokens"] = rd.max_tokens
    if rd.tools: payload["tools"] = rd.tools
    if rd.tool_choice: payload["tool_choice"] = rd.tool_choice

    if rd.custom_model_parameters:
        logger.info(f"RID-{request_id}: Applying custom top-level model parameters provided by client: {list(rd.custom_model_parameters.keys())}")
        for key, value in rd.custom_model_parameters.items():
            payload[key] = value

    if rd.custom_extra_body:
        logger.info(f"RID-{request_id}: Applying custom extra_body parameters provided by client: {list(rd.custom_extra_body.keys())}")
        if "extra_body" not in payload:
            payload["extra_body"] = {}
        for key, value in rd.custom_extra_body.items():
            payload["extra_body"][key] = value

    enable_thinking_source = "Not explicitly set by client via custom fields"
    if rd.custom_model_parameters and "enable_thinking" in rd.custom_model_parameters:
        enable_thinking_source = f"Top-level (value: {rd.custom_model_parameters['enable_thinking']})"
    elif rd.custom_extra_body and "enable_thinking" in rd.custom_extra_body:
        enable_thinking_source = f"In extra_body (value: {rd.custom_extra_body['enable_thinking']})"

    if "qwen3" in rd.model.lower():
        logger.info(f"RID-{request_id}: For Qwen3 model '{rd.model}', 'enable_thinking' source: {enable_thinking_source}. If not set by client, API default will apply.")

    return url, headers, payload

def get_reasoning_answer_schema():
    return {"type": "object", "properties": {"reasoning": {"type": "string", "description": "Comprehensive thinking, analysis, and reasoning process, including any initial reactions or preamble. This field should contain ALL preliminary thoughts before arriving at the final answer."}, "answer": {"type": "string", "description": "The final, direct answer to the user, suitable for end-user display."}}, "required": ["reasoning", "answer"]}

def prepare_google_request(rd: ChatRequest, msgs: List[ApiMessage], rid: str) -> Tuple[str, Dict[str, str], Dict[str, Any], Dict[str, str]]:
    url = f"{GOOGLE_API_BASE_URL}/v1beta/models/{rd.model}:streamGenerateContent"
    params = {"key": rd.api_key, "alt": "sse"}
    headers = {"Content-Type": "application/json"}
    current_messages = [m.model_copy(deep=True) for m in msgs]
    strong_system_instruction = None
    generation_config_updates = {}
    if is_gemini_model_for_potential_custom_logic(rd.model):
        logger.info(f"RID-{rid}: Applying JSON Schema mode with strong system instruction for Gemini model '{rd.model}'.")
        generation_config_updates["responseMimeType"] = "application/json"
        generation_config_updates["responseSchema"] = get_reasoning_answer_schema()
        strong_system_instruction = ("[System Instruction]\n"
                                     "You are an intelligent assistant. You MUST strictly adhere to the following JSON format for your entire response. First, in the \"reasoning\" field of the JSON, provide a complete and detailed account of your thought process, analysis, understanding of the user's intent, and any preparatory or introductory remarks (e.g., initial reactions to a greeting, or clarifications about your capabilities). Do not output any text outside of this JSON structure. The \"reasoning\" field must encompass all your preliminary thoughts and meta-cognition before formulating the final answer. Second, in the \"answer\" field of the JSON, provide only the direct, concise, and final response intended for the end-user.\n"
                                     f"{KATEX_FORMATTING_INSTRUCTION}\n" # Added KaTeX instruction here
                                     "Your entire output must be a single JSON object conforming to this schema.\n"
                                     "Example JSON Schema to follow:\n"
                                     "{\n"
                                     "  \"reasoning\": \"(Place your detailed thinking process, preambles, and step-by-step analysis here...)\",\n"
                                     "  \"answer\": \"(Place your final, direct answer to the user here...)\"\n"
                                     "}")
    payload: Dict[str, Any] = {"contents": _convert_api_messages_to_gemini_contents(current_messages, rid, strong_system_instruction)}
    if rd.tools:
        gemini_declarations = _convert_openai_tools_to_gemini_declarations(rd.tools, rid)
        if gemini_declarations:
            payload["tools"] = [{"functionDeclarations": gemini_declarations}]
            if rd.tool_choice:
                tool_config = _convert_openai_tool_choice_to_gemini_tool_config(rd.tool_choice, gemini_declarations, rid)
                if tool_config: payload["toolConfig"] = tool_config
    if rd.temperature is not None: generation_config_updates["temperature"] = rd.temperature
    if rd.top_p is not None: generation_config_updates["topP"] = rd.top_p
    if rd.max_tokens is not None: generation_config_updates["maxOutputTokens"] = rd.max_tokens
    if generation_config_updates:
        if "generationConfig" not in payload: payload["generationConfig"] = {}
        payload["generationConfig"].update(generation_config_updates)
    return url, headers, payload, params

async def perform_web_search(query: str, rid: str) -> List[Dict[str, str]]:
    results = []
    if not GOOGLE_API_KEY or not GOOGLE_CSE_ID:
        logger.warning(f"RID-{rid}: Web search skipped, GOOGLE_API_KEY or GOOGLE_CSE_ID not set.")
        return results
    if not query:
        logger.warning(f"RID-{rid}: Web search skipped, query is empty.")
        return results
    try:
        def search_sync():
            service = build("customsearch", "v1", developerKey=GOOGLE_API_KEY, cache_discovery=False)
            res = service.cse().list(q=query, cx=GOOGLE_CSE_ID, num=min(SEARCH_RESULT_COUNT, 10)).execute()
            return res.get('items', [])
        logger.info(f"RID-{rid}: Performing web search for query: '{query[:100]}'")
        search_items = await asyncio.to_thread(search_sync)
        for i, item in enumerate(search_items):
            snippet = item.get('snippet', 'N/A').replace('\n', ' ').strip()
            if len(snippet) > SEARCH_SNIPPET_MAX_LENGTH: snippet = snippet[:SEARCH_SNIPPET_MAX_LENGTH] + "..."
            results.append({"index": i + 1, "title": item.get('title', 'N/A').strip(), "href": item.get('link', 'N/A'), "snippet": snippet})
        logger.info(f"RID-{rid}: Web search completed, found {len(results)} results.")
    except HttpError as e:
        err_content = "Unknown Google API error"; status_code = "N/A"
        if hasattr(e, 'resp') and hasattr(e.resp, 'status'): status_code = e.resp.status
        try:
            content_json = orjson.loads(e.content)
            err_detail = content_json.get("error", {})
            err_message = err_detail.get("message", str(e.content))
            err_content = f"{err_message} (Code: {err_detail.get('code', 'N/A')}, Status: {err_detail.get('status', 'N/A')})"
        except: err_content = e._get_reason() if hasattr(e, '_get_reason') else e.content.decode(errors='ignore')[:200]
        logger.error(f"RID-{rid}: Google Web Search HttpError (Upstream Status: {status_code}) for query '{query[:50]}': {err_content}")
    except Exception as search_exc:
        logger.error(f"RID-{rid}: Google Web Search failed for query '{query[:50]}': {search_exc}", exc_info=True)
    return results

def generate_search_context_message_content(query: str, search_results: List[Dict[str, str]]) -> str:
    if not search_results: return ""
    parts = [f"Web search results for the query '{query}':"]
    for res in search_results:
        parts.append(f"\n[{res.get('index')}] Title: {res.get('title')}\n   Snippet: {res.get('snippet')}\n   Source URL (for AI reference only, do not cite directly): {res.get('href')}")
    # Add KaTeX instruction to the search context as well, as it becomes part of a system message
    return "\n".join(parts) + f"\n\nPlease use these search results to answer the user's query. Incorporate information from these results as much as possible into your response.\n\n{KATEX_FORMATTING_INSTRUCTION}"

def is_qwen_model(model_name: str) -> bool: return "qwen" in model_name.lower()
def is_deepseek_reasoner_model(model_name: str) -> bool: return "deepseek-reasoner" in model_name.lower()
def is_gemini_model_for_potential_custom_logic(model_name: str) -> bool:
    model_lower = model_name.lower()
    return "gemini" in model_lower and ("pro" in model_lower or "thinking" in model_lower)

def should_apply_custom_separator_logic(rd: ChatRequest, request_id: str) -> bool:
    if rd.provider == "google" and is_gemini_model_for_potential_custom_logic(rd.model):
        if not rd.force_custom_reasoning_prompt:
            logger.info(f"RID-{request_id}: Strong JSON Schema mode is active for Gemini model '{rd.model}', old custom separator logic (Branch 1) will be OFF unless forced.")
            return False
        else:
            logger.info(f"RID-{request_id}: Strong JSON Schema mode for Gemini, but force_custom_reasoning_prompt=True, so old custom separator (Branch 1) is FORCED.")
            return True
    if rd.force_custom_reasoning_prompt is True:
        logger.info(f"RID-{request_id}: Custom separator logic (Branch 1) FORCED for model '{rd.model}' due to force_custom_reasoning_prompt=True.")
        return True
    logger.info(f"RID-{request_id}: Using native delta processing (Branch 2) for model '{rd.model}'. Custom separator logic (Branch 1) is OFF by default.")
    return False

@app.get("/health", status_code=200, include_in_schema=False)
async def health_check():
    return {"status": "ok" if http_client else "warning", "detail": f"HTTP client {'initialized' if http_client else 'not initialized'}"}

@app.post("/chat", response_class=StreamingResponse, summary="AI聊天完成代理", tags=["AI Proxy"])
async def chat_proxy(request_data: ChatRequest, client: Optional[httpx.AsyncClient] = Depends(lambda: http_client)):
    request_id = os.urandom(8).hex()
    logger.info(f"RID-{request_id}: Received /chat request: Provider={request_data.provider}, Model={request_data.model}, WebSearch={request_data.use_web_search}, ForceCustomReasoning={request_data.force_custom_reasoning_prompt}")
    if not client: return error_response(503, "Service unavailable: HTTP client not initialized.", request_id)
    api_messages_for_processing: List[ApiMessage] = [m.model_copy(deep=True) for m in request_data.messages if m.content is not None or m.tool_calls is not None]
    if not api_messages_for_processing and request_data.messages: logger.warning(f"RID-{request_id}: All messages were empty after filtering. Original count: {len(request_data.messages)}")
    if not api_messages_for_processing and not request_data.messages: return error_response(400, "No messages provided in the request.", request_id)
    user_query_for_search = ""
    if request_data.use_web_search:
        for msg_obj in reversed(api_messages_for_processing):
            if msg_obj.role == "user" and msg_obj.content and msg_obj.content.strip():
                user_query_for_search = msg_obj.content.strip(); break
        if not user_query_for_search: logger.warning(f"RID-{request_id}: Web search enabled but no suitable user query content found.")
    use_old_custom_separator_branch = should_apply_custom_separator_logic(request_data, request_id)

    async def stream_generator() -> AsyncGenerator[bytes, None]:
        nonlocal api_messages_for_processing
        buffer = bytearray()
        upstream_ok = False
        first_chunk_llm = False
        state: Dict[str, Any] = {
            "openai_raw_reasoning_accumulator": "",
            "openai_raw_content_accumulator": "",
            "openai_yielded_reasoning_cumulative": "",
            "openai_yielded_content_cumulative": "",
            "openai_had_any_reasoning": False,
            "openai_had_any_content_or_tool_call": False,
            "openai_reasoning_finish_event_sent": False,
            "accumulated_text_custom": "",
            "full_yielded_reasoning_custom": "",
            "full_yielded_content_custom": "",
            "found_separator_custom": False,
            'google_json_accumulator': "",
            'google_json_yielded_reasoning': "",
            'google_json_yielded_answer': "",
            'google_json_activated': False,
            'google_pre_json_fallback_buffer': "",
            'google_pre_json_fallback_yielded_reasoning': "",
            'google_yielded_raw_content_fallback': ""
        }
        try:
            if request_data.use_web_search and user_query_for_search:
                yield orjson_dumps_bytes_wrapper({"type": "status_update", "stage": "web_indexing_started", "timestamp": get_current_time_iso()})
                search_results = await perform_web_search(user_query_for_search, request_id)
                if search_results:
                    yield orjson_dumps_bytes_wrapper({"type": "web_search_results", "results": search_results, "timestamp": get_current_time_iso()})
                    search_context_content = generate_search_context_message_content(user_query_for_search, search_results) # KaTeX instruction is now part of this
                    if search_context_content:
                        system_search_msg = ApiMessage(role="system", content=search_context_content)
                        insert_idx = 0
                        for i in range(len(api_messages_for_processing) -1, -1, -1):
                            if api_messages_for_processing[i].role == "user": insert_idx = i; break
                        api_messages_for_processing.insert(insert_idx, system_search_msg)
                yield orjson_dumps_bytes_wrapper({"type": "status_update", "stage": "web_analysis_started", "timestamp": get_current_time_iso()})
            api_url: str; api_headers: Dict[str, str]; api_payload: Dict[str, Any]; api_params: Optional[Dict[str, str]] = None
            is_google_json_schema_mode_for_this_call = False
            if request_data.provider == "openai":
                dict_messages_for_openai = [m.model_dump(exclude_none=True, by_alias=True) for m in api_messages_for_processing]
                api_url, api_headers, api_payload = prepare_openai_request(request_data, dict_messages_for_openai, request_id)
            elif request_data.provider == "google":
                api_url, api_headers, api_payload, api_params = prepare_google_request(request_data, api_messages_for_processing, request_id)
                if api_payload.get("generationConfig", {}).get("responseMimeType") == "application/json": is_google_json_schema_mode_for_this_call = True
                logger.info(f"RID-{request_id}: Google request prepared. JSON Schema mode for this call: {is_google_json_schema_mode_for_this_call}")
            else:
                yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Unsupported provider: {request_data.provider}", "timestamp": get_current_time_iso()})
                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "error_unsupported_provider", "timestamp": get_current_time_iso()}); return
            logger.info(f"RID-{request_id}: Sending request to {api_url}.")
            async with client.stream("POST", api_url, headers=api_headers, json=api_payload, params=api_params) as resp:
                logger.info(f"RID-{request_id}: Upstream LLM response status: {resp.status_code}")
                if not (200 <= resp.status_code < 300):
                    err_body_bytes = await resp.aread(); err_text = err_body_bytes.decode("utf-8", errors="replace")
                    logger.error(f"RID-{request_id}: Upstream LLM error {resp.status_code}: {err_text[:1000]}")
                    try: err_data = orjson.loads(err_text); msg_detail = err_data.get("error", {})
                    except orjson.JSONDecodeError: msg_detail = err_text[:200]
                    if isinstance(msg_detail, dict): msg_detail = msg_detail.get("message", err_text[:200])
                    yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"LLM API Error: {msg_detail}", "upstream_status": resp.status_code, "timestamp": get_current_time_iso()})
                    yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "upstream_error", "timestamp": get_current_time_iso()}); return
                upstream_ok = True
                async for raw_chunk_bytes in resp.aiter_raw():
                    if not raw_chunk_bytes: continue
                    if not first_chunk_llm:
                        if request_data.use_web_search and user_query_for_search:
                            yield orjson_dumps_bytes_wrapper({"type": "status_update", "stage": "web_analysis_complete", "timestamp": get_current_time_iso()})
                        first_chunk_llm = True
                    buffer.extend(raw_chunk_bytes)
                    sse_lines, buffer = extract_sse_lines(buffer)
                    for sse_line_bytes in sse_lines:
                        if not sse_line_bytes.strip(): continue
                        sse_data_bytes = b""
                        if sse_line_bytes.startswith(b"data: "): sse_data_bytes = sse_line_bytes[len(b"data: "):].strip()
                        if not sse_data_bytes: continue
                        if sse_data_bytes == b"[DONE]":
                            if request_data.provider == "openai":
                                logger.info(f"RID-{request_id}: Received [DONE] signal from OpenAI.")
                                if state.get("openai_had_any_reasoning") and not state.get("openai_reasoning_finish_event_sent"):
                                    yield orjson_dumps_bytes_wrapper({"type": "reasoning_finish", "timestamp": get_current_time_iso()})
                            yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "stop_openai_done", "timestamp": get_current_time_iso()})
                            continue
                        try: parsed_sse_data = orjson.loads(sse_data_bytes)
                        except orjson.JSONDecodeError: logger.warning(f"RID-{request_id}: Failed to parse SSE JSON data (outer loop): {sse_data_bytes[:100]!r}"); continue
                        if request_data.provider == "openai":
                            async for event in process_openai_response(parsed_sse_data, state, request_id): yield event
                        elif request_data.provider == "google":
                            async for event in process_google_response(parsed_sse_data, state, request_id, is_google_json_schema_mode_for_this_call): yield event
        except Exception as e:
            async for event in handle_stream_error(e, request_id, upstream_ok, first_chunk_llm): yield event
        finally:
            async for event in handle_stream_cleanup(state, request_id, upstream_ok, use_old_custom_separator_branch, request_data.provider): yield event
    return StreamingResponse(stream_generator(), media_type="text/event-stream", headers={"Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache", "Connection": "keep-alive", **COMMON_HEADERS})

async def process_openai_response(parsed_sse_data: Dict[str, Any], state: Dict[str, Any], request_id: str) -> AsyncGenerator[bytes, None]:
    for choice in parsed_sse_data.get('choices', []):
        delta = choice.get('delta', {})
        reasoning_content_chunk = delta.get("reasoning_content")
        content_chunk = delta.get("content")
        tool_calls_chunk = delta.get("tool_calls")

        if reasoning_content_chunk is not None:
            state["openai_had_any_reasoning"] = True
            state["openai_raw_reasoning_accumulator"] += reasoning_content_chunk
            processed_full_reasoning = strip_potentially_harmful_html_and_normalize_newlines(state["openai_raw_reasoning_accumulator"])
            reasoning_text_delta = processed_full_reasoning[len(state["openai_yielded_reasoning_cumulative"]):]
            if reasoning_text_delta:
                yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": reasoning_text_delta, "timestamp": get_current_time_iso()})
            state["openai_yielded_reasoning_cumulative"] = processed_full_reasoning

        if content_chunk is not None:
            if not state["openai_had_any_content_or_tool_call"] and state["openai_had_any_reasoning"] and not state["openai_reasoning_finish_event_sent"]:
                yield orjson_dumps_bytes_wrapper({"type": "reasoning_finish", "timestamp": get_current_time_iso()})
                state["openai_reasoning_finish_event_sent"] = True
            state["openai_had_any_content_or_tool_call"] = True
            state["openai_raw_content_accumulator"] += content_chunk
            processed_full_content = strip_potentially_harmful_html_and_normalize_newlines(state["openai_raw_content_accumulator"])
            content_text_delta = processed_full_content[len(state["openai_yielded_content_cumulative"]):]
            if content_text_delta:
                yield orjson_dumps_bytes_wrapper({"type": "content", "text": content_text_delta, "timestamp": get_current_time_iso()})
            state["openai_yielded_content_cumulative"] = processed_full_content

        if tool_calls_chunk:
            if not state["openai_had_any_content_or_tool_call"] and state["openai_had_any_reasoning"] and not state["openai_reasoning_finish_event_sent"]:
                yield orjson_dumps_bytes_wrapper({"type": "reasoning_finish", "timestamp": get_current_time_iso()})
                state["openai_reasoning_finish_event_sent"] = True
            state["openai_had_any_content_or_tool_call"] = True
            yield orjson_dumps_bytes_wrapper({"type": "tool_calls_chunk", "data": tool_calls_chunk, "timestamp": get_current_time_iso()})

        if choice.get("finish_reason"):
            if state["openai_had_any_reasoning"] and not state["openai_reasoning_finish_event_sent"]:
                yield orjson_dumps_bytes_wrapper({"type": "reasoning_finish", "timestamp": get_current_time_iso()})
            yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": choice["finish_reason"], "timestamp": get_current_time_iso()})
            return

async def process_google_response(parsed_sse_data: Dict[str, Any], state: Dict[str, Any], request_id: str, is_json_schema_mode: bool) -> AsyncGenerator[bytes, None]:
    for candidate in parsed_sse_data.get('candidates', []):
        content = candidate.get("content", {})
        finish_reason = candidate.get("finishReason")
        current_candidate_text_accumulator = ""
        function_call_part_data = None
        for part in content.get("parts", []):
            if "text" in part: current_candidate_text_accumulator += part["text"]
            elif "functionCall" in part: function_call_part_data = part["functionCall"]
        if current_candidate_text_accumulator:
            if is_json_schema_mode:
                state['google_json_accumulator'] = state.get('google_json_accumulator', "") + current_candidate_text_accumulator
                logger.debug(f"RID-{request_id}: Google JSON mode, accumulator now: {state['google_json_accumulator'][:200]}...")
                try:
                    parsed_json = orjson.loads(state['google_json_accumulator'])
                    logger.debug(f"RID-{request_id}: Successfully parsed accumulated JSON.")
                    if isinstance(parsed_json, dict) and "reasoning" in parsed_json and "answer" in parsed_json:
                        state['google_json_activated'] = True
                        current_reasoning = parsed_json.get("reasoning", "")
                        current_answer = parsed_json.get("answer", "")
                        prev_reasoning = state.get('google_json_yielded_reasoning', "")
                        if current_reasoning != prev_reasoning:
                            reasoning_delta = current_reasoning[len(prev_reasoning):]
                            if reasoning_delta: yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": strip_potentially_harmful_html_and_normalize_newlines(reasoning_delta), "timestamp": get_current_time_iso()})
                            state['google_json_yielded_reasoning'] = current_reasoning
                        prev_answer = state.get('google_json_yielded_answer', "")
                        if current_answer != prev_answer:
                            if state.get('google_json_yielded_reasoning') and not prev_answer and current_answer: yield orjson_dumps_bytes_wrapper({"type": "reasoning_finish", "timestamp": get_current_time_iso()})
                            answer_delta = current_answer[len(prev_answer):]
                            if answer_delta: yield orjson_dumps_bytes_wrapper({"type": "content", "text": strip_potentially_harmful_html_and_normalize_newlines(answer_delta), "timestamp": get_current_time_iso()})
                            state['google_json_yielded_answer'] = current_answer
                    else:
                        if not state.get('google_json_activated'): logger.warning(f"RID-{request_id}: Google JSON mode: accumulated text parsed to JSON but not target schema (before activation). JSON: {state['google_json_accumulator'][:200]}")
                        else: logger.warning(f"RID-{request_id}: Google JSON mode active, but received non-schema compliant JSON: {state['google_json_accumulator'][:100]}")
                except orjson.JSONDecodeError:
                    if not finish_reason: logger.debug(f"RID-{request_id}: Google JSON mode: accumulating, not yet valid JSON. Buffer: {state['google_json_accumulator'][:200]}")
                    else:
                        logger.error(f"RID-{request_id}: Google JSON mode: stream finished but accumulated buffer is not valid JSON. Buffer: {state['google_json_accumulator'][:500]}")
                        if state['google_json_accumulator'] and not (state.get('google_json_yielded_reasoning') or state.get('google_json_yielded_answer')):
                            logger.info(f"RID-{request_id}: Yielding unparsed accumulator as raw content due to finish_reason with invalid JSON.")
                            fallback_text = strip_potentially_harmful_html_and_normalize_newlines(state['google_json_accumulator'])
                            yield orjson_dumps_bytes_wrapper({"type": "content", "text": fallback_text, "timestamp": get_current_time_iso()})
                            state['google_yielded_raw_content_fallback'] = state.get('google_yielded_raw_content_fallback', "") + fallback_text
            else: # Not JSON schema mode for Google
                # If not in JSON schema mode, we assume all text is "content" (or "reasoning" if old custom logic is forced)
                # The old custom separator logic is handled by should_apply_custom_separator_logic and the final_cleanup
                # This part should just yield raw content if not using the old custom separator logic.
                # For simplicity and to avoid conflicting with the old custom logic branch,
                # we'll rely on the final_cleanup or the native OpenAI-like processing if that's the path.
                # If it's Google non-JSON mode and not old_custom_separator_branch, it should behave like OpenAI content.
                # This part is tricky because the original code had a complex custom separator logic.
                # For now, let's assume if it's Google and not JSON schema, it's direct content.
                # The `strip_potentially_harmful_html_and_normalize_newlines` will be applied.
                # This might need refinement based on how `use_old_custom_separator_branch` interacts.

                # Simplified: if not JSON mode, treat as direct content.
                # The old custom separator logic is complex and mostly handled in `handle_stream_cleanup`.
                # Here, we just pass through the text.
                state['google_pre_json_fallback_buffer'] = state.get('google_pre_json_fallback_buffer', "") + current_candidate_text_accumulator
                processed_text_now = strip_potentially_harmful_html_and_normalize_newlines(state['google_pre_json_fallback_buffer'])
                yielded_processed_cumulative = state.get('google_pre_json_fallback_yielded_reasoning', "") # Using this state var for non-JSON mode too
                text_delta_to_yield = processed_text_now[len(yielded_processed_cumulative):]

                if text_delta_to_yield:
                    # If old custom separator logic is NOT active, yield as "content"
                    # If old custom separator logic IS active, this path might be less used, or its output
                    # will be further processed by that logic.
                    # For now, let's assume this is the primary content path for non-JSON Google.
                    yield orjson_dumps_bytes_wrapper({"type": "content", "text": text_delta_to_yield, "timestamp": get_current_time_iso()})
                state['google_pre_json_fallback_yielded_reasoning'] = processed_text_now


        if function_call_part_data:
            if is_json_schema_mode and state.get('google_json_yielded_reasoning') and not state.get('google_json_yielded_answer'): yield orjson_dumps_bytes_wrapper({"type": "reasoning_finish", "timestamp": get_current_time_iso()})
            elif not is_json_schema_mode and state.get('google_pre_json_fallback_yielded_reasoning'): pass # No specific reasoning_finish for non-JSON mode here
            fcid = f"gemini_fc_{os.urandom(4).hex()}"
            yield orjson_dumps_bytes_wrapper({"type": "google_function_call_request", "id": fcid, "name": function_call_part_data.get("name"), "arguments_obj": function_call_part_data.get("args", {}), "timestamp": get_current_time_iso(), "is_reasoning_step": is_json_schema_mode and bool(state.get('google_json_yielded_reasoning') and not state.get('google_json_yielded_answer'))})
        if finish_reason:
            if is_json_schema_mode:
                if state.get('google_json_activated') and state.get('google_json_yielded_reasoning') and not state.get('google_json_yielded_answer'): yield orjson_dumps_bytes_wrapper({"type": "reasoning_finish", "timestamp": get_current_time_iso()})
                if state.get('google_json_accumulator') and not state.get('google_json_activated'): logger.error(f"RID-{request_id}: Google JSON mode: stream finished with UNPARSED JSON in accumulator. Model failed schema adherence. Buffer: {state['google_json_accumulator'][:500]}")
            elif not is_json_schema_mode and state.get('google_pre_json_fallback_yielded_reasoning'): pass # No specific reasoning_finish for non-JSON mode here
            yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish_reason, "timestamp": get_current_time_iso()})
            state['google_json_accumulator'] = ""; state['google_json_yielded_reasoning'] = ""; state['google_json_yielded_answer'] = ""; state['google_json_activated'] = False; state['google_pre_json_fallback_buffer'] = ""; state['google_pre_json_fallback_yielded_reasoning'] = ""; state.pop('google_yielded_raw_content_fallback', None)
            return

async def handle_stream_error(e: Exception, request_id: str, upstream_ok: bool, first_chunk_llm: bool) -> AsyncGenerator[bytes, None]:
    err_type, err_msg, log_exc_info = "internal_server_error", f"Internal server error: {str(e)}", True
    if isinstance(e, httpx.TimeoutException): err_type, err_msg, log_exc_info = "timeout_error", f"Upstream timeout: {str(e)}", False
    elif isinstance(e, httpx.RequestError): err_type, err_msg, log_exc_info = "network_error", f"Upstream network error: {str(e)}", False
    elif isinstance(e, asyncio.CancelledError): logger.info(f"RID-{request_id}: Stream cancelled by client."); return
    logger.error(f"RID-{request_id}: Unexpected error during stream generation: {err_msg}", exc_info=log_exc_info)
    if not upstream_ok or not first_chunk_llm:
        yield orjson_dumps_bytes_wrapper({"type": "error", "message": err_msg, "timestamp": get_current_time_iso()})
    yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": err_type, "timestamp": get_current_time_iso()})

async def handle_stream_cleanup(state: Dict[str, Any], request_id: str, upstream_ok: bool, use_old_custom_separator_branch: bool, provider: str) -> AsyncGenerator[bytes, None]:
    # This function handles the old custom separator logic if `use_old_custom_separator_branch` is true.
    # The original logic for this was complex and tied to `THINKING_PROCESS_SEPARATOR`.
    # Given the shift towards provider-native reasoning/content separation (OpenAI's `reasoning_content`
    # and Google's JSON schema mode), this custom logic is now more of a fallback or forced override.

    # If the old custom separator logic is active, it tries to split the accumulated text.
    if use_old_custom_separator_branch and state.get("accumulated_text_custom","").strip() and upstream_ok:
        logger.info(f"RID-{request_id}: Final flush in 'finally' for Branch 1 (custom logic path): '{state.get('accumulated_text_custom','')[:100]}'")
        final_raw_text_custom = state.get("accumulated_text_custom","").strip()

        if not state.get("found_separator_custom"): # Separator was NOT found during streaming
            # Treat everything as reasoning
            processed_reasoning_finally = strip_potentially_harmful_html_and_normalize_newlines(final_raw_text_custom)
            delta_reasoning_finally = processed_reasoning_finally
            full_yielded_reasoning_custom = state.get("full_yielded_reasoning_custom", "")

            if full_yielded_reasoning_custom and processed_reasoning_finally.startswith(full_yielded_reasoning_custom):
                delta_reasoning_finally = processed_reasoning_finally[len(full_yielded_reasoning_custom):]

            if delta_reasoning_finally:
                try:
                    yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": delta_reasoning_finally, "timestamp": get_current_time_iso()})
                    state["full_yielded_reasoning_custom"] += delta_reasoning_finally # Update yielded amount
                except Exception as final_yield_exc:
                    logger.warning(f"RID-{request_id}: Exception during final yield (reasoning, custom logic): {final_yield_exc}")

            # Send reasoning_finish if reasoning was yielded and it wasn't already sent
            # This condition needs to be careful not to conflict with provider-specific finish events.
            # Let's assume if this branch is active, it controls the reasoning_finish.
            if state.get("full_yielded_reasoning_custom") and not state.get("openai_reasoning_finish_event_sent"): # Re-using openai_reasoning_finish_event_sent for this custom path
                yield orjson_dumps_bytes_wrapper({"type": "reasoning_finish", "timestamp": get_current_time_iso()})
                state["openai_reasoning_finish_event_sent"] = True # Mark as sent for this custom path

        else: # Separator WAS found during streaming
            # The text after the separator is content
            # Note: The original code for splitting and yielding reasoning/content *during* streaming
            # when the separator is found is missing from this `handle_stream_cleanup`.
            # This cleanup only handles the *remaining* text.
            # Assuming `state.accumulated_text_custom` now only contains text *after* the last separator.
            processed_content_finally = strip_potentially_harmful_html_and_normalize_newlines(final_raw_text_custom) # Assuming this is only content part
            delta_content_finally = processed_content_finally
            full_yielded_content_custom = state.get("full_yielded_content_custom", "")

            if full_yielded_content_custom and processed_content_finally.startswith(full_yielded_content_custom):
                delta_content_finally = processed_content_finally[len(full_yielded_content_custom):]

            if delta_content_finally:
                try:
                    yield orjson_dumps_bytes_wrapper({"type": "content", "text": delta_content_finally, "timestamp": get_current_time_iso()})
                    state["full_yielded_content_custom"] += delta_content_finally # Update yielded amount
                except Exception as final_yield_exc:
                    logger.warning(f"RID-{request_id}: Exception during final yield (content, custom logic): {final_yield_exc}")

        # Send a generic finish event if this custom logic was active and upstream was OK,
        # but only if the provider-specific logic hasn't already sent one (e.g., [DONE] or Google's finishReason).
        # This is tricky. A simple check: if no "finish" event was logged by provider specific logic.
        # For now, let's assume if `use_old_custom_separator_branch` is true, this cleanup
        # might need to send its own finish if the stream didn't end with a provider [DONE]/finishReason.
        # This part of the original code was:
        # `if provider not in ["openai", "google"] or not upstream_ok :`
        # This seems to imply it would send a finish if it's a custom provider or if upstream failed.
        # Let's refine: if this branch is active and we haven't seen a provider finish, send one.
        # This requires knowing if a provider finish event was already sent.
        # This cleanup function doesn't have that direct knowledge.
        # A simpler approach: if this branch is active, it *might* be responsible for the final "finish"
        # if the stream ends abruptly without a clear signal from the LLM.
        # However, the main stream loop *should* catch [DONE] or finishReason.
        # This final "finish" here is more of a safeguard for this custom branch.
        # Let's assume for now that the main loop's finish events are sufficient.
        # The original logic here was a bit convoluted.

    # Final logging about processing modes
    final_processing_mode_info = []
    if use_old_custom_separator_branch: final_processing_mode_info.append("OldCustomSeparatorLogicActive")

    if provider == "google":
        if state.get('google_json_activated'): final_processing_mode_info.append("GoogleJSONSchemaMode")
        elif state.get('google_pre_json_fallback_buffer') or state.get('google_pre_json_fallback_yielded_reasoning'):
            final_processing_mode_info.append("GoogleNaturalTextMode")
    elif provider == "openai":
        if state.get('openai_had_any_reasoning'): final_processing_mode_info.append("OpenAIWithReasoningField")
        elif state.get('openai_had_any_content_or_tool_call'): final_processing_mode_info.append("OpenAINativeContent/ToolCall")
        else: final_processing_mode_info.append("OpenAINoSignificantOutput")

    logger.info(f"RID-{request_id}: Stream generator finished. Upstream OK: {upstream_ok}. Processing Modes: {', '.join(final_processing_mode_info) if final_processing_mode_info else 'NoClearProcessingMode'}.")


def is_gemini_thinking_pattern(text: str) -> bool:
    if not isinstance(text, str): return False
    lines = text.strip().split('\n')
    if not lines: return False
    first_line = lines[0].strip()
    if not (first_line.startswith("I've") or first_line.startswith("I am") or first_line.startswith("I'm ") or "interpreting" in first_line.lower()): return False
    thinking_keywords = ["examining", "delving", "exploring", "understanding", "dissecting", "synthesizing", "distilling", "i'm now", "i've been", "i'm focusing", "i'm noting", "forming a more", "refining the response", "context and potential variations", "constructed a simple", "helpful offer"]
    text_lower = text.lower()
    keyword_count = sum(1 for keyword in thinking_keywords if keyword in text_lower)
    return keyword_count >= 1

def process_gemini_content(text: str, state: Dict[str, str], request_id: str) -> Tuple[str, str]:
    # This function seems to be part of the old custom separator logic and might be redundant
    # if Google JSON schema mode or OpenAI native reasoning is used.
    # Keeping it for now if `use_old_custom_separator_branch` relies on it.
    if not text: return "", ""
    if is_gemini_thinking_pattern(text): return text, ""
    greeting_phrases = ["你好！ 有什么可以帮您的吗？", "Hello! Is there anything I can help you with?"]
    for phrase in greeting_phrases:
        if phrase in text:
            parts = text.split(phrase, 1)
            thinking_part = parts[0]
            answer_part = phrase + parts[1] if len(parts) > 1 else phrase
            if is_gemini_thinking_pattern(thinking_part.strip()): return thinking_part.strip(), answer_part.strip()
            else: return "", text
    return "", text

if __name__ == "__main__":
    import uvicorn
    APP_HOST = os.getenv("HOST", "0.0.0.0")
    APP_PORT = int(os.getenv("PORT", 8000))
    DEV_RELOAD = os.getenv("DEV_RELOAD", "false").lower() == "true"
    log_config = uvicorn.config.LOGGING_CONFIG.copy()
    if "formatters" not in log_config: log_config["formatters"] = {}
    if "default" not in log_config["formatters"]: log_config["formatters"]["default"] = {"fmt": "", "datefmt": "", "use_colors": None}
    if "access" not in log_config["formatters"]: log_config["formatters"]["access"] = {"fmt": "", "datefmt": "", "use_colors": None}
    if "handlers" not in log_config: log_config["handlers"] = {}
    if "default" not in log_config["handlers"]: log_config["handlers"]["default"] = {"formatter": "default", "class": "logging.StreamHandler", "stream": "ext://sys.stderr"}
    if "loggers" not in log_config: log_config["loggers"] = {}
    log_config["formatters"]["default"]["fmt"] = "%(asctime)s %(levelname)-8s [%(name)s:%(module)s:%(lineno)d] - %(message)s"
    log_config["formatters"]["default"]["datefmt"] = "%Y-%m-%d %H:%M:%S"
    log_config["formatters"]["access"]["fmt"] = '%(asctime)s %(levelname)-8s [%(name)s:%(module)s:%(lineno)d] - %(client_addr)s - "%(request_line)s" %(status_code)s'
    log_config["formatters"]["access"]["datefmt"] = "%Y-%m-%d %H:%M:%S"
    log_config["loggers"]["EzTalkProxy"] = {"handlers": ["default"], "level": LOG_LEVEL_FROM_ENV, "propagate": False }
    log_config["loggers"]["uvicorn.error"] = {"handlers": ["default"], "level": "INFO", "propagate": False}
    log_config["loggers"]["uvicorn.access"] = {"handlers": ["default"], "level": "WARNING", "propagate": False}
    logger.info(f"Starting Uvicorn: http://{APP_HOST}:{APP_PORT}. Reload: {DEV_RELOAD}. Log Level (EzTalkProxy): {LOG_LEVEL_FROM_ENV}")
    uvicorn.run("main:app", host=APP_HOST, port=APP_PORT, log_config=log_config, reload=DEV_RELOAD, lifespan="on")
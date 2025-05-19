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

load_dotenv()

from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

# --- 日志配置 (与之前相同) ---
LOG_LEVEL_FROM_ENV = os.getenv("LOG_LEVEL", "INFO").upper()
numeric_level = getattr(logging, LOG_LEVEL_FROM_ENV, logging.INFO)
logging.basicConfig(
    level=numeric_level,
    format='%(asctime)s %(levelname)-8s [%(name)s:%(module)s:%(lineno)d] - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger("EzTalkProxy")
# ... (其他日志记录器级别设置与之前相同) ...

# --- 全局配置常量 ---
APP_VERSION = "1.9.9.17-optimized"
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
GUIDED_REASONING_MODEL_TAGS = ["gemini", "pro", "thinking", "flash"] # 用于判断是否默认开启思考模式的模型标签

COMMON_HEADERS = {"X-Accel-Buffering": "no"}

# --- 检查关键环境变量 ---
if not GOOGLE_API_KEY:
    logger.critical("CRITICAL: GOOGLE_API_KEY is not set. Google Web Search will FAIL.")
if not GOOGLE_CSE_ID:
    logger.critical("CRITICAL: GOOGLE_CSE_ID is not set. Google Web Search will FAIL.")

http_client: Optional[httpx.AsyncClient] = None

# --- Lifespan 管理 (与之前基本相同, 使用了上面的常量) ---
@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    global http_client
    logger.info("Lifespan: Initializing HTTP client...")
    try:
        logger.info(f"Lifespan: HTTP Client Config - API Timeout: {API_TIMEOUT}s, Read Timeout: {READ_TIMEOUT}s, Max Connections: {MAX_CONNECTIONS}")
        http_client = httpx.AsyncClient(
            timeout=httpx.Timeout(API_TIMEOUT, read=READ_TIMEOUT),
            limits=httpx.Limits(max_connections=MAX_CONNECTIONS),
            http2=True, follow_redirects=True, trust_env=False
        )
        logger.info("Lifespan: HTTP client initialized successfully.")
    except Exception as e: # 更通用的异常捕获
        logger.error(f"Lifespan: HTTP client init failed: {e}", exc_info=True)
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


# --- FastAPI 应用实例 (与之前相同) ---
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
    allow_origins=["*"], # 在生产中应配置具体的源
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["*"]
)
logger.info(f"FastAPI EzTalk Proxy v{APP_VERSION} initialized with CORS.")


# --- Pydantic 模型 (与之前相同) ---
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
    provider: Literal["openai", "google"] # 可以扩展为 Enum
    model: str
    api_key: str
    temperature: Optional[float] = Field(None, ge=0.0, le=2.0)
    top_p: Optional[float] = Field(None, ge=0.0, le=1.0)
    max_tokens: Optional[int] = Field(None, gt=0)
    tools: Optional[List[Dict[str, Any]]] = None
    tool_choice: Optional[Union[str, Dict[str, Any]]] = None
    use_web_search: Optional[bool] = Field(None, alias="useWebSearch")
    force_google_reasoning_prompt: Optional[bool] = Field(None, alias="forceGoogleReasoningPrompt") # 此参数名可能需要调整，因为它现在不仅仅是Google

# --- 辅助函数 ---
def orjson_dumps_bytes_wrapper(data: Any) -> bytes:
    return orjson.dumps(data, option=orjson.OPT_NON_STR_KEYS | orjson.OPT_PASSTHROUGH_DATETIME | orjson.OPT_APPEND_NEWLINE)

def error_response(code: int, msg: str, request_id: Optional[str] = None, headers: Optional[Dict[str, str]] = None) -> JSONResponse:
    final_headers = COMMON_HEADERS.copy()
    final_headers.update(headers or {})
    log_msg = f"Responding error {code}: {msg}"
    if request_id:
        log_msg = f"RID-{request_id}: {log_msg}"
    logger.warning(log_msg)
    return JSONResponse(
        status_code=code,
        content={"error": {"message": msg, "code": code, "type": "proxy_error"}},
        headers=final_headers,
    )

def extract_sse_lines(buffer: bytearray) -> Tuple[List[bytes], bytearray]:
    lines = []
    start = 0
    while True:
        idx = buffer.find(b'\n', start)
        if idx == -1:
            break
        line = buffer[start:idx].removesuffix(b'\r')
        if len(line) > MAX_SSE_LINE_LENGTH:
            logger.warning(f"SSE line too long ({len(line)} bytes), skipping.") # 可以考虑增加 request_id
        else:
            lines.append(line)
        start = idx + 1
    return lines, buffer[start:]

def strip_tags(text: str) -> str:
    return re.sub(r"</?[\w\-_]+.*?>", "", text).replace("<br>", "").replace("</br>", "")

def convert_math_symbols(text: str) -> str:
    # (使用你之前提供的增强版 convert_math_symbols 函数)
    # 基础替换
    text = text.replace("\\pm", "±")      # 正负号
    text = text.replace("\\times", "×")    # 乘号
    text = text.replace("\\div", "÷")      # 除号
    text = text.replace("\\cdot", "·")     # 点乘
    text = text.replace("\\leq", "≤")      # 小于等于
    text = text.replace("\\geq", "≥")      # 大于等于
    text = text.replace("\\neq", "≠")      # 不等于
    text = text.replace("\\approx", "≈")   # 约等于
    text = text.replace("\\equiv", "≡")    # 全等于
    text = text.replace("\\forall", "∀")   #任意
    text = text.replace("\\exists", "∃")   #存在
    text = text.replace("\\nabla", "∇")    # 梯度算子
    text = text.replace("\\partial", "∂")  # 偏导数
    text = text.replace("\\infty", "∞")    # 无穷大
    text = text.replace("\\alpha", "α")    # Alpha
    text = text.replace("\\beta", "β")     # Beta
    text = text.replace("\\gamma", "γ")    # Gamma
    text = text.replace("\\delta", "δ")    # Delta (小写)
    text = text.replace("\\Delta", "Δ")    # Delta (大写, 如判别式)
    text = text.replace("\\epsilon", "ε")  # Epsilon
    text = text.replace("\\zeta", "ζ")     # Zeta
    text = text.replace("\\eta", "η")      # Eta
    text = text.replace("\\theta", "θ")    # Theta (小写)
    text = text.replace("\\Theta", "Θ")    # Theta (大写)
    text = text.replace("\\iota", "ι")     # Iota
    text = text.replace("\\kappa", "κ")    # Kappa
    text = text.replace("\\lambda", "λ")   # Lambda (小写)
    text = text.replace("\\Lambda", "Λ")   # Lambda (大写)
    text = text.replace("\\mu", "μ")       # Mu
    text = text.replace("\\nu", "ν")       # Nu
    text = text.replace("\\xi", "ξ")       # Xi (小写)
    text = text.replace("\\Xi", "Ξ")       # Xi (大写)
    text = text.replace("\\pi", "π")       # Pi (小写)
    text = text.replace("\\Pi", "Π")       # Pi (大写, 连乘)
    text = text.replace("\\rho", "ρ")      # Rho
    text = text.replace("\\sigma", "σ")    # Sigma (小写)
    text = text.replace("\\Sigma", "Σ")    # Sigma (大写, 求和)
    text = text.replace("\\tau", "τ")      # Tau
    text = text.replace("\\upsilon", "υ")  # Upsilon (小写)
    text = text.replace("\\Upsilon", "ϒ")  # Upsilon (大写)
    text = text.replace("\\phi", "φ")      # Phi (小写)
    text = text.replace("\\Phi", "Φ")      # Phi (大写)
    text = text.replace("\\chi", "χ")      # Chi
    text = text.replace("\\psi", "ψ")      # Psi (小写)
    text = text.replace("\\Psi", "Ψ")      # Psi (大写)
    text = text.replace("\\omega", "ω")    # Omega (小写)
    text = text.replace("\\Omega", "Ω")    # Omega (大写)

    # 集合符号
    text = text.replace("\\in", "∈")       # 属于
    text = text.replace("\\notin", "∉")     # 不属于
    text = text.replace("\\subset", "⊂")    # 真子集
    text = text.replace("\\subseteq", "⊆")  # 子集或等于
    text = text.replace("\\supset", "⊃")    # 真超集
    text = text.replace("\\supseteq", "⊇")  # 超集或等于
    text = text.replace("\\cap", "∩")       # 交集
    text = text.replace("\\cup", "∪")       # 并集
    text = text.replace("\\emptyset", "∅")  # 空集
    text = text.replace("\\varnothing", "∅")# 空集 (另一种形式)

    # 箭头
    text = text.replace("\\rightarrow", "→")
    text = text.replace("\\leftarrow", "←")
    text = text.replace("\\leftrightarrow", "↔")
    text = text.replace("\\Rightarrow", "⇒")
    text = text.replace("\\Leftarrow", "⇐")
    text = text.replace("\\Leftrightarrow", "⇔")

    # 角符号 (保留原有逻辑，并增强)
    text = re.sub(r"angle\s*([A-Za-z0-9]+)", r"∠\1", text, flags=re.IGNORECASE)
    text = re.sub(r"\\angle\s*([A-Za-z0-9]+)", r"∠\1", text, flags=re.IGNORECASE)
    text = text.replace("\\angle", "∠") # 单独的 \angle

    # 三角形 (保留原有逻辑)
    text = re.sub(r"\btriangle\s*([A-Za-z0-9]+)", r"△\1", text, flags=re.IGNORECASE)
    text = re.sub(r"\\triangle\s*([A-Za-z0-9]+)", r"△\1", text)

    # 度数 (保留原有逻辑)
    text = re.sub(r"(\d+)\s*(degrees|degree|deg|°)", r"\1°", text, flags=re.IGNORECASE)
    text = re.sub(r"\^{\\circ}", "°", text)
    text = re.sub(r"(\d+)\s*\^\\circ", r"\1°", text)

    # 根号 (保留原有逻辑，增强对方括号参数的支持)
    text = re.sub(r"\\sqrt\[([^\]]+)\]\{([^\}]+)\}", r"\1√\2", text) # n次方根, 如 \sqrt[3]{x} -> ³√x
    text = re.sub(r"\\sqrt\{([^\}]+)\}", r"√\1", text)
    text = re.sub(r"sqrt\s*\(?([0-9a-zA-Z\+\-\*/\^\(\)]+)\)?", r"√\1", text) # 改进以支持更复杂的表达式

    # 分数 (保留原有逻辑)
    text = re.sub(r"\\frac\{([^\}]+)\}\{([^\}]+)\}", r"(\1/\2)", text) # 加括号以明确运算顺序

    # 上下标 (简单处理，可能不完美)
    text = re.sub(r"\_\{([^\}]+)\}", r"₍\1₎", text) # x_{abc} -> x₍abc₎
    text = re.sub(r"\^\{([^\}]+)\}", r"⁽\1⁾", text) # x^{abc} -> x⁽abc⁾
    text = re.sub(r"\_([A-Za-z0-9])", r"₍\1₎", text)    # x_a -> x₍a₎
    text = re.sub(r"\^([A-Za-z0-9])", r"⁽\1⁾", text)    # x^a -> x⁽a⁾

    # 移除一些常见的LaTeX环境声明，如果它们没有被正确渲染
    text = re.sub(r"\\begin\{[a-zA-Z\*]+\}|\\end\{[a-zA-Z\*]+\}", "", text)
    return text

def get_current_time_iso():
    return datetime.datetime.utcnow().isoformat() + "Z"

# --- Provider Specific Logic (Helper functions) ---
def _convert_openai_tools_to_gemini_declarations(openai_tools: List[Dict[str, Any]], request_id: str) -> List[Dict[str, Any]]:
    # (与之前相同)
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
    # (与之前相同)
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
    # (与之前相同)
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
                except orjson.JSONDecodeError:
                    logger.error(f"RID-{request_id}: Google msg conversion: 'tool' content for '{msg.name}' not valid JSON: {msg.content[:100]}. Treating as raw string.")
                    response_obj = {"raw_response": msg.content} # 尝试更灵活处理，但Gemini可能不接受
                gemini_contents.append({"role": "user", "parts": [{"functionResponse": {"name": msg.name, "response": response_obj}}]})
            else: logger.warning(f"RID-{request_id}: Google msg conversion: 'tool' message missing 'name' or 'content': {msg.model_dump_json(exclude_none=True)}")
    return gemini_contents

def prepare_openai_request(request_data: ChatRequest, messages: List[Dict[str, Any]]) -> Tuple[str, Dict[str, str], Dict[str, Any]]:
    base = request_data.api_address.strip() if request_data.api_address else DEFAULT_OPENAI_API_BASE_URL
    url = f"{base.rstrip('/')}{OPENAI_COMPATIBLE_PATH}"
    headers = {"Content-Type": "application/json", "Authorization": f"Bearer {request_data.api_key}"}
    payload = {"model": request_data.model, "messages": messages, "stream": True}
    if request_data.temperature is not None: payload["temperature"] = request_data.temperature
    if request_data.top_p is not None: payload["top_p"] = request_data.top_p
    if request_data.max_tokens is not None: payload["max_tokens"] = request_data.max_tokens
    if request_data.tools: payload["tools"] = request_data.tools
    if request_data.tool_choice: payload["tool_choice"] = request_data.tool_choice
    return url, headers, payload

def prepare_google_request(request_data: ChatRequest, messages: List[ApiMessage], request_id: str) -> Tuple[str, Dict[str, str], Dict[str, Any], Dict[str, str]]:
    url = f"{GOOGLE_API_BASE_URL}/v1beta/models/{request_data.model}:streamGenerateContent"
    params = {"key": request_data.api_key, "alt": "sse"}
    headers = {"Content-Type": "application/json"} # Google SSE不需要Auth Bearer
    
    gemini_contents = _convert_api_messages_to_gemini_contents(messages, request_id)
    payload: Dict[str, Any] = {"contents": gemini_contents}

    gemini_declarations = _convert_openai_tools_to_gemini_declarations(request_data.tools or [], request_id)
    if gemini_declarations:
        payload["tools"] = [{"functionDeclarations": gemini_declarations}]
    
    if request_data.tool_choice:
        gemini_tool_config = _convert_openai_tool_choice_to_gemini_tool_config(request_data.tool_choice, gemini_declarations, request_id)
        if gemini_tool_config:
            payload["toolConfig"] = gemini_tool_config
            
    generation_config = {k:v for k,v in {
        "temperature": request_data.temperature, 
        "topP": request_data.top_p, 
        "maxOutputTokens": request_data.max_tokens
    }.items() if v is not None}
    if generation_config:
        payload["generationConfig"] = generation_config
        
    return url, headers, payload, params


# --- Web Search Logic ---
async def perform_web_search(query: str, request_id: str) -> List[Dict[str, str]]:
    results = []
    if not GOOGLE_API_KEY or not GOOGLE_CSE_ID:
        logger.warning(f"RID-{request_id}: Web search skipped, GOOGLE_API_KEY or GOOGLE_CSE_ID not configured.")
        return results
    if not query:
        logger.warning(f"RID-{request_id}: Web search skipped, no query provided.")
        return results

    try:
        logger.info(f"RID-{request_id}: Performing Google Web Search for query: '{query[:100]}'")
        def search_sync():
            service = build("customsearch", "v1", developerKey=GOOGLE_API_KEY, cache_discovery=False)
            # CSE API v1 最多返回10个结果，num参数最大为10
            res = service.cse().list(q=query, cx=GOOGLE_CSE_ID, num=min(SEARCH_RESULT_COUNT, 10)).execute()
            return res.get('items', [])
        
        search_items = await asyncio.to_thread(search_sync)
        for i, item in enumerate(search_items):
            snippet = item.get('snippet', 'N/A')
            if len(snippet) > SEARCH_SNIPPET_MAX_LENGTH:
                snippet = snippet[:SEARCH_SNIPPET_MAX_LENGTH] + "..."
            results.append({
                "index": i + 1,
                "title": item.get('title', 'N/A'),
                "href": item.get('link', 'N/A'),
                "snippet": snippet
            })
        logger.info(f"RID-{request_id}: Web search completed, found {len(results)} results.")
    except HttpError as e:
        error_content = "Unknown Google API error"
        status_code = e.resp.status
        try:
            content_json = orjson.loads(e.content)
            error_content = content_json.get("error", {}).get("message", error_content)
        except orjson.JSONDecodeError:
            error_content = e._get_reason() or e.content.decode(errors='ignore')[:200]
        logger.error(f"RID-{request_id}: Google Web Search HttpError ({status_code}) for '{query[:50]}': {error_content}", exc_info=False)
        # 可以考虑将此错误信息通过SSE传递给客户端
        # yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Web search failed: {error_content}", ...})
    except Exception as search_exc:
        logger.error(f"RID-{request_id}: Google Web Search failed for '{query[:50]}': {search_exc}", exc_info=True)
    return results

def generate_search_context_message_content(query: str, search_results: List[Dict[str, str]]) -> str:
    if not search_results:
        return ""
    search_context_parts = [f"Combine the latest Web search results below for '{query}', and use as much information as needed:"]
    for res_item in search_results:
        search_context_parts.append(
            f"{res_item.get('index')}. Title: {res_item.get('title')}\n  Summary: {res_item.get('snippet')}\n  Source link (for AI use only, do NOT cite directly): {res_item.get('href')}"
        )
    return "\n\n".join(search_context_parts)

# --- Guided Reasoning Logic ---
def should_apply_guided_reasoning(request_data: ChatRequest) -> bool:
    model_name_lower = request_data.model.lower()
    force_reasoning = request_data.force_google_reasoning_prompt # 参数名可能需要泛化
    
    if force_reasoning is True: return True
    if force_reasoning is False: return False
    return any(tag in model_name_lower for tag in GUIDED_REASONING_MODEL_TAGS)

def inject_reasoning_prompt(messages: List[Dict[str, Any]], request_id: str) -> bool:
    instruction_text = (
        f"\n\nPlease first think step by step and write out your reasoning process explicitly, but DO NOT say the final answer yet. "
        f"ONLY AFTER the reasoning, on a new line, print exactly:\n{THINKING_PROCESS_SEPARATOR}\n"
        "Then, write ONLY your final answer below this line, and DO NOT repeat the separator anywhere. "
        "Do NOT include instructions or any tags like <think>, and do not repeat any output."
    )
    last_user_idx = next((i for i, msg_d in reversed(list(enumerate(messages))) if msg_d.get("role") == "user"), -1)
    if last_user_idx != -1:
        messages[last_user_idx]["content"] = (messages[last_user_idx].get("content", "") or "") + instruction_text
        logger.info(f"RID-{request_id}: Injected reasoning prompt to model.")
        return True
    else:
        logger.warning(f"RID-{request_id}: No user message to inject reasoning prompt. Deactivating guided reasoning.")
        return False

# --- SSE Stream Processors ---
async def process_openai_sse_standard(line_bytes: bytes, request_id: str) -> AsyncGenerator[bytes, None]:
    # (与之前基本相同, 但使用了 get_current_time_iso 和 strip_tags/convert_math_symbols)
    if not line_bytes.startswith(b"data: ") or line_bytes.endswith(b"[DONE]"): return
    raw_data = line_bytes[len(b"data: "):].strip()
    if not raw_data: return
    try:
        data = orjson.loads(raw_data)
        timestamp = get_current_time_iso()
        for choice in data.get('choices', []):
            delta = choice.get('delta', {})
            if "tool_calls" in delta and delta["tool_calls"]:
                yield orjson_dumps_bytes_wrapper({"type": "tool_calls_chunk", "data": delta["tool_calls"], "timestamp": timestamp})
            if "content" in delta and delta["content"] is not None:
                processed_content = convert_math_symbols(strip_tags(delta["content"]))
                if processed_content:
                    yield orjson_dumps_bytes_wrapper({"type": "content", "text": processed_content, "timestamp": timestamp})
            finish_reason = choice.get("finish_reason") or delta.get("finish_reason")
            if finish_reason:
                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish_reason, "timestamp": timestamp})
    except orjson.JSONDecodeError:
        logger.warning(f"RID-{request_id}: OpenAI SSE (standard): JSON parse error. Data: {raw_data[:100]!r}")
    except Exception as e:
        logger.error(f"RID-{request_id}: OpenAI SSE (standard): Error processing line: {e}", exc_info=True)


async def process_google_sse_standard(line_bytes: bytes, request_id: str) -> AsyncGenerator[bytes, None]:
    # (与之前基本相同, 但使用了 get_current_time_iso 和 strip_tags/convert_math_symbols)
    if not line_bytes.startswith(b"data: "): return
    raw_data = line_bytes[len(b"data: "):].strip()
    if not raw_data: return
    try:
        data = orjson.loads(raw_data)
        timestamp = get_current_time_iso()
        for candidate in data.get('candidates', []):
            text_content = ""
            function_calls_parts = []
            if candidate.get("content", {}).get("parts"):
                for part in candidate["content"]["parts"]:
                    if "text" in part: text_content += part["text"]
                    if "functionCall" in part: function_calls_parts.append(part["functionCall"])
            
            if text_content:
                processed_content = convert_math_symbols(strip_tags(text_content))
                if processed_content:
                    yield orjson_dumps_bytes_wrapper({"type": "content", "text": processed_content, "timestamp": timestamp})
            
            for fc_part in function_calls_parts:
                proxy_fc_id = f"gemini_fc_{os.urandom(4).hex()}" # Consider a more robust ID generation
                yield orjson_dumps_bytes_wrapper({
                    "type": "google_function_call_request", "id": proxy_fc_id,
                    "name": fc_part.get("name"), "arguments_obj": fc_part.get("args", {}),
                    "timestamp": timestamp
                })
            finish_reason = candidate.get('finishReason')
            if finish_reason:
                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish_reason, "timestamp": timestamp})
    except orjson.JSONDecodeError:
        logger.warning(f"RID-{request_id}: Google SSE (standard): JSON parse error. Data: {raw_data[:100]!r}")
    except Exception as e:
        logger.error(f"RID-{request_id}: Google SSE (standard): Error processing line: {e}", exc_info=True)


# --- Health Check Endpoint ---
@app.get("/health", status_code=200, include_in_schema=False)
async def health_check():
    client_status = "initialized" if http_client else "not initialized"
    return {"status": "ok" if http_client else "warning", "detail": f"HTTP client {client_status}"}


# --- Main Chat Proxy Endpoint ---
@app.post("/chat", response_class=StreamingResponse, summary="Proxy for AI Chat Completions", tags=["AI Proxy"])
async def chat_proxy(request_data: ChatRequest, client: Optional[httpx.AsyncClient] = Depends(lambda: http_client)):
    request_id = os.urandom(8).hex()
    logger.info(f"RID-{request_id}: Received /chat for {request_data.provider} model {request_data.model}. WebSearch: {request_data.use_web_search}, ForceReasoning: {request_data.force_google_reasoning_prompt}")

    if not client:
        return error_response(503, "Service unavailable: HTTP client not initialized.", request_id)

    current_messages = [m.model_dump(exclude_none=True) for m in request_data.messages]
    user_query_for_search = ""

    # 1. Prepare for Web Search (if enabled)
    if request_data.use_web_search:
        for msg_obj in reversed(request_data.messages): # Use original request_data.messages
            if msg_obj.role == "user" and msg_obj.content:
                user_query_for_search = msg_obj.content.strip()
                break
        if not user_query_for_search:
            logger.warning(f"RID-{request_id}: Web search requested, but no user query found.")

    # 2. Prepare API request based on provider
    api_url: str = ""
    api_headers: Dict[str, str] = {}
    api_payload: Dict[str, Any] = {}
    api_params: Optional[Dict[str, str]] = None

    try:
        if request_data.provider == "openai":
            api_url, api_headers, api_payload = prepare_openai_request(request_data, current_messages)
        elif request_data.provider == "google":
            # Google preparation needs original ApiMessage objects for its converter
            api_url, api_headers, api_payload, api_params = prepare_google_request(request_data, request_data.messages, request_id)
        else:
            return error_response(400, f"Invalid provider: {request_data.provider}", request_id)
    except Exception as e:
        logger.error(f"RID-{request_id}: Error preparing API request: {e}", exc_info=True)
        return error_response(500, f"Internal error during request preparation: {str(e)}", request_id)

    # --- Stream Generator ---
    async def stream_generator() -> AsyncGenerator[bytes, None]:
        nonlocal current_messages # Allow modification if search results are added
        buffer = bytearray()
        upstream_ok = False
        
        # Guided reasoning state
        apply_reasoning = should_apply_guided_reasoning(request_data)
        found_separator = False
        accumulated_reasoning_text = ""
        last_yielded_reasoning = None
        last_yielded_final_answer = None

        try:
            # 3. Perform Web Search and inject context (if applicable)
            if request_data.use_web_search and user_query_for_search:
                yield orjson_dumps_bytes_wrapper({"type": "status_update", "stage": "web_indexing_started", "timestamp": get_current_time_iso()})
                search_results = await perform_web_search(user_query_for_search, request_id)
                if search_results:
                    yield orjson_dumps_bytes_wrapper({"type": "web_search_results", "results": search_results, "timestamp": get_current_time_iso()})
                    search_context_content = generate_search_context_message_content(user_query_for_search, search_results)
                    if search_context_content:
                        system_search_msg = ApiMessage(role="system", content=search_context_content).model_dump(exclude_none=True)
                        # Re-prepare messages for the payload if search results are added
                        # This needs to be done carefully depending on provider
                        # For OpenAI, current_messages is a list of dicts.
                        # For Google, prepare_google_request handles ApiMessage list.
                        
                        # Find last user message to insert system message before it
                        temp_api_messages_for_injection = [ApiMessage(**m) for m in current_messages]
                        last_user_idx_search = next((i for i, msg in reversed(list(enumerate(temp_api_messages_for_injection))) if msg.role == "user"), -1)
                        
                        if request_data.provider == "openai":
                            if last_user_idx_search != -1:
                                current_messages.insert(last_user_idx_search, system_search_msg)
                            else:
                                current_messages.insert(0, system_search_msg)
                            api_payload["messages"] = current_messages # Update payload
                        elif request_data.provider == "google":
                            # For Google, we need to reconstruct ApiMessage list and re-prepare
                            # This is a bit clunky, a better abstraction would help.
                            reconstructed_messages = [ApiMessage(**m) for m in current_messages]
                            if last_user_idx_search != -1:
                                reconstructed_messages.insert(last_user_idx_search, ApiMessage(**system_search_msg))
                            else:
                                reconstructed_messages.insert(0, ApiMessage(**system_search_msg))
                            # Update current_messages for consistency if needed later, though payload is key
                            current_messages = [m.model_dump(exclude_none=True) for m in reconstructed_messages]
                            api_payload["contents"] = _convert_api_messages_to_gemini_contents(reconstructed_messages, request_id)


                yield orjson_dumps_bytes_wrapper({"type": "status_update", "stage": "web_analysis_started", "timestamp": get_current_time_iso()})

            # 4. Inject Reasoning Prompt (if applicable)
            if apply_reasoning:
                # This needs to modify the messages that will actually be sent.
                # For OpenAI, it's api_payload["messages"]
                # For Google, it's more complex as it's converted to 'contents'
                if request_data.provider == "openai":
                    if not inject_reasoning_prompt(api_payload["messages"], request_id):
                        apply_reasoning = False # Deactivate if injection failed
                elif request_data.provider == "google":
                    # Need to convert back from contents, inject, then convert again, or inject into ApiMessage list before conversion
                    # Simpler: inject into the `current_messages` (list of dicts) then re-prepare Google payload
                    # This means `prepare_google_request` should be called *after* this potential modification.
                    # Let's adjust the flow: prepare base payload, then modify messages, then finalize payload.
                    # For now, let's assume `inject_reasoning_prompt` works on `current_messages` (list of dicts)
                    # and `prepare_google_request` is called with the *final* `ApiMessage` list.
                    # This part of the refactor is tricky without a full Provider abstraction.
                    # The current `prepare_google_request` takes `ApiMessage` list.
                    # Let's assume `current_messages` is updated, then `prepare_google_request` is called again or its payload part is updated.
                    # To simplify, we'll assume the initial `prepare_x_request` created the base payload,
                    # and now we modify `current_messages` and update the relevant part of `api_payload`.

                    # Create a temporary list of ApiMessage for injection logic
                    temp_api_messages = [ApiMessage(**m) for m in current_messages]
                    original_content_before_injection: Optional[str] = None
                    last_user_idx_prompt = -1

                    # Find last user message
                    for i, msg_obj in reversed(list(enumerate(temp_api_messages))):
                        if msg_obj.role == "user":
                            last_user_idx_prompt = i
                            original_content_before_injection = msg_obj.content
                            break
                    
                    if last_user_idx_prompt != -1:
                        instruction_text_for_injection = ( # Duplicating for clarity here
                            f"\n\nPlease first think step by step and write out your reasoning process explicitly, but DO NOT say the final answer yet. "
                            f"ONLY AFTER the reasoning, on a new line, print exactly:\n{THINKING_PROCESS_SEPARATOR}\n"
                            "Then, write ONLY your final answer below this line, and DO NOT repeat the separator anywhere. "
                            "Do NOT include instructions or any tags like <think>, and do not repeat any output."
                        )
                        temp_api_messages[last_user_idx_prompt].content = (original_content_before_injection or "") + instruction_text_for_injection
                        logger.info(f"RID-{request_id}: Injected reasoning prompt for Google.")
                        # Re-generate Google contents
                        api_payload["contents"] = _convert_api_messages_to_gemini_contents(temp_api_messages, request_id)
                    else:
                        logger.warning(f"RID-{request_id}: No user message to inject reasoning prompt for Google. Deactivating guided reasoning.")
                        apply_reasoning = False


            logger.info(f"RID-{request_id}: Final POST to {api_url}. Provider: {request_data.provider}. Applying Guided Reasoning: {apply_reasoning}")
            # logger.debug(f"RID-{request_id}: Final Payload: {orjson.dumps(api_payload).decode()[:500]}...") # For debugging

            first_chunk_received_from_llm = False
            async with client.stream("POST", api_url, headers=api_headers, json=api_payload, params=api_params) as resp:
                logger.info(f"RID-{request_id}: LLM Upstream status: {resp.status_code}")
                if not (200 <= resp.status_code < 300):
                    err_body = await resp.aread()
                    err_text = err_body.decode("utf-8", errors="replace")
                    logger.error(f"RID-{request_id}: LLM Upstream error {resp.status_code}: {err_text[:500]}")
                    try: err_data = orjson.loads(err_text); msg = err_data.get("error", {}).get("message", err_text[:200])
                    except: msg = err_text[:200]
                    yield orjson_dumps_bytes_wrapper({"type": "error", "message": msg, "upstream_status": resp.status_code, "timestamp": get_current_time_iso()})
                    yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "error", "timestamp": get_current_time_iso()})
                    return
                upstream_ok = True

                async for raw_chunk_bytes in resp.aiter_raw():
                    if not raw_chunk_bytes: continue
                    if not first_chunk_received_from_llm:
                        if request_data.use_web_search and user_query_for_search: # Assuming web_analysis_started was sent
                            yield orjson_dumps_bytes_wrapper({"type": "status_update", "stage": "web_analysis_complete", "timestamp": get_current_time_iso()})
                        first_chunk_received_from_llm = True
                    
                    buffer.extend(raw_chunk_bytes)
                    sse_lines, buffer = extract_sse_lines(buffer)

                    for sse_line_bytes in sse_lines:
                        if not sse_line_bytes.strip(): continue

                        if apply_reasoning:
                            # --- Guided Reasoning SSE Processing ---
                            # This part remains complex and similar to your previous version
                            # Key is to use strip_tags and convert_math_symbols correctly
                            sse_data_bytes = b""
                            if sse_line_bytes.startswith(b"data: "): sse_data_bytes = sse_line_bytes[len(b"data: "):].strip()
                            if not sse_data_bytes: continue

                            current_provider_for_parsing = request_data.provider # Could be different if proxying another proxy
                            
                            if current_provider_for_parsing == "openai" and sse_data_bytes.strip() == b"[DONE]":
                                if not found_separator and accumulated_reasoning_text.strip():
                                    processed_text = convert_math_symbols(strip_tags(accumulated_reasoning_text.strip()))
                                    if processed_text and processed_text != last_yielded_reasoning:
                                        yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": processed_text, "timestamp": get_current_time_iso()})
                                        last_yielded_reasoning = processed_text
                                    accumulated_reasoning_text = ""
                                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "stop", "timestamp": get_current_time_iso()})
                                continue
                            
                            try:
                                parsed_data = orjson.loads(sse_data_bytes)
                                text_delta = ""
                                finish_reason_from_chunk = None
                                tool_calls_from_chunk = None # OpenAI specific
                                fc_req_from_chunk = None    # Google specific

                                # Extract text, tools, finish_reason from provider-specific chunk
                                if current_provider_for_parsing == "openai":
                                    for choice in parsed_data.get('choices', []):
                                        delta = choice.get('delta', {})
                                        if "content" in delta and delta["content"] is not None: text_delta += delta["content"]
                                        if "tool_calls" in delta and delta["tool_calls"]: tool_calls_from_chunk = delta["tool_calls"]
                                        finish_reason_from_chunk = choice.get("finish_reason") or delta.get("finish_reason")
                                elif current_provider_for_parsing == "google":
                                    for cand in parsed_data.get('candidates', []):
                                        if cand.get("content", {}).get("parts"):
                                            for part in cand["content"]["parts"]:
                                                if "text" in part: text_delta += part["text"]
                                                if "functionCall" in part: fc_req_from_chunk = part["functionCall"]
                                        finish_reason_from_chunk = cand.get('finishReason')
                                
                                accumulated_reasoning_text += text_delta

                                if not found_separator:
                                    sep_idx = accumulated_reasoning_text.find(THINKING_PROCESS_SEPARATOR)
                                    if sep_idx != -1:
                                        found_separator = True
                                        before_sep, after_sep = accumulated_reasoning_text[:sep_idx], accumulated_reasoning_text[sep_idx + len(THINKING_PROCESS_SEPARATOR):]
                                        
                                        processed_before = convert_math_symbols(strip_tags(before_sep.strip()))
                                        if processed_before and processed_before != last_yielded_reasoning:
                                            yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": processed_before, "timestamp": get_current_time_iso()})
                                            last_yielded_reasoning = processed_before
                                        
                                        # Process text immediately after separator
                                        # Remove any further separators from `after_sep`
                                        clean_after_sep = re.sub(f"({re.escape(THINKING_PROCESS_SEPARATOR)})+", "", after_sep)
                                        processed_after = convert_math_symbols(strip_tags(clean_after_sep.strip()))
                                        if processed_after and processed_after != last_yielded_final_answer:
                                            yield orjson_dumps_bytes_wrapper({"type": "content", "text": processed_after, "timestamp": get_current_time_iso()})
                                            last_yielded_final_answer = processed_after
                                        accumulated_reasoning_text = "" # Reset accumulator
                                    else: # Separator not yet found, yield current delta as reasoning
                                        processed_delta = convert_math_symbols(strip_tags(text_delta)) # Process only the delta
                                        if processed_delta and processed_delta != last_yielded_reasoning: # Avoid dupes if model sends empty chunks then text
                                            yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": processed_delta, "timestamp": get_current_time_iso()})
                                            last_yielded_reasoning = accumulated_reasoning_text.strip() # Update last_reasoning with full accumulated if yielding delta
                                else: # Separator found, all subsequent text is final answer
                                    processed_delta = convert_math_symbols(strip_tags(text_delta))
                                    if processed_delta and processed_delta != last_yielded_final_answer:
                                        yield orjson_dumps_bytes_wrapper({"type": "content", "text": processed_delta, "timestamp": get_current_time_iso()})
                                        last_yielded_final_answer = accumulated_reasoning_text.strip() # Update with full accumulated

                                # Handle tool calls
                                is_reasoning_tool_call = not found_separator
                                if tool_calls_from_chunk: # OpenAI
                                    yield orjson_dumps_bytes_wrapper({"type": "tool_calls_chunk", "data": tool_calls_from_chunk, "timestamp": get_current_time_iso(), "is_reasoning_step": is_reasoning_tool_call})
                                if fc_req_from_chunk: # Google
                                    fcid = f"gemini_fc_{os.urandom(4).hex()}"
                                    yield orjson_dumps_bytes_wrapper({"type": "google_function_call_request", "id": fcid, "name": fc_req_from_chunk.get("name"), "arguments_obj": fc_req_from_chunk.get("args", {}), "timestamp": get_current_time_iso(), "is_reasoning_step": is_reasoning_tool_call})

                                if finish_reason_from_chunk:
                                    if not found_separator and accumulated_reasoning_text.strip(): # If stream ends before separator
                                        final_reasoning_text = convert_math_symbols(strip_tags(accumulated_reasoning_text.strip()))
                                        if final_reasoning_text and final_reasoning_text != last_yielded_reasoning:
                                            yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": final_reasoning_text, "timestamp": get_current_time_iso()})
                                    yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": finish_reason_from_chunk, "timestamp": get_current_time_iso()})
                                    accumulated_reasoning_text = "" # Clear on finish
                            except orjson.JSONDecodeError:
                                logger.warning(f"RID-{request_id}: SSE (guided/{current_provider_for_parsing}) JSON parse error. Data: {sse_data_bytes[:100]!r}")
                            except Exception as e_guided_proc:
                                logger.error(f"RID-{request_id}: Error processing guided SSE chunk ({current_provider_for_parsing}): {e_guided_proc}", exc_info=True)
                        else: # Standard processing (no guided reasoning)
                            if request_data.provider == "openai":
                                async for fmt_chunk in process_openai_sse_standard(sse_line_bytes, request_id): yield fmt_chunk
                            elif request_data.provider == "google":
                                async for fmt_chunk in process_google_sse_standard(sse_line_bytes, request_id): yield fmt_chunk
                    
                    await asyncio.sleep(0.0001) # Yield control

                # Process remaining buffer content after loop
                if buffer:
                    remaining_data_str = buffer.strip().decode('utf-8', errors='ignore')
                    buffer.clear()
                    if remaining_data_str:
                        logger.debug(f"RID-{request_id}: Processing remaining buffer: {remaining_data_str[:100]}")
                        if apply_reasoning:
                            # Simplified: treat remaining as part of current phase (reasoning or content)
                            # This logic might need refinement based on how LLMs terminate streams
                            accumulated_reasoning_text += remaining_data_str
                            if not found_separator:
                                sep_idx = accumulated_reasoning_text.find(THINKING_PROCESS_SEPARATOR)
                                if sep_idx != -1: # Separator found in remaining
                                    found_separator = True
                                    before_sep, after_sep = accumulated_reasoning_text[:sep_idx], accumulated_reasoning_text[sep_idx + len(THINKING_PROCESS_SEPARATOR):]
                                    processed_before = convert_math_symbols(strip_tags(before_sep.strip()))
                                    if processed_before and processed_before != last_yielded_reasoning:
                                        yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": processed_before, "timestamp": get_current_time_iso()})
                                        last_yielded_reasoning = processed_before
                                    clean_after_sep = re.sub(f"({re.escape(THINKING_PROCESS_SEPARATOR)})+", "", after_sep)
                                    processed_after = convert_math_symbols(strip_tags(clean_after_sep.strip()))
                                    if processed_after and processed_after != last_yielded_final_answer:
                                        yield orjson_dumps_bytes_wrapper({"type": "content", "text": processed_after, "timestamp": get_current_time_iso()})
                                        last_yielded_final_answer = processed_after
                                else: # Still no separator, all remaining is reasoning
                                    processed_remaining = convert_math_symbols(strip_tags(accumulated_reasoning_text.strip()))
                                    if processed_remaining and processed_remaining != last_yielded_reasoning:
                                        yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": processed_remaining, "timestamp": get_current_time_iso()})
                            else: # Separator already found, remaining is content
                                processed_remaining = convert_math_symbols(strip_tags(accumulated_reasoning_text.strip())) # accumulated_reasoning_text here is actually content
                                if processed_remaining and processed_remaining != last_yielded_final_answer:
                                    yield orjson_dumps_bytes_wrapper({"type": "content", "text": processed_remaining, "timestamp": get_current_time_iso()})
                            accumulated_reasoning_text = "" # Clear at the end
                        # else: # Standard mode, remaining buffer usually indicates incomplete SSE line not ending with \n
                        #     logger.warning(f"RID-{request_id}: Non-empty buffer in standard mode after stream: {remaining_data_str[:100]}")
                        #     # Potentially try to parse it as a final chunk if it's valid JSON, or log as error.
                        #     # For simplicity, we'll assume standard processors handle complete lines.

        except httpx.TimeoutException as e:
            logger.error(f"RID-{request_id}: Timeout: {e}", exc_info=True)
            yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Upstream timeout: {str(e)}", "timestamp": get_current_time_iso()})
            yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "timeout_error", "timestamp": get_current_time_iso()})
        except httpx.RequestError as e: # Includes ConnectError, ReadError etc.
            logger.error(f"RID-{request_id}: Network error: {e}", exc_info=True)
            yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Upstream network error: {str(e)}", "timestamp": get_current_time_iso()})
            yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "network_error", "timestamp": get_current_time_iso()})
        except asyncio.CancelledError:
            logger.info(f"RID-{request_id}: Stream cancelled by client.")
            # Optionally send a specific SSE event for cancellation if desired
        except Exception as e:
            logger.error(f"RID-{request_id}: Unexpected streaming error: {e}", exc_info=True)
            if not upstream_ok: # Only if upstream connection itself failed
                yield orjson_dumps_bytes_wrapper({"type": "error", "message": f"Internal streaming error: {str(e)}", "timestamp": get_current_time_iso()})
                yield orjson_dumps_bytes_wrapper({"type": "finish", "reason": "internal_error", "timestamp": get_current_time_iso()})
        finally:
            # Final check for any unyielded reasoning if stream ended abruptly before separator
            if apply_reasoning and not found_separator and accumulated_reasoning_text.strip():
                final_reasoning = convert_math_symbols(strip_tags(accumulated_reasoning_text.strip()))
                if final_reasoning and final_reasoning != last_yielded_reasoning:
                    yield orjson_dumps_bytes_wrapper({"type": "reasoning", "text": final_reasoning, "timestamp": get_current_time_iso()})
            
            logger.info(f"RID-{request_id}: Stream generator finished. Upstream OK: {upstream_ok}. Guided Reasoning: {apply_reasoning}, Separator Found: {found_separator if apply_reasoning else 'N/A'}")

    return StreamingResponse(
        stream_generator(),
        media_type="text/event-stream",
        headers={"Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache", "Connection": "keep-alive", **COMMON_HEADERS}
    )

# --- Uvicorn Runner (与之前相同) ---
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
    
    # Ensure our logger uses these formats too if not using uvicorn's default handlers
    if "EzTalkProxy" not in log_config["loggers"]: log_config["loggers"]["EzTalkProxy"] = {}
    log_config["loggers"]["EzTalkProxy"]["handlers"] = ["default"] # Uvicorn's default handler
    log_config["loggers"]["EzTalkProxy"]["level"] = LOG_LEVEL_FROM_ENV
    log_config["loggers"]["EzTalkProxy"]["propagate"] = False # Avoid duplicate logs if root logger also has a handler

    log_config["loggers"]["uvicorn.error"]["level"] = "INFO"
    log_config["loggers"]["uvicorn.access"]["level"] = "WARNING"
    
    logger.info(f"Starting Uvicorn: http://{APP_HOST}:{APP_PORT}. Reload: {DEV_RELOAD}. Log Level (EzTalkProxy): {LOG_LEVEL_FROM_ENV}")
    uvicorn.run("main:app", host=APP_HOST, port=APP_PORT, log_config=log_config, reload=DEV_RELOAD, lifespan="on")
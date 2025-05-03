# main.py - 支持 OpenAI & Google 的通用代理服务（性能优化版）
import os
import json
import fastapi
from fastapi import FastAPI, Request, Depends, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, JSONResponse
from pydantic import BaseModel, Field, validator
from typing import List, Dict, Any, Literal, Optional, AsyncGenerator
import httpx
import logging
from contextlib import asynccontextmanager

# --- 配置日志 ---
logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO"),
    format='%(asctime)s - %(levelname)s - [%(name)s] - %(message)s'
)
logger = logging.getLogger(__name__)
logging.getLogger("httpx").setLevel(logging.WARNING)

# --- 常量定义 ---
API_TIMEOUT = int(os.getenv("API_TIMEOUT", 300))
MAX_CONNECTIONS = int(os.getenv("MAX_CONNECTIONS", 200))
PORT = int(os.getenv("PORT", 8000))
OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
GOOGLE_API_BASE = "https://generativelanguage.googleapis.com"

# --- 预定义响应头 ---
COMMON_HEADERS = {"X-Accel-Buffering": "no"}
OPENAI_HEADERS = {
    "Content-Type": "application/json",
    "Accept": "application/json"
}
GOOGLE_HEADERS = {
    "Content-Type": "application/json",
    "Accept": "text/event-stream"
}

# --- 全局HTTP客户端 ---
http_client: Optional[httpx.AsyncClient] = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    global http_client
    try:
        http_client = httpx.AsyncClient(
            timeout=httpx.Timeout(API_TIMEOUT),
            limits=httpx.Limits(max_connections=MAX_CONNECTIONS),
            http2=True  # 启用HTTP/2提升性能
        )
        logger.info("HTTP Client initialized")
        yield
    finally:
        if http_client:
            await http_client.aclose()
            logger.info("HTTP Client closed")

# --- FastAPI 初始化 ---
app = FastAPI(
    title="EzTalk Proxy",
    lifespan=lifespan,
    docs_url=None,  # 禁用文档减少开销
    redoc_url=None
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["*"]  # 优化CORS处理
)

# --- 模型定义 ---
class Message(BaseModel):
    role: str
    content: Optional[str] = None

class ChatRequest(BaseModel):
    messages: List[Message]
    provider: Literal["openai", "google"] = "openai"
    model: str = Field(..., description="目标模型名称")
    api_key: str
    temperature: Optional[float] = None
    top_p: Optional[float] = None
    max_tokens: Optional[int] = None
    tools: Optional[List[Dict]] = None
    tool_config: Optional[Dict] = None

# --- 预编译JSON编码器 ---
json_dumps = json.JSONEncoder(ensure_ascii=False, separators=(',', ':')).encode

# --- 错误响应 ---
def error_response(code: int, msg: str):
    return JSONResponse(
        status_code=code,
        content={"error": msg},
        headers=COMMON_HEADERS
    )

# --- 流式解析优化 ---
async def parse_openai(response: httpx.Response) -> AsyncGenerator[str, None]:
    buffer = bytearray()
    try:
        async for chunk in response.aiter_bytes():
            if not chunk:
                continue
                
            buffer.extend(chunk)
            while b'\n' in buffer:
                line, buffer = buffer.split(b'\n', 1)
                if not line.startswith(b'data:'):
                    continue
                    
                raw = line[5:].strip()
                if not raw or raw == b'[DONE]':
                    continue
                    
                try:
                    data = json.loads(raw)
                    if 'choices' in data:
                        for choice in data['choices']:
                            if 'delta' in choice and 'content' in choice['delta']:
                                yield json_dumps({"type": "content", "text": choice['delta']['content']}) + "\n"
                            if 'finish_reason' in choice:
                                yield json_dumps({"type": "finish", "reason": choice['finish_reason']}) + "\n"
                except json.JSONDecodeError:
                    continue
    finally:
        await response.aclose()

async def parse_google(response: httpx.Response) -> AsyncGenerator[str, None]:
    buffer = bytearray()
    try:
        async for chunk in response.aiter_bytes():
            if not chunk:
                continue
                
            buffer.extend(chunk)
            while b'\n\n' in buffer:
                event_block, buffer = buffer.split(b'\n\n', 1)
                for line in event_block.split(b'\n'):
                    if not line.startswith(b'data:'):
                        continue
                        
                    raw = line[5:].strip()
                    if not raw:
                        continue
                        
                    if raw == b'[DONE]':
                        yield json_dumps({"type": "finish"}) + "\n"
                        return
                        
                    try:
                        data = json.loads(raw)
                        for candidate in data.get('candidates', []):
                            parts = candidate.get('content', {}).get('parts', [])
                            for part in parts:
                                if 'text' in part:
                                    yield json_dumps({"type": "content", "text": part['text']}) + "\n"
                    except json.JSONDecodeError:
                        continue
    finally:
        await response.aclose()

# --- 主接口优化 ---
@app.post("/chat")
async def chat_proxy(
    request: ChatRequest,
    client: httpx.AsyncClient = Depends(lambda: http_client)
):
    try:
        # 预构建基础请求参数
        headers = {
            "Authorization": f"Bearer {request.api_key}",
            **OPENAI_HEADERS if request.provider == "openai" else GOOGLE_HEADERS
        }
        payload = {
            "model": request.model,
            "temperature": request.temperature,
            "top_p": request.top_p,
            "max_tokens": request.max_tokens,
            **(request.tools and {"tools": request.tools} or {}),
            **(request.tool_config and {"tool_config": request.tool_config} or {})
        }

        # 平台特定参数
        if request.provider == "openai":
            payload.update({
                "messages": [m.model_dump(exclude_unset=True) for m in request.messages],
                "stream": True
            })
            url = OPENAI_API_URL
        elif request.provider == "google":
            contents = []
            for msg in request.messages:
                contents.append({
                    "role": "model" if msg.role == "assistant" else "user",
                    "parts": [{"text": msg.content or ""}]
                })
            payload["contents"] = contents
            url = f"{GOOGLE_API_BASE}/v1beta/models/{request.model}:streamGenerateContent?key={request.api_key}&alt=sse"
        else:
            raise HTTPException(400, "Unknown provider")

        # 使用更高效的请求构建方式
        upstream_resp = await client.post(
            url,
            headers=headers,
            json=payload,
            stream=True
        )
        
        try:
            upstream_resp.raise_for_status()
        except httpx.HTTPStatusError as e:
            await upstream_resp.aclose()
            logger.warning(f"Upstream error: {e.response.text}")
            return error_response(502, f"上游服务错误: {e.response.status_code}")

        # 流式响应
        parser = parse_google if request.provider == "google" else parse_openai
        return StreamingResponse(
            parser(upstream_resp),
            media_type="text/event-stream",
            headers=COMMON_HEADERS
        )

    except Exception as e:
        logger.exception("Unexpected error")
        return error_response(500, f"Internal server error: {str(e)}")

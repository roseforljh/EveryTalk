# main.py
import fastapi
from fastapi import FastAPI, HTTPException, Request, Depends
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
import httpx
import json
import os
import datetime
from pydantic import BaseModel, Field, Extra
from typing import List, Dict, Any, Literal, AsyncGenerator, Optional
import asyncio
import logging # 确保 logging 被导入
import urllib.parse
from contextlib import asynccontextmanager

# --- 日志设置 ---
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - [%(funcName)s] - %(message)s')
logger = logging.getLogger(__name__)
logging.getLogger("httpx").setLevel(logging.WARNING)

# --- 可选：使用 orjson ---
_json_loads = json.loads
_json_dumps = json.dumps
ORJSONResponse = JSONResponse

try:
    import orjson
    class _ORJSONResponse(JSONResponse):
        media_type = "application/json"
        def render(self, content: Any) -> bytes:
            try: return orjson.dumps(content, option=orjson.OPT_NON_STR_KEYS | orjson.OPT_SERIALIZE_NUMPY | orjson.OPT_APPEND_NEWLINE)
            except TypeError as e: logger.warning(f"orjson serialization failed: {e}. Fallback."); return json.dumps(content, ensure_ascii=False).encode('utf-8') + b'\n'
    def _orjson_dumps(v, *, default=None) -> str:
        try: return orjson.dumps(v, option=orjson.OPT_NON_STR_KEYS | orjson.OPT_SERIALIZE_NUMPY).decode()
        except TypeError as e: logger.warning(f"orjson dumps failed: {e}. Fallback."); return json.dumps(v, ensure_ascii=False)
    _json_dumps = _orjson_dumps
    _json_loads = orjson.loads
    ORJSONResponse = _ORJSONResponse
    logger.info("Using orjson.")
except ImportError: logger.warning("orjson not installed. Fallback to standard json.")
except Exception as e_orjson: logger.error(f"Error setting up orjson: {e_orjson}. Fallback.", exc_info=True); _json_loads=json.loads; _json_dumps=json.dumps; ORJSONResponse=JSONResponse

# --- 全局 HTTP 客户端 ---
http_client: Optional[httpx.AsyncClient] = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    global http_client
    limits = httpx.Limits(max_connections=200, max_keepalive_connections=50)
    timeout = httpx.Timeout(300.0, connect=60.0)
    http_client = httpx.AsyncClient(timeout=timeout, follow_redirects=True, limits=limits)
    logger.info(f"HTTPX Client initialized: Limits={limits!r}, Timeout={timeout!r}")
    yield
    if http_client:
        try: await http_client.aclose(); logger.info("HTTPX Client closed.")
        except Exception as e: logger.error(f"Error closing HTTPX client: {e}", exc_info=True)
    else: logger.warning("HTTPX Client was None during shutdown.")

# --- 模型定义 ---
class Message(BaseModel, extra=Extra.allow): role: str; content: Optional[str] = None
class ChatRequest(BaseModel):
    messages: List[Message] = Field(...)
    provider: Literal["openai", "google"] = Field(...)
    api_address: str = Field(...) # 对于 OpenAI provider，这里预期是基础地址
    api_key: str = Field(...)
    model: str = Field(...)
    temperature: Optional[float] = None
    top_p: Optional[float] = None
    max_tokens: Optional[int] = None

# --- FastAPI 应用实例和 CORS ---
app = FastAPI(
    title="EzTalk Multi-Provider Backend API",
    description="Backend handling SSE/Google streams with reasoning support. OpenAI provider auto-appends path. Console output disabled.",
    version="3.2.2", # 版本更新：移除控制台流式输出
    lifespan=lifespan,
    default_response_class=ORJSONResponse
)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])

# --- 依赖注入 ---
async def get_http_client() -> httpx.AsyncClient:
    if http_client is None:
        logger.critical("CRITICAL: HTTP Client not initialized!")
        raise HTTPException(status_code=503, detail="HTTP Client Service Unavailable")
    return http_client

# --- 根路径 ---
@app.get("/")
async def read_root():
    logger.info("Root '/' accessed.")
    return {"message": "EzTalk Backend v3.2.2 (Console Output Disabled)"}

# --- 辅助函数：错误处理 (保持健壮版本) ---
async def handle_api_error(error: httpx.HTTPStatusError, provider: str) -> JSONResponse:
    status_code = error.response.status_code
    error_message = f"Failed to communicate with {provider.capitalize()} API (Status: {status_code})."
    response_body_text = "[Could not read error response body]" # Default placeholder
    error_body = None
    content_to_return = {"success": False, "error_message": ""}
    try:
        error_body = await error.response.aread()
        response_body_text = error_body.decode('utf-8', errors='replace')
        logger.debug(f"Raw error body read from {provider} (Status {status_code}): {response_body_text[:500]}")
        try:
            error_detail_json = _json_loads(error_body)
            detail = error_detail_json; extracted_msg = None
            if isinstance(detail, dict):
                 err_obj = detail.get("error", detail)
                 if isinstance(err_obj, dict):
                      extracted_msg = err_obj.get("message", err_obj.get("detail"))
                      if not extracted_msg and "code" in err_obj: extracted_msg = f"Code: {err_obj.get('code')}, Type: {err_obj.get('type')}"
                      if not extracted_msg and "fault" in err_obj and isinstance(err_obj["fault"], dict): extracted_msg = err_obj["fault"].get("faultString") or err_obj["fault"].get("detail")
                 elif isinstance(err_obj, str): extracted_msg = err_obj
                 elif "detail" in detail and isinstance(detail["detail"], str): extracted_msg = detail["detail"]
                 elif "message" in detail and isinstance(detail["message"], str): extracted_msg = detail["message"]
                 if extracted_msg: error_message += f" Details: {extracted_msg}"
                 else: error_message += f" Raw JSON Error: {_json_dumps(error_detail_json)[:200]}..."
            else: error_message += f" Unexpected Response Format: {str(detail)[:200]}..."
        except (json.JSONDecodeError, orjson.JSONDecodeError): error_message += f" Raw Text Response: {response_body_text[:200]}..."
        except Exception as parse_exc: logger.error(f"Error parsing error detail JSON: {parse_exc}", exc_info=True); error_message += f" Raw Text Response (parsing failed): {response_body_text[:200]}..."
    except (httpx.StreamClosed, httpx.ResponseNotRead, httpx.StreamError) as read_err: logger.warning(f"Could not read error response body from {provider} (Status: {status_code}). Reason: {type(read_err).__name__}."); error_message += f" Could not read error details ({type(read_err).__name__})."
    except Exception as e: logger.error(f"Unexpected error reading/decoding upstream error response body: {e}", exc_info=True); error_message += " Error retrieving detailed error message from provider."
    log_message = f"{provider.capitalize()} API Error (Status {status_code}). Final Message: {error_message}\nResponse Body Snippet:\n{response_body_text[:500]}"; logger.error(log_message)
    content_to_return["error_message"] = error_message
    effective_status_code = status_code if 400 <= status_code < 500 else 502
    try: await error.response.aclose()
    except Exception: pass
    return ORJSONResponse(status_code=effective_status_code, content=content_to_return)

# --- Helper to format error chunks for streaming ---
def yield_error_chunk(message: str) -> str:
    error_payload = {"type": "error", "text": message}
    logger.error(f"Yielding stream error chunk: {message}")
    return _json_dumps(error_payload) + "\n"

# --- Stream Parsers ---
async def parse_openai_sse_and_yield(response: httpx.Response) -> AsyncGenerator[str, None]:
    buffer = b""; logger.info("Starting standard OpenAI SSE parsing (with reasoning support)..."); processed_chunk_count = 0; stream_ended_normally = False
    try:
        async for chunk in response.aiter_bytes():
            buffer += chunk
            while b"\n\n" in buffer:
                event_chunk, buffer = buffer.split(b"\n\n", 1); event_data_bytes = b''
                for line in event_chunk.splitlines():
                    if line.startswith(b"data:"): event_data_bytes = line[len(b"data:"):] ; break
                if event_data_bytes:
                    stripped_data = event_data_bytes.strip()
                    if stripped_data == b"[DONE]": logger.info("SSE stream finished ([DONE] marker received)."); stream_ended_normally = True; return # Removed print()
                    if not stripped_data: continue
                    try:
                        data = _json_loads(stripped_data); choices = data.get("choices", [])
                        if not choices or not isinstance(choices, list): continue
                        choice = choices[0] if choices else {}; delta = choice.get("delta", {}) if isinstance(choice, dict) else {}; finish_reason = choice.get("finish_reason")
                        if finish_reason: logger.info(f"SSE stream finished (finish_reason: {finish_reason})."); stream_ended_normally = True; return # Removed print()
                        output_payload = None; # log_prefix no longer needed for print
                        reasoning_chunk = delta.get('reasoning_content') if isinstance(delta, dict) else None
                        content_chunk = delta.get('content') if isinstance(delta, dict) else None
                        if isinstance(reasoning_chunk, str) and reasoning_chunk: output_payload = {"type": "reasoning", "text": reasoning_chunk}
                        elif isinstance(content_chunk, str) and content_chunk: output_payload = {"type": "content", "text": content_chunk}
                        if output_payload:
                            processed_chunk_count += 1
                            # --- REMOVED CONSOLE PRINT BLOCK ---
                            yield _json_dumps(output_payload) + "\n"
                    except (json.JSONDecodeError, orjson.JSONDecodeError) as e: logger.warning(f"Failed to decode SSE JSON chunk: {stripped_data!r}. Error: {e}")
                    except IndexError: logger.warning(f"IndexError processing SSE choices: {stripped_data!r}")
                    except Exception as e: logger.error(f"Unexpected error processing SSE chunk: {e} - Bytes: {stripped_data!r}", exc_info=True)
    except httpx.StreamError as e: logger.error(f"[OpenAI] StreamError during SSE processing: {e}", exc_info=True); yield yield_error_chunk(f"Stream error while reading from OpenAI API: {e}")
    except Exception as e: logger.error(f"[OpenAI] Unexpected error in SSE parser: {e}", exc_info=True); yield yield_error_chunk(f"Internal server error during OpenAI stream processing.")
    finally:
        if not response.is_closed:
            try: await response.aclose()
            except Exception as close_err: logger.warning(f"Error closing OpenAI response stream: {close_err}")
        if not stream_ended_normally and buffer: logger.warning(f"OpenAI SSE stream ended unexpectedly. Buffer: {buffer.decode(errors='ignore')[:200]}...")
        elif buffer: logger.debug(f"OpenAI SSE stream finished. Remainder: {buffer.decode(errors='ignore')!r}")
        logger.info(f"OpenAI SSE parsing finished. Chunks: {processed_chunk_count}. Normal End: {stream_ended_normally}")

async def parse_google_sse_and_yield(response: httpx.Response) -> AsyncGenerator[str, None]:
    buffer = b""; brace_level = 0; potential_json_start = -1; processed_chunk_count = 0; stream_ended_normally = False
    logger.info("Starting Google JSON stream parsing (Accumulation)...")
    try:
        async for chunk in response.aiter_bytes():
            buffer += chunk; scan_offset = 0
            while True:
                found_json = False; current_scan_pos = scan_offset
                if potential_json_start != -1: current_scan_pos = potential_json_start
                i = current_scan_pos
                while i < len(buffer):
                    byte = buffer[i:i+1]
                    if potential_json_start == -1:
                        if byte == b'{': brace_level = 1; potential_json_start = i
                    else:
                        if byte == b'{': brace_level += 1
                        elif byte == b'}':
                            brace_level -= 1
                            if brace_level == 0:
                                json_bytes = buffer[potential_json_start : i + 1]
                                try:
                                    data = _json_loads(json_bytes); candidates = data.get("candidates")
                                    if isinstance(candidates, list) and candidates:
                                        first_candidate = candidates[0]; content = first_candidate.get("content", {}); parts = content.get("parts")
                                        if isinstance(parts, list) and parts:
                                            text_chunk = parts[0].get("text")
                                            if isinstance(text_chunk, str):
                                                output_payload = {"type": "content", "text": text_chunk}; processed_chunk_count += 1
                                                # --- REMOVED CONSOLE PRINT BLOCK ---
                                                yield _json_dumps(output_payload) + "\n"
                                        finish_reason = first_candidate.get("finishReason")
                                        if finish_reason and finish_reason not in ("FINISH_REASON_UNSPECIFIED", "NOT_SET"):
                                            logger.info(f"Google stream finished (finishReason: {finish_reason}).")
                                            if finish_reason == "SAFETY": logger.warning("Google stopped: safety."); yield yield_error_chunk("Content blocked by safety filter.")
                                            elif finish_reason == "MAX_TOKENS": logger.info("Google finished: max tokens.")
                                            stream_ended_normally = True; buffer = b""; return # Removed print()
                                except (json.JSONDecodeError, orjson.JSONDecodeError) as e: logger.warning(f"Failed decode Google JSON: {json_bytes!r}. Err: {e}")
                                except Exception as e: logger.error(f"Err processing Google JSON: {e} - Bytes: {json_bytes!r}", exc_info=True)
                                buffer = buffer[i + 1 :]; potential_json_start = -1; brace_level = 0; scan_offset = 0; found_json = True; break
                    i += 1
                if not found_json: scan_offset = len(buffer); break
    except httpx.StreamError as e: logger.error(f"[Google] StreamError: {e}", exc_info=True); yield yield_error_chunk(f"Stream error Google")
    except Exception as e: logger.error(f"[Google] Unexpected Google parser err: {e}", exc_info=True); yield yield_error_chunk(f"Internal err Google stream")
    finally:
        if not response.is_closed:
            try: await response.aclose()
            except Exception as close_err: logger.warning(f"Error closing Google response stream: {close_err}")
        if not stream_ended_normally:
             if brace_level > 0 or potential_json_start != -1: logger.warning(f"Google stream ended incomplete JSON. Buffer: {buffer.decode(errors='ignore')[:200]}...")
             elif buffer: logger.warning(f"Google stream ended unprocessed buffer: {buffer.decode(errors='ignore')[:200]}...")
        logger.info(f"Google JSON parsing finished. Chunks: {processed_chunk_count}. Normal End: {stream_ended_normally}")

# --- 对话接口 (Streaming Enabled) ---
@app.post("/chat")
async def chat_streaming(request: ChatRequest, client: httpx.AsyncClient = Depends(get_http_client)):
    provider = request.provider
    request_start_time = datetime.datetime.now()
    target_api_url = "" # 初始化
    try:
        # --- Message Validation and Filtering (保持不变) ---
        valid_input_messages = []
        skipped_count = 0
        allowed_roles = {"user", "assistant", "system", "tool"}
        for i, msg in enumerate(request.messages):
            if msg.role and msg.content and msg.content.strip():
                 if msg.role not in allowed_roles: logger.warning(f"Skip msg #{i} invalid role: '{msg.role}'."); skipped_count += 1; continue
                 valid_input_messages.append(msg)
            else: logger.warning(f"Skip msg #{i} empty content. Role='{msg.role}'"); skipped_count += 1
        if not valid_input_messages: logger.error("Req failed: No valid messages."); raise HTTPException(status_code=400, detail="No valid messages provided.")
        logger.debug(f"Input messages: {len(valid_input_messages)} valid, {skipped_count} skipped.")

        # --- Provider-Specific Configuration ---
        payload = {}; headers = {}; api_params: Optional[Dict[str, Any]] = None; sse_parser: Optional[callable] = None
        base_api_address = request.api_address.rstrip('/')

        if provider == "openai":
            target_api_url = base_api_address + "/v1/chat/completions"
            logger.info(f"Constructed OpenAI target URL: {target_api_url}")
            headers = {"Content-Type": "application/json", "Authorization": f"Bearer {request.api_key}"}
            openai_messages = [msg.model_dump(exclude_none=True, mode='json') for msg in valid_input_messages]
            payload = { "model": request.model, "messages": openai_messages, "stream": True }
            if request.temperature is not None: payload["temperature"] = request.temperature
            if request.top_p is not None: payload["top_p"] = request.top_p
            if request.max_tokens is not None: payload["max_tokens"] = request.max_tokens; logger.debug(f"Using client-provided max_tokens: {request.max_tokens}")
            else: logger.debug("No max_tokens provided by client, relying on API default.")
            sse_parser = parse_openai_sse_and_yield
            logger.debug(f"Configured for OpenAI provider. Parser: {sse_parser.__name__}")

        elif provider == "google":
            model_action_path = f"v1beta/models/{request.model}:streamGenerateContent"
            api_params = {"key": request.api_key, "alt": "sse"}
            target_api_url = urllib.parse.urljoin(base_api_address + '/', model_action_path)
            logger.info(f"Constructed Google target URL: {target_api_url}")
            headers = {"Content-Type": "application/json"}
            contents = []; last_role = None; temp_parts = []
            for msg in valid_input_messages:
                current_role = None
                if msg.role == "assistant": current_role = "model"
                elif msg.role == "user": current_role = "user"
                else: logger.warning(f"Skipping message role '{msg.role}' for Google."); continue
                if current_role == last_role:
                     if temp_parts: temp_parts.append({"text": msg.content})
                     else: logger.warning(f"Consecutive role '{current_role}' but no temp_parts. Skipping.")
                else:
                    if last_role and temp_parts: contents.append({"role": last_role, "parts": temp_parts})
                    temp_parts = [{"text": msg.content}]
                    last_role = current_role
            if last_role and temp_parts: contents.append({"role": last_role, "parts": temp_parts})
            if not contents: raise HTTPException(status_code=400, detail="No messages suitable for the Google API format.")
            if contents[-1]["role"] != "user": raise HTTPException(status_code=400, detail="Last message for Google API must be from 'user'.")
            payload = {"contents": contents}
            generation_config = {}
            if request.temperature is not None: generation_config["temperature"] = request.temperature
            if request.top_p is not None: generation_config["topP"] = request.top_p
            if request.max_tokens is not None: generation_config["maxOutputTokens"] = request.max_tokens; logger.debug(f"Using client-provided max_tokens (for Google): {request.max_tokens}")
            else: logger.debug("No max_tokens provided by client (for Google), relying on API default.")
            if generation_config: payload["generationConfig"] = generation_config
            sse_parser = parse_google_sse_and_yield
            logger.debug(f"Configured for Google provider. Parser: {sse_parser.__name__}. Params: {api_params}")

        else:
            logger.critical(f"Internal logic error: Unsupported provider '{provider}'.")
            raise HTTPException(status_code=501, detail=f"Provider '{provider}' is not implemented.")

        if not sse_parser:
            logger.critical("Internal logic error: SSE Parser not assigned.")
            raise HTTPException(status_code=500, detail="Internal Server Error: Stream parser config failed.")

        logger.info(f"Received /chat request: provider={provider}, model='{request.model}', final_target_url='{target_api_url}', msgs={len(valid_input_messages)}")
        logger.info(f"Sending streaming POST request to: {target_api_url}")

        req = client.build_request("POST", target_api_url, headers=headers, json=payload, params=api_params)
        upstream_response = await client.send(req, stream=True)
        connect_time = datetime.datetime.now()
        logger.info(f"Connection to {provider} API established (Status: {upstream_response.status_code}). Time: {(connect_time - request_start_time).total_seconds():.2f}s")

        try: upstream_response.raise_for_status()
        except httpx.HTTPStatusError as e:
            logger.warning(f"Initial connection to {provider} failed: {e.response.status_code}. Handling error.")
            if not upstream_response.is_closed: await upstream_response.aclose()
            return await handle_api_error(e, provider)

        # --- Define the Generator for StreamingResponse ---
        async def stream_generator() -> AsyncGenerator[str, None]:
            generator_start_time = datetime.datetime.now()
            logger.debug(f"[{provider}] Stream generator initiated.")
            chunk_count = 0
            parser_error = None
            nonlocal upstream_response

            try:
                # Call the appropriate parser function, passing the response stream
                async for processed_chunk in sse_parser(upstream_response):
                    yield processed_chunk
                    chunk_count += 1
            except Exception as e_gen:
                parser_error = e_gen
                logger.error(f"[{provider}] Error occurred within the stream generator or parser: {e_gen}", exc_info=True)
                try:
                    yield yield_error_chunk(f"Internal error processing {provider} response stream.")
                except Exception as yield_err:
                    logger.error(f"[{provider}] Critical: Failed to yield final error chunk to client: {yield_err}")
            finally:
                if upstream_response and not upstream_response.is_closed:
                    try:
                        await upstream_response.aclose()
                        logger.debug(f"[{provider}] Upstream response stream explicitly closed.")
                    except Exception as e_close:
                        logger.warning(f"[{provider}] Error closing upstream response stream in finally block: {e_close}", exc_info=True)

                generator_end_time = datetime.datetime.now()
                duration = (generator_end_time - generator_start_time).total_seconds()
                status = "failed" if parser_error else "completed"
                logger.info(f"[{provider}] Stream generator {status}. Chunks yielded: {chunk_count}. Duration: {duration:.2f}s. Error: {type(parser_error).__name__ if parser_error else 'None'}")

        # Return the StreamingResponse
        return StreamingResponse(stream_generator(), media_type="text/event-stream; charset=utf-8")

    except httpx.RequestError as e:
        effective_url_for_log = target_api_url if target_api_url else request.api_address
        logger.error(f"Network error connecting to {provider} API near {effective_url_for_log}: {e}", exc_info=True)
        error_msg = f"Could not connect upstream {provider} API."; status_code = 503
        if isinstance(e, httpx.ConnectTimeout): status_code = 504; error_msg = f"Timeout connecting upstream {provider} API."
        elif isinstance(e, httpx.ConnectError): error_msg = f"Connection error for upstream {provider} API."
        return ORJSONResponse(status_code=status_code, content={"success": False, "error_message": error_msg})
    except HTTPException as e: logger.debug(f"Re-raising HTTPException: {e.status_code}"); raise e
    except Exception as e: logger.critical(f"CRITICAL UNEXPECTED error setting up stream for {provider}: {e}", exc_info=True); return ORJSONResponse(status_code=500, content={"success": False, "error_message": "Internal server error."})
    finally:
        request_end_time = datetime.datetime.now(); total_duration = (request_end_time - request_start_time).total_seconds(); logger.info(f"Finished /chat req {provider}. Total duration: {total_duration:.2f}s")


# --- Run Server ---
if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True, workers=1)
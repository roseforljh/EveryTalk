import httpx
import asyncio
import time
import json
import os

# --- 配置 ---
API_URL = "https://api.siliconflow.cn/v1/chat/completions"
# 从环境变量或直接设置你的 API Key
API_KEY = os.getenv("。。。", "sk-riwgfehifihkluefbtssjgqejrgbmxshzqbkynrfbykupfmj")
HEADERS = {
    "Authorization": f"Bearer {API_KEY}",
    "Content-Type": "application/json",
    "Accept": "text/event-stream", # 明确要求 event-stream
}
PAYLOAD = {
    "model": "deepseek-ai/DeepSeek-R1", # 或者 Qwen
    "messages": [{"role": "user", "content": "hi"}],
    "stream": True,
}

async def main():
    print("--- Minimal HTTPX Stream Test ---")
    # 确保不使用任何代理
    async with httpx.AsyncClient(timeout=300.0, trust_env=False) as client:
        start_time = time.time()
        try:
            print(f"Sending request to {API_URL} at {start_time:.4f}")
            async with client.stream("POST", API_URL, headers=HEADERS, json=PAYLOAD) as response:
                headers_received_time = time.time()
                print(f"Received status {response.status_code} in {headers_received_time - start_time:.4f}s")
                response.raise_for_status() # 检查状态码

                first_chunk_time = None
                async for chunk in response.aiter_bytes():
                    now = time.time()
                    if first_chunk_time is None:
                        first_chunk_time = now
                        print(f"First chunk received at {now:.4f}. Time since headers: {now - headers_received_time:.4f}s. Size: {len(chunk)}")
                    # else:
                    #    print(f"Next chunk received at {now:.4f}. Size: {len(chunk)}")
    
                    # 简单打印接收到的原始字节 (部分)
                    print(f"Raw chunk sample: {chunk[:150]!r}")
    
                    # 你也可以在这里加入之前的逐行解析逻辑进行更详细的测试
                    # ...
    
        except httpx.RequestError as e:
            print(f"HTTPX Request Error: {e}")
        except Exception as e:
            print(f"An unexpected error occurred: {e}")
        finally:
            end_time = time.time()
            print(f"--- Test finished at {end_time:.4f}. Total duration: {end_time - start_time:.4f}s ---")

if __name__ == "__main__":
    # 确保设置 API Key
    if API_KEY == "你的_API_KEY":
        print("错误：请在脚本中或通过环境变量 SILICONFLOW_API_KEY 设置你的 API Key")
    else:
        asyncio.run(main())
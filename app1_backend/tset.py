# tset.py
import httpx # 或者 import requests
import sys

print(f"Python executable: {sys.executable}") # 打印当前使用的 Python 解释器路径
print(f"Python version: {sys.version}")   # 打印 Python 版本

target_url = "https://www.googleapis.com/discovery/v1/apis"
print(f"Attempting to connect to: {target_url}")

try:
    # 使用 httpx
    response = httpx.get(target_url, timeout=20.0) # 增加超时时间到20秒
    
    # 或者如果你安装了 requests 库，可以使用 requests
    # import requests
    # response = requests.get(target_url, timeout=20)

    print(f"Status Code: {response.status_code}")
    if response.status_code == 200:
        print(f"Successfully connected!")
        # print(f"Response Text (first 200 chars): {response.text[:200]}")
    else:
        print(f"Failed to connect. Status: {response.status_code}")
        print(f"Response Text: {response.text}")

except httpx.TimeoutException as e: # 捕捉 httpx 的超时
    print(f"Error: Connection timed out. {e}")
except httpx.RequestError as e: # 捕捉 httpx 其他网络错误
    print(f"Error: Request failed. {e}")
except Exception as e:
    print(f"An unexpected error occurred: {e}")

print("Test script finished.")
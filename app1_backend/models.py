from pydantic import BaseModel, Field
from typing import List, Optional

# 用于接收来自安卓应用的请求体
class ChatRequest(BaseModel):
    user_message: str = Field(..., description="用户发送的消息内容")
    api_address: str = Field(..., description="大模型API的地址")
    api_key: str = Field(..., description="大模型API的密钥")
    model: str = Field(..., description="使用的大模型名称")
    # 如果需要支持多轮对话，可能还需要一个 messages history 字段
    # history: List[Message] = Field(default_factory=list, description="历史对话消息")

# 用于发送回安卓应用的响应体
class ChatResponse(BaseModel):
    success: bool = Field(..., description="请求是否成功")
    ai_message: Optional[str] = Field(None, description="大模型生成的回复消息")
    error_message: Optional[str] = Field(None, description="错误信息（如果请求失败）")

# 如果需要支持多轮对话，可能需要一个消息模型
# class Message(BaseModel):
#     role: str = Field(..., description="消息角色 (user/assistant)")
#     content: str = Field(..., description="消息内容")
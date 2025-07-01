package com.example.everytalk.data.DataClass

/**
 * 统一消息接口，用于减少消息类型的冗余
 * 所有消息类型都应实现此接口
 */
interface IMessage {
    val id: String
    val role: String
    val name: String?
}
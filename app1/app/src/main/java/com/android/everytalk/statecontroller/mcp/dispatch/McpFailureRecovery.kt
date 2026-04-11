package com.android.everytalk.statecontroller.mcp.dispatch

enum class McpFailureType {
    NETWORK_ERROR,
    TIMEOUT,
    AUTH_ERROR,
    INVALID_ARGUMENT,
    EMPTY_RESULT,
    SERVER_ERROR,
    UNKNOWN,
}

enum class McpRecoveryAction {
    RETRY_ONCE,
    FAILOVER,
    FALLBACK,
    TRIP_SERVER,
}

fun classifyMcpFailure(message: String, isEmptyResult: Boolean): McpRecoveryAction {
    val normalized = message.lowercase()
    if (isEmptyResult) return McpRecoveryAction.FALLBACK
    return when {
        "401" in normalized || "403" in normalized || "unauthorized" in normalized || "forbidden" in normalized -> McpRecoveryAction.TRIP_SERVER
        "timeout" in normalized || "timed out" in normalized -> McpRecoveryAction.FAILOVER
        "invalid" in normalized || "argument" in normalized -> McpRecoveryAction.RETRY_ONCE
        "server" in normalized || "500" in normalized -> McpRecoveryAction.FAILOVER
        else -> McpRecoveryAction.FALLBACK
    }
}

fun nextRecoveryAction(
    failureType: McpFailureType,
    hasSameCategoryBackup: Boolean,
    retryCount: Int,
): McpRecoveryAction {
    return when (failureType) {
        McpFailureType.AUTH_ERROR -> McpRecoveryAction.TRIP_SERVER
        McpFailureType.TIMEOUT,
        McpFailureType.SERVER_ERROR,
        McpFailureType.NETWORK_ERROR -> if (hasSameCategoryBackup) McpRecoveryAction.FAILOVER else McpRecoveryAction.FALLBACK
        McpFailureType.INVALID_ARGUMENT -> if (retryCount == 0) McpRecoveryAction.RETRY_ONCE else McpRecoveryAction.FALLBACK
        McpFailureType.EMPTY_RESULT -> if (hasSameCategoryBackup) McpRecoveryAction.FAILOVER else McpRecoveryAction.FALLBACK
        McpFailureType.UNKNOWN -> McpRecoveryAction.FALLBACK
    }
}

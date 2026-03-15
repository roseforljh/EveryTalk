package com.android.everytalk.statecontroller

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewModelStateHolderOpenClawStateTest {

    @Test
    fun `conversation id change does not backfill openclaw session id`() {
        val holder = ViewModelStateHolder()

        holder.updateOpenClawSessionId("remote-session-1")
        holder.setCurrentConversationId("local-conv-2")

        assertEquals("local-conv-2", holder._currentConversationId.value)
        assertEquals("remote-session-1", holder._currentOpenClawSessionId.value)
    }

    @Test
    fun `pairing pending stage updates openclaw gateway status`() {
        val holder = ViewModelStateHolder()

        holder.updateOpenClawGatewayStatus("pairing_pending:device-1")

        assertEquals(OpenClawGatewayConnectionState.PAIRING_PENDING, holder._openClawGatewayStatus.value.connectionState)
        assertEquals("device-1", holder._openClawGatewayStatus.value.pendingDeviceId)
        assertEquals("等待 OpenClaw 配对批准", holder._openClawGatewayStatus.value.statusText)
    }

    @Test
    fun `connected stage clears pairing pending state`() {
        val holder = ViewModelStateHolder()

        holder.updateOpenClawGatewayStatus("pairing_pending:device-1")
        holder.updateOpenClawGatewayStatus("connected")

        assertEquals(OpenClawGatewayConnectionState.CONNECTED, holder._openClawGatewayStatus.value.connectionState)
        assertEquals(null, holder._openClawGatewayStatus.value.pendingDeviceId)
        assertEquals("OpenClaw Gateway 已连接", holder._openClawGatewayStatus.value.statusText)
    }

    @Test
    fun `remote progress stage keeps connected state and shows remote control text`() {
        val holder = ViewModelStateHolder()

        holder.updateOpenClawGatewayStatus("connected")
        holder.updateOpenClawGatewayStatus("正在修改 /workspace/app/main.kt")

        assertEquals(OpenClawGatewayConnectionState.CONNECTED, holder._openClawGatewayStatus.value.connectionState)
        assertEquals("远程控制中 · 正在修改 /workspace/app/main.kt", holder._openClawGatewayStatus.value.statusText)
    }

}

package com.android.everytalk.ui.computer

import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.ui.screens.computer.COMPUTER_CARD_ACCENT_COLOR_COUNT
import com.android.everytalk.ui.screens.computer.computerCardAccentColorIndexes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerCardAccentColorTest {
    @Test
    fun `同一批服务器颜色分配稳定且色板容量内不重复`() {
        val computers = List(COMPUTER_CARD_ACCENT_COLOR_COUNT) { index -> computer("random-id-$index") }

        val first = computerCardAccentColorIndexes(computers)
        val second = computerCardAccentColorIndexes(computers)

        assertEquals(first, second)
        assertEquals(computers.size, first.values.toSet().size)
        assertTrue(first.values.all { it in 0 until COMPUTER_CARD_ACCENT_COLOR_COUNT })
    }

    @Test
    fun `颜色由创建时的随机服务器ID决定而非列表位置`() {
        val firstComputer = computer("random-id-a")
        val secondComputer = computer("random-id-b")
        val original = computerCardAccentColorIndexes(listOf(firstComputer, secondComputer))
        val reordered = computerCardAccentColorIndexes(listOf(secondComputer, firstComputer))

        assertEquals(original[firstComputer.id], reordered[firstComputer.id])
        assertEquals(original[secondComputer.id], reordered[secondComputer.id])
        assertNotEquals(original[firstComputer.id], original[secondComputer.id])
    }

    private fun computer(id: String): Computer = Computer(
        id = id,
        displayName = id,
        host = "example.com",
        port = 22,
        username = "root",
        authKind = ComputerAuthKind.PASSWORD,
        runMode = ComputerRunMode.DIRECT,
    )
}

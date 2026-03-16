package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandParserTest {

    @Test
    fun `parse help command`() {
        assertEquals(SlashCommand.Help, parseSlashCommand("/help"))
    }

    @Test
    fun `parse model command`() {
        assertEquals(SlashCommand.Model(), parseSlashCommand("/model"))
    }

    @Test
    fun `parse models command`() {
        assertEquals(SlashCommand.Models(), parseSlashCommand("/models"))
    }

    @Test
    fun `parse model command with args`() {
        assertEquals(SlashCommand.Model("main"), parseSlashCommand("/model main"))
    }

    @Test
    fun `parse models command with provider arg`() {
        assertEquals(SlashCommand.Models("openai"), parseSlashCommand("/models openai"))
    }

    @Test
    fun `do not fuzzy match model and models`() {
        assertNull(parseSlashCommand("/modelsx"))
        assertNull(parseSlashCommand("/modelx"))
    }

    @Test
    fun `parse new command`() {
        assertEquals(SlashCommand.New, parseSlashCommand("/new"))
    }

    @Test
    fun `parse reset command`() {
        assertEquals(SlashCommand.Reset, parseSlashCommand("/reset"))
    }

    @Test
    fun `parse reasoning on command`() {
        assertEquals(SlashCommand.Reasoning(true), parseSlashCommand("/reasoning on"))
    }

    @Test
    fun `parse reasoning off command`() {
        assertEquals(SlashCommand.Reasoning(false), parseSlashCommand("/reasoning off"))
    }

    @Test
    fun `trim surrounding spaces before parsing`() {
        assertEquals(SlashCommand.Help, parseSlashCommand("  /help  "))
    }

    @Test
    fun `return null for normal text`() {
        assertNull(parseSlashCommand("hello world"))
    }

    @Test
    fun `return null for incomplete reasoning command`() {
        assertNull(parseSlashCommand("/reasoning"))
    }

    @Test
    fun `return null for unknown command`() {
        assertNull(parseSlashCommand("/unknown"))
    }

    @Test
    fun `openclaw slash commands are only enabled for openclaw sessions`() {
        assertEquals(true, shouldHandleOpenClawSlashCommandLocally("/model", provider = "OpenClaw Remote", channel = "OpenClaw"))
        assertEquals(true, shouldHandleOpenClawSlashCommandLocally("/models", provider = "OpenClaw Remote", channel = "OpenClaw"))
        assertEquals(false, shouldHandleOpenClawSlashCommandLocally("/model", provider = "OpenAI", channel = "openai"))
        assertEquals(false, shouldHandleOpenClawSlashCommandLocally("/models", provider = "OpenAI", channel = "openai"))
    }

    @Test
    fun `model command reply uses ai bubble sender`() {
        assertEquals(Sender.AI, localSlashReplySender(SlashCommand.Model()))
    }

    @Test
    fun `models command reply uses ai bubble sender`() {
        assertEquals(Sender.AI, localSlashReplySender(SlashCommand.Models()))
    }
}

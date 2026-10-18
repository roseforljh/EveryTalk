package com.android.everytalk.data.DataClass

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExecutionFileChangeTest {
    @Test
    fun `diffLines keeps common lines and marks changed ones`() {
        val diff = diffLines("a\nb\nc\nd", "a\nx\nc\nd\ne")
        assertEquals(
            listOf(
                DiffLine(DiffLineType.Context, "a"),
                DiffLine(DiffLineType.Removed, "b"),
                DiffLine(DiffLineType.Added, "x"),
                DiffLine(DiffLineType.Context, "c"),
                DiffLine(DiffLineType.Context, "d"),
                DiffLine(DiffLineType.Added, "e"),
            ),
            diff,
        )
    }

    @Test
    fun `edit and write_file arguments become file changes`() {
        val edit = executionFileChange(
            "edit",
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive("src/a.kt"),
                    "edits" to JsonPrimitive("""[{"oldText":"old","newText":"new\nline"}]"""),
                ),
            ),
        )
        assertEquals(ExecutionFileChange("src/a.kt", false, listOf(ExecutionFileEdit("old", "new\nline"))), edit)

        val summary = fileChangeSummaries(listOf(ExecutionStep("1", ExecutionStepType.Agent, "t", fileChange = edit)))
        assertEquals(2 to 1, summary.single().additions to summary.single().deletions)

        val write = executionFileChange(
            "write_file",
            JsonObject(mapOf("path" to JsonPrimitive("b.txt"), "content" to JsonPrimitive("hi"))),
        )
        assertEquals(true, write?.written)
        assertNull(executionFileChange("exec", JsonObject(mapOf("command" to JsonPrimitive("ls"), "edits" to JsonArray(emptyList())))))
    }
}

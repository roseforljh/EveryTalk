package com.android.everytalk.data.network

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DocumentProcessorFormatPaginationTest {
    private lateinit var context: Context
    private lateinit var testDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDir = File(context.cacheDir, "document-pagination-tests").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun `PDF分页拼接与完整提取一致`() = runTest {
        PDFBoxResourceLoader.init(context)
        val file = File(testDir, "sample.pdf")
        PDDocument().use { document ->
            val page = PDPage().also(document::addPage)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(50f, 700f)
                stream.showText("PDF pagination content for EveryTalk attachment reader")
                stream.endText()
            }
            document.save(file)
        }

        assertPagedExtractionMatchesFull(file, "application/pdf")
    }

    @Test
    fun `DOCX分页拼接与完整提取一致`() = runTest {
        val file = File(testDir, "sample.docx")
        writeZipEntry(
            file = file,
            entryName = "word/document.xml",
            content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:body><w:p><w:r><w:t>DOCX第一页内容</w:t></w:r><w:r><w:t>第二页内容</w:t></w:r></w:p></w:body>
                </w:document>
            """.trimIndent(),
        )

        assertPagedExtractionMatchesFull(
            file,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
    }

    @Test
    fun `XLSX分页拼接与完整提取一致`() = runTest {
        val file = File(testDir, "sample.xlsx")
        writeZipEntry(
            file = file,
            entryName = "xl/sharedStrings.xml",
            content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <si><t>XLSX第一页内容</t></si><si><t>第二页内容</t></si>
                </sst>
            """.trimIndent(),
        )

        assertPagedExtractionMatchesFull(
            file,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )
    }

    private suspend fun assertPagedExtractionMatchesFull(file: File, mimeType: String) {
        val full = readAllPages(file, mimeType, pageChars = 10_000)
        val paged = readAllPages(file, mimeType, pageChars = 7)

        assertTrue(full.isNotBlank())
        assertEquals(full, paged)
    }

    private suspend fun readAllPages(file: File, mimeType: String, pageChars: Int): String {
        val output = StringBuilder()
        var offset = 0L
        var pageCount = 0
        while (true) {
            val page = checkNotNull(
                DocumentProcessor.extractTextPage(
                    context = context,
                    uri = Uri.fromFile(file),
                    mimeType = mimeType,
                    offsetChars = offset,
                    maxOutputChars = pageChars,
                )
            )
            output.append(page.content)
            pageCount++
            offset = page.nextOffset ?: break
            check(pageCount < 100) { "分页游标未能结束" }
        }
        return output.toString()
    }

    private fun writeZipEntry(file: File, entryName: String, content: String) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}

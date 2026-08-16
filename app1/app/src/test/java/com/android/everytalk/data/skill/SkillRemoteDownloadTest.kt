package com.android.everytalk.data.skill

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.database.daos.SkillDao
import io.mockk.coEvery
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SkillRemoteDownloadTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        stopKoin()
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.resolve("skill-catalog").deleteRecursively()
    }

    @After
    fun cleanUp() {
        context.filesDir.resolve("skills").deleteRecursively()
        context.filesDir.resolve("skill-catalog").deleteRecursively()
        stopKoin()
    }

    @Test
    fun `catalog page keeps pagination metadata for loading more`() {
        var requestCount = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestCount += 1
            val page = chain.request().url.pathSegments.last()
            val body = if (page == "1") {
                """{"skills":[{"source":"owner/one","skillId":"one","name":"One"}],"total":2,"hasMore":true}"""
            } else {
                """{"skills":[{"source":"owner/two","skillId":"two","name":"Two"}],"total":2,"hasMore":false}"""
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val catalog = SkillCatalogClient(context = context, client = client)

        val first = catalog.collectionPage(SkillCatalogCollection.POPULAR, 1)
        val second = catalog.collectionPage(SkillCatalogCollection.POPULAR, 2)
        val cachedFirst = catalog.collectionPage(SkillCatalogCollection.POPULAR, 1)

        assertEquals(1, first.page)
        assertEquals(2, first.total)
        assertEquals(1, first.pageSize)
        assertTrue(first.hasMore)
        assertEquals("owner/two", second.skills.single().source)
        assertFalse(second.hasMore)
        assertEquals(first, cachedFirst)
        assertEquals(2, requestCount)
    }

    @Test
    fun `catalog preloads three pages before and after the current page`() {
        assertEquals(listOf(2, 3, 4), catalogPrefetchPages(currentPage = 1, maxPage = 10))
        assertEquals(listOf(2, 3, 4, 6, 7, 8), catalogPrefetchPages(currentPage = 5, maxPage = 10))
        assertEquals(listOf(7, 8, 9), catalogPrefetchPages(currentPage = 10, maxPage = 10))
    }

    @Test
    fun `archive url pins the exact repository commit`() {
        val url = remoteRepositoryArchiveUrl(
            source = "browser-use/browser-use",
            commit = "abc123",
        )

        assertEquals(
            "https://codeload.github.com/browser-use/browser-use/zip/abc123",
            url.toString(),
        )
    }

    @Test
    fun `github tree discovers every real child and ignores reference examples`() {
        var requestCount = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestCount += 1
            val url = chain.request().url
            val body = when {
                url.host == "api.github.com" && "/commits/HEAD" in url.encodedPath ->
                    """{"sha":"commit123"}"""
                url.host == "api.github.com" && "/git/trees/commit123" in url.encodedPath ->
                    """{"sha":"tree","truncated":false,"tree":[
                        {"path":"skills/ponytail/SKILL.md","type":"blob","sha":"a","size":40},
                        {"path":"skills/ponytail-review/SKILL.md","type":"blob","sha":"b","size":42},
                        {"path":".openclaw/skills/ponytail/SKILL.md","type":"blob","sha":"a2","size":40},
                        {"path":".openclaw/skills/ponytail-review/SKILL.md","type":"blob","sha":"b2","size":42},
                        {"path":"references/example/SKILL.md","type":"blob","sha":"c","size":10}
                    ]}"""
                else -> error("未处理请求：$url")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val catalog = SkillCatalogClient(client = client)
        val item = RemoteSkillPackageCatalogItem(
            source = "dietrichgebert/ponytail",
            name = "Ponytail",
            matchedSkills = listOf(RemoteSkillCatalogItem("dietrichgebert/ponytail", "ponytail", "ponytail")),
        )

        val detail = catalog.packageDetail(item)
        val cachedDetail = catalog.packageDetail(item)

        assertEquals(listOf("ponytail", "ponytail-review"), detail.skills.map { it.name })
        assertEquals("commit123", detail.branch)
        assertEquals(detail, cachedDetail)
        assertEquals(2, requestCount)
    }

    @Test
    fun `remote package installs every child in one transaction`() = runTest {
        val ponytail = """
            ---
            name: ponytail
            description: Minimal coding
            ---
            # Ponytail
        """.trimIndent().toByteArray()
        val review = """
            ---
            name: ponytail-review
            description: Review code
            ---
            # Ponytail Review
        """.trimIndent().toByteArray()
        val detail = RemoteSkillPackageDetail(
            packageId = "remote:dietrichgebert/ponytail",
            name = "Ponytail",
            source = "dietrichgebert/ponytail",
            sourceRepository = "https://github.com/dietrichgebert/ponytail",
            branch = "main",
            contentHash = "package-hash",
            skills = listOf(
                RemoteSkillPackageChild(
                    name = "ponytail",
                    description = "Minimal coding",
                    sourcePath = "skills/ponytail",
                    invocationMode = SkillInvocationMode.AUTO,
                    files = listOf(RemoteSkillPackageFile("SKILL.md", "skills/ponytail/SKILL.md", ponytail.size.toLong())),
                ),
                RemoteSkillPackageChild(
                    name = "ponytail-review",
                    description = "Review code",
                    sourcePath = "skills/ponytail-review",
                    invocationMode = SkillInvocationMode.AUTO,
                    files = listOf(RemoteSkillPackageFile("SKILL.md", "skills/ponytail-review/SKILL.md", review.size.toLong())),
                ),
            ),
        )
        val dao = mockk<SkillDao>()
        coEvery { dao.getPackageChildren(any()) } returns emptyList()
        coEvery { dao.replacePackage(any(), any(), any()) } returns Unit
        val repository = SkillRepository(context, dao)
        val zipBytes = ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                // GitHub codeload 的真实 ZIP 会显式包含以斜杠结尾的目录项。
                zip.putNextEntry(ZipEntry("ponytail-commit/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("ponytail-commit/skills/"))
                zip.closeEntry()
                listOf(
                    "ponytail-commit/skills/ponytail/SKILL.md" to ponytail,
                    "ponytail-commit/skills/ponytail-review/SKILL.md" to review,
                    "ponytail-commit/README.md" to "ignored".toByteArray(),
                ).forEach { (path, content) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }
        val progress = mutableListOf<RemoteSkillInstallProgress>()

        val installed = repository.importRemotePackage(
            detail = detail,
            downloadArchive = { target, report ->
                target.writeBytes(zipBytes)
                report(zipBytes.size.toLong(), zipBytes.size.toLong())
            },
            onProgress = progress::add,
        )

        assertEquals("Ponytail", installed.name)
        assertEquals(2, installed.children.size)
        assertEquals(listOf("ponytail", "ponytail-review"), installed.children.map { it.name })
        assertTrue(installed.enabled)
        assertTrue(progress.any { it.stage == RemoteSkillInstallStage.DOWNLOADING })
        assertEquals(2L, progress.last().completed)
    }
}

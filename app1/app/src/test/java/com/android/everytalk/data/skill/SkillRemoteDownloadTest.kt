package com.android.everytalk.data.skill

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.database.daos.SkillDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
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
    }

    @After
    fun cleanUp() {
        context.filesDir.resolve("skills").deleteRecursively()
        stopKoin()
    }

    @Test
    fun `raw file url keeps the exact skill path`() {
        val url = remoteRepositoryFileUrl(
            source = "browser-use/browser-use",
            branch = "main",
            repositoryPath = "browser_use/skills/browser-use/SKILL.md",
        )

        assertEquals(
            "https://raw.githubusercontent.com/browser-use/browser-use/main/browser_use/skills/browser-use/SKILL.md",
            url.toString(),
        )
    }

    @Test
    fun `github tree discovers every real child and ignores reference examples`() {
        val ponytailText = "---\nname: ponytail\ndescription: Minimal coding\n---\n"
        val reviewText = "---\nname: ponytail-review\ndescription: Review code\n---\n"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val body = when {
                url.host == "api.github.com" && "/git/trees/HEAD" in url.encodedPath ->
                    """{"sha":"tree","truncated":false,"tree":[
                        {"path":"skills/ponytail/SKILL.md","type":"blob","sha":"a","size":${ponytailText.toByteArray().size}},
                        {"path":"skills/ponytail-review/SKILL.md","type":"blob","sha":"b","size":${reviewText.toByteArray().size}},
                        {"path":".openclaw/skills/ponytail/SKILL.md","type":"blob","sha":"a2","size":${ponytailText.toByteArray().size}},
                        {"path":".openclaw/skills/ponytail-review/SKILL.md","type":"blob","sha":"b2","size":${reviewText.toByteArray().size}},
                        {"path":"references/example/SKILL.md","type":"blob","sha":"c","size":10}
                    ]}"""
                url.encodedPath.endsWith("/skills/ponytail/SKILL.md") -> ponytailText
                url.encodedPath.endsWith("/skills/ponytail-review/SKILL.md") -> reviewText
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

        assertEquals(listOf("ponytail", "ponytail-review"), detail.skills.map { it.name })
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

        val installed = repository.importRemotePackage(detail) { _, entry, target ->
            target.parentFile?.mkdirs()
            target.writeBytes(if (entry.repositoryPath.endsWith("ponytail-review/SKILL.md")) review else ponytail)
        }

        assertEquals("Ponytail", installed.name)
        assertEquals(2, installed.children.size)
        assertEquals(listOf("ponytail", "ponytail-review"), installed.children.map { it.name })
        assertTrue(installed.enabled)
    }
}

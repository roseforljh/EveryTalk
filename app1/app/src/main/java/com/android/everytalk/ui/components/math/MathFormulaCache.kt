package com.android.everytalk.ui.components.math
import com.android.everytalk.statecontroller.*

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

internal data class MathFormulaCacheKey(
    val formulaId: String,
    val display: Boolean,
    val fontSizePx: Float,
    val color: String,
    val maxWidthPx: Float?,
    val mathJaxVersion: String,
    val mathJaxConfigHash: String,
)

private data class CachedSvgResult(
    val result: MathJaxRenderResult,
    val byteSize: Int,
)

private data class CachedSyntaxFailure(
    val result: MathJaxRenderResult,
    val expiresAtNanos: Long,
)

@Serializable
private data class DiskSvgMetadata(
    val cacheKey: String,
    val widthPx: Float,
    val heightPx: Float,
    val depthPx: Float,
    val viewBox: String? = null,
    val mathJaxVersion: String,
    val mathJaxConfigHash: String,
)

/**
 * 进程级公式缓存统一处理内存、磁盘和短期语法错误缓存。
 * 所有请求仍由唯一的 [MathJaxSvgRenderer] 串行转换。
 */
internal object MathFormulaSvgCache {
    private const val MAX_MEMORY_CACHE_BYTES = 16 * 1024 * 1024
    private const val MAX_FAILURE_ENTRIES = 256
    private const val SYNTAX_FAILURE_TTL_NANOS = 60_000_000_000L

    private val mutex = Mutex()
    private val memoryLock = Any()
    private val memoryEntries =
        LinkedHashMap<MathFormulaCacheKey, CachedSvgResult>(16, 0.75f, true)
    private val syntaxFailures =
        LinkedHashMap<MathFormulaCacheKey, CachedSyntaxFailure>(16, 0.75f, true)
    private var memoryBytes = 0

    suspend fun render(
        cacheRoot: File,
        renderer: MathJaxSvgRenderer,
        requests: List<MathJaxRenderRequest>,
    ): List<Pair<MathFormulaCacheKey, MathJaxRenderResult>> = mutex.withLock {
        if (requests.isEmpty()) return@withLock emptyList()

        val diskCache = MathFormulaDiskCache(File(cacheRoot, MathFormulaDiskCache.DIRECTORY_NAME))
        val keys = requests.map(::cacheKeyOf)
        val memoryResults = getMemoryReadyResults(requests)
        val resolved = arrayOfNulls<Pair<MathFormulaCacheKey, MathJaxRenderResult>>(requests.size)
        val unresolvedIndexes = mutableListOf<Int>()
        val nowNanos = System.nanoTime()
        removeExpiredSyntaxFailures(nowNanos)

        requests.forEachIndexed { index, request ->
            val key = keys[index]
            val memoryResult = memoryResults[key]
            val failureResult = syntaxFailures[key]
                ?.takeIf { it.expiresAtNanos > nowNanos }
                ?.result
            when {
                memoryResult != null -> resolved[index] = key to memoryResult.forRequest(request)
                failureResult != null -> resolved[index] = key to failureResult.forRequest(request)
                else -> unresolvedIndexes += index
            }
        }

        val diskHits = withContext(Dispatchers.IO) {
            unresolvedIndexes.mapNotNull { index ->
                runCatching {
                    diskCache.read(keys[index], requests[index])
                }.getOrNull()?.let { result -> index to result }
            }
        }
        diskHits.forEach { (index, result) ->
            val key = keys[index]
            putMemory(key, result.copy(requestVersion = 0L))
            resolved[index] = key to result
        }

        val diskHitIndexes = diskHits.mapTo(mutableSetOf()) { it.first }
        val renderIndexes = unresolvedIndexes.filterNot(diskHitIndexes::contains)
        val diskWrites = mutableListOf<Pair<MathFormulaCacheKey, MathJaxRenderResult>>()

        renderIndexes.chunked(MathJaxSvgRenderer.MAX_QUEUE_SIZE).forEach { indexes ->
            val uncachedRequests = indexes.map(requests::get)
            val uncachedResults = renderer.render(uncachedRequests)
            indexes.forEachIndexed { resultIndex, requestIndex ->
                val request = requests[requestIndex]
                val key = keys[requestIndex]
                val result = normalizeRendererResult(uncachedResults[resultIndex])
                resolved[requestIndex] = key to result

                when (result.status) {
                    MathJaxRenderStatus.READY -> {
                        putMemory(key, result.copy(requestVersion = 0L))
                        syntaxFailures.remove(key)
                        diskWrites += key to result.copy(requestVersion = 0L)
                    }

                    MathJaxRenderStatus.SYNTAX_ERROR -> putSyntaxFailure(
                        key = key,
                        result = result.copy(requestVersion = 0L),
                        nowNanos = nowNanos,
                    )

                    MathJaxRenderStatus.ENGINE_ERROR -> Unit
                }
            }
        }

        if (diskWrites.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                diskWrites.forEach { (key, result) ->
                    runCatching { diskCache.write(key, result) }
                }
            }
        }

        resolved.map { requireNotNull(it) }
    }

    /**
     * 同步读取已经完成安全校验的内存结果，供 Compose 首帧直接恢复真实公式尺寸。
     * 锁内只做 LRU 查询和轻量数据类复制，不执行 SVG 解析或磁盘访问。
     */
    internal fun getMemoryReadyResults(
        requests: List<MathJaxRenderRequest>,
    ): Map<MathFormulaCacheKey, MathJaxRenderResult> = synchronized(memoryLock) {
        buildMap {
            requests.forEach { request ->
                val key = cacheKeyOf(request)
                memoryEntries[key]?.result?.let { result ->
                    put(key, result.forRequest(request))
                }
            }
        }
    }

    private fun normalizeRendererResult(result: MathJaxRenderResult): MathJaxRenderResult {
        if (result.status != MathJaxRenderStatus.READY || result.hasUsableMathSvg()) return result
        return result.copy(
            status = MathJaxRenderStatus.ENGINE_ERROR,
            svg = null,
            widthPx = null,
            heightPx = null,
            depthPx = null,
            viewBox = null,
            errorMessage = "MathJax SVG 安全或尺寸校验失败",
        )
    }

    private fun putMemory(key: MathFormulaCacheKey, result: MathJaxRenderResult) {
        val svg = result.svg ?: return
        if (!result.hasUsableMathSvg()) return
        val byteSize = svg.toByteArray(Charsets.UTF_8).size
        if (byteSize > MAX_MEMORY_CACHE_BYTES) return

        synchronized(memoryLock) {
            memoryEntries.remove(key)?.let { previous -> memoryBytes -= previous.byteSize }
            memoryEntries[key] = CachedSvgResult(result = result, byteSize = byteSize)
            memoryBytes += byteSize

            val iterator = memoryEntries.entries.iterator()
            while (memoryBytes > MAX_MEMORY_CACHE_BYTES && iterator.hasNext()) {
                val eldest = iterator.next().value
                memoryBytes -= eldest.byteSize
                iterator.remove()
            }
        }
    }

    private fun putSyntaxFailure(
        key: MathFormulaCacheKey,
        result: MathJaxRenderResult,
        nowNanos: Long,
    ) {
        syntaxFailures[key] = CachedSyntaxFailure(
            result = result,
            expiresAtNanos = nowNanos + SYNTAX_FAILURE_TTL_NANOS,
        )
        while (syntaxFailures.size > MAX_FAILURE_ENTRIES) {
            val iterator = syntaxFailures.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun removeExpiredSyntaxFailures(nowNanos: Long) {
        val iterator = syntaxFailures.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAtNanos <= nowNanos) iterator.remove()
        }
    }
}

/** 64 MiB 的进程私有磁盘缓存，使用临时文件和原子移动写入。 */
internal class MathFormulaDiskCache(
    private val directory: File,
    private val maxBytes: Long = MAX_DISK_CACHE_BYTES,
) {
    init {
        require(maxBytes > 0L) { "公式磁盘缓存上限必须大于 0" }
    }

    fun read(
        key: MathFormulaCacheKey,
        request: MathJaxRenderRequest,
    ): MathJaxRenderResult? {
        val files = filesFor(key)
        if (!files.svg.isFile || !files.metadata.isFile) {
            deletePair(files)
            return null
        }
        if (files.svg.length() <= 0L || files.svg.length() > MAX_MATH_SVG_BYTES) {
            deletePair(files)
            return null
        }
        if (files.metadata.length() <= 0L || files.metadata.length() > MAX_METADATA_BYTES) {
            deletePair(files)
            return null
        }

        return runCatching {
            val metadata = JSON.decodeFromString<DiskSvgMetadata>(
                files.metadata.readText(Charsets.UTF_8)
            )
            check(metadata.cacheKey == key.stableValue())
            check(metadata.mathJaxVersion == MathJaxSvgRenderer.MATHJAX_VERSION)
            check(metadata.mathJaxConfigHash == MathJaxSvgRenderer.MATHJAX_CONFIG_HASH)

            val svg = files.svg.readText(Charsets.UTF_8)
            val result = MathJaxRenderResult(
                id = request.id,
                status = MathJaxRenderStatus.READY,
                svg = svg,
                widthPx = metadata.widthPx,
                heightPx = metadata.heightPx,
                depthPx = metadata.depthPx,
                viewBox = metadata.viewBox,
                requestVersion = request.requestVersion,
            )
            check(result.hasUsableMathSvg())
            touch(files)
            result
        }.getOrElse {
            deletePair(files)
            null
        }
    }

    fun write(key: MathFormulaCacheKey, result: MathJaxRenderResult) {
        if (result.status != MathJaxRenderStatus.READY || !result.hasUsableMathSvg()) return
        val svg = requireNotNull(result.svg)
        val files = filesFor(key)
        directory.mkdirs()
        val metadata = DiskSvgMetadata(
            cacheKey = key.stableValue(),
            widthPx = requireNotNull(result.widthPx),
            heightPx = requireNotNull(result.heightPx),
            depthPx = requireNotNull(result.depthPx),
            viewBox = result.viewBox,
            mathJaxVersion = MathJaxSvgRenderer.MATHJAX_VERSION,
            mathJaxConfigHash = MathJaxSvgRenderer.MATHJAX_CONFIG_HASH,
        )

        try {
            writeAtomically(files.svg, svg.toByteArray(Charsets.UTF_8))
            writeAtomically(files.metadata, JSON.encodeToString(metadata).toByteArray(Charsets.UTF_8))
            touch(files)
            trimToSize()
        } catch (error: Throwable) {
            deletePair(files)
            throw error
        }
    }

    private fun trimToSize() {
        val files = directory.listFiles().orEmpty()
        files.filter { it.name.endsWith(TEMP_SUFFIX) }.forEach(File::delete)

        val stems = files.asSequence()
            .mapNotNull { file ->
                when {
                    file.name.endsWith(SVG_SUFFIX) -> file.name.removeSuffix(SVG_SUFFIX)
                    file.name.endsWith(METADATA_SUFFIX) -> file.name.removeSuffix(METADATA_SUFFIX)
                    else -> null
                }
            }
            .toSet()
        val entries = stems.mapNotNull { stem ->
            val pair = DiskFiles(
                svg = File(directory, stem + SVG_SUFFIX),
                metadata = File(directory, stem + METADATA_SUFFIX),
            )
            if (!pair.svg.isFile || !pair.metadata.isFile) {
                deletePair(pair)
                null
            } else {
                DiskEntry(
                    files = pair,
                    byteSize = pair.svg.length() + pair.metadata.length(),
                    lastUsedAt = maxOf(pair.svg.lastModified(), pair.metadata.lastModified()),
                )
            }
        }.sortedBy(DiskEntry::lastUsedAt)

        var totalBytes = entries.sumOf(DiskEntry::byteSize)
        for (entry in entries) {
            if (totalBytes <= maxBytes) break
            deletePair(entry.files)
            totalBytes -= entry.byteSize
        }
    }

    private fun filesFor(key: MathFormulaCacheKey): DiskFiles {
        val hash = key.stableValue().sha256()
        return DiskFiles(
            svg = File(directory, hash + SVG_SUFFIX),
            metadata = File(directory, hash + METADATA_SUFFIX),
        )
    }

    private fun touch(files: DiskFiles) {
        val now = System.currentTimeMillis()
        files.svg.setLastModified(now)
        files.metadata.setLastModified(now)
    }

    private fun deletePair(files: DiskFiles) {
        files.svg.delete()
        files.metadata.delete()
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File(
            directory,
            ".${target.name}.${UUID.randomUUID()}$TEMP_SUFFIX",
        )
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private data class DiskFiles(
        val svg: File,
        val metadata: File,
    )

    private data class DiskEntry(
        val files: DiskFiles,
        val byteSize: Long,
        val lastUsedAt: Long,
    )

    companion object {
        const val DIRECTORY_NAME = "mathjax-svg-cache-v1"
        const val MAX_DISK_CACHE_BYTES = 64L * 1024L * 1024L

        private const val SVG_SUFFIX = ".svg"
        private const val METADATA_SUFFIX = ".json"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_METADATA_BYTES = 16L * 1024L
        private val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

internal const val MAX_MATH_SVG_BYTES = 1024L * 1024L
internal const val MAX_MATH_SVG_NODES = 8192

/** 对 WebView 返回值和磁盘缓存内容执行第二层 SVG 安全校验。 */
internal fun isSafeMathSvg(svg: String): Boolean {
    val bytes = svg.toByteArray(Charsets.UTF_8)
    if (bytes.isEmpty() || bytes.size > MAX_MATH_SVG_BYTES) return false
    if (FORBIDDEN_XML_DECLARATION.containsMatchIn(svg)) return false

    val document = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setAttribute(ACCESS_EXTERNAL_DTD_PROPERTY, "") }
            runCatching { setAttribute(ACCESS_EXTERNAL_SCHEMA_PROPERTY, "") }
        }
        factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(ByteArrayInputStream(bytes))
    }.getOrNull() ?: return false

    val root = document.documentElement ?: return false
    if (root.safeLocalName() != "svg") return false

    var nodeCount = 0
    val nodes = ArrayDeque<Node>()
    nodes.add(root)
    while (nodes.isNotEmpty()) {
        val node = nodes.removeLast()
        if (node.nodeType == Node.ELEMENT_NODE) {
            nodeCount += 1
            if (nodeCount > MAX_MATH_SVG_NODES) return false
            if (!isSafeSvgElement(node as Element)) return false
        }
        val children = node.childNodes
        for (index in 0 until children.length) nodes.add(children.item(index))
    }
    return true
}

internal fun MathJaxRenderResult.hasUsableMathSvg(): Boolean =
    !svg.isNullOrBlank() &&
        widthPx?.isFinite() == true && widthPx > 0f &&
        heightPx?.isFinite() == true && heightPx > 0f &&
        depthPx?.isFinite() == true && depthPx >= 0f && depthPx < heightPx &&
        isSafeMathSvg(svg)

internal fun cacheKeyOf(request: MathJaxRenderRequest): MathFormulaCacheKey = MathFormulaCacheKey(
    formulaId = request.id,
    display = request.display,
    fontSizePx = request.fontSizePx,
    color = request.color,
    maxWidthPx = request.maxWidthPx,
    mathJaxVersion = MathJaxSvgRenderer.MATHJAX_VERSION,
    mathJaxConfigHash = MathJaxSvgRenderer.MATHJAX_CONFIG_HASH,
)

internal fun MathFormulaCacheKey.coilMemoryCacheKey(): String =
    "everytalk-math-svg:${stableValue()}"

internal fun MathFormulaCacheKey.stableValue(): String = buildString {
    append(formulaId)
    append(':').append(if (display) '1' else '0')
    append(':').append(fontSizePx.toRawBits())
    append(':').append(color)
    append(':').append(maxWidthPx?.toRawBits() ?: "none")
    append(':').append(mathJaxVersion)
    append(':').append(mathJaxConfigHash)
}

private fun MathJaxRenderResult.forRequest(request: MathJaxRenderRequest): MathJaxRenderResult = copy(
    id = request.id,
    requestVersion = request.requestVersion,
)

private fun isSafeSvgElement(element: Element): Boolean {
    if (element.safeLocalName() in FORBIDDEN_SVG_ELEMENTS) return false
    val attributes = element.attributes
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        val localName = (attribute.localName ?: attribute.nodeName.substringAfter(':')).lowercase()
        val rawName = attribute.nodeName.lowercase()
        val value = attribute.nodeValue.orEmpty().trim()
        if (rawName == "xmlns" || rawName.startsWith("xmlns:")) continue
        if (localName.startsWith("on") || rawName.substringAfter(':').startsWith("on")) return false
        if (localName == "href" || localName == "src") {
            if (!LOCAL_FRAGMENT_REFERENCE.matches(value)) return false
        }
        if (localName in URL_RESOURCE_ATTRIBUTES && UNSAFE_URL_REFERENCE.containsMatchIn(value)) {
            return false
        }
        if (localName == "style" && UNSAFE_PROTOCOL.containsMatchIn(value)) return false
    }
    return true
}

private fun Element.safeLocalName(): String =
    (localName ?: nodeName.substringAfter(':')).lowercase()

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private val FORBIDDEN_XML_DECLARATION = Regex("<!\\s*(?:DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)
private val FORBIDDEN_SVG_ELEMENTS = setOf(
    "a",
    "embed",
    "foreignobject",
    "iframe",
    "image",
    "link",
    "object",
    "script",
    "style",
)
private val LOCAL_FRAGMENT_REFERENCE = Regex("^#[A-Za-z0-9_.:-]+$")
private val URL_RESOURCE_ATTRIBUTES = setOf(
    "clip-path",
    "cursor",
    "fill",
    "filter",
    "marker-end",
    "marker-mid",
    "marker-start",
    "mask",
    "stroke",
    "style",
)
private val UNSAFE_URL_REFERENCE = Regex("url\\(\\s*['\"]?(?!#)", RegexOption.IGNORE_CASE)
private val UNSAFE_PROTOCOL = Regex("(?:javascript|https?|file|content|data):", RegexOption.IGNORE_CASE)
private const val ACCESS_EXTERNAL_DTD_PROPERTY =
    "http://javax.xml.XMLConstants/property/accessExternalDTD"
private const val ACCESS_EXTERNAL_SCHEMA_PROPERTY =
    "http://javax.xml.XMLConstants/property/accessExternalSchema"

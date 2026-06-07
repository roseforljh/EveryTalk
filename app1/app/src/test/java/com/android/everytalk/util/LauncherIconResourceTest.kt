package com.android.everytalk.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconResourceTest {

    @Test
    fun `launcher resources use centered chat logo foreground assets`() {
        val resDir = findResDir()
        val lightLauncherFiles = listOf(
            resDir.resolve("mipmap-anydpi-v26/ic_launcher.xml"),
            resDir.resolve("mipmap-anydpi-v26/ic_launcher_round.xml"),
        )
        val nightLauncherFiles = listOf(
            resDir.resolve("mipmap-anydpi-v26/ic_launcher_night.xml"),
            resDir.resolve("mipmap-anydpi-v26/ic_launcher_night_round.xml"),
            resDir.resolve("mipmap-night-anydpi-v26/ic_launcher.xml"),
            resDir.resolve("mipmap-night-anydpi-v26/ic_launcher_round.xml"),
        )

        (lightLauncherFiles + nightLauncherFiles).forEach { file ->
            assertTrue("Missing launcher resource: ${file.path}", file.isFile)
            val text = file.readText()
            assertFalse(
                "${file.path} must not use @drawable/logo_dark because it has an opaque black background",
                text.contains("@drawable/logo_dark"),
            )
            assertFalse(
                "${file.path} must not use old launcher_logo_white resources",
                text.contains("launcher_logo_white"),
            )
            assertFalse(
                "${file.path} must not use old beetle resources",
                text.contains("beetle_logo"),
            )
            assertFalse(
                "${file.path} must not use the old ET foreground",
                text.contains("@drawable/ic_foreground_logo") || text.contains("@drawable/logo2"),
            )
            assertFalse(
                "${file.path} must not define monochrome because themed icons recolor the fixed launcher icon",
                text.contains("<monochrome"),
            )
        }
        lightLauncherFiles.forEach { file ->
            val text = file.readText()
            assertTrue("${file.path} must use the new launcher foreground", text.contains("@drawable/launcher_logo_foreground_asset"))
            assertTrue("${file.path} must use the white launcher background", text.contains("@color/ic_launcher_background"))
        }
        nightLauncherFiles.forEach { file ->
            val text = file.readText()
            assertTrue("${file.path} must use the new launcher foreground", text.contains("@drawable/launcher_logo_foreground_asset"))
            assertTrue("${file.path} must keep the white launcher background", text.contains("@color/ic_launcher_background"))
            assertFalse("${file.path} must not switch launcher background by theme", text.contains("@color/ic_launcher_background_dark"))
        }

        assertTrue(
            "Missing transparent launcher foreground PNG",
            resDir.resolve("drawable/launcher_logo_foreground_asset.png").isFile,
        )
        assertTrue(
            "Missing fixed launcher preview PNG",
            resDir.resolve("drawable/launcher_logo_asset.png").isFile,
        )
        assertTrue(
            "Launcher foreground PNG must be valid",
            resDir.resolve("drawable/launcher_logo_foreground_asset.png")
                .readBytes()
                .take(8)
                .toByteArray()
                .contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)),
        )
        val foregroundSize = resDir.resolve("drawable/launcher_logo_foreground_asset.png").readPngSize()
        assertEquals("Launcher foreground width must stay high resolution", 1024, foregroundSize.width)
        assertEquals("Launcher foreground height must stay high resolution", 1024, foregroundSize.height)
        assertTrue(
            "Launcher adaptive icon background must be white",
            resDir.resolve("values/ic_launcher_background.xml")
                .readText()
                .contains("#FFFFFFFF"),
        )
        assertFalse("Old drawable launcher foreground XML must be deleted", resDir.resolve("drawable/ic_launcher_foreground.xml").exists())
        assertFalse("Old default green launcher background XML must be deleted", resDir.resolve("drawable/ic_launcher_background.xml").exists())
        assertFalse("Old white launcher XML must be deleted", resDir.resolve("drawable/launcher_logo_white.xml").exists())
        assertFalse("Old white launcher PNG must be deleted", resDir.resolve("drawable/launcher_logo_white_asset.png").exists())
        assertFalse("Old drawable launcher bitmap XML must be deleted", resDir.resolve("drawable/launcher_logo.xml").exists())
        assertFalse("Old beetle black XML must be deleted", resDir.resolve("drawable/beetle_logo_black.xml").exists())
        assertFalse("Old beetle white XML must be deleted", resDir.resolve("drawable/beetle_logo_white.xml").exists())
        assertFalse("Old beetle black PNG must be deleted", resDir.resolve("drawable-nodpi/beetle_logo_black.png").exists())
        assertFalse("Old beetle white PNG must be deleted", resDir.resolve("drawable-nodpi/beetle_logo_white.png").exists())
        assertFalse("Old ET foreground WebP must be deleted", resDir.resolve("drawable/ic_foreground_logo.webp").exists())
        assertFalse("Old light logo PNG must be deleted", resDir.resolve("drawable/logo2.png").exists())
        assertFalse("Old dark logo PNG must be deleted", resDir.resolve("drawable/logo_dark.png").exists())
        assertFalse("Old night logo PNG must be deleted", resDir.resolve("drawable-night/logo2.png").exists())
        assertFalse("Old hdpi foreground WebP must be deleted", resDir.resolve("mipmap-hdpi/ic_launcher_foreground.webp").exists())
        assertFalse("Old xhdpi foreground WebP must be deleted", resDir.resolve("mipmap-xhdpi/ic_launcher_foreground.webp").exists())
        assertFalse("Old xxhdpi foreground WebP must be deleted", resDir.resolve("mipmap-xxhdpi/ic_launcher_foreground.webp").exists())

        val manifest = requireNotNull(resDir.parentFile).resolve("AndroidManifest.xml")
        assertTrue("Missing AndroidManifest.xml: ${manifest.path}", manifest.isFile)
        val manifestText = manifest.readText()
        assertTrue(
            "Manifest launcher icon must use adaptive launcher icon",
            manifestText.contains("@mipmap/ic_launcher"),
        )
        assertFalse(
            "Manifest launcher icon must not directly use transparent PNG because launchers can wrap it with a white legacy background",
            manifestText.contains("@drawable/launcher_logo_white_asset"),
        )
        assertTrue(
            "Manifest must expose the cache-busting white launcher alias",
            manifestText.contains("android:name=\".LauncherWhite\""),
        )
        assertTrue(
            "Manifest launcher aliases must open MainActivity directly for a single system splash",
            listOf(".LauncherWhite", ".LauncherLight", ".LauncherDark").all { alias ->
                manifestText.substringAfter("android:name=\"$alias\"").substringBefore("</activity-alias>")
                    .contains("android:targetActivity=\"com.android.everytalk.statecontroller.MainActivity\"")
            },
        )
        assertFalse(
            "Manifest must not declare the old video SplashActivity",
            manifestText.contains("android:name=\"com.android.everytalk.statecontroller.SplashActivity\""),
        )
        assertTrue(
            "Old light launcher alias must be disabled to clear launcher icon cache",
            manifestText.contains("android:name=\".LauncherLight\"") &&
                manifestText.substringAfter("android:name=\".LauncherLight\"").substringBefore("</activity-alias>")
                    .contains("android:enabled=\"false\""),
        )
        assertTrue(
            "Old dark launcher alias must be disabled to clear launcher icon cache",
            manifestText.contains("android:name=\".LauncherDark\"") &&
                manifestText.substringAfter("android:name=\".LauncherDark\"").substringBefore("</activity-alias>")
                    .contains("android:enabled=\"false\""),
        )
    }

    @Test
    fun `fixed launcher asset is a png and not the old opaque logo`() {
        val asset = findResDir().resolve("drawable/launcher_logo_asset.png")
        val bytes = asset.readBytes()
        val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        assertTrue("Launcher asset must be a valid PNG", bytes.take(8).toByteArray().contentEquals(pngSignature))
        assertTrue("Launcher asset PNG must include an IHDR color type byte", bytes.size > 25)
    }

    @Test
    fun `legacy launcher webp assets are fixed visible launcher logo`() {
        val resDir = findResDir()
        val launcherWebpFiles = listOf(
            "mipmap-hdpi/ic_launcher.webp",
            "mipmap-hdpi/ic_launcher_round.webp",
            "mipmap-mdpi/ic_launcher.webp",
            "mipmap-mdpi/ic_launcher_round.webp",
            "mipmap-xhdpi/ic_launcher.webp",
            "mipmap-xhdpi/ic_launcher_round.webp",
            "mipmap-xxhdpi/ic_launcher.webp",
            "mipmap-xxhdpi/ic_launcher_round.webp",
            "mipmap-xxxhdpi/ic_launcher.webp",
            "mipmap-xxxhdpi/ic_launcher_round.webp",
        )

        launcherWebpFiles.forEach { relative ->
            val file = resDir.resolve(relative)
            assertTrue("Missing launcher WebP: ${file.path}", file.isFile)
        }
    }

    @Test
    fun `android 12 splash uses provided transparent logo scaled to four times current size`() {
        val resDir = findResDir()
        val v31Theme = resDir.resolve("values-v31/themes.xml")
        val lightSplash = resDir.resolve("drawable-nodpi/splash_logo.png")
        val darkSplash = resDir.resolve("drawable-night-nodpi/splash_logo.png")
        val lightColors = resDir.resolve("values/colors.xml")
        val darkColors = resDir.resolve("values-night/colors.xml")

        assertTrue("Missing Android 12 theme", v31Theme.isFile)
        assertTrue("Missing light splash logo", lightSplash.isFile)
        assertTrue("Missing dark splash logo", darkSplash.isFile)
        assertTrue("Missing light splash background color", lightColors.isFile)
        assertTrue("Missing dark splash background color", darkColors.isFile)

        val themeText = v31Theme.readText()
        assertTrue(
            "Android 12 splash theme must use the theme-specific logo",
            themeText.contains("windowSplashScreenAnimatedIcon") &&
                themeText.contains("@drawable/splash_logo"),
        )
        assertTrue(
            "Android 12 splash theme must use the theme-specific background color",
            themeText.contains("windowSplashScreenBackground") &&
                themeText.contains("@color/splash_screen_background"),
        )
        assertFalse(
            "Android 12 splash theme must not define animation duration",
            themeText.contains("windowSplashScreenAnimationDuration"),
        )
        assertFalse(
            "Android 12 splash theme must not keep the old video splash theme",
            themeText.contains("Theme.SplashVideo") ||
                themeText.contains("@drawable/splash_video_empty_icon"),
        )
        assertTrue(
            "Light splash logo must be a transparent pure-logo PNG",
            lightSplash.readBytes()
                .take(8)
                .toByteArray()
                .contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)),
        )
        assertTrue(
            "Dark splash logo must be a transparent pure-logo PNG",
            darkSplash.readBytes()
                .take(8)
                .toByteArray()
                .contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)),
        )
        assertEquals("Light splash PNG must use the provided pure-logo width", 718, lightSplash.readPngSize().width)
        assertEquals("Light splash PNG must use the provided pure-logo height", 730, lightSplash.readPngSize().height)
        assertEquals("Dark splash PNG must use the provided pure-logo width", 718, darkSplash.readPngSize().width)
        assertEquals("Dark splash PNG must use the provided pure-logo height", 730, darkSplash.readPngSize().height)
        assertTrue("Light splash PNG corners must stay transparent", lightSplash.hasTransparentCorners())
        assertTrue("Dark splash PNG corners must stay transparent", darkSplash.hasTransparentCorners())
        listOf(
            "Light" to lightSplash.readVisibleAlphaBounds(),
            "Dark" to darkSplash.readVisibleAlphaBounds(),
        ).forEach { (label, bounds) ->
            assertEquals("$label splash visible logo must be four times the previous width", 288, bounds.width)
            assertEquals("$label splash visible logo must be four times the previous height", 292, bounds.height)
            assertTrue("$label splash visible logo must stay centered with empty margins", bounds.minMargin >= 215)
        }
        assertTrue("Light and dark splash must share the same transparent logo asset", lightSplash.readBytes().contentEquals(darkSplash.readBytes()))
        assertFalse("Light splash XML wrapper must be deleted", resDir.resolve("drawable/splash_logo.xml").exists())
        assertFalse("Dark splash XML wrapper must be deleted", resDir.resolve("drawable-night/splash_logo.xml").exists())
        assertFalse("Old light splash intermediate PNG must be deleted", resDir.resolve("drawable/splash_logo_black_asset.png").exists())
        assertFalse("Old dark splash intermediate PNG must be deleted", resDir.resolve("drawable/splash_logo_white_asset.png").exists())
        assertTrue(
            "Light splash background must be white",
            lightColors.readText().contains("<color name=\"splash_screen_background\">#FFFFFFFF</color>"),
        )
        assertTrue(
            "Dark splash background must be black",
            darkColors.readText().contains("<color name=\"splash_screen_background\">#FF000000</color>"),
        )
    }

    @Test
    fun `single splash launch chain does not use video activity`() {
        val mainDir = requireNotNull(findResDir().parentFile)
        val manifestText = mainDir.resolve("AndroidManifest.xml").readText()
        val baseTheme = findResDir().resolve("values/themes.xml").readText()
        val v31Theme = findResDir().resolve("values-v31/themes.xml").readText()

        assertFalse("Launcher manifest must not route through SplashActivity", manifestText.contains("SplashActivity"))
        assertFalse("Old SplashActivity source must be deleted", mainDir.resolve("java/com/android/everytalk/statecontroller/SplashActivity.kt").exists())
        assertFalse("Old light splash video must be deleted", findResDir().resolve("raw/light_splash.mp4").exists())
        assertFalse("Old empty splash icon must be deleted", findResDir().resolve("drawable/splash_video_empty_icon.xml").exists())
        assertFalse("Base theme must not keep Theme.SplashVideo", baseTheme.contains("Theme.SplashVideo"))
        assertFalse("Android 12 theme must not keep Theme.SplashVideo", v31Theme.contains("Theme.SplashVideo"))
        assertTrue(
            "MainActivity must own the one system splash theme",
            manifestText.substringAfter("android:name=\"com.android.everytalk.statecontroller.MainActivity\"")
                .substringBefore("</activity>")
                .contains("android:theme=\"@style/Theme.App1\""),
        )
    }

    private fun findResDir(): File {
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .forEach { dir ->
                listOf(
                    dir.resolve("src/main/res"),
                    dir.resolve("app/src/main/res"),
                ).firstOrNull { it.isDirectory }?.let { return it }
            }
        error("Unable to locate src/main/res")
    }

    private data class PngSize(val width: Int, val height: Int)

    private data class RgbaPng(
        val width: Int,
        val height: Int,
        val pixels: ByteArray,
    ) {
        fun argbAt(x: Int, y: Int): Int {
            val index = (y * width + x) * 4
            val red = pixels[index].toInt() and 0xFF
            val green = pixels[index + 1].toInt() and 0xFF
            val blue = pixels[index + 2].toInt() and 0xFF
            val alpha = pixels[index + 3].toInt() and 0xFF
            return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    private data class PngContentBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val imageWidth: Int,
        val imageHeight: Int,
    ) {
        val width: Int = right - left + 1
        val height: Int = bottom - top + 1
        val minMargin: Int = minOf(left, top, imageWidth - right - 1, imageHeight - bottom - 1)
    }

    private fun File.readPngSize(): PngSize {
        val bytes = readBytes()
        require(bytes.size > 24) { "Invalid PNG: $path" }
        return PngSize(width = bytes.readPngInt(16), height = bytes.readPngInt(20))
    }

    private fun File.readVisibleAlphaBounds(): PngContentBounds {
        val image = readRgbaPng()
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.argbAt(x, y).ushr(24) > 0) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }

        require(right >= left && bottom >= top) { "PNG has no visible content: $path" }
        return PngContentBounds(left, top, right, bottom, image.width, image.height)
    }

    private fun File.hasTransparentCorners(): Boolean {
        val image = readRgbaPng()
        return image.argbAt(0, 0).ushr(24) == 0 &&
            image.argbAt(image.width - 1, 0).ushr(24) == 0 &&
            image.argbAt(0, image.height - 1).ushr(24) == 0 &&
            image.argbAt(image.width - 1, image.height - 1).ushr(24) == 0
    }

    private fun File.readRgbaPng(): RgbaPng {
        val bytes = readBytes()
        require(
            bytes.take(8).toByteArray()
                .contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)),
        ) { "Invalid PNG signature: $path" }

        var offset = 8
        var width = 0
        var height = 0
        var bitDepth = -1
        var colorType = -1
        val idat = ByteArrayOutputStream()

        while (offset + 8 <= bytes.size) {
            val length = bytes.readPngInt(offset)
            offset += 4
            val chunkType = String(bytes, offset, 4, Charsets.US_ASCII)
            offset += 4

            when (chunkType) {
                "IHDR" -> {
                    width = bytes.readPngInt(offset)
                    height = bytes.readPngInt(offset + 4)
                    bitDepth = bytes[offset + 8].toInt() and 0xFF
                    colorType = bytes[offset + 9].toInt() and 0xFF
                }
                "IDAT" -> idat.write(bytes, offset, length)
                "IEND" -> break
            }
            offset += length + 4
        }

        require(width > 0 && height > 0) { "PNG missing IHDR: $path" }
        require(bitDepth == 8 && colorType == 6) { "PNG must be 8-bit RGBA: $path" }
        return RgbaPng(width, height, decodeRgbaRows(inflateZlib(idat.toByteArray()), width, height))
    }

    private fun decodeRgbaRows(inflated: ByteArray, width: Int, height: Int): ByteArray {
        val bytesPerPixel = 4
        val stride = width * bytesPerPixel
        require(inflated.size >= height * (stride + 1)) { "Invalid PNG row data" }

        val output = ByteArray(width * height * bytesPerPixel)
        var inputOffset = 0
        var previous = ByteArray(stride)
        var row = ByteArray(stride)

        for (y in 0 until height) {
            val filter = inflated[inputOffset++].toInt() and 0xFF
            for (x in 0 until stride) {
                val raw = inflated[inputOffset++].toInt() and 0xFF
                val left = if (x >= bytesPerPixel) row[x - bytesPerPixel].toInt() and 0xFF else 0
                val up = previous[x].toInt() and 0xFF
                val upperLeft = if (x >= bytesPerPixel) previous[x - bytesPerPixel].toInt() and 0xFF else 0
                val value = when (filter) {
                    0 -> raw
                    1 -> raw + left
                    2 -> raw + up
                    3 -> raw + ((left + up) / 2)
                    4 -> raw + paeth(left, up, upperLeft)
                    else -> error("Unsupported PNG filter: $filter")
                }
                row[x] = (value and 0xFF).toByte()
            }

            System.arraycopy(row, 0, output, y * stride, stride)
            val reusable = previous
            previous = row
            row = reusable
        }

        return output
    }

    private fun inflateZlib(data: ByteArray): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    output.write(buffer, 0, count)
                } else {
                    require(!inflater.needsInput()) { "Truncated PNG zlib data" }
                    require(!inflater.needsDictionary()) { "Unsupported PNG zlib dictionary" }
                }
            }
            output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun paeth(left: Int, up: Int, upperLeft: Int): Int {
        val estimate = left + up - upperLeft
        val leftDistance = abs(estimate - left)
        val upDistance = abs(estimate - up)
        val upperLeftDistance = abs(estimate - upperLeft)
        return when {
            leftDistance <= upDistance && leftDistance <= upperLeftDistance -> left
            upDistance <= upperLeftDistance -> up
            else -> upperLeft
        }
    }

    private fun ByteArray.readPngInt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
}

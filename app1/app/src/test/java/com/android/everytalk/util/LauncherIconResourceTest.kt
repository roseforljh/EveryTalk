package com.android.everytalk.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconResourceTest {

    @Test
    fun `launcher resources do not reference theme-qualified logo2`() {
        val resDir = findResDir()
        val launcherFiles = listOf(
            resDir.resolve("mipmap-anydpi-v26/ic_launcher.xml"),
            resDir.resolve("mipmap-anydpi-v26/ic_launcher_round.xml"),
            resDir.resolve("mipmap-anydpi-v26/ic_launcher_night.xml"),
            resDir.resolve("mipmap-anydpi-v26/ic_launcher_night_round.xml"),
            resDir.resolve("mipmap-night-anydpi-v26/ic_launcher.xml"),
            resDir.resolve("mipmap-night-anydpi-v26/ic_launcher_round.xml"),
            resDir.resolve("drawable/launcher_logo.xml"),
        )

        launcherFiles.forEach { file ->
            assertTrue("Missing launcher resource: ${file.path}", file.isFile)
            val text = file.readText()
            assertFalse(
                "${file.path} must not use @drawable/logo2 because drawable-night/logo2 overrides it",
                text.contains("@drawable/logo2"),
            )
            assertFalse(
                "${file.path} must not use @drawable/logo_dark because it has an opaque black background",
                text.contains("@drawable/logo_dark"),
            )
            assertFalse(
                "${file.path} must not use old launcher_logo_white resources",
                text.contains("launcher_logo_white"),
            )
            if (file.path.contains("mipmap")) {
                assertTrue(
                    "${file.path} must use the fixed launcher logo foreground",
                    text.contains("@drawable/launcher_logo"),
                )
                assertFalse(
                    "${file.path} must not define monochrome because themed icons recolor the fixed launcher icon",
                    text.contains("<monochrome"),
                )
            }
        }

        assertTrue(
            "Missing fixed launcher logo asset",
            resDir.resolve("drawable/launcher_logo_asset.png").isFile,
        )
        assertTrue(
            "Missing fixed launcher foreground asset",
            resDir.resolve("drawable/launcher_logo_foreground_asset.png").isFile,
        )
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
        assertFalse(
            "Launcher asset must not be the old opaque black logo asset",
            bytes.contentEquals(findResDir().resolve("drawable/logo_dark.png").readBytes()),
        )
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
            assertFalse(
                "${file.path} must not be the old opaque black logo asset",
                file.readBytes().contentEquals(resDir.resolve("drawable/logo_dark.png").readBytes()),
            )
        }
    }

    @Test
    fun `splash logo colors match light and dark themes`() {
        val resDir = findResDir()
        val lightSplash = resDir.resolve("drawable/splash_logo.xml")
        val darkSplash = resDir.resolve("drawable-night/splash_logo.xml")
        val lightColors = resDir.resolve("values/colors.xml")
        val darkColors = resDir.resolve("values-night/colors.xml")
        val v31Theme = resDir.resolve("values-v31/themes.xml")

        assertTrue("Missing light splash logo", lightSplash.isFile)
        assertTrue("Missing dark splash logo", darkSplash.isFile)
        assertFalse("Light splash frames must be removed", resDir.resolve("drawable").listFiles().orEmpty().any { it.name.startsWith("splash_logo_frame_") })
        assertFalse("Dark splash frames must be removed", resDir.resolve("drawable-night").listFiles().orEmpty().any { it.name.startsWith("splash_logo_frame_") })
        listOf(lightSplash, darkSplash).forEach { file ->
            val text = file.readText()
            assertTrue("${file.path} must use a static bitmap", text.contains("<bitmap"))
            assertFalse("${file.path} must not use frame animation", text.contains("animation-list"))
            assertFalse("${file.path} must not use vector animation", text.contains("animated-vector"))
            assertFalse("${file.path} must not use rotate animation", text.contains("animated-rotate"))
        }
        assertTrue(
            "Light splash must use black logo asset",
            lightSplash.readText().contains("@drawable/splash_logo_black_asset"),
        )
        assertTrue(
            "Dark splash must use white logo asset",
            darkSplash.readText().contains("@drawable/splash_logo_white_asset"),
        )
        assertFalse("Malformed black splash vector must be removed", resDir.resolve("drawable/splash_logo_vector_black.xml").exists())
        assertFalse("Malformed white splash vector must be removed", resDir.resolve("drawable/splash_logo_vector_white.xml").exists())
        assertTrue(
            "Light splash background must be white",
            lightColors.readText().contains("<color name=\"splash_screen_background\">#FFFFFFFF</color>"),
        )
        assertTrue(
            "Dark splash background must be black",
            darkColors.readText().contains("<color name=\"splash_screen_background\">#FF000000</color>"),
        )
        assertTrue(
            "Android 12 splash theme must use theme-qualified splash logo",
            v31Theme.readText().contains("android:windowSplashScreenAnimatedIcon") &&
                v31Theme.readText().contains("@drawable/splash_logo"),
        )
        assertFalse(
            "Android 12 splash theme must not define animation duration",
            v31Theme.readText().contains("windowSplashScreenAnimationDuration"),
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
}

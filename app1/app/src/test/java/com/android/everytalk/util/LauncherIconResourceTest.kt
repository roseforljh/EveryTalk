package com.android.everytalk.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconResourceTest {

    @Test
    fun `launcher resources use beetle foreground assets`() {
        val resDir = findResDir()
        val lightLauncherFiles = listOf(
            resDir.resolve("mipmap-anydpi-v26/ic_launcher.xml"),
            resDir.resolve("mipmap-anydpi-v26/ic_launcher_round.xml"),
        )
        val darkLauncherFiles = listOf(
            resDir.resolve("mipmap-anydpi-v26/ic_launcher_night.xml"),
            resDir.resolve("mipmap-anydpi-v26/ic_launcher_night_round.xml"),
            resDir.resolve("mipmap-night-anydpi-v26/ic_launcher.xml"),
            resDir.resolve("mipmap-night-anydpi-v26/ic_launcher_round.xml"),
        )

        (lightLauncherFiles + darkLauncherFiles).forEach { file ->
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
            assertTrue("${file.path} must use the black beetle launcher foreground", text.contains("@drawable/beetle_logo_black"))
            assertTrue("${file.path} must use the white launcher background", text.contains("@color/ic_launcher_background"))
        }
        darkLauncherFiles.forEach { file ->
            val text = file.readText()
            assertTrue("${file.path} must use the white beetle launcher foreground", text.contains("@drawable/beetle_logo_white"))
            assertTrue("${file.path} must use the black launcher background", text.contains("@color/ic_launcher_background_dark"))
        }

        assertTrue(
            "Missing black beetle launcher foreground vector",
            resDir.resolve("drawable/beetle_logo_black.xml").isFile,
        )
        assertTrue(
            "Missing white beetle launcher foreground vector",
            resDir.resolve("drawable/beetle_logo_white.xml").isFile,
        )
        assertTrue(
            "Light launcher foreground must be vector to avoid bitmap scaling blur",
            resDir.resolve("drawable/beetle_logo_black.xml").readText().contains("<vector"),
        )
        assertTrue(
            "Dark launcher foreground must be vector to avoid bitmap scaling blur",
            resDir.resolve("drawable/beetle_logo_white.xml").readText().contains("<vector"),
        )
        assertTrue(
            "Missing transparent black beetle source PNG",
            resDir.resolve("drawable-nodpi/beetle_logo_black.png").isFile,
        )
        assertTrue(
            "Missing transparent white beetle source PNG",
            resDir.resolve("drawable-nodpi/beetle_logo_white.png").isFile,
        )
        assertTrue(
            "Launcher adaptive icon background must be white",
            resDir.resolve("values/ic_launcher_background.xml")
                .readText()
                .contains("#FFFFFFFF"),
        )
        assertTrue(
            "Dark launcher adaptive icon background must be black",
            resDir.resolve("values/ic_launcher_background.xml")
                .readText()
                .contains("#FF000000"),
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
    fun `android 12 splash uses theme specific beetle logo`() {
        val resDir = findResDir()
        val v31Theme = resDir.resolve("values-v31/themes.xml")
        val lightSplash = resDir.resolve("drawable/splash_logo.xml")
        val darkSplash = resDir.resolve("drawable-night/splash_logo.xml")
        val lightColors = resDir.resolve("values/colors.xml")
        val darkColors = resDir.resolve("values-night/colors.xml")

        assertTrue("Missing Android 12 theme", v31Theme.isFile)
        assertTrue("Missing light splash logo", lightSplash.isFile)
        assertTrue("Missing dark splash logo", darkSplash.isFile)
        assertTrue("Missing light splash background color", lightColors.isFile)
        assertTrue("Missing dark splash background color", darkColors.isFile)

        val themeText = v31Theme.readText()
        assertTrue(
            "Android 12 splash theme must use the theme-specific beetle logo",
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
        assertTrue(
            "Light splash logo must be a black/red vector",
            lightSplash.readText().contains("<vector") &&
                lightSplash.readText().contains("#FF000000") &&
                lightSplash.readText().contains("#FF5B0202"),
        )
        assertTrue(
            "Dark splash logo must be a white/red vector",
            darkSplash.readText().contains("<vector") &&
                darkSplash.readText().contains("#FFFFFFFF") &&
                darkSplash.readText().contains("#FF5B0202"),
        )
        assertTrue(
            "Light splash background must be white",
            lightColors.readText().contains("<color name=\"splash_screen_background\">#FFFFFFFF</color>"),
        )
        assertTrue(
            "Dark splash background must be black",
            darkColors.readText().contains("<color name=\"splash_screen_background\">#FF000000</color>"),
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

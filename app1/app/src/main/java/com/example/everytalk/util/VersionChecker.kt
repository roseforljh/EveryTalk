package com.example.everytalk.util

object VersionChecker {

    /**
     * Compares two version strings.
     *
     * @param currentVersion The current version of the application (e.g., "1.0.0").
     * @param latestVersion The latest version from the server (e.g., "v1.0.1").
     * @return `true` if the latest version is newer than the current version, `false` otherwise.
     */
    fun isNewVersionAvailable(currentVersion: String, latestVersion: String): Boolean {
        // Remove any leading 'v' from the latest version string
        val cleanLatestVersion = latestVersion.removePrefix("v")

        val currentParts = currentVersion.split('.').mapNotNull { it.toIntOrNull() }
        val latestParts = cleanLatestVersion.split('.').mapNotNull { it.toIntOrNull() }

        val maxParts = maxOf(currentParts.size, latestParts.size)

        for (i in 0 until maxParts) {
            val currentPart = currentParts.getOrNull(i) ?: 0
            val latestPart = latestParts.getOrNull(i) ?: 0

            if (latestPart > currentPart) {
                return true
            }
            if (latestPart < currentPart) {
                return false
            }
        }
        return false
    }
}
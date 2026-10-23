package io.github.dshpet.mobile

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** What the update dialog needs: the new version, where to get it, what changed. */
data class UpdateInfo(val version: String, val pageUrl: String, val notes: String)

/**
 * Reads the newest published release from GitHub and decides whether it is
 * newer than this build.
 *
 * The APK is published as a release asset, not on a store, so the release list
 * *is* the update channel. Two details bite anyone who skips them: GitHub
 * answers **403** (not 401) when the `User-Agent` header is missing, and an
 * unauthenticated client is limited to 60 requests per hour per IP - hence the
 * "at most once every few hours" gate the caller applies, and the silent
 * failure: a phone offline is not an error worth a dialog.
 *
 * Everything here is blocking; call it off the main thread.
 */
object UpdateChecker {
    /** The repository the APK is published from. */
    private const val OWNER = "Jcnet-9527"
    private const val REPO = "dsh-Jpet-phone"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /** GitHub rejects requests without one. */
    private const val USER_AGENT = "dsh-pet-android"

    /** The newest release, or null when this build is already the newest. */
    fun latest(current: String = BuildConfig.VERSION_NAME): UpdateInfo? {
        val parsed = JSONObject(fetch())
        // /releases/latest already skips both, but a draft can be the newest
        // thing in the list after a mistaken publish.
        if (parsed.optBoolean("draft") || parsed.optBoolean("prerelease")) return null
        // Tags look like `app-v0.6.2`; accept a bare `v0.6.2` too.
        val version = parsed.optString("tag_name").trim()
            .removePrefix("app-").removePrefix("v").trim()
        if (version.isBlank()) return null
        if (!isNewer(version, current)) return null
        return UpdateInfo(
            version = version,
            pageUrl = parsed.optString("html_url"),
            notes = parsed.optString("body"),
        )
    }

    /**
     * True when [remote] has a higher number than [local].
     *
     * Compared part by part as integers, so 0.10.0 really is newer than 0.9.1 -
     * comparing the strings would say the opposite.
     */
    fun isNewer(remote: String, local: String): Boolean {
        val a = numbers(remote)
        val b = numbers(local)
        for (index in 0 until maxOf(a.size, b.size)) {
            val newer = a.getOrElse(index) { 0 }
            val older = b.getOrElse(index) { 0 }
            if (newer != older) return newer > older
        }
        return false
    }

    /** The numeric parts of a version; a suffix like `-beta1` contributes nothing. */
    private fun numbers(version: String): List<Int> =
        version.split('.', '-', '+', ' ')
            .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }

    private fun fetch(): String {
        val connection = (URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("GitHub 返回 $status")
            body
        } catch (e: IOException) {
            throw IOException(e.message ?: "连不上 GitHub")
        } finally {
            connection.disconnect()
        }
    }
}

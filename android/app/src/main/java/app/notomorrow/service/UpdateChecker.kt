package app.notomorrow.service

import app.notomorrow.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A release version such as `v1.2.3`, `0.4` or `1.0.0-beta.2`, compared by semantic-versioning
 * precedence: numeric parts left to right (missing parts count as 0, so `1.2` == `1.2.0`), then a
 * pre-release sorts before its release. Build metadata (`+…`) is ignored. The port of
 * `ReleaseVersion` in `NoTomorrow/Services/UpdateChecker.swift`.
 */
class ReleaseVersion private constructor(
    val numbers: List<Int>,
    val preRelease: List<String>,
) : Comparable<ReleaseVersion> {

    override fun compareTo(other: ReleaseVersion): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val l = numbers.getOrElse(i) { 0 }
            val r = other.numbers.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        // Same numbers: a pre-release comes before the release itself.
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return preRelease.isEmpty().compareTo(other.preRelease.isEmpty())
        }
        for ((l, r) in preRelease.zip(other.preRelease)) {
            if (l == r) continue
            val ln = l.numericOrNull()
            val rn = r.numericOrNull()
            if (ln != null && rn != null) return ln.compareTo(rn)
            if (ln != null) return -1   // numeric identifiers sort before alphanumeric ones
            if (rn != null) return 1
            return l.compareTo(r)
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun equals(other: Any?): Boolean = other is ReleaseVersion && compareTo(other) == 0

    override fun hashCode(): Int = numbers.dropLastWhile { it == 0 }.hashCode() * 31 + preRelease.hashCode()

    override fun toString(): String =
        numbers.joinToString(".") + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        fun parse(string: String): ReleaseVersion? {
            var text = string.trim()
            if (text.startsWith("v") || text.startsWith("V")) text = text.drop(1)
            text = text.substringBefore('+')
            var core = text
            var pre = emptyList<String>()
            val dash = text.indexOf('-')
            if (dash >= 0) {
                core = text.substring(0, dash)
                pre = text.substring(dash + 1).split('.')
                if (pre.any { it.isEmpty() }) return null
            }
            val numbers = core.split('.').map { it.numericOrNull() ?: return null }
            return ReleaseVersion(numbers, pre)
        }

        private fun String.numericOrNull(): Int? =
            if (isNotEmpty() && all { it in '0'..'9' }) toIntOrNull() else null
    }
}

/**
 * Looks up the latest GitHub release once per launch and, when it is newer than this build, offers
 * it in a banner (`app/UpdateBanner.kt`). The app is sideloaded, so this is the only way people
 * hear of a new version. The port of `UpdateChecker` in `NoTomorrow/Services/UpdateChecker.swift`.
 *
 * Every failure (offline, timeout, GitHub's 60-an-hour anonymous rate limit, an unexpected body)
 * is silent: the banner simply does not show, and the next launch tries again.
 */
class UpdateChecker(
    private val fetch: suspend () -> Reply,
    private val currentVersion: () -> String?,
    private val dismissedTag: DismissedTagStore,
    private val scope: CoroutineScope,
) {
    /** A status code and body from the latest-release endpoint. */
    class Reply(val status: Int, val body: String)

    /** `update.dismissedTag`, the release the close button hid (the same key as iOS). */
    interface DismissedTagStore {
        suspend fun get(): String?
        suspend fun set(tag: String)
    }

    private val _availableTag = MutableStateFlow<String?>(null)

    /** The newer release to offer, e.g. `v0.7.0`, or null when there is none (or it was dismissed). */
    val availableTag: StateFlow<String?> = _availableTag.asStateFlow()

    private val hasChecked = AtomicBoolean(false)

    /** Runs the check the first time it is called in this process; later calls do nothing. */
    suspend fun checkOnce() {
        if (hasChecked.compareAndSet(false, true)) check()
    }

    suspend fun check() {
        val tag = latestTag() ?: return
        val latest = ReleaseVersion.parse(tag) ?: return
        val current = currentVersion()?.let(ReleaseVersion::parse) ?: return
        if (current >= latest) return
        if (dismissedTag.get() == tag) return
        _availableTag.value = tag
    }

    /** Hides the banner until a release newer than this one comes out. */
    fun dismiss() {
        val tag = _availableTag.value ?: return
        _availableTag.value = null
        scope.launch { dismissedTag.set(tag) }
    }

    private suspend fun latestTag(): String? {
        val reply = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (reply.status != 200) return null
        val release = runCatching { json.decodeFromString(Release.serializer(), reply.body) }.getOrNull()
            ?: return null
        if (release.draft == true || release.prerelease == true) return null
        return release.tagName
    }

    @Serializable
    private class Release(
        @SerialName("tag_name") val tagName: String,
        val draft: Boolean? = null,
        val prerelease: Boolean? = null,
    )

    companion object {
        const val LATEST_RELEASE_API = "https://api.github.com/repos/joxan2137/NoTomorrow/releases/latest"

        /**
         * Where a tap goes: the release's own page, with its notes and the `.apk` to download
         * (iOS opens the releases list, where the newest release is on top).
         */
        fun releasePage(tag: String): String = "https://github.com/joxan2137/NoTomorrow/releases/tag/$tag"

        /**
         * Debug builds only: stands in for `versionName`, to see the banner without installing an
         * old build — `--es app.notomorrow.debug.simulatedAppVersion 0.1.0` (`MainActivity`), the
         * counterpart of iOS's `-NTSimulatedAppVersion 0.1.0`.
         */
        @Volatile var simulatedAppVersion: String? = null

        /** This build's `versionName`, or [simulatedAppVersion] in a debug build. */
        fun appVersion(): String? =
            (if (BuildConfig.DEBUG) simulatedAppVersion else null) ?: BuildConfig.VERSION_NAME

        /** One GET of [LATEST_RELEASE_API], 15 s timeouts, no cache. */
        suspend fun fetchLatestRelease(): Reply = withContext(Dispatchers.IO) {
            val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                val status = connection.responseCode
                val body = if (status == 200) connection.inputStream.bufferedReader().use { it.readText() } else ""
                Reply(status, body)
            } finally {
                connection.disconnect()
            }
        }

        private val json = Json { ignoreUnknownKeys = true }
    }
}

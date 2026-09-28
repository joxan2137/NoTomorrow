package app.notomorrow.service

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The launch-time update check: semantic version order, and a banner only for a newer, undismissed
 * release, with every failure (offline, rate limit, odd body) silent. Mirrors
 * `NoTomorrowTests/UpdateCheckerTests.swift`.
 */
class UpdateCheckerTest {

    private class MemoryStore : UpdateChecker.DismissedTagStore {
        var tag: String? = null
        override suspend fun get() = tag
        override suspend fun set(tag: String) { this.tag = tag }
    }

    private val store = MemoryStore()

    // MARK: - Versions

    private fun v(string: String) = ReleaseVersion.parse(string)!!

    @Test
    fun versionOrder() {
        assertEquals(v("v0.4.1"), v("0.4.1"))
        assertEquals(v("1.2"), v("1.2.0"))
        assertEquals(v("1.2").hashCode(), v("1.2.0").hashCode())
        assertEquals(v("1.0.0+build.5"), v("1.0.0"))
        assertTrue(v("0.4.1") < v("0.5.0"))
        assertTrue(v("0.9.0") < v("0.10.0"))   // numeric, not lexical
        assertFalse(v("0.4.1") < v("0.4.1"))
    }

    @Test
    fun preReleaseOrder() {
        assertTrue(v("1.0.0-beta.2") < v("1.0.0"))
        assertFalse(v("1.0.0") < v("1.0.0-beta.2"))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-alpha.1"))
        assertTrue(v("1.0.0-alpha.1") < v("1.0.0-alpha.beta"))
        assertTrue(v("1.0.0-beta.2") < v("1.0.0-beta.11"))
    }

    @Test
    fun invalidVersions() {
        for (invalid in listOf("", "abc", "1..2", "1.0-", "1.x")) {
            assertNull(invalid, ReleaseVersion.parse(invalid))
        }
    }

    // MARK: - Check

    private fun TestScope.checker(current: String, status: Int = 200, tag: String = "v0.5.0") =
        UpdateChecker(
            fetch = { UpdateChecker.Reply(status, """{"tag_name":"$tag","draft":false,"prerelease":false,"name":"x"}""") },
            currentVersion = { current },
            dismissedTag = store,
            scope = this,
        )

    @Test
    fun newerReleaseShowsUntilDismissed() = runTest {
        val checker = checker(current = "0.4.1")
        checker.checkOnce()
        assertEquals("v0.5.0", checker.availableTag.value)
        checker.dismiss()
        assertNull(checker.availableTag.value)
        advanceUntilIdle()
        assertEquals("v0.5.0", store.tag)

        val nextLaunch = checker(current = "0.4.1")
        nextLaunch.check()
        assertNull("a dismissed release stays hidden", nextLaunch.availableTag.value)

        val newerRelease = checker(current = "0.4.1", tag = "v0.6.0")
        newerRelease.check()
        assertEquals("v0.6.0", newerRelease.availableTag.value)
    }

    @Test
    fun checkOnceRunsOnce() = runTest {
        var calls = 0
        val checker = UpdateChecker(
            fetch = { calls++; UpdateChecker.Reply(200, """{"tag_name":"v0.5.0"}""") },
            currentVersion = { "0.4.1" },
            dismissedTag = store,
            scope = this,
        )
        checker.checkOnce()
        checker.checkOnce()
        assertEquals(1, calls)
    }

    @Test
    fun sameOrOlderReleaseShowsNothing() = runTest {
        for (current in listOf("0.5.0", "0.5", "0.6.0")) {
            val checker = checker(current = current)
            checker.check()
            assertNull(current, checker.availableTag.value)
        }
    }

    @Test
    fun preReleasesAndDraftsAreSkipped() = runTest {
        for (body in listOf(
            """{"tag_name":"v0.9.0","prerelease":true}""",
            """{"tag_name":"v0.9.0","draft":true}""",
        )) {
            val checker = UpdateChecker({ UpdateChecker.Reply(200, body) }, { "0.1.0" }, store, this)
            checker.check()
            assertNull(body, checker.availableTag.value)
        }
    }

    @Test
    fun failuresAreSilent() = runTest {
        val rateLimited = checker(current = "0.1.0", status = 403)
        rateLimited.check()
        assertNull(rateLimited.availableTag.value)

        val offline = UpdateChecker({ throw IOException("offline") }, { "0.1.0" }, store, this)
        offline.check()
        assertNull(offline.availableTag.value)

        val garbage = UpdateChecker({ UpdateChecker.Reply(200, "<html>") }, { "0.1.0" }, store, this)
        garbage.check()
        assertNull(garbage.availableTag.value)

        val unknownVersion = UpdateChecker({ UpdateChecker.Reply(200, """{"tag_name":"v0.5.0"}""") }, { null }, store, this)
        unknownVersion.check()
        assertNull(unknownVersion.availableTag.value)
    }
}

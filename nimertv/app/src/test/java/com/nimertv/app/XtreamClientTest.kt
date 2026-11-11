package com.nimertv.app

import com.nimertv.app.data.AuthResult
import com.nimertv.app.data.Catalog
import com.nimertv.app.data.XtreamClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** Real HTTP round-trips against [FakeProvider]. */
class XtreamClientTest {
    private val provider = FakeProvider()
    private val backup = FakeProvider()

    /** An address where nothing listens: connection refused, like a dead DNS entry. */
    private val dead = ServerSocket(0).use { "http://127.0.0.1:${it.localPort}" }

    @After fun tearDown() {
        provider.stop()
        backup.stop()
    }

    @Test fun signsInAndLoadsEverything() = runBlocking {
        val client = XtreamClient { listOf(provider.url) }
        val session = (client.authenticate("demo", "secret", null) as AuthResult.Success).session
        assertEquals(provider.url, session.server)
        val catalog = Catalog(client)
        assertEquals(listOf("أخبار", "Sports"), catalog.liveCategories().map { it.name })
        assertEquals(40, catalog.channels().size)
        assertEquals("قناة 2", catalog.channels()[1].name)
        assertEquals(60, catalog.movies().size)
        assertEquals(30, catalog.series().size)
        val info = catalog.movieInfo(catalog.movies().first())
        assertEquals("1h 40m", info.duration)
        assertTrue(info.plot.startsWith("تجربة"))
        val series = catalog.seriesInfo(catalog.series().first())
        assertEquals(listOf(1, 2), series.seasons)
        assertEquals("الحلقة الأولى", series.episodes[1]!!.first().title)
        assertEquals("أخبار", catalog.epg(101).single().title)
        // Second read is served from the in-memory catalogue.
        val before = provider.hits
        catalog.movies()
        assertEquals(before, provider.hits)
        catalog.invalidate()
        catalog.movies()
        assertEquals(before + 1, provider.hits)
    }

    @Test fun wrongPasswordIsReportedAndDoesNotFailOver() {
        val client = XtreamClient { listOf(provider.url, backup.url) }
        assertEquals(AuthResult.InvalidCredentials, client.authenticate("demo", "wrong", null))
        assertEquals(0, backup.hits)
    }

    @Test fun expiredAccountIsReported() {
        provider.accountStatus = "Expired"
        val client = XtreamClient { listOf(provider.url) }
        assertEquals(AuthResult.AccountBlocked("Expired"), client.authenticate("demo", "secret", null))
    }

    @Test fun failsOverToBackupDnsAtSignIn() {
        val client = XtreamClient { listOf(dead, backup.url) }
        val session = (client.authenticate("demo", "secret", null) as AuthResult.Success).session
        assertEquals(backup.url, session.server)
    }

    @Test fun failsOverWhenPrimaryDiesAfterSignIn() = runBlocking {
        val client = XtreamClient { listOf(provider.url, backup.url) }
        assertTrue(client.authenticate("demo", "secret", null) is AuthResult.Success)
        provider.stop()
        assertEquals(40, Catalog(client).channels().size)
        // Streams now use the server that answered.
        assertTrue(client.session!!.liveUrl(101, "ts").startsWith(backup.url))
    }

    @Test fun reportsUnreachableWhenEveryServerIsDown() {
        assertTrue(XtreamClient { listOf(dead) }.authenticate("demo", "secret", null) is AuthResult.Unreachable)
        assertTrue(XtreamClient { emptyList() }.authenticate("demo", "secret", null) is AuthResult.Unreachable)
    }
}

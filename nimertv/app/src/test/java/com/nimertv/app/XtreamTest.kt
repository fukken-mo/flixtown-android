package com.nimertv.app

import com.nimertv.app.data.AuthResult
import com.nimertv.app.data.Movie
import com.nimertv.app.data.Progress
import com.nimertv.app.data.ProgressRules
import com.nimertv.app.data.ProviderException
import com.nimertv.app.data.Series
import com.nimertv.app.data.Session
import com.nimertv.app.data.XtreamParser
import com.nimertv.app.ui.matchesQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader

class XtreamTest {
    private fun auth(body: String) = XtreamParser.auth(body, "http://s.example.test:88", "user", "p@ss")

    @Test fun authSuccessWithMixedTypes() {
        val r = auth("""{"user_info":{"username":"user","auth":1,"status":"Active","exp_date":"1800000000","is_trial":"0",
            "max_connections":"2","allowed_output_formats":["m3u8","ts","rtmp"]},"server_info":{"url":"x","port":"88"}}""")
        val s = (r as AuthResult.Success).session
        assertEquals(1800000000L, s.expiresAtSec)
        assertEquals("2", s.maxConnections)
        assertEquals(listOf("m3u8", "ts"), s.liveFormats)
        assertEquals("http://s.example.test:88/live/user/p%40ss/12.m3u8", s.liveUrl(12, "m3u8"))
        assertEquals("http://s.example.test:88/movie/user/p%40ss/7.mkv", s.movieUrl("7", "mkv"))
        assertEquals("http://s.example.test:88/series/user/p%40ss/99.mp4", s.episodeUrl("99", ""))
    }

    @Test fun authFailures() {
        assertEquals(AuthResult.InvalidCredentials, auth("""{"user_info":{"auth":0}}"""))
        assertEquals(AuthResult.InvalidCredentials, auth(""))
        assertEquals(AuthResult.InvalidCredentials, auth("[]"))
        assertEquals(AuthResult.AccountBlocked("Expired"), auth("""{"user_info":{"auth":1,"status":"Expired"}}"""))
        assertEquals(AuthResult.AccountBlocked("Banned"), auth("""{"user_info":{"auth":1,"status":"Banned"}}"""))
        assertTrue(auth("<html>captive portal</html>") is AuthResult.Unreachable)
        assertTrue(auth("""{"something":"else"}""") is AuthResult.Unreachable)
    }

    @Test fun tsOnlyProvidersAndUnknownFormats() {
        val base = Session("http://s", "u", "p", "Active", 0, "", false, listOf("ts"))
        assertEquals(listOf("ts"), base.liveFormats)
        assertEquals(listOf("m3u8", "ts"), base.copy(formats = emptyList()).liveFormats)
    }

    @Test fun streamListsTolerateTypesAndKeepArabic() {
        val json = """[
            {"num":1,"name":"الجزيرة الإخبارية HD","stream_type":"live","stream_id":101,"stream_icon":"http://img/aj.png","category_id":"5","tv_archive":0},
            {"num":"2","name":"MBC 1","stream_id":"102","stream_icon":null,"category_id":7},
            {"name":"broken"}, "junk", null]"""
        val list = XtreamParser.list(StringReader(json), XtreamParser::channel)
        assertEquals(2, list.size)
        assertEquals("الجزيرة الإخبارية HD", list[0].name)
        assertEquals(102, list[1].id)
        assertEquals(2, list[1].number)
        assertEquals("", list[1].logo)
        assertEquals("7", list[1].categoryId)
    }

    @Test fun emptyAndUnexpectedListResponses() {
        assertTrue(XtreamParser.list(StringReader(""), XtreamParser::movie).isEmpty())
        assertTrue(XtreamParser.list(StringReader("[]"), XtreamParser::movie).isEmpty())
        assertEquals(1, XtreamParser.list(StringReader("""{"0":{"stream_id":5,"name":"x"}}"""), XtreamParser::movie).size)
        try {
            XtreamParser.list(StringReader("""{"user_info":{"auth":0}}"""), XtreamParser::movie)
            fail("expected rejection")
        } catch (e: ProviderException) {
            // expected
        }
    }

    @Test fun movieRatingsAreOnlyShownWhenProvided() {
        val list = XtreamParser.list(
            StringReader("""[{"stream_id":1,"name":"A","rating":"7.4","added":"1700000000","container_extension":"mkv"},
                {"stream_id":2,"name":"B","rating":"","rating_5based":0},{"stream_id":3,"name":"C","rating":0,"rating_5based":4.5}]"""),
            XtreamParser::movie,
        )
        assertEquals(7.4, list[0].rating, 0.001)
        assertEquals("mkv", list[0].ext)
        assertEquals(1700000000L, list[0].added)
        assertEquals(0.0, list[1].rating, 0.001)
        assertEquals("mp4", list[1].ext)
        assertEquals(9.0, list[2].rating, 0.001)
    }

    private val movie = Movie(1, "A", "poster", "1", 0, 0.0, "mp4", "")

    @Test fun movieInfoHandlesEmptyAndFullMetadata() {
        val empty = XtreamParser.movieInfo("""{"info":[],"movie_data":{"stream_id":1,"container_extension":"mkv"}}""", movie)
        assertEquals("", empty.plot)
        assertEquals("poster", empty.poster)
        assertEquals("mkv", empty.ext)
        val full = XtreamParser.movieInfo(
            """{"info":{"plot":"قصة الفيلم","cast":"A, B","director":"D","genre":"Drama","releasedate":"2021-05-01",
                "duration_secs":5400,"duration":"01:30:00","rating":"8","backdrop_path":["http://img/b.jpg"],"movie_image":"http://img/p.jpg"},
                "movie_data":{"container_extension":"mp4"}}""", movie,
        )
        assertEquals("قصة الفيلم", full.plot)
        assertEquals("1h 30m", full.duration)
        assertEquals("http://img/b.jpg", full.backdrop)
        assertEquals("http://img/p.jpg", full.poster)
        assertEquals(8.0, full.rating, 0.001)
        // Garbage instead of JSON still leaves the movie playable.
        assertEquals("mp4", XtreamParser.movieInfo("oops", movie).ext)
    }

    private val series = Series(9, "S", "cover", "1", "", "", "", "", "", 0.0, "", 0)

    @Test fun seriesInfoObjectForm() {
        val info = XtreamParser.seriesInfo(
            """{"seasons":[],"info":{"name":"S","plot":"P","backdrop_path":"http://img/b.jpg"},
                "episodes":{"2":[{"id":"21","episode_num":1,"title":"S02E01","container_extension":"mkv","season":2,"info":[]}],
                            "1":[{"id":"12","episode_num":"2","title":"","container_extension":"mp4","info":{"plot":"ep plot","duration_secs":1500,"movie_image":"http://img/e.jpg"}},
                                 {"id":"11","episode_num":1,"title":"الحلقة 1","season":1}]}}""", series,
        )
        assertEquals(listOf(1, 2), info.seasons)
        assertEquals(listOf("11", "12"), info.episodes[1]!!.map { it.id })
        assertEquals("الحلقة 1", info.episodes[1]!![0].title)
        assertEquals("Episode 2", info.episodes[1]!![1].title)
        assertEquals("25m", info.episodes[1]!![1].duration)
        assertEquals("mkv", info.episodes[2]!![0].ext)
        assertEquals("P", info.series.plot)
        assertEquals("http://img/b.jpg", info.series.backdrop)
        assertEquals("cover", info.series.cover)
    }

    @Test fun seriesInfoArrayFormAndNoEpisodes() {
        val arr = XtreamParser.seriesInfo("""{"info":[],"episodes":[[{"id":"1","episode_num":1}],[{"id":"2","episode_num":1}]]}""", series)
        assertEquals(listOf(1, 2), arr.seasons)
        val none = XtreamParser.seriesInfo("""{"info":{"name":"S"},"episodes":[]}""", series)
        assertTrue(none.seasons.isEmpty())
    }

    @Test fun epgIsDecodedAndSorted() {
        // "أخبار" and "News" in Base64
        val body = """{"epg_listings":[
            {"title":"TmV3cw==","description":"","start_timestamp":"2000","stop_timestamp":"3000"},
            {"title":"2KPYrtio2KfYsQ==","description":"2KPYrtio2KfYsQ==","start_timestamp":1000,"stop_timestamp":2000},
            {"title":"","start_timestamp":1,"stop_timestamp":2}]}"""
        val epg = XtreamParser.epg(body)
        assertEquals(listOf("أخبار", "News"), epg.map { it.title })
        assertEquals(1_000_000L, epg[0].startMs)
        assertTrue(XtreamParser.epg("""{"epg_listings":[]}""").isEmpty())
        assertTrue(XtreamParser.epg("").isEmpty())
    }

    /* ---------------- resume rules ---------------- */

    private fun progress(key: String, kind: String, seriesId: Int, pos: Long, dur: Long, at: Long) =
        Progress(key, kind, key, key, "", "", "mp4", seriesId, pos, dur, at)

    @Test fun resumeRules() {
        val hour = 3_600_000L
        assertFalse(ProgressRules.isResumable(5_000, hour))
        assertTrue(ProgressRules.isResumable(600_000, hour))
        assertFalse(ProgressRules.isResumable((hour * 0.97).toLong(), hour))
        assertTrue(ProgressRules.isFinished(hour - 10_000, hour))
        // Unknown duration (some streams): still resumable.
        assertTrue(ProgressRules.isResumable(600_000, 0))
        assertFalse(ProgressRules.isFinished(600_000, 0))
    }

    @Test fun continueWatchingShowsLatestUnfinishedPerSeries() {
        val hour = 3_600_000L
        val list = ProgressRules.continueWatching(
            listOf(
                progress("movie:1", Progress.MOVIE, 0, 600_000, hour, 10),
                progress("movie:2", Progress.MOVIE, 0, hour, hour, 50),
                progress("episode:a", Progress.EPISODE, 7, 600_000, hour, 20),
                progress("episode:b", Progress.EPISODE, 7, 900_000, hour, 30),
                progress("episode:c", Progress.EPISODE, 8, 600_000, hour, 5),
                progress("episode:d", Progress.EPISODE, 8, hour, hour, 40),
            ),
        )
        // Series 8's latest episode is finished, so the older half-watched one is not offered.
        assertEquals(listOf("episode:b", "movie:1"), list.map { it.key })
    }

    @Test fun searchMatchesArabicAndLatin() {
        assertTrue(matchesQuery("الجزيرة الإخبارية HD", listOf("الجزيرة", "hd")))
        assertTrue(matchesQuery("MBC Action", listOf("mbc")))
        assertFalse(matchesQuery("MBC Action", listOf("mbc", "drama")))
    }
}

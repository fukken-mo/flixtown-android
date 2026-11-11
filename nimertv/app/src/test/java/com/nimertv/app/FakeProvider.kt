package com.nimertv.app

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A minimal Xtream Codes player API served on localhost, used to exercise the real HTTP
 * client: sign-in, DNS failover and catalogue loading. All content is synthetic test data.
 */
class FakeProvider(private val user: String = "demo", private val pass: String = "secret", private val art: Boolean = false) {
    private val server = MockWebServer()
    val url: String get() = "http://127.0.0.1:${server.port}"
    @Volatile var accountStatus = "Active"
    @Volatile var hits = 0

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits++
                val u = request.requestUrl!!
                if (u.encodedPath.startsWith("/img/")) {
                    val bytes = javaClass.getResourceAsStream("/art/" + u.encodedPath.removePrefix("/img/"))?.readBytes()
                        ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setBody(okio.Buffer().write(bytes))
                }
                if (u.encodedPath != "/player_api.php") return MockResponse().setResponseCode(404)
                val q = u.queryParameterNames.associateWith { u.queryParameter(it) }
                if (q["username"] != user || q["password"] != pass) return json("""{"user_info":{"auth":0}}""")
                return json(body(q))
            }
        }
        server.start()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun body(q: Map<String, String?>): String =
        when (q["action"]) {
                    null -> """{"user_info":{"username":"$user","auth":1,"status":"$accountStatus","exp_date":"1893456000","is_trial":"0","max_connections":"2","allowed_output_formats":["m3u8","ts"]},"server_info":{"server_protocol":"http"}}"""
                    "get_live_categories" -> """[{"category_id":"1","category_name":"أخبار","parent_id":0},{"category_id":"2","category_name":"Sports","parent_id":0}]"""
                    "get_live_streams" -> (1..40).joinToString(",", "[", "]") {
                        """{"num":$it,"name":"${if (it % 2 == 0) "قناة $it" else "Channel $it"}","stream_type":"live","stream_id":${100 + it},"stream_icon":"${if (art) "$url/img/logo${1 + it % 4}.png" else ""}","category_id":"${1 + it % 2}"}"""
                    }
                    "get_vod_categories" -> """[{"category_id":"10","category_name":"أفلام عربية"},{"category_id":"11","category_name":"Action"}]"""
                    "get_vod_streams" -> (1..60).joinToString(",", "[", "]") {
                        """{"num":$it,"name":"${if (it % 3 == 0) "فيلم رقم $it" else "Movie $it"}","stream_type":"movie","stream_id":${200 + it},"stream_icon":"${if (art) "$url/img/poster${1 + it % 6}.jpg" else ""}","rating":"${if (it % 4 == 0) "7.${it % 10}" else ""}","added":"${1700000000 + it * 1000}","category_id":"${10 + it % 2}","container_extension":"mkv"}"""
                    }
                    "get_series_categories" -> """[{"category_id":"20","category_name":"مسلسلات"}]"""
                    "get_series" -> (1..30).joinToString(",", "[", "]") {
                        """{"num":$it,"name":"${if (it % 2 == 0) "مسلسل $it" else "Series $it"}","series_id":${300 + it},"cover":"${if (art) "$url/img/poster${1 + (it + 3) % 6}.jpg" else ""}","plot":"Plot $it","cast":"","genre":"Drama","releaseDate":"2024-01-0${1 + it % 9}","last_modified":"${1700000000 + it}","rating":"","backdrop_path":[],"category_id":"20"}"""
                    }
                    "get_vod_info" -> """{"info":{"plot":"تجربة وصف الفيلم. A test description.","cast":"Actor One, Actor Two","director":"Director","genre":"Drama","releasedate":"2023-02-03","duration_secs":6000,"backdrop_path":[]},"movie_data":{"stream_id":${q["vod_id"]},"container_extension":"mkv"}}"""
                    "get_series_info" -> """{"seasons":[],"info":{"name":"x","plot":"قصة المسلسل"},"episodes":{"1":[{"id":"9001","episode_num":1,"title":"الحلقة الأولى","container_extension":"mp4","info":{"duration_secs":1500,"plot":"Pilot"},"season":1},{"id":"9002","episode_num":2,"title":"Episode 2","container_extension":"mp4","info":[],"season":1}],"2":[{"id":"9003","episode_num":1,"title":"Episode 1","container_extension":"mkv","info":[],"season":2}]}}"""
                    "get_short_epg" -> """{"epg_listings":[{"title":"2KPYrtio2KfYsQ==","description":"TmV3cw==","start_timestamp":"${System.currentTimeMillis() / 1000 - 600}","stop_timestamp":"${System.currentTimeMillis() / 1000 + 1200}"}]}"""
                    else -> "[]"
                }

    fun stop() = runCatching { server.shutdown() }
}

package com.nimertv.app.data

import android.content.Context
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** Pure scheduling rules for the holiday splash; unit tested. */
object SplashLogic {
    /** A panel-forced splash without an end date is trusted for this long after the last successful sync. */
    const val FORCED_MAX_AGE_MS = 24L * 60 * 60 * 1000

    /**
     * The holiday artwork that should show at [now], or null for the default splash.
     *
     * 1. A panel-declared `active` splash wins while the configuration is recent (it covers
     *    manual overrides in the panel) and its own window, when present, has not ended.
     * 2. Otherwise the schedule decides, so holidays start and end on time even when the
     *    device is offline, and expired artwork is never shown.
     */
    fun active(config: PanelConfig?, now: Long, fetchedAt: Long): SplashImage? {
        if (config == null) return null
        val declared = config.splashActive
        if (declared != null) {
            val notEnded = declared.endMs == 0L || now < declared.endMs
            val started = declared.startMs == 0L || declared.startMs <= now
            val recent = now - fetchedAt in 0..FORCED_MAX_AGE_MS
            if (started && notEnded && (recent || declared.endMs != 0L)) return declared
        }
        return config.splashSchedule.firstOrNull { it.startMs <= now && now < it.endMs }
    }

    /** Images worth keeping on the device: default, active now, and anything starting within [horizonMs]. */
    fun wanted(config: PanelConfig, now: Long, fetchedAt: Long, horizonMs: Long = 45L * 86_400_000L): List<SplashImage> {
        val out = ArrayList<SplashImage>()
        active(config, now, fetchedAt)?.let(out::add)
        config.splashDefault?.let(out::add)
        config.splashSchedule.filterTo(out) { it.endMs > now && it.startMs < now + horizonMs }
        return out.distinctBy { it.sha256 }
    }
}

/** Downloads, verifies and caches splash artwork published by the panel. */
class SplashStore(context: Context) {
    private val dir = File(context.filesDir, "splash").apply { mkdirs() }

    private fun file(img: SplashImage) = File(dir, img.sha256 + ".img")

    /**
     * The file to display right now, or null to use the artwork bundled in the APK.
     * Falls back from holiday → panel default → bundled.
     */
    fun currentFile(snapshot: PanelSnapshot?, now: Long = System.currentTimeMillis()): File? {
        val cfg = snapshot?.config ?: return null
        val holiday = SplashLogic.active(cfg, now, snapshot.fetchedAt)
        if (holiday != null) file(holiday).takeIf { it.exists() }?.let { return it }
        return cfg.splashDefault?.let { file(it) }?.takeIf { it.exists() }
    }

    /** Blocking: fetch missing artwork and delete files that are no longer referenced. */
    fun sync(snapshot: PanelSnapshot) {
        val now = System.currentTimeMillis()
        val wanted = SplashLogic.wanted(snapshot.config, now, snapshot.fetchedAt)
        wanted.forEach { if (!file(it).exists()) download(it) }
        if (snapshot.fresh) {
            val keep = wanted.map { file(it).name }.toSet()
            dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        }
    }

    private fun download(img: SplashImage) {
        if (!img.url.startsWith("https://")) return
        val tmp = File(dir, img.sha256 + ".part")
        try {
            Http.panel.newCall(Request.Builder().url(img.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return
                val md = MessageDigest.getInstance("SHA-256")
                var total = 0L
                resp.body!!.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IllegalStateException("Splash image too large")
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
                if (md.digest().toHex() == img.sha256) tmp.renameTo(file(img)) else tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    private companion object {
        const val MAX_BYTES = 8_000_000L
    }
}

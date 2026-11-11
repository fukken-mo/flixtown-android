package com.nimertv.app

import com.nimertv.app.data.PanelConfig
import com.nimertv.app.data.SplashLogic
import com.nimertv.app.data.UpdateInfo
import com.nimertv.app.data.UpdateLogic
import com.nimertv.app.data.UpdateState
import com.nimertv.app.data.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelConfigTest {
    /** Response captured from the production panel (provider host replaced). */
    private val live = javaClass.getResource("/panel_config_live.json")!!.readText()

    @Test fun parsesLivePanelResponse() {
        val c = PanelConfig.parse(live)!!
        assertEquals("Nimer TV", c.appName)
        assertEquals(60, c.refreshMinutes)
        assertEquals(listOf("http://provider.example.test:88"), c.servers.map { it.url })
        assertFalse(c.maintenanceEnabled)
        assertNull(c.update)
        assertNull(c.splashActive)
        assertNotNull(c.splashDefault)
        assertEquals(64, c.splashDefault!!.sha256.length)
        assertEquals(5, c.splashSchedule.size)
        assertEquals("new_year", c.splashSchedule.first().id)
        assertTrue(c.announcements.isEmpty())
    }

    @Test fun rejectsUnsupportedOrBrokenDocuments() {
        assertNull(PanelConfig.parse("<html>502</html>"))
        assertNull(PanelConfig.parse("{\"api_version\":2,\"app\":{},\"servers\":[]}"))
        assertNull(PanelConfig.parse("{\"api_version\":1,\"servers\":[]}"))
        assertNull(PanelConfig.parse("{\"error\":\"not found\"}"))
    }

    @Test fun serversAreOrderedByPriorityAndValidated() {
        val json = """{"api_version":1,"app":{"name":"Nimer TV"},"servers":[
            {"name":"Backup","url":"https://b.example.test/","priority":2},
            {"name":"Bad","url":"ftp://x.example.test","priority":0},
            {"name":"Empty","url":"","priority":0},
            {"name":"Primary","url":"http://a.example.test:8080//","priority":1}]}"""
        val c = PanelConfig.parse(json)!!
        assertEquals(listOf("http://a.example.test:8080", "https://b.example.test"), c.servers.map { it.url })
    }

    @Test fun parsesUpdateMaintenanceAndAnnouncements() {
        val json = """{"api_version":1,
            "app":{"name":"Nimer TV","maintenance":{"enabled":true,"message":"Back at 9"},"login_message":"Welcome","support":{"whatsapp":"+1 555"}},
            "servers":[],
            "update":{"package":"com.nimertv.app","version_code":7,"version_name":"3.1.0","notes":"Fixes","mandatory":true,
                      "min_supported_version_code":5,"apk_url":"https://panelsandapps.com/x/NimerTV.apk","sha256":"${"AB".repeat(32)}","size":123,"signer_sha256":"AA:BB"},
            "announcements":[{"id":3,"updated_at":"2026-11-01","title":"مرحبا","message":"Hello","popup":true,"dismissible":false,"priority":true,"start_ms":10,"end_ms":20},{"id":4}]}"""
        val c = PanelConfig.parse(json)!!
        assertTrue(c.maintenanceEnabled)
        assertEquals("Back at 9", c.maintenanceMessage)
        assertEquals("+1 555", c.support.whatsapp)
        val u = c.update!!
        assertEquals(7, u.versionCode)
        assertTrue(u.mandatory)
        assertEquals("ab".repeat(32), u.sha256)
        assertEquals("aabb", u.signerSha256)
        assertEquals(1, c.announcements.size)
        val a = c.announcements.single()
        assertEquals("مرحبا", a.title)
        assertTrue(a.popup && !a.dismissible && a.priority)
        assertTrue(a.activeAt(15))
        assertFalse(a.activeAt(20))
        assertFalse(a.activeAt(5))
    }

    @Test fun splashArtworkMustBeHttpsWithChecksum() {
        val json = """{"api_version":1,"app":{},"servers":[],"splash":{
            "default":{"image_url":"http://insecure.example.test/a.jpg","image_sha256":"${"a".repeat(64)}"},
            "schedule":[{"id":"x","start_ms":1,"end_ms":2,"image_url":"https://ok.example.test/a.jpg","image_sha256":"short"}]}}"""
        val c = PanelConfig.parse(json)!!
        assertNull(c.splashDefault)
        assertTrue(c.splashSchedule.isEmpty())
    }

    /* ---------------- holiday splash scheduling ---------------- */

    @Test fun holidayStartsAndEndsOnSchedule() {
        val c = PanelConfig.parse(live)!!
        val newYear = c.splashSchedule.first()
        val fetched = newYear.startMs - 40L * 86_400_000
        assertNull(SplashLogic.active(c, newYear.startMs - 1, fetched))
        assertEquals("new_year", SplashLogic.active(c, newYear.startMs, fetched)?.id)
        assertEquals("new_year", SplashLogic.active(c, newYear.endMs - 1, fetched)?.id)
        // Expired artwork is never shown, even with a configuration that was cached long ago (offline device).
        assertNull(SplashLogic.active(c, newYear.endMs, fetched))
    }

    @Test fun adjacentHolidaysSwitchAutomatically() {
        val c = PanelConfig.parse(live)!!
        val ramadan = c.splashSchedule.first { it.id == "ramadan" }
        assertEquals("ramadan", SplashLogic.active(c, ramadan.endMs - 1, 0)?.id)
        assertEquals("eid_al_fitr", SplashLogic.active(c, ramadan.endMs, 0)?.id)
    }

    @Test fun forcedSplashWithoutEndDateExpiresWhenConfigurationIsStale() {
        val json = """{"api_version":1,"app":{},"servers":[],"splash":{
            "active":{"id":"promo","image_url":"https://ok.example.test/p.jpg","image_sha256":"${"b".repeat(64)}"},"schedule":[]}}"""
        val c = PanelConfig.parse(json)!!
        val fetched = 1_000_000_000_000L
        assertEquals("promo", SplashLogic.active(c, fetched + 60_000, fetched)?.id)
        assertNull(SplashLogic.active(c, fetched + SplashLogic.FORCED_MAX_AGE_MS + 1, fetched))
    }

    @Test fun prefetchKeepsDefaultCurrentAndUpcomingOnly() {
        val c = PanelConfig.parse(live)!!
        val newYear = c.splashSchedule.first()
        val wanted = SplashLogic.wanted(c, newYear.startMs - 86_400_000, 0)
        assertTrue(wanted.any { it.id == "default" })
        assertTrue(wanted.any { it.id == "new_year" })
        assertFalse(wanted.any { it.id == "eid_al_adha" })
    }

    /* ---------------- updates ---------------- */

    private fun update(code: Int, mandatory: Boolean = false, min: Int = 0, url: String = "https://panelsandapps.com/NimerTV.apk", pkg: String = "com.nimertv.app") =
        UpdateInfo(pkg, code, "v$code", "", mandatory, min, url, "a".repeat(64), 1000, "")

    @Test fun updateStateComparesVersionCodes() {
        val pkg = "com.nimertv.app"
        assertEquals(UpdateState.NONE, UpdateLogic.state(null, 1, pkg))
        assertEquals(UpdateState.NONE, UpdateLogic.state(update(1), 1, pkg))
        assertEquals(UpdateState.NONE, UpdateLogic.state(update(1), 2, pkg))
        assertEquals(UpdateState.OPTIONAL, UpdateLogic.state(update(2), 1, pkg))
        assertEquals(UpdateState.MANDATORY, UpdateLogic.state(update(2, mandatory = true), 1, pkg))
        assertEquals(UpdateState.MANDATORY, UpdateLogic.state(update(3, min = 2), 1, pkg))
        assertEquals(UpdateState.OPTIONAL, UpdateLogic.state(update(3, min = 2), 2, pkg))
        // Once installed, a mandatory update no longer gates the app: no update loop.
        assertEquals(UpdateState.NONE, UpdateLogic.state(update(2, mandatory = true), 2, pkg))
        assertEquals(UpdateState.NONE, UpdateLogic.state(update(2, pkg = "com.other"), 1, pkg))
        assertEquals(UpdateState.NONE, UpdateLogic.state(update(2, url = "http://insecure/NimerTV.apk"), 1, pkg))
    }

    @Test fun resumeChecksAreThrottled() {
        assertFalse(UpdateLogic.shouldCheck(10_000, 9_000))
        assertTrue(UpdateLogic.shouldCheck(UpdateLogic.RESUME_CHECK_INTERVAL_MS + 9_000, 9_000))
        assertTrue(UpdateLogic.shouldCheck(5, 9_000)) // clock moved backwards
    }

    @Test fun archiveIdentityIsVerified() {
        val pkg = "com.nimertv.app"
        val u = update(2)
        fun check(archivePkg: String?, code: Long, signer: String?, installedSigner: String? = "s1", info: UpdateInfo = u) =
            UpdateLogic.verifyIdentity(pkg, 1, installedSigner, info, archivePkg, code, signer)
        assertNull(check(pkg, 2, "s1"))
        assertNull(check(pkg, 3, "s1"))
        assertEquals(FailureKind.INTEGRITY, check(null, 0, null)?.kind)
        assertEquals(FailureKind.INTEGRITY, check("com.evil", 2, "s1")?.kind)
        assertEquals(FailureKind.INTEGRITY, check(pkg, 2, "s2")?.kind)
        assertEquals(FailureKind.INTEGRITY, check(pkg, 2, "s1", info = u.copy(signerSha256 = "other"))?.kind)
        // Server file older than the announced version: must not lock the customer out or loop.
        assertEquals(FailureKind.NOT_PUBLISHED, check(pkg, 1, "s1")?.kind)
        assertEquals(FailureKind.NOT_PUBLISHED, check(pkg, 2, "s1", info = update(3))?.kind)
        // Signers unavailable on old Android: left to the system installer.
        assertNull(check(pkg, 2, null))
    }
}

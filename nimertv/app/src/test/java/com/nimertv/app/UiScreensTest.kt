package com.nimertv.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.github.takahirom.roborazzi.captureRoboImage
import com.nimertv.app.data.AuthResult
import com.nimertv.app.data.PanelConfig
import com.nimertv.app.data.PanelSnapshot
import com.nimertv.app.data.Progress
import com.nimertv.app.ui.LoginScreen
import com.nimertv.app.ui.MainScreens
import com.nimertv.app.ui.MaintenanceScreen
import com.nimertv.app.ui.Palette
import com.nimertv.app.ui.UpdateScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real screens at 1080p TV size against [FakeProvider] and drives them with
 * D-pad key events. Screenshots land in app/build/screens/.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val provider = FakeProvider(art = true)
    private val app get() = RuntimeEnvironment.getApplication() as NimerApp
    private var signedOut = 0

    private fun config(extra: String = "") = PanelConfig.parse(
        """{"api_version":1,"refresh_minutes":60,
            "app":{"name":"Nimer TV","support":{"whatsapp":"+1 555 0100","email":"support@example.test"},"maintenance":{"enabled":false,"message":""},"login_message":""},
            "servers":[{"name":"Nimer TV","url":"${provider.url}","priority":1}],
            "announcements":[{"id":1,"updated_at":"1","title":"Welcome to Nimer TV","message":"New channels were added this week.","popup":false,"dismissible":true,"priority":false,"start_ms":0,"end_ms":0}]
            $extra}""",
    )!!

    @Before fun setUp() {
        app.panel = PanelSnapshot(config(), System.currentTimeMillis(), fresh = true)
        val result = app.client.authenticate("demo", "secret", null)
        app.signIn((result as AuthResult.Success).session)
    }

    @After fun tearDown() {
        app.signOut()
        provider.stop()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        // Image cross-fades start on their first draw, so draw twice with time passing in between.
        repeat(2) {
            rule.onRoot().captureRoboImage("build/screens/$name.png")
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500))
        }
        rule.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private fun waitForText(text: String, timeout: Long = 15_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** Lets image requests (real threads) and their cross-fades finish before a screenshot. */
    private fun settleArtwork() {
        repeat(6) {
            Thread.sleep(400)
            // Coil's cross-fade reads SystemClock, which Robolectric only advances on request.
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(400))
            rule.mainClock.advanceTimeBy(400)
            rule.waitForIdle()
        }
    }

    private fun key(k: Key) {
        rule.onRoot().performKeyInput { pressKey(k) }
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun showMain() {
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Palette.Background)) {
                MainScreens(app, onSignOut = { signedOut++ }, onShowUpdate = {}, onExit = {})
            }
        }
    }

    @Test fun homeBrowseAndDetailsWithRemote() {
        // One half-watched movie and an episode, so Continue Watching has real entries.
        app.library!!.saveProgress(Progress("movie:203", Progress.MOVIE, "203", "فيلم رقم 3", "", "${provider.url}/img/poster4.jpg", "mkv", 0, 1_500_000, 6_000_000, 2))
        app.library!!.toggleChannel(102)
        app.library!!.toggleChannel(105)
        showMain()
        waitForText("60 movies")
        waitForText("40 channels")
        settleArtwork()
        shot("01_home")

        // Remote: focus starts on LIVE TV; ▶ moves to MOVIES; OK opens it.
        key(Key.DirectionRight)
        settleArtwork()
        shot("02_home_movies_focused")
        key(Key.DirectionCenter)
        waitForText("All movies")
        waitForText("60 titles")
        settleArtwork()
        shot("03_movies")

        // ▶ into the grid, OK opens the first movie.
        key(Key.DirectionRight)
        key(Key.DirectionCenter)
        waitForText("Add to favorites")
        waitForText("A test description")
        shot("04_movie_detail")
        rule.onNodeWithText("Play").assertExists()

        // Favorite it with the remote: ▶ to the favorite button, OK.
        key(Key.DirectionRight)
        key(Key.DirectionCenter)
        waitForText("In favorites")
        assertEquals(1, app.library!!.favoriteMovies.size)

        // Back returns to the grid, Back again to home.
        back()
        waitForText("All movies")
        back()
        waitForText("60 movies")

        // Focus was restored to MOVIES; ◀ goes to LIVE TV, OK opens it.
        key(Key.DirectionLeft)
        key(Key.DirectionCenter)
        waitForText("All channels")
        waitForText("40 channels")
        key(Key.DirectionRight)
        waitForText("PROGRAM GUIDE")
        waitForText("أخبار", 8000)
        settleArtwork()
        shot("05_live")
        back()

        // SERIES: ▶ ▶ from LIVE TV.
        key(Key.DirectionRight)
        key(Key.DirectionRight)
        key(Key.DirectionCenter)
        waitForText("All series")
        key(Key.DirectionRight)
        key(Key.DirectionCenter)
        waitForText("الحلقة الأولى")
        waitForText("Play S1 E1")
        settleArtwork()
        shot("06_series_detail")
    }

    @Test fun searchAndAccount() {
        showMain()
        waitForText("60 movies")
        key(Key.DirectionUp)
        key(Key.DirectionCenter) // Search (first pill above the hero row)
        waitForText("Find something to watch")
        shot("07_search_empty")
        back()
        waitForText("60 movies")
        key(Key.DirectionRight)
        key(Key.DirectionCenter) // Account
        waitForText("Sign out")
        waitForText("demo")
        shot("08_account")
    }

    @Test fun exitNeedsConfirmation() {
        showMain()
        waitForText("60 movies")
        back()
        waitForText("Exit Nimer TV?")
        assertEquals(0, signedOut)
    }

    @Test fun loginScreen() {
        var submitted: Pair<String, String>? = null
        rule.setContent { LoginScreen(app.panel?.config, "", "The username or password is incorrect.") { u, p -> submitted = u to p } }
        waitForText("Sign in")
        shot("09_login")
        // ▼ ▼ from Username reaches the Sign in button; OK submits.
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        key(Key.DirectionCenter)
        assertTrue(submitted != null)
    }

    @Test fun maintenanceScreen() {
        rule.setContent { MaintenanceScreen("We are upgrading our servers. Back at 9 PM.", app.panel?.config?.support) {} }
        waitForText("We'll be right back")
        shot("10_maintenance")
    }

    @Test fun mandatoryUpdateCannotBeSkippedButCanBeRetried() {
        val update = config(
            ""","update":{"package":"com.nimertv.app","version_code":999,"version_name":"9.9.9","notes":"Faster channel switching and new holiday artwork.",
                "mandatory":true,"min_supported_version_code":0,"apk_url":"https://127.0.0.1:9/NimerTV.apk","sha256":"${"a".repeat(64)}","size":1000,"signer_sha256":""}""",
        ).update!!
        var later = 0
        var exit = 0
        rule.setContent { UpdateScreen(update, mandatory = true, updater = app.updater, onLater = { later++ }, onUnavailable = { later++ }, onExit = { exit++ }) }
        // The update server is unreachable in this test: the gate must stay and offer Retry / Exit only.
        waitForText("Try again", 20_000)
        shot("11_update_required")
        assertTrue(rule.onAllNodesWithText("Later").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Continue").fetchSemanticsNodes().isEmpty())
        back()
        assertEquals(0, later)
        assertEquals(1, exit)
    }
}

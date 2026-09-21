# Build status

## Android

**Result: BUILD SUCCESSFUL.** Verified by actually running the build — this
sandboxed dev environment's network policy blocks Google's Maven repo
(`dl.google.com`), so the build was run for real via this repo's own GitHub
Actions workflow (`.github/workflows/build-apk.yml`, `./gradlew
:app:assembleDebug --stacktrace`), not asserted from reading the code.

- Commit built: `2ce94b40ddf3becf62f9b0d90059dcc9613c9c58`
- Workflow run: https://github.com/fukken-mo/flixtown-android/actions/runs/35566886929
  (conclusion: `success`)
- Artifact: `FlixTown-TV-debug` (10,660,168 bytes), expires 2026-10-21
- Toolchain actually used: AGP 8.9.2, Kotlin 2.1.0, Gradle 8.11.1, JDK 17,
  compileSdk/targetSdk 36, minSdk 23

One real compile-error round was found and fixed along the way (first CI run
on this branch failed with two missing `androidx.compose.runtime.getValue`
imports for `by`-delegated `State<T>`; fixed and the next run was green) —
noted here rather than glossed over, per "don't claim success unless you ran
it."

**Not done:** emulator/device testing, lint, release/signed build. No
signing config is included in this package.

## Backend (PHP)

**Result: all 15 PHP files pass `php -l`** (`includes/*.php`,
`public_html/**/*.php`, `tools/cleanup_pairings.php`, `secrets.example.php`).

Additionally verified with functional smoke tests against a throwaway SQLite
database (standing in for MySQL, since this sandbox has no MySQL server):
the full pairing lifecycle (start → phone completes → TV polls/decrypts →
ack promotes a device session → row transitions to `acked`), the libsodium
encrypt/decrypt roundtrip, and specifically the new ACK-retry path (a second
ack call for an already-`acked` pairing returns the identical device token
instead of minting a new one, and rejects a mismatched temp token even on
replay).

**Not done:** never run against a real MySQL/MariaDB server, never deployed
to real cPanel hosting, never tested over real HTTPS.

## Correctness checklist (this packaging pass)

All verified by reading the actual code (not assumed):

- ViewModels are lifecycle-managed via `androidx.lifecycle.viewmodel.compose.viewModel(factory=...)`, never constructed manually.
- `StartupViewModel.start()` loads config before checking stored auth.
- Network/server errors during startup route to Home without touching stored credentials (`SecureCredentialStore.clearAccount()` is only ever called from an explicit sign-out path, never from an error branch).
- An expired Xtream account routes to `Route.RenewalRequired` without clearing credentials.
- A failed QR ACK (`PairingCompletionResult.Failure`) surfaces as `PairingUiState.Error`, never `Success`; the device token is only saved after a successful ack response.
- Manual login only stores credentials/token after `AuthRepository.manualLogin` receives `AuthOutcome.Success` from the backend's `auth/register.php`, which itself re-validates against Xtream server-side.
- No `.trim()` is ever called on a password, in Kotlin or PHP (only usernames are trimmed).
- Credentials/device token are stored via `AndroidKeyStore` AES/GCM/256, unique IV per encryption (`KeystoreCipher.kt`) — not the deprecated `androidx.security.crypto`.
- QR polling (`PairingViewModel`) correctly distinguishes Pending/Completed/Expired/Error and only calls ack once per detected completion.
- A retried `pair/ack.php` call for an already-acknowledged pairing replays the same device token (see `backend/public_html/api/v1/pair/ack.php`); a permanent `devices` session is created only inside the ack flow, never in `pair/status.php`.
- No duplicate class/file names across the Android source tree; no leftover files from the previous app version.
- Manifest is TV-only: `android.software.leanback` `required="true"`, touchscreen/telephony/camera/microphone all `required="false"`, `LEANBACK_LAUNCHER` only (no phone `LAUNCHER` category), banner + adaptive icon present.

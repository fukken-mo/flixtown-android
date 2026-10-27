# Claude review handoff

Review `codex/account-sync` against production baseline `a9bd9a7e332393c3e990e104762beb5c7dbb4821` (3.8.18 / 10038). Do not merge, production-sign, deploy or alter live config. This is an optional local-first sync feature for movie/episode resume, Continue Watching, watched flags and My List.

Read docs/ACCOUNT-SYNC.md and panel/onepanel-account-sync/INSTALL.md. Review the exact diff, not older audit branches. All PHP tests use fake adapter accounts and disposable databases; all Android sync instrumentation uses isolated preferences with a fake server. No customer tests are requested.

Check especially:
- account/endpoint isolation and late responses after sign-out/account switch;
- initial merge preserving local-only items, existing server wins CAS conflict;
- updates made while a request is in flight, retries/idempotence, DB rollback cursor reset;
- removal tombstones and watched/unwatched independence;
- foreground lifecycle and no remote mutation while video is active;
- bounded work on the main thread, no network in player callbacks, bandwidth/backoff;
- disabled/unavailable endpoint preserves all local playback/progress behavior;
- HMAC scope, authentication/active checks, whitelisted records, no credentials/URLs in persistence or logs;
- PDO MySQL/MariaDB concurrency and migration re-run safety;
- report any gaps in actual playback performance or process-kill testing. Do not call emulator state checks a real-hardware frame-rate test.

Return concrete findings with file references and severity. No design changes are needed. If fixing a finding, keep it on this feature branch, add a meaningful regression check and do not change production version/signing.

## Follow-up after the first review

Fixed on this branch (findings 1-7 of the first review):
1. Sync work off the main thread: capture, journal writes and store reads on a low-priority worker; no per-save capture during playback (heartbeat and stop); migration snapshot in its own file.
2. Large imports: parsed and validated off the main thread, written in slices of 200 records with one SharedPreferences write each.
3. Database restores: microsecond revisions (never reused), anchor-based detection, re-read without uploads, this TV's newer or lost copies uploaded again, never rolled back to the backup.
4. Limits per account (sessions, exchanges) and failed sign-ins per address/username; per-address flood ceiling only.
5. Operations refused one by one (invalid, capacity); the rest of the batch applies; refused versions are not resent forever.
6. Imported records checked on the TV with the server's rules before anything is written.
7. Leaving the player uploads and, at Home, applies waiting changes at once; an existing history catches up in quick rounds of 100.

Regression tests: device tests for a 3,000-record history (longest main-thread stall and import slice measured and bounded), playback saves, restore, account switch and invalid records; PHP tests for a 2,000-record history, per-operation refusals, capacity and restores; HTTP tests for shared-address sign-ins and failed sign-in limits.

Still not covered: real TV hardware (frame rate, flash wear), a real process kill (the reattach test detaches in-process), and production-scale load on the identity backend.


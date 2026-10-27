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

# Episode watched Preview

Baseline: production-signed 3.8.20 / 10040, commit 14b77643ec591fa74420d5fea3fbc73ddc0dce51. Development branch codex/episode-watched. No production version, signing, backend, schema, sync protocol or live configuration changes.

## Behavior

- Episode cards display a green `✓ Watched` label. The numeric episode index remains plain. Continue and Watched labels stack without sharing a position.
- Existing watched keys are unchanged, so existing account sync transfers the watched/unwatched flag as before.
- A separate local, bounded interval ledger records actual played media spans. Seeks, pauses, buffering, lifecycle gaps and duplicate replayed spans do not count as viewing. Playback speed counts media time.
- Automatic pre-EOF marking applies to episodes only: at least 90% unique viewing coverage AND current position in the last 10% AND at most 3 minutes remaining. No credits timestamps are guessed or fetched. Metadata support is deferred because none was found in the inspected provider contract.
- Setting the watched flag never clears resume, queues the next episode, stops playback or changes autoplay. Existing normal EOF and explicit Play now completion rules remain unchanged.
- Manual Mark unwatched clears the local ledger; in the currently playing instance it suppresses automatic pre-EOF marking until a new episode/session. Normal EOF keeps its existing semantics.
- Old resume positions are not proof of viewing and are not converted into coverage. Customers can manually mark these episodes watched.
- Ledger survives same-device restarts and resumes, but viewing coverage itself is NOT synced. Half watched on each of two TVs may need the manual override or normal EOF. Only the resulting watched flag syncs.
- Ledger is stored under `watched_coverage_watched_series:ID:EPISODE`, never uploaded by SyncRecord's strict whitelist, and removed on another account's login by the existing watched_ cleanup. It contains times only. A completed ledger is removed to avoid retaining unnecessary history.
- Ledger writes are grouped into existing 5-second progress saves, not the 500ms observation loop. There are no new requests, threads, services or network dependencies.

## Review focus

Review PlayerActivity observer/discontinuity lifecycle, manual unwatched, episode changes, and existing EOF handling. Review placement at 720p/1080p and long titles. Verify UI badge refresh on returning from playback and after manual toggle. Verify account switch clears ledger and sync ignores it while transferring watched flags. Never use the unrelated Compose prototype branch as baseline.

## Validation

WatchCoverageTest covers thresholds, long/short episodes, seek jumps (including missed callbacks), replay, pause/buffering gaps, speed, persistence, corrupt data and bounded fragmentation. A separate branch-only CI builds Preview/QA, runs the complete JVM suite, then existing account-sync instrumentation (13 scenarios) on API 28 and 31. Those instrumentation scenarios test sync, not actual video/rendering; do not describe them as player or visual tests.

Local Gradle cannot download its wrapper in this environment. Compilation and JVM execution must be confirmed by CI before delivery. Physical-TV badge layout and actual playback acceptance remain required. No production APK should be built or published by this workflow.

# One-time customer notices

Branch: codex/notice-once. Based on e71b1a5 on codex/episode-watched, preserving the tested watched update and production 3.8.20 features. Production version, signing, backend, schema and live configuration are unchanged.

## Requested behavior

- Request approval, request ready and resolved-report popups are marked seen immediately after the dialog is shown. No action is required to make them stay seen.
- `Got it` closes the popup. Watch and View requests retain their existing destination. Later is removed. Back closes without restoring unread status.
- An approval and the later ready event are two distinct events, each shown once. Showing ready also consumes approval to avoid a stale approval popup afterward.
- Existing event identities are preserved, so old dismissals and migrated legacy flags continue to count. Duplicate backend rows with the same key cannot produce duplicate popups.
- Acknowledgements are per account on this installation. Same-account login, QR login and A -> B -> A do not erase them.
- Remove the 1,000-entry eviction: previously old reports could resurface after enough new notices. The small local store now retains acknowledgement keys without automatic expiry; no repeated pruning or network calls are added.
- This does not promise cloud-wide delivery once. Another device, reinstall, or clearing app data can show the notice again. No backend acknowledgement API exists. Syncing acknowledgements across devices is a separate protocol/backend feature, not silently included here.
- Update prompts, account expiry, maintenance and actual playback errors keep their existing behavior. Only customer request/report notifications change.
- Report IDs are still the resolution identity. Reopening/resolving the same report ID does not create a new popup.

## Validation

- JVM: retain same-account re-login, A/B isolation, migration and privacy tests; add 1,500 acknowledgements without forgetting the first and idempotent marking.
- Real offline QA UI flows on API 28 / 720p and API 31 / 1080p: closing, Back, restart, manual and QR re-login, A -> B -> A and new event delivery.
- Customer notice QA: Back does not defer, force-stop with dialog still open does not replay, Got it closes, request approval does not replay after Back, approval -> ready still gives the new event, Watch/View requests actions and service outage remain usable.
- Updated expectations replace the explicitly removed Later behavior; unrelated expected results stay intact. No real requests or reports are submitted.

## Profile avatar handoff (next task, not implemented)

Use 12 premade selectable avatars initially. Each profile has a user-entered name plus an avatar ID, with no arbitrary URL or upload in the TV UI. Input images: square 512 x 512 PNG/JPG preferred, centered artwork safe for circle cropping. Twelve avatar choices does not mean twelve profiles. Profile count, migration and profile-specific sync still need implementation planning before changes to existing account data.

# Customer notices candidate

Based on production source 8b94cce (3.8.16 / 10036). Production version, package, signing and updater unchanged. Only a separate Preview is built. Nothing deployed.

Home checks authenticated customer request history and resolved playback reports asynchronously after Home is ready. Each service fails independently and silently. Playback, intro, loading, other screens, dialogs and the updater take priority. No timers poll the services. Foreground/focus checks have a five-minute cooldown; a fresh process can check immediately.

- Approved: explains availability is still pending. Opens Requests.
- Ready: only local, meaning the backend confirmed availability in the customer's Flix Town catalog. Seerr availability alone, approval and partial availability do not trigger this notice. Uses the backend's existing requested-season status.
- Resolved: only reports belonging to the authenticated account whose current status is resolved.
- Watch opens existing catalog Details when matched safely. It never guesses catalog IDs from TMDB IDs or starts playback automatically. Without a match, requests offer View requests; reports offer Dismiss.
- Dismiss / Watch / View requests acknowledge on this device. Later or Back defers until a later foreground visit. Same account keeps acknowledgements; switching accounts clears them. Acknowledgements are local to the TV.
- Availability acknowledgements are shared with My Requests to avoid duplicate toasts. Approval and ready are separate events, identified by type, TMDB ID, sorted seasons and request creation time.
- First rollout may show historical approved/available requests and resolved reports never acknowledged on this TV. Reads are capped at ten pages per service per check, fifty reports per page.

## Backend patch
Back up and replace only two OnePanel files from panel/onepanel-playback-reports:
- api/tv-playback-reports.php
- includes/playback_reports.php

No SQL migration or configuration change. Existing admin resolution action works as-is. POST action resolved uses the existing purpose-scoped report session, validates the active account, ignores caller-supplied owners, rate limits reads, and returns selected display/catalog fields only. Old apps remain compatible. With an old backend the new app skips resolution notices.

## Testing
Candidate workflow builds separate Preview and QA, runs unit tests, tests report backend on MySQL 8 and MariaDB 10.11 with fake accounts/catalog, and exercises notice dialogs on API 28 / 720p and API 31 / 1080p. QA notifications are opt-in. No real reports or Seerr requests.

Live acceptance requires checking an approved request, availability after import, a resolved report for the same account, and isolation from another account. No live actions are automated.

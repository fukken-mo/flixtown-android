# Episode focus preview candidate

Base: d298b40, feature/request-media (3.8.12 subtitle positioning preserved).
Branch: codex/episode-focus-preview. No production version/signing/config changes.

TV series only: focusing an episode for 250ms updates a reserved preview heading,
metadata, synopsis and full-width backdrop. The series title remains visible.
Episode art uses existing Images cache with a 960px decode target and stale-image
protection. No metadata requests or polling on focus. Missing stills retain series
backdrop; missing synopsis explicitly says unavailable. Series actions, cast and
cast restore the series overview. The selector retains context on the way to More info; changing seasons restores the overview.

Existing episode selection/playback/next-episode/resume code is unchanged.
Movie details are unchanged. Synopsis and title dimensions are fixed to prevent
page movement caused by differing episode text lengths. Existing vertical scroll
still provides cast access on small screens. No full screen rebuild on focus.

Review prompt for Claude:
Review this branch against d298b40 without redesigning it. Inspect D-pad focus,
250ms cancellation, delayed image callbacks, activity teardown, missing metadata,
season switches, and return from playback. Confirm the app parses the live episode
payload fields (info.plot/overview/description and info.movie_image). Check series
preview/card/selector fit at 720p and 1080p and long titles/font scaling. Verify
movie details and the 3.8.12 subtitle positioning are unaffected. Report findings
before edits, and do not publish or bump production versions. Use focused tests,
not repeated broad CI matrices. The Preview APK uses a separate package.

Manual acceptance: focus E1/E2/E3; fast-scroll; switch seasons; return to selector;
open/close More info; play/resume episode and return; check missing image/synopsis;
check cast navigation; inspect one movie. Real hardware QA is still required.

## Review corrections
- Explicit episode-card Up -> season selector Up -> More info path. Passing through
  the selector retains episode context; choosing a season or another series action
  restores the overview. This makes episode More info reachable with the remote.
- The stored overview starts with the loading message and records the failure message,
  so focus movement cannot erase either state.
- Episode backdrops decode into a second hidden buffer and swap only when ready.
  Generation checks prevent late callbacks from switching the wrong artwork.
- Series title supports two lines when needed; text areas reserve font-metric-based
  heights at larger font scales. The scroll view remains available for extra height.
- The overview heading and More info action are intentional parts of the approved
  concept; the user tested and approved this design on hardware.

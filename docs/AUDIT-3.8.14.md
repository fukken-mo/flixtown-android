# Flix Town 3.8.14 (10034) — production-readiness audit

Base: 3.8.13 (10033, commit 80e154e, the version the panel serves). Audit branch: claude/youthful-feynman-v2bnqb.

## Fixed
1. QR sign-in: an expired/used pairing code is replaced by a fresh one (was: polled a dead code forever).
2. QR sign-in: an approved account is kept until saved, so a failed config fetch retries without a new scan.
3. Account switch: a different account no longer sees the previous customer's My List, progress or Continue
   Watching. Same account keeps them. Renewal "Use a different account" signs out like Settings.
   Migration: a TV signed in when it updates records its account as owner on start (data kept); data with
   no owner at sign-in (TV signed out before updating) is removed.
4. Messages: disabled/banned accounts, stream timeouts, non-JSON error pages.
5. Logs: no raw exceptions; addresses keep scheme://host only; credential parameters removed; Media3 stack
   traces off. Renewal/startup error text redacted.
6. Autoplay continued only one episode; now continues through the series (across seasons, after resuming).
7. Autoplay: a late "Play now" and a seek pending at the episode change could skip an episode.

## Known limitations
- Autoplay queue: the player is given the next episode plus up to 200 later ones. After that many
  consecutive autoplayed episodes the player returns to Details; Continue Watching is already on the next
  episode and the customer presses Continue. Nothing is marked complete or removed. (Accepted for 3.8.14.)
- A customer who signed out on 3.8.13 or earlier and signs back in after updating loses that TV's
  My List/progress once (ownership cannot be verified).
- Endpoint transport: config.php, the content server (https://api.myflixtown.com), Requests, intro and
  update APK are https. A stream-shaped URL with placeholder credentials returned 401 without redirect; the
  scheme of authenticated stream redirects (if any) was NOT verified.
- Real hardware: only the subtitle position and the episode preview were tested on the owner's TV.

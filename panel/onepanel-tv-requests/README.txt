OnePanel add-on: media requests from the Flix Town Android TV app (Seerr)
========================================================================

NOT DEPLOYED. Review first. Nothing here changes the existing Media Requests admin page,
approve/decline, the Seerr settings, customers, payments or provider sync.

Files (all new):
  api/tv-requests.php                     customer JSON API used by the TV app
  includes/tv_requests.php                the logic (sessions, Seerr calls, duplicate rules, history)
  sql/migrations/2026-10-14-tv-requests.sql   two new tables: tv_media_requests, tv_request_rate
  config.tv-requests.example.txt          the lines to add to the existing 'seerr' config block
Tests (not uploaded): tests/tv_requests_test.php + tests/rate_worker.php (php -S workers + a real MySQL or
  MariaDB database built from the shipped migration + fake Seerr and fake Flix Town catalog; never calls a
  real Seerr or Flix Town). Run with TVQ_MYSQL_HOST/PORT/USER/PASS pointing at a TEST server (it creates
  and drops its own database tvq_test_<pid>). Never point it at the production database.

How it works
  TV app -> api/tv-requests.php -> includes/tv_requests.php -> Seerr (same base_url and API key as
  the admin page, read through includes/seerr.php).
  - Sign-in: the TV sends the customer's Flix Town username/password once; OnePanel checks them with
    the Flix Town backend (the same adapter OnePanel already uses) and returns a 30-minute signed
    session. Expired or disabled lines are refused. Every other call needs that session.
  - Requester: requests are created in Seerr as ONE dedicated Seerr user (X-API-User =
    requester_user_id). Seerr would auto-approve a request made as the API-key administrator or as any
    user with Admin / Manage Requests / Auto-Approve, so OnePanel reads that user's permissions before
    each request and refuses if it could approve anything. Requests therefore stay Pending until you
    approve them (OnePanel Media Requests or Seerr). In Seerr they show as requested by that user;
    OnePanel's tv_media_requests table records which Flix Town customer asked.
  - Duplicates: inside a per-title lock and a per-customer lock (MySQL GET_LOCK named locks, so they
    also hold across PHP workers and across web servers sharing the database), OnePanel re-reads the
    title from Seerr, checks its own records (a pending/approved request of any customer for that movie
    or season), and refuses: movies that are available, partly available, pending, approved/processing or
    declined; TV seasons that are available, partly available, pending, approved or declined. Only the
    seasons the customer picks are requested (never "all", never season 0). Seerr's own checks then run too.
  - Flix Town catalog (server side): before every request OnePanel reads the customer's OWN Flix Town
    line (player_api.php get_vod_streams / get_series / get_series_info with that line's username and
    password from the Flix Town adapter; the password is never stored, logged or returned). A movie
    whose TMDB id is in that catalog (same year, +/- 1, or no year) is refused: "This is already in
    Flix Town." A TV season whose every episode is there is refused the same way; a season only partly
    there can still be requested. This holds whatever the app sends (stale app catalog, direct calls).
    The catalog is cached in storage/tv-requests-catalog/ (TMDB ids, catalog ids and years only; file
    names are hashes) for 10 minutes for browsing and at most 2 minutes for a request. If the catalog
    cannot be read, browsing still works but requests are refused (fails closed).
    Limit: a catalog entry without a TMDB id (or with a wrong one) cannot be matched.
  - Declined: a declined title or season is shown as Declined and not offered again (unless
    customer_allow_rerequest_declined is true). You can still request it yourself in Seerr.
  - History: a customer only ever sees rows recorded for their own username. One rule for every
    request, the same as in details: in the customer's Flix Town catalog (the movie, or every
    requested season complete) = "In Flix Town", whatever Seerr says; some requested seasons there =
    partly available; then Seerr: declined, available, partly available, approved/processing, pending.
    Approval alone is never "available". For a series only the requested seasons count, never the
    whole show. Seerr states are re-read at most once a minute per request (the TV asks each time My
    Requests opens or the customer comes back to it); if Seerr is down the last known state is shown.
  - Limits: 40 searches/minute (400/hour), 60 detail views/minute, 6 submissions per 10 minutes and
    customer_daily_limit per day per customer; 30 sign-ins and 10 wrong passwords per IP per 15 minutes.
  - Failures: any Seerr or Flix Town problem answers "Requests are temporarily unavailable." The rest
    of OnePanel and the TV app are not affected.

Install (when you are ready)
  1. Back up OnePanel and its database.
  2. In Seerr, create a local user (e.g. "Flix Town TV"). Permissions: Request (or Request Movie +
     Request Series). Make sure Auto-Approve, Manage Requests and Admin are all OFF. Note its user ID.
     If you use Seerr request limits, set this user's limits as you want them for all TV customers together.
  3. Upload api/tv-requests.php and includes/tv_requests.php; run the SQL migration once (MySQL 5.7+ /
     MariaDB 10.3+; the OnePanel database user needs no extra privileges: GET_LOCK needs none).
     Make sure storage/ is writable (catalog cache) as it already is for OnePanel.
  4. Add the lines from config.tv-requests.example.txt to the 'seerr' block in config/config.php
     (requester_user_id = the user from step 2, a new random session secret), then set
     customer_requests_enabled => true. If the Flix Town adapter's user records do not include the
     line password, also set customer_catalog_username / customer_catalog_password (a line that sees
     the full catalog); without one of the two, requests stay refused.
  5. In the Flix Town app panel's config.php answer, add:
        "requests_api_url": "https://<your OnePanel>/api/tv-requests.php"
     The TV app shows "Requests are temporarily unavailable." until this is set.

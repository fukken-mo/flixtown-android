OnePanel add-on: media requests from the Flix Town Android TV app (Seerr)
========================================================================

NOT DEPLOYED. Review first. Nothing here changes the existing Media Requests admin page,
approve/decline, the Seerr settings, customers, payments or provider sync.

Files (all new):
  api/tv-requests.php                     customer JSON API used by the TV app
  includes/tv_requests.php                the logic (sessions, Seerr calls, duplicate rules, history)
  sql/migrations/2026-10-14-tv-requests.sql   two new tables: tv_media_requests, tv_request_rate
  config.tv-requests.example.txt          the lines to add to the existing 'seerr' config block
Tests (not uploaded): tests/tv_requests_test.php (php -S + SQLite + fake Seerr; never calls a real Seerr)

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
  - Duplicates: inside a per-title lock (and a per-customer lock), OnePanel re-reads the title from
    Seerr and refuses: movies that are available, partly available, pending, approved/processing or
    declined; TV seasons that are available, partly available, pending, approved or declined. Only the
    seasons the customer picks are requested (never "all", never season 0). Seerr's own checks then run too.
  - Declined: a declined title or season is shown as Declined and not offered again (unless
    customer_allow_rerequest_declined is true). You can still request it yourself in Seerr.
  - History: a customer only ever sees rows recorded for their own username. States are refreshed
    from Seerr at most every 5 minutes; if Seerr is down the last known state is shown.
  - Limits: 40 searches/minute (400/hour), 60 detail views/minute, 6 submissions per 10 minutes and
    customer_daily_limit per day per customer; 30 sign-ins and 10 wrong passwords per IP per 15 minutes.
  - Failures: any Seerr or Flix Town problem answers "Requests are temporarily unavailable." The rest
    of OnePanel and the TV app are not affected.

Install (when you are ready)
  1. Back up OnePanel and its database.
  2. In Seerr, create a local user (e.g. "Flix Town TV"). Permissions: Request (or Request Movie +
     Request Series). Make sure Auto-Approve, Manage Requests and Admin are all OFF. Note its user ID.
     If you use Seerr request limits, set this user's limits as you want them for all TV customers together.
  3. Upload api/tv-requests.php and includes/tv_requests.php; run the SQL migration once.
  4. Add the lines from config.tv-requests.example.txt to the 'seerr' block in config/config.php
     (requester_user_id = the user from step 2, a new random session secret), then set
     customer_requests_enabled => true.
  5. In the Flix Town app panel's config.php answer, add:
        "requests_api_url": "https://<your OnePanel>/api/tv-requests.php"
     The TV app shows "Requests are temporarily unavailable." until this is set.

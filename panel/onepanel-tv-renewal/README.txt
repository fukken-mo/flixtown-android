OnePanel add-on: in-app renewal for the Flix Town Android TV app (Cash App Pay through Square)
============================================================================================

What it adds (nothing existing is changed):
  api/tv-renewal.php         Public JSON API the TV app calls: context, quote, create, status, cancel
  api/square-webhook.php     Square webhook (payment.created / payment.updated): renews even if the TV was off
  includes/tv_renewal.php    The logic: sessions, server-side prices, Square payment links, one-time renewal
  cron/tv-renewal-sweep.php  Optional safety net for cron (command line only)
  sql/migrations/2026-10-10-tv-renewal.sql   Two new tables: tv_payments, tv_api_rate

Install
  1. Back up OnePanel and its database.
  2. Copy api/, includes/tv_renewal.php and cron/ into the OnePanel folder (next to includes/bootstrap.php).
  3. Run sql/migrations/2026-10-10-tv-renewal.sql once.
  4. Add the 'tv_api' and 'square' blocks from config.tv-renewal.example.php to config/config.php
     (Square access token + location id from the Square Developer Dashboard; never commit them).
     Prices stay in ['pricing']['flix_town'] (1 => 15.00, 3 => 40.00, 6 => 75.00, 12 => 135.00).
  5. In Square Developer Dashboard > Webhooks, subscribe payment.created and payment.updated to
     https://<onepanel>/api/square-webhook.php and copy the signature key into config.php.
  6. Optional: cron every 5 minutes:  php /path/to/onepanel/cron/tv-renewal-sweep.php
  7. Turn it on for the TV app: in the Flix Town app panel's config.php answer, add
       "renewal_api_url": "https://<onepanel>/api/tv-renewal.php"
     Remove that key to switch every TV back to the original renewal screen.
  Test first with 'environment' => 'sandbox' and a Square sandbox token.

Security
  - The TV app holds no Square, OnePanel or Flix Town secret. It signs in once with the customer's own
    Flix Town username/password (checked against the Flix Town backend), gets a 30-minute signed
    session, and afterwards sends only that session, a plan length and a payment id.
  - Prices come only from OnePanel config; the account is renewed only after Square reports the
    payment COMPLETED for exactly that amount in USD, and only once per payment.
  - Paid-but-not-renewed payments are marked "needs assistance" (never "failed"); look for them in
    the tv_payments table (state = needs_assistance, note explains why) and in activity logs.

Tests: php tests/tv_renewal_test.php  (SQLite + fake Square/Flix Town; no network)
